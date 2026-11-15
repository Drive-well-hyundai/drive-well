#!/usr/bin/env python3
"""dp-console: PT 시연자가 호스트 PC에서 Pleos Virtual Console을 키보드로 조작하는 도구.

규칙 원본은 implementation.md "호스트 조작 도구"다. demo 빌드 산출물이다.
Virtual Console 카드에는 속도가 없어 REST로 넣는다(sdk.md "에뮬레이터 신호 실측 결과").

키
  d / p      기어 D / P
  ↑ / ↓      목표 속도 ±10km/h. 1초에 5km/h씩 바꾼다(급가속 기준 아래)
  ← / →      깜빡이 LEFT / RIGHT. 같은 키를 한 번 더 누르면 Off
  [ / ]      핸들 왼쪽 / 오른쪽 300도를 2초. 콘솔에는 반대 부호로 보낸다
  q          끝

--script "d,up,up,up,wait:8,left,wait:4,[,down,down,down,wait:7,p"처럼 키를 차례로 보낼 수도 있다.
wait:N은 N초 기다린다. 리허설과 자동 확인에 쓴다.

표준 라이브러리만 쓴다(Python 3.8+, Windows·macOS·Linux).
"""

import argparse
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

# ---------- 상수 (implementation.md "호스트 조작 도구") ----------

SPEED_STEP_KMH = 10          # ↑/↓ 한 번에 바뀌는 목표 속도
RAMP_KMH_PER_S = 5           # 실제 속도가 목표로 가는 빠르기. 급가속 기준(8~12 km/h/s) 아래
TICK_S = 0.2                 # 속도를 보내는 간격. 0.2초마다 1km/h
MAX_SPEED_KMH = 120
STEER_DEG = 300              # 앱 값 기준. 왼쪽이 음수
STEER_HOLD_S = 2.0
HEARTBEAT_S = 1.0


# ---------- 순수 함수 (단위 테스트 대상) ----------

def ramp_step(current, target, per_tick):
    """current를 target 쪽으로 최대 per_tick만큼 옮긴다."""
    if current < target:
        return min(target, current + per_tick)
    if current > target:
        return max(target, current - per_tick)
    return current


def console_steering(app_deg):
    """앱이 받는 조향은 콘솔 값과 부호가 반대다(sdk.md 11/10, driving-record.md "요각 추정").
    왼쪽 300도(앱 -300)는 콘솔에 +300을 넣는다."""
    return -app_deg


def pedals(current, target):
    """콘솔 controls의 accelerator, brake(%). 가속 중 가속 페달, 감속 중 브레이크."""
    if target > current:
        return 20, 0
    if target < current:
        return 0, 30
    return (10, 0) if current > 0 else (0, 0)


def next_blink(current, pressed):
    """같은 방향을 한 번 더 누르면 Off."""
    return "Off" if current == pressed else pressed


# ---------- Virtual Console REST ----------

class Console:
    def __init__(self, base, client):
        self.base = base.rstrip("/")
        self.client = client
        self.last_error = None

    def _call(self, method, path):
        req = urllib.request.Request(self.base + path, method=method, data=b"" if method != "GET" else None)
        try:
            with urllib.request.urlopen(req, timeout=2) as r:
                self.last_error = None
                return r.status
        except urllib.error.HTTPError as e:
            self.last_error = f"{method} {path} → {e.code}"
            return e.code
        except (urllib.error.URLError, OSError) as e:
            self.last_error = f"{method} {path} → {e}"
            return None

    def heartbeat(self, closing=False):
        q = f"/browser/session?client={self.client}" + ("&closing=1" if closing else "")
        return self._call("POST", q)

    def gear(self, name):
        return self._call("PUT", f"/vehicle/gear?gear={name}")

    def turn_signal(self, mode):
        return self._call("PUT", f"/vehicle/turn-signal?mode={mode}")

    def controls(self, speed, accelerator, brake, steering):
        return self._call(
            "PUT", f"/vehicle/controls?speed={speed}&accelerator={accelerator}&brake={brake}&steering={steering}"
        )


# ---------- 키 입력 ----------

def read_key():
    """키 하나를 이름으로 돌려준다: 'up', 'down', 'left', 'right', 또는 글자."""
    if os.name == "nt":
        import msvcrt
        ch = msvcrt.getwch()
        if ch in ("\x00", "\xe0"):
            return {"H": "up", "P": "down", "K": "left", "M": "right"}.get(msvcrt.getwch(), "")
        return ch
    import termios
    import tty
    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    try:
        tty.setraw(fd)
        ch = sys.stdin.read(1)
        if ch == "\x1b":
            seq = sys.stdin.read(2)
            return {"[A": "up", "[B": "down", "[D": "left", "[C": "right"}.get(seq, "")
        return ch
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)


# ---------- 상태와 루프 ----------

class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.gear = "Park"
        self.speed = 0           # 지금 보낸 속도(km/h)
        self.target = 0
        self.blink = "Off"
        self.steer_app = 0       # 앱 값 기준
        self.steer_until = 0.0
        self.running = True


def status_line(s):
    blink = {"BlinkLeft": "◀ LEFT", "BlinkRight": "RIGHT ▶", "Off": "Off"}[s.blink]
    return f"기어 {s.gear[0]}  속도 {s.speed:>3}/{s.target:<3}km/h  깜빡이 {blink:<8} 핸들 {s.steer_app:+4}도"


def print_status(s, extra=""):
    sys.stdout.write("\r" + status_line(s) + ("  " + extra if extra else "") + " " * 8)
    sys.stdout.flush()


def heartbeat_loop(con, state):
    while state.running:
        con.heartbeat()
        time.sleep(HEARTBEAT_S)


def control_loop(con, state):
    per_tick = max(1, round(RAMP_KMH_PER_S * TICK_S))
    sent = None
    while state.running:
        with state.lock:
            if state.steer_app and time.monotonic() >= state.steer_until:
                state.steer_app = 0
            accel, brake = pedals(state.speed, state.target)
            state.speed = ramp_step(state.speed, state.target, per_tick)
            want = (state.speed, accel, brake, console_steering(state.steer_app))
        if want != sent:
            if con.controls(*want) == 200:
                sent = want
            with state.lock:
                print_status(state, con.last_error or "")
        time.sleep(TICK_S)


def reset(con, state):
    """시작할 때 속도 0, 깜빡이 Off, 기어 P로 초기화한다."""
    ok = [
        con.controls(0, 0, 0, 0),
        con.turn_signal("Off"),
        con.gear("Park"),
    ]
    with state.lock:
        state.gear, state.speed, state.target, state.blink, state.steer_app = "Park", 0, 0, "Off", 0
    return all(code == 200 for code in ok)


def script_keys(text):
    """--script 값을 키 이름 목록으로. 끝나면 q로 닫는다."""
    keys = [k.strip() for k in text.split(",") if k.strip()]
    return iter(keys + ["wait:1", "q"])


def find_adb():
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(env)
        if root:
            exe = os.path.join(root, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
            if os.path.exists(exe):
                return exe
    return shutil.which("adb")


def main(argv=None):
    ap = argparse.ArgumentParser(description="Pleos Virtual Console 키보드 조작 (Drive Well 시연)")
    ap.add_argument("--port", type=int, default=8088)
    ap.add_argument("--no-forward", action="store_true", help="adb forward를 하지 않는다")
    ap.add_argument("--script", help='키를 차례로 보낸다. 예: "d,up,up,up,wait:8,left,p"')
    args = ap.parse_args(argv)
    if not sys.stdout.isatty():
        # 파이프로 받을 때 Windows 기본 인코딩(cp949) 대신 UTF-8로 쓴다
        sys.stdout.reconfigure(encoding="utf-8")

    if not args.no_forward:
        adb = find_adb()
        if not adb:
            print("adb를 찾지 못했다. ANDROID_HOME을 설정하거나 --no-forward로 직접 포트를 연다.")
            return 1
        subprocess.run([adb, "forward", f"tcp:{args.port}", f"tcp:{args.port}"], check=False,
                       stdout=subprocess.DEVNULL)

    con = Console(f"http://127.0.0.1:{args.port}", f"dp-console-{uuid.uuid4().hex[:8]}")
    state = State()
    if con.heartbeat() is None:
        print(f"Virtual Console에 연결하지 못했다: {con.last_error}")
        return 1

    threading.Thread(target=heartbeat_loop, args=(con, state), daemon=True).start()
    time.sleep(2)  # 하트비트가 붙기 전 쓰기는 503이다
    if not reset(con, state):
        print(f"초기화 실패: {con.last_error}")
    print("d/p 기어 · ↑/↓ 목표 속도 ±10 · ←/→ 깜빡이 · [/] 핸들 300도 2초 · q 끝")
    threading.Thread(target=control_loop, args=(con, state), daemon=True).start()

    keys = script_keys(args.script) if args.script else iter(read_key, None)
    try:
        for key in keys:
            if key.startswith("wait:"):
                time.sleep(float(key[5:]))
                continue
            with state.lock:
                if key in ("q", "Q", "\x03"):
                    break
                if key in ("d", "D"):
                    state.gear = "Drive"
                    con.gear("Drive")
                elif key in ("p", "P"):
                    state.gear = "Park"
                    con.gear("Park")
                elif key == "up":
                    state.target = min(MAX_SPEED_KMH, state.target + SPEED_STEP_KMH)
                elif key == "down":
                    state.target = max(0, state.target - SPEED_STEP_KMH)
                elif key in ("left", "right"):
                    state.blink = next_blink(state.blink, "BlinkLeft" if key == "left" else "BlinkRight")
                    con.turn_signal(state.blink)
                elif key in ("[", "]"):
                    state.steer_app = -STEER_DEG if key == "[" else STEER_DEG
                    state.steer_until = time.monotonic() + STEER_HOLD_S
                print_status(state, con.last_error or "")
    except KeyboardInterrupt:
        pass
    finally:
        state.running = False
        con.heartbeat(closing=True)
        print("\n끝. 하트비트를 닫았다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
