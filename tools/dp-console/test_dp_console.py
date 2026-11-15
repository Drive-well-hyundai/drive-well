"""dp-console 순수 함수 테스트. 실행: python -m unittest discover -s app/tools/dp-console"""

import unittest

import dp_console as dc


def accel_candidates(samples_10hz):
    """demo-sim.js의 급가속 후보 판정을 옮긴 것(driving-record.md "신호 처리", "기본 항목").
    평활 속도 = 직전 10개 평균, 가감속 = 평활 속도의 1초 차분.
    속도 6km/h 이상에서 기준(10 이하 12, 20 이하 10, 초과 8 km/h/s) 이상이 4샘플 이어지면 후보."""
    vs, out, run = [], [], 0
    for i, _ in enumerate(samples_10hz):
        w = samples_10hz[max(0, i - 9): i + 1]
        vs.append(sum(w) / len(w))
        a = vs[i] - vs[i - 10] if i >= 10 else 0.0
        thr = 12 if vs[i] <= 10 else 10 if vs[i] <= 20 else 8
        if vs[i] >= 6 and a >= thr and abs(a) <= 45:
            run += 1
            if run == 4:
                out.append(i)
        else:
            run = 0
    return out, max((vs[i] - vs[i - 10]) for i in range(10, len(vs))) if len(vs) > 10 else 0.0


def console_ramp(start, target, seconds):
    """dp-console이 TICK_S마다 보내는 속도를 앱이 10Hz로 받는다고 보고 늘어놓는다(직전 값 유지)."""
    per_tick = max(1, round(dc.RAMP_KMH_PER_S * dc.TICK_S))
    sent, cur, t = [], start, 0.0
    while t < seconds:
        sent.append((t, cur))
        cur = dc.ramp_step(cur, target, per_tick)
        t += dc.TICK_S
    samples, j = [], 0
    for k in range(int(seconds * 10)):
        tk = k / 10
        while j + 1 < len(sent) and sent[j + 1][0] <= tk + 1e-9:
            j += 1
        samples.append(sent[j][1])
    return samples


class RampTest(unittest.TestCase):
    def test_ramp_moves_toward_target(self):
        self.assertEqual(dc.ramp_step(0, 30, 1), 1)
        self.assertEqual(dc.ramp_step(30, 0, 1), 29)
        self.assertEqual(dc.ramp_step(29.5, 30, 1), 30)
        self.assertEqual(dc.ramp_step(30, 30, 1), 30)

    def test_zero_to_30_has_no_accel_candidate(self):
        # V-8 완료 기준: 0 → 30km/h로 올릴 때 급가속 후보가 찍히지 않는다
        samples = console_ramp(0, 30, 10)
        self.assertEqual(samples[-1], 30)
        found, peak = accel_candidates(samples)
        self.assertEqual(found, [])
        self.assertLessEqual(peak, 5.0 + 1e-9)

    def test_jump_to_30_would_be_a_candidate(self):
        # 콘솔에 바로 30을 넣으면 1초 평활 뒤에도 급가속 1건이 찍힌다(implementation.md)
        samples = [0] * 10 + [30] * 30
        found, _ = accel_candidates(samples)
        self.assertEqual(len(found), 1)


class MappingTest(unittest.TestCase):
    def test_console_steering_is_opposite(self):
        self.assertEqual(dc.console_steering(-300), 300)   # 왼쪽
        self.assertEqual(dc.console_steering(300), -300)

    def test_blink_toggles_off(self):
        self.assertEqual(dc.next_blink("Off", "BlinkLeft"), "BlinkLeft")
        self.assertEqual(dc.next_blink("BlinkLeft", "BlinkLeft"), "Off")
        self.assertEqual(dc.next_blink("BlinkLeft", "BlinkRight"), "BlinkRight")

    def test_pedals(self):
        self.assertEqual(dc.pedals(0, 30), (20, 0))
        self.assertEqual(dc.pedals(30, 0), (0, 30))
        self.assertEqual(dc.pedals(30, 30), (10, 0))
        self.assertEqual(dc.pedals(0, 0), (0, 0))


if __name__ == "__main__":
    unittest.main()
