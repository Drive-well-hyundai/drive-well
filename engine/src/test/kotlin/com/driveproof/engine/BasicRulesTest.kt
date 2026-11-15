package com.driveproof.engine

import com.driveproof.model.BasicKind
import com.driveproof.model.Brake
import com.driveproof.model.Gear
import com.driveproof.model.GearPos
import com.driveproof.model.RoadLimit
import com.driveproof.model.Signal
import com.driveproof.model.Speed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 골든 시나리오에 없는 규칙. 원본은 driving-record.md "신호 처리", "기본 항목", "주행의 시작과 끝"이다 */
class BasicRulesTest {

    /** 속도를 시점 사이에서 직선으로 이어 10Hz로 만든다. [points]는 (초, km/h)다 */
    private fun speeds(vararg points: Pair<Double, Double>, from: Long = 0): List<Signal> {
        val out = ArrayList<Signal>()
        val endMs = (points.last().first * 1000).toLong()
        var t = (points.first().first * 1000).toLong()
        while (t <= endMs) {
            val s = t / 1000.0
            val i = points.indexOfLast { it.first <= s }.coerceAtMost(points.size - 2)
            val (t0, v0) = points[i]
            val (t1, v1) = points[i + 1]
            out += Speed(from + t, v0 + (v1 - v0) * (s - t0) / (t1 - t0))
            t += 100
        }
        return out
    }

    private fun engineWith(vararg first: Signal) = TripEngine().also { e -> first.forEach(e::onSignal) }

    private fun TripEngine.feed(signals: List<Signal>) = signals.forEach(::onSignal)

    @Test
    fun hardBrakeNeedsThePedal() {
        val brake = speeds(0.0 to 60.0, 5.0 to 60.0, 7.0 to 20.0, 12.0 to 20.0)

        val pressed = engineWith(Gear(0, GearPos.D), Brake(0, true))
        pressed.feed(brake)
        assertEquals(listOf(BasicKind.DECEL), pressed.end(12_000).basic.map { it.kind })

        val released = engineWith(Gear(0, GearPos.D))
        released.feed(brake)
        assertEquals(emptyList<BasicRec>(), released.end(12_000).basic)
    }

    @Test
    fun hardAccelIsCountedOnceWhileItLasts() {
        val e = engineWith(Gear(0, GearPos.D))
        e.feed(speeds(0.0 to 10.0, 2.0 to 10.0, 5.0 to 46.0, 8.0 to 46.0)) // 12km/h/s를 3초
        val basic = e.end(8_000).basic
        assertEquals(1, basic.size)
        assertEquals(BasicKind.ACCEL, basic[0].kind)
        assertEquals(12.0, basic[0].value, 0.01)
    }

    @Test
    fun sameKindIsNotCountedAgainWithinFiveSeconds() {
        // 12km/h/s로 2초 올리는 급가속 두 번. 시작이 3초 떨어져 있으면 1회, 6초 떨어져 있으면 2회다
        fun count(gapSec: Double): Int {
            val e = engineWith(Gear(0, GearPos.D))
            val second = 2.0 + gapSec
            e.feed(speeds(0.0 to 10.0, 2.0 to 10.0, 4.0 to 34.0, 5.0 to 10.0, second to 10.0, second + 2.0 to 34.0, second + 4.0 to 34.0))
            return e.end(((second + 4.0) * 1000).toLong()).basic.count { it.kind == BasicKind.ACCEL }
        }
        assertEquals(1, count(3.0))
        assertEquals(2, count(6.0))
    }

    @Test
    fun belowSixKmhIsNotCounted() {
        val e = engineWith(Gear(0, GearPos.D))
        e.feed(speeds(0.0 to 0.0, 2.0 to 0.0, 2.4 to 5.5, 6.0 to 5.5))
        assertEquals(emptyList<BasicRec>(), e.end(6_000).basic)
    }

    /**
     * 45km/h/s를 넘는 칸은 버린다. demo-sim.js와 같이 그 칸만 건너뛴다.
     * 1초 평활 때문에 값이 한 번에 튀어도 가감속은 0.1초마다 조금씩 커진다.
     * 그래서 45를 넘기 전의 칸으로 이벤트가 세어지고, 최댓값은 45를 넘지 않는다.
     */
    @Test
    fun ticksAboveFortyFiveAreLeftOutOfThePeak() {
        val e = engineWith(Gear(0, GearPos.D), Brake(0, true))
        // 0.1초 만에 80에서 20으로 떨어졌다. 1초 차분은 60까지 간다
        e.feed(speeds(0.0 to 80.0, 3.0 to 80.0, 3.1 to 20.0, 8.0 to 20.0))
        val basic = e.end(8_000).basic
        assertEquals(listOf(BasicKind.DECEL), basic.map { it.kind })
        assertEquals(42.0, basic[0].value, 1e-9)
    }

    @Test
    fun speedingNeedsAKnownLimitAndTwentyOver() {
        val cruise = speeds(0.0 to 75.0, 10.0 to 75.0)

        val unknown = engineWith(Gear(0, GearPos.D), RoadLimit(0, 0))
        unknown.feed(cruise)
        assertEquals(emptyList<BasicRec>(), unknown.end(10_000).basic)

        val within = engineWith(Gear(0, GearPos.D), RoadLimit(0, 60)) // 75는 60+20 아래다
        within.feed(cruise)
        assertEquals(emptyList<BasicRec>(), within.end(10_000).basic)

        val over = engineWith(Gear(0, GearPos.D), RoadLimit(0, 50))
        over.feed(cruise)
        val basic = over.end(10_000).basic
        assertEquals(listOf(BasicKind.OVER), basic.map { it.kind })
        assertEquals(75.0, basic[0].value, 1e-9)
    }

    @Test
    fun speedingIsCountedAgainAfterDroppingBelowTheMargin() {
        val e = engineWith(Gear(0, GearPos.D), RoadLimit(0, 50))
        e.feed(speeds(0.0 to 75.0, 4.0 to 75.0, 5.0 to 60.0, 10.0 to 60.0, 11.0 to 75.0, 15.0 to 75.0))
        assertEquals(2, e.end(15_000).basic.count { it.kind == BasicKind.OVER })
    }

    /** Pleos 신호는 등록 직후 같은 값이 두 번 올 수 있다. 결과가 달라지면 안 된다 */
    @Test
    fun repeatedSignalsDoNotChangeTheResult() {
        val signals = Golden.signals("s5")
        val once = Golden.run(TripEngine(), signals)
        val twice = Golden.run(TripEngine(), signals.flatMap { listOf(it, it) })
        assertEquals(once, twice)
    }

    @Test
    fun speedGapOverThreeSecondsIsNotJudged() {
        val e = engineWith(Gear(0, GearPos.D), Brake(0, true))
        e.feed(speeds(0.0 to 60.0, 5.0 to 60.0))
        // 4초 동안 속도가 오지 않고, 다시 왔을 때는 20km/h다. 이 사이의 감속은 세지 않는다
        e.feed(speeds(9.0 to 20.0, 15.0 to 20.0))
        val result = e.end(15_000)
        assertEquals(emptyList<BasicRec>(), result.basic)
        assertTrue(result.hadGap)
    }

    @Test
    fun shortSpeedGapIsBridgedWithTheLastValue() {
        val e = engineWith(Gear(0, GearPos.D))
        e.feed(speeds(0.0 to 36.0, 5.0 to 36.0))
        e.feed(speeds(7.0 to 36.0, 10.0 to 36.0)) // 2초 빔
        val result = e.end(10_000)
        assertFalse(result.hadGap)
        assertEquals(0.1, result.km, 0.002) // 36km/h로 10초
    }

    @Test
    fun restartWithinThirtySecondsContinuesTheTrip() {
        val e = engineWith(Gear(0, GearPos.D))
        e.feed(speeds(0.0 to 36.0, 10.0 to 36.0))
        assertEquals(0.1, e.end(10_000).km, 0.002)
        e.onSignal(Gear(10_000, GearPos.P))
        e.feed(speeds(10.0 to 0.0, 30.0 to 0.0))
        e.onSignal(Gear(30_000, GearPos.D))
        e.feed(speeds(30.0 to 36.0, 40.0 to 36.0))
        assertEquals(0.2, e.end(40_000).km, 0.003)
    }

    @Test
    fun restartAfterThirtySecondsIsANewTrip() {
        val e = engineWith(Gear(0, GearPos.D), Brake(0, true))
        e.feed(speeds(0.0 to 60.0, 5.0 to 60.0, 7.0 to 20.0, 10.0 to 20.0))
        assertEquals(1, e.end(10_000).basic.size)
        e.onSignal(Gear(10_000, GearPos.P))
        e.feed(speeds(10.0 to 0.0, 50.0 to 0.0))
        e.onSignal(Gear(50_000, GearPos.D))
        e.feed(speeds(50.0 to 0.0, 60.0 to 36.0, 70.0 to 36.0))
        val second = e.end(70_000)
        assertEquals(emptyList<BasicRec>(), second.basic)
        assertEquals(0.15, second.km, 0.003)
    }

    /** 재생을 다시 시작하면 시각이 0으로 돌아간다. 새 주행으로 센다 */
    @Test
    fun clockGoingBackStartsANewTrip() {
        val e = TripEngine()
        val first = Golden.run(e, Golden.signals("s5"))
        val again = Golden.run(e, Golden.signals("s5"))
        assertEquals(first, again)
    }

    @Test
    fun confirmedGivesEachEventOnceAfterFiveSeconds() {
        val e = engineWith(Gear(0, GearPos.D), Brake(0, true))
        e.feed(speeds(0.0 to 60.0, 5.0 to 60.0, 7.0 to 20.0, 9.0 to 20.0))
        assertEquals(emptyList<Confirmed>(), e.confirmed()) // 급감속은 5초대에 시작했다. 아직 5초가 안 지났다
        e.feed(speeds(9.1 to 20.0, 12.0 to 20.0))
        assertEquals(listOf(BasicKind.DECEL), e.confirmed().map { (it as BasicRec).kind })
        assertEquals(emptyList<Confirmed>(), e.confirmed())
    }
}
