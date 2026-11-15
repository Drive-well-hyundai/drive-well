package com.driveproof.engine

import com.driveproof.model.Principle
import com.driveproof.model.Blink
import com.driveproof.model.Consts
import com.driveproof.model.Gear
import com.driveproof.model.GearPos
import com.driveproof.model.Signal
import com.driveproof.model.Speed
import com.driveproof.model.Tbt
import com.driveproof.model.TurnSignal
import com.driveproof.model.TurnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 하이브리드 시연(H 대본)의 판정. demo-sim.js에는 없어서 따로 본다(implementation.md "기준 구현과 골든 테스트").
 * 입력은 11/10 검증의 수신 시각열이다(sdk.md "하이브리드 시연 검증"): 속도와 깜빡이를 앱이 받은 시각.
 * 남은 거리는 HybridSource처럼 만든다. 첫 항목 80m에서 0.1초마다 속도를 적분해 빼고,
 * 0을 지나면 그 회전의 0.0을 낸 뒤 다음 회전으로 넘어간다.
 */
class HybridTurnTest {

    private val demo = Consts(minKm = 0.2)
    private val turns = listOf(TurnType.TURN_LEFT to 80.0, TurnType.TURN_RIGHT to 420.0, TurnType.TURN_LEFT to 600.0)

    /**
     * @param speedSteps 속도를 받은 시각(초)과 값(km/h). 콘솔은 값을 계단식으로 바꾼다
     * @param blinks 깜빡이를 받은 시각(초)과 값
     * @param parkAt 기어 P를 받은 시각(초)
     */
    private fun drive(speedSteps: List<Pair<Double, Double>>, blinks: List<Pair<Double, Blink>>, parkAt: Double): TripResult {
        val engine = TripEngine(demo)
        val startMs = 50_000L // elapsedRealtime처럼 0이 아닌 시각에서 시작한다
        val endMs = (parkAt * 1000).toLong()
        var index = 0
        var remain = turns[0].second
        var kmh = 0.0
        var result: TripResult? = null
        val send = { s: Signal -> engine.onSignal(s) }
        send(Gear(startMs, GearPos.D))
        var t = startMs
        while (t <= endMs + 1_000) {
            val sec = t / 1000.0
            speedSteps.lastOrNull { it.first <= sec + 1e-9 }?.let { kmh = it.second }
            // HybridSource: 합성 TBT. 회전을 지나면 0.0, 그다음 회전
            if (index < turns.size && t > startMs) {
                remain -= kmh / 3.6 * 0.1
                while (index < turns.size && remain <= 0.0) {
                    send(Tbt(t, turns[index].first, 0.0))
                    val overflow = -remain
                    index++
                    remain = (turns.getOrNull(index)?.second ?: 0.0) - overflow
                }
            }
            if (index < turns.size) send(Tbt(t, turns[index].first, remain))
            blinks.filter { (it.first * 1000).toLong() == t }.forEach { send(TurnSignal(t, it.second)) }
            if (t == endMs) {
                result = engine.end(t)
                send(Gear(t, GearPos.P))
            }
            send(Speed(t, kmh))
            t += 100
        }
        return result!!
    }

    private fun TripResult.signal() = scenes.single { it.principle == Principle.SIGNAL }

    /** 64m 회: 속도 30 수신 53.5초, 깜빡이 LEFT 수신 55.4초. 80 − 1.9초 × 8.33m/s ≈ 64m */
    @Test
    fun signalAtSixtyFourMetresPasses() {
        val r = drive(listOf(50.0 to 0.0, 53.5 to 30.0, 85.0 to 0.0), listOf(55.4 to Blink.LEFT, 66.0 to Blink.OFF), parkAt = 87.0)
        val s = r.signal()
        assertEquals(SceneKind.LEFT_TURN, s.kind)
        assertEquals(ScenePath.NAVI, s.path)
        assertEquals(64.0, s.onDistanceM!!, 1.0)
        assertEquals(1.0, s.s, 0.0)
    }

    /** 2m 회: 속도 30 수신 69.3초, 속도 20 수신 77.6초, 깜빡이 LEFT 수신 79.2초. 80 − 69 − 9 ≈ 2m */
    @Test
    fun signalAtTwoMetresIsLate() {
        val r = drive(
            listOf(50.0 to 0.0, 69.3 to 30.0, 77.6 to 20.0, 82.0 to 30.0, 104.0 to 0.0),
            listOf(79.2 to Blink.LEFT, 84.0 to Blink.OFF),
            parkAt = 106.0,
        )
        val s = r.signal()
        assertEquals(SceneKind.LEFT_TURN, s.kind)
        assertEquals(2.0, s.onDistanceM!!, 1.0)
        assertEquals(2.0 / 30.0, s.s, 0.04)
    }

    @Test
    fun noSignalIsZero() {
        val r = drive(listOf(50.0 to 0.0, 53.5 to 30.0, 85.0 to 0.0), emptyList(), parkAt = 87.0)
        val s = r.signal()
        assertNull(s.onDistanceM)
        assertEquals(0.0, s.s, 0.0)
    }

    /** 통과 전에 끄면 미점등이다(demo-replay.md "H 대본" 4단계) */
    @Test
    fun signalTurnedOffBeforeThePassIsZero() {
        val r = drive(listOf(50.0 to 0.0, 53.5 to 30.0, 85.0 to 0.0), listOf(55.4 to Blink.LEFT, 60.0 to Blink.OFF), parkAt = 87.0)
        assertNull(r.signal().onDistanceM)
    }

    /** 대본대로 250m쯤 달리고 주차한다. 다음 회전(420m 뒤)은 아직 멀어서 장면이 아니다 */
    @Test
    fun secondTurnFarAheadIsNotAScene() {
        val r = drive(listOf(50.0 to 0.0, 53.5 to 30.0, 85.0 to 0.0), listOf(55.4 to Blink.LEFT, 66.0 to Blink.OFF), parkAt = 87.0)
        assertEquals(listOf(Principle.SIGNAL, Principle.DECEL), r.scenes.map { it.principle })
        assertEquals(0.26, r.km, 0.01)
    }
}
