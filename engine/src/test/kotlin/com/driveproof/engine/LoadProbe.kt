package com.driveproof.engine

import com.driveproof.model.Blink
import com.driveproof.model.Brake
import com.driveproof.model.Caution
import com.driveproof.model.CautionType
import com.driveproof.model.Gear
import com.driveproof.model.GearPos
import com.driveproof.model.Lamp
import com.driveproof.model.NaviState
import com.driveproof.model.RoadLimit
import com.driveproof.model.Signal
import com.driveproof.model.Speed
import com.driveproof.model.Steering
import com.driveproof.model.Tbt
import com.driveproof.model.Telltale
import com.driveproof.model.TurnSignal
import com.driveproof.model.TurnType
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.abs

/**
 * 엔진 부하를 잰다. 판정이 맞는지는 보지 않는다. 평소 테스트에서는 건너뛴다.
 *
 * ```
 * DP_LOAD=1 ./gradlew :engine:test --tests '*LoadProbe*' --rerun-tasks
 * ```
 *
 * 결과는 `engine/build/load-probe.txt`에 쓴다. 호스트 JVM에서 잰 값이다. 차량 기기의 값이 아니다.
 * Pleos 기준(주행 중 CPU 5% 미만, RAM 400MB 이하, driving-record.md "자원")은 에뮬레이터에서 앱 전체로 잰다.
 */
class LoadProbe {

    private val out = StringBuilder()

    private fun say(line: String) {
        out.appendLine(line)
        println(line)
    }

    /**
     * 시내 주행. 400m마다 회전한다. 회전 앞에서 20km/h로 줄이고 핸들을 200도 돌린다.
     * 다섯 번에 한 번은 신호 대기로 15초 선다. 열 번에 한 번은 급하게 줄인다. 스무 번에 한 번은 ABS 경고등이 켜진다.
     * 일곱 번에 한 번은 과속방지턱 안내가 온다. 깜빡이는 세 번에 한 번 늦게 켜고, 여덟 번에 한 번 켜지 않는다.
     * @param perTick 한 칸(0.1초)에 속도와 조향을 몇 번 보내는가. 1이면 10Hz, 10이면 100Hz다
     */
    private fun urban(minutes: Int, perTick: Int): List<Signal> {
        val s = ArrayList<Signal>(minutes * 600 * (perTick * 2 + 1))
        var t = 1_000L
        var kmh = 0.0
        var remain = 400.0
        var turn = 0
        var steerLeft = 0     // 핸들을 돌린 채 남은 칸
        var stopLeft = 0      // 서 있을 남은 칸
        var blink = Blink.OFF
        var blinkOffAt = -1L
        var brake = false
        var bump = -1.0       // 과속방지턱까지 남은 거리. 없으면 음수
        var absOffAt = -1L
        s += NaviState(t, true)
        s += RoadLimit(t, 50)
        s += Gear(t, GearPos.D)
        val ticks = minutes * 600
        for (n in 0 until ticks) {
            val type = if (turn % 2 == 0) TurnType.TURN_LEFT else TurnType.TURN_RIGHT
            val harsh = turn % 10 == 9
            val target = when {
                stopLeft > 0 -> 0.0
                steerLeft > 0 || remain < (if (harsh) 25.0 else 90.0) -> 20.0
                bump in 0.0..40.0 -> 25.0
                else -> 50.0
            }
            val step = if (harsh && target < kmh) 1.6 else 0.5 // 칸당 km/h. 1.6이면 16km/h/s다
            val before = kmh
            kmh = if (abs(target - kmh) <= step) target else kmh + if (target > kmh) step else -step
            if (stopLeft > 0 && kmh == 0.0) stopLeft--

            val nowBrake = kmh < before
            if (nowBrake != brake) { brake = nowBrake; s += Brake(t, brake) }

            val moved = kmh / 3.6 * 0.1
            remain -= moved
            if (bump >= 0.0) {
                bump -= moved
                if (bump <= 0.0) { s += Caution(t, CautionType.SPEED_BUMP, 0.0); s += Caution(t, null, null); bump = -1.0 }
                else if (n % 10 == 0) s += Caution(t, CautionType.SPEED_BUMP, bump)
            }
            if (remain <= 0.0) {
                s += Tbt(t, type, 0.0)
                steerLeft = 40
                blinkOffAt = t + 3_000
                turn++
                remain += 400.0
                if (turn % 5 == 4) stopLeft = 150
                if (turn % 7 == 3) bump = 200.0
                if (turn % 20 == 10) { s += Telltale(t, "ABS", Lamp.ON); absOffAt = t + 2_000 }
            } else if (n % 10 == 0 || perTick > 1) {
                s += Tbt(t, type, remain)
            }
            if (t == absOffAt) s += Telltale(t, "ABS", Lamp.OFF)

            val wantAt = when { turn % 8 == 7 -> -1.0; turn % 3 == 2 -> 10.0; else -> 45.0 }
            if (blink == Blink.OFF && steerLeft == 0 && remain <= wantAt) {
                blink = if (type == TurnType.TURN_LEFT) Blink.LEFT else Blink.RIGHT
                s += TurnSignal(t, blink)
            }
            if (blink != Blink.OFF && t == blinkOffAt) { blink = Blink.OFF; s += TurnSignal(t, blink) }

            val deg = if (steerLeft > 0) { steerLeft--; if (turn % 2 == 1) -200.0 else 200.0 } else (n % 7 - 3) * 0.5
            for (k in 0 until perTick) {
                val at = t + k * (100L / perTick)
                s += Steering(at, deg)
                s += Speed(at, kmh + (k % 3 - 1) * 0.05)
            }
            t += 100
        }
        s += Speed(t, 0.0)
        return s
    }

    /** 고속도로 순항. 100km/h, 핸들은 거의 그대로, 회전 안내는 먼 거리만 온다 */
    private fun highway(minutes: Int): List<Signal> {
        val s = ArrayList<Signal>(minutes * 600 * 2 + 64)
        var t = 1_000L
        s += NaviState(t, true)
        s += RoadLimit(t, 100)
        s += Gear(t, GearPos.D)
        var remain = 300_000.0
        for (n in 0 until minutes * 600) {
            val kmh = 100.0 + (n % 11 - 5) * 0.2
            remain -= kmh / 3.6 * 0.1
            if (n % 10 == 0) s += Tbt(t, TurnType.TURN_KEEP_R, remain)
            s += Steering(t, (n % 9 - 4) * 0.4)
            s += Speed(t, kmh)
            t += 100
        }
        return s
    }

    private class Run(val ticks: Int, val signals: Int, val nsPerTick: Double, val bytesPerTick: Double, val result: TripResult, val endNs: Long)

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** 엔진에 넣는 시간만 잰다. 1초마다 `confirmed()`도 부른다(저장이 붙으면 서비스가 부를 자리다) */
    private fun feed(signals: List<Signal>): Run {
        val engine = TripEngine()
        val ticks = ((signals.last().t - signals.first().t) / 100).toInt()
        val me = Thread.currentThread().id
        val bytes0 = threads.getThreadAllocatedBytes(me)
        val t0 = System.nanoTime()
        var nextConfirm = signals.first().t + 1_000
        for (sig in signals) {
            engine.onSignal(sig)
            if (sig.t >= nextConfirm) { engine.confirmed(); nextConfirm += 1_000 }
        }
        val t1 = System.nanoTime()
        val bytes1 = threads.getThreadAllocatedBytes(me)
        val result = engine.end(signals.last().t)
        val t2 = System.nanoTime()
        return Run(ticks, signals.size, (t1 - t0).toDouble() / ticks, (bytes1 - bytes0).toDouble() / ticks, result, t2 - t1)
    }

    /** 호출 한 번이 걸린 시간의 분포. 신호 스레드를 가장 오래 붙잡는 호출을 본다 */
    private fun latency(signals: List<Signal>): LongArray {
        val engine = TripEngine()
        val ns = LongArray(signals.size)
        for ((i, sig) in signals.withIndex()) {
            val t0 = System.nanoTime()
            engine.onSignal(sig)
            ns[i] = System.nanoTime() - t0
        }
        ns.sort()
        return ns
    }

    /** 주행 끝 직전에 엔진이 쥐고 있는 메모리. GC 뒤 힙 차이로 어림한다 */
    private fun retained(signals: List<Signal>): Long {
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(50) }; return rt.totalMemory() - rt.freeMemory() }
        val before = used()
        var engine: TripEngine? = TripEngine()
        for (sig in signals) engine!!.onSignal(sig)
        val with = used()
        engine!!.end(signals.last().t)
        engine = null
        return with - before
    }

    private fun report(name: String, signals: List<Signal>) {
        val run = (1..3).map { feed(signals) }.minBy { it.nsPerTick }
        val lat = latency(signals)
        val kept = retained(signals)
        fun pct(p: Double) = lat[((lat.size - 1) * p).toInt()]
        val r = run.result
        say("")
        say("## $name")
        say(f("- 입력: %d칸(%.0f분), 신호 %d개(칸당 %.1f개)", run.ticks, run.ticks / 600.0, run.signals, run.signals.toDouble() / run.ticks))
        say(f("- 결과: %.1fkm · 장면 %d · 기본 항목 %d · 위험 장면 %d", r.km, r.scenes.size, r.basic.size, r.risks.size))
        say(f("- 칸당 계산: %.0fns → 1초(10칸)에 %.1fµs = 코어 하나의 %.5f%%", run.nsPerTick, run.nsPerTick * 10 / 1_000, run.nsPerTick * 10 / 1e9 * 100))
        say(f("- 칸당 할당: %.0f바이트 → 1초에 %.1fKB, 1시간에 %.1fMB", run.bytesPerTick, run.bytesPerTick * 10 / 1024, run.bytesPerTick * 36_000 / 1024 / 1024))
        say(f("- 호출 한 번: 중앙 %dns · 99%% %dns · 99.9%% %dns · 가장 긴 것 %.1fµs", pct(0.5), pct(0.99), pct(0.999), lat.last() / 1_000.0))
        say(f("- 주행 끝 직전에 쥔 메모리: 약 %.2fMB", kept / 1024.0 / 1024.0))
        say(f("- 주행 끝 계산(end): %.2fms", run.endNs / 1e6))
    }

    private fun f(format: String, vararg args: Any) = String.format(Locale.ROOT, format, *args)

    @Test
    fun measure() {
        assumeTrue("DP_LOAD=1일 때만 잰다", System.getenv("DP_LOAD") == "1")
        val rt = Runtime.getRuntime()
        say("# 엔진 부하 (호스트 JVM)")
        say("")
        say("- JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")} · ${System.getProperty("os.arch")} · 코어 ${rt.availableProcessors()}개 · 최대 힙 ${rt.maxMemory() / 1024 / 1024}MB")

        // JIT가 붙기 전 첫 1분. 처음 돌린 값이라 클래스 로딩도 들어 있다
        val firstMinute = urban(1, 1)
        val cold0 = System.nanoTime()
        TripEngine().let { e -> for (sig in firstMinute) e.onSignal(sig) }
        val coldNs = (System.nanoTime() - cold0) / 600.0
        say(f("- 처음 1분(JIT 전, 클래스 로딩 포함): 칸당 %.0fns → 코어 하나의 %.4f%%", coldNs, coldNs * 10 / 1e9 * 100))

        // 데우기
        val warm = urban(30, 1)
        repeat(5) { feed(warm) }
        feed(highway(30))

        report("시내 2시간 · 10Hz", urban(120, 1))
        report("고속도로 2시간 · 10Hz", highway(120))
        report("시내 2시간 · 100Hz (속도·조향을 칸마다 10번)", urban(120, 10))
        report("시내 8시간 · 10Hz (오래 달린 하루)", urban(480, 1))

        File("build").mkdirs()
        File("build/load-probe.txt").writeText(out.toString())
    }
}
