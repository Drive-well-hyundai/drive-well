package com.driveproof.engine

import com.driveproof.model.Blink
import com.driveproof.model.CardTopic
import com.driveproof.model.Consts
import com.driveproof.model.Gear
import com.driveproof.model.GearPos
import com.driveproof.model.NaviState
import com.driveproof.model.Principle
import com.driveproof.model.Speed
import com.driveproof.model.Tbt
import com.driveproof.model.TurnSignal
import com.driveproof.model.TurnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H 대본(demo-replay.md "H 대본")을 JVM에서 처음부터 끝까지 돌린다. 에뮬레이터 리허설에서 나올 결과를 미리 본다.
 *
 * 입력은 시연 도구들이 내는 신호를 흉내 낸다.
 * - dp-console: 목표 속도를 1초에 5km/h씩 바꾼다. 브레이크 키는 없다
 * - HybridSource: 첫 회전 80m에서 0.1초마다 속도를 적분해 뺀 남은 거리를 합성 Tbt로 낸다. 회전을 지나면 0.0, 그다음 회전
 * - 시연자: 로그의 남은 거리를 보고 깜빡이를 켜고, 통과 줄이 뜬 뒤 끈다
 */
class HScriptRehearsalTest {

    private val demo = Consts(minKm = 0.2)
    // 11/10 경로의 앞 세 항목. 첫 항목은 TURN_LEFT 80m다(sdk.md "하이브리드 시연 검증"). 뒤 거리는 250m 이상이면 된다
    private val turns = listOf(TurnType.TURN_LEFT to 80.0, TurnType.TURN_RIGHT to 420.0, TurnType.TURN_LEFT to 600.0)

    /**
     * 대본 한 번. [startMs]에 하이브리드를 시작한다(정차, 기어 P).
     * @param signalAtM 남은 거리가 이 값 이하가 되는 순간 깜빡이 LEFT를 켠다. null이면 켜지 않는다
     * @param naviStatus 안내 상태 신호를 보낼지. 안내를 미리 걸어 둔 채 시작하면 오지 않을 수 있다
     */
    private fun run(engine: TripEngine, startMs: Long, signalAtM: Double?, naviStatus: Boolean = false): TripResult {
        var t = startMs
        var kmh = 0.0
        var target = 0.0
        var index = 0
        var remain = turns[0].second
        var blink = Blink.OFF
        var passedAt: Long? = null
        var result: TripResult? = null
        var phase = 0 // 0 정차, 1 가속·주행, 2 회전 뒤 20초, 3 감속
        var phaseAt = t

        if (naviStatus) engine.onSignal(NaviState(t, true))
        engine.onSignal(Gear(t, GearPos.P))
        while (result == null) {
            // 대본 2: 2초 뒤 기어 D, 목표 속도 30
            if (phase == 0 && t - phaseAt >= 2_000) { engine.onSignal(Gear(t, GearPos.D)); target = 30.0; phase = 1 }
            // 대본 5: 회전 뒤 30km/h로 20초
            if (phase == 1 && passedAt != null) { phase = 2; phaseAt = t }
            // 대본 6: 속도 0
            if (phase == 2 && t - phaseAt >= 20_000) { target = 0.0; phase = 3 }

            // dp-console: 1초에 5km/h씩
            if ((t - startMs) % 1_000 == 0L && kmh != target) kmh += if (target > kmh) 5.0 else -5.0

            // HybridSource: 합성 Tbt
            if (index < turns.size) {
                remain -= kmh / 3.6 * 0.1
                while (index < turns.size && remain <= 0.0) {
                    engine.onSignal(Tbt(t, turns[index].first, 0.0))
                    if (index == 0) passedAt = t
                    val overflow = -remain
                    index++
                    remain = (turns.getOrNull(index)?.second ?: 0.0) - overflow
                }
                if (index < turns.size) engine.onSignal(Tbt(t, turns[index].first, remain))
            }

            // 대본 3: 남은 거리를 보고 깜빡이. 대본 4: 통과 줄이 뜨고 3초 뒤 끈다
            if (signalAtM != null && blink == Blink.OFF && passedAt == null && index == 0 && remain <= signalAtM && kmh > 0) {
                blink = Blink.LEFT
                engine.onSignal(TurnSignal(t, blink))
            }
            val passed = passedAt
            if (blink == Blink.LEFT && passed != null && t - passed >= 3_000) {
                blink = Blink.OFF
                engine.onSignal(TurnSignal(t, blink))
            }

            // 대본 6: 멈춘 뒤 기어 P. 서비스는 P 신호를 엔진에 넘기기 전에 end를 부른다
            if (phase == 3 && kmh == 0.0 && (t - startMs) % 1_000 == 500L) {
                result = engine.end(t)
                engine.onSignal(Gear(t, GearPos.P))
            }
            engine.onSignal(Speed(t, kmh))
            t += 100
        }
        return result
    }

    private fun texts(r: TripResult) = renderCard(r.card, Golden::cardText).let { it.lines + it.notes }

    /** 통과: 남은 거리 50m쯤에서 켠다 */
    @Test
    fun signalAtFiftyMetresGivesTwoPraises() {
        val r = run(TripEngine(demo), 50_000, signalAtM = 50.0)
        val signal = r.scenes.single { it.principle == Principle.SIGNAL }
        assertEquals(50.0, signal.onDistanceM!!, 1.0)
        assertEquals(1.0, signal.s, 0.0)
        assertEquals(ScenePath.NAVI, signal.path)
        // 대본 5: 주행거리 약 250m. 시연 기록은 0.2km 이상이면 판정한다
        assertEquals(0.27, r.km, 0.02)
        assertEquals(
            listOf("좌회전 ${Math.round(signal.onDistanceM!!)}m 전에 깜빡이를 켰어요", "좌회전 전에 미리 속도를 줄였어요"),
            texts(r),
        )
    }

    /** 늦음: 남은 거리 8m에서 켠다 */
    @Test
    fun signalAtEightMetresIsTheThingToFix() {
        val r = run(TripEngine(demo), 50_000, signalAtM = 8.0)
        val signal = r.scenes.single { it.principle == Principle.SIGNAL }
        assertEquals(8.0, signal.onDistanceM!!, 1.0)
        assertTrue(signal.s < 0.3)
        assertEquals(
            listOf("좌회전 전에 미리 속도를 줄였어요", "좌회전 ${Math.round(signal.onDistanceM!!)}m 전에 깜빡이를 켰어요. 다음엔 30m 전부터 켜 주세요"),
            texts(r),
        )
    }

    /** 미점등: 켜지 않는다 */
    @Test
    fun noSignalIsTheThingToFix() {
        val r = run(TripEngine(demo), 50_000, signalAtM = null)
        assertEquals(listOf("좌회전 전에 미리 속도를 줄였어요", "좌회전 1번은 깜빡이를 안 켰어요. 회전 30m 전부터 켜 주세요"), texts(r))
    }

    /** 대본 2: 1초에 5km/h씩 올리고 내리면 급가속·급감속 후보가 찍히지 않는다 */
    @Test
    fun rampingFiveKmhPerSecondCountsNoBasicEvents() {
        for (at in listOf(50.0, 8.0, null)) {
            val r = run(TripEngine(demo), 50_000, at)
            assertEquals(emptyList<BasicRec>(), r.basic)
            assertEquals(emptyList<RiskRec>(), r.risks)
            assertEquals(false, r.hadGap)
        }
    }

    /** 회전 앞에서 30km/h로 그대로 지나므로 미리 줄이기는 1점이다 */
    @Test
    fun steadySpeedBeforeTheTurnScoresOneForDecel() {
        val decel = run(TripEngine(demo), 50_000, 50.0).scenes.single { it.principle == Principle.DECEL }
        assertEquals(1.0, decel.s, 0.0)
        assertEquals(30.0, decel.passKmh!!, 0.0)
    }

    /**
     * 안내를 미리 걸어 둔 채 하이브리드를 시작하면 안내 상태 신호가 오지 않을 수 있다.
     * 그래도 회전 안내를 받았으니 "내비 경로 안내 없이…"를 붙이지 않는다.
     */
    @Test
    fun routeNoteIsNotAddedEvenWithoutTheNavigationStatus() {
        val without = run(TripEngine(demo), 50_000, 50.0, naviStatus = false)
        val with = run(TripEngine(demo), 50_000, 50.0, naviStatus = true)
        assertEquals(emptyList<String>(), without.card.notes)
        assertEquals(with.card, without.card)
    }

    /** 이슈 완료 기준: H 대본이 3번 연속 같은 결과로 끝난다. 리허설 사이는 30초 넘게 띄운다 */
    @Test
    fun threeRunsInARowGiveTheSameResult() {
        val engine = TripEngine(demo)
        val first = run(engine, 50_000, 50.0)
        val second = run(engine, 50_000 + 90_000, 50.0)
        val third = run(engine, 50_000 + 180_000, 50.0)
        assertEquals(texts(first), texts(second))
        assertEquals(texts(first), texts(third))
        assertEquals(first.km, second.km, 1e-9)
        assertEquals(first.scenes.map { it.s }, third.scenes.map { it.s })
        assertEquals(first.scenes.map { it.onDistanceM }, third.scenes.map { it.onDistanceM })
    }

    /**
     * 주의: 기어 P 뒤 30초 안에 다시 출발하면 같은 주행으로 이어진다(driving-record.md "주행의 시작과 끝").
     * 리허설을 바로 이어 하면 두 번째 한 장에 회전이 2번으로 나온다.
     */
    @Test
    fun runStartedWithinThirtySecondsIsJoinedToThePreviousTrip() {
        val engine = TripEngine(demo)
        val first = run(engine, 50_000, 50.0)
        val end = 50_000 + 60_000L // 첫 대본은 40초쯤 걸린다. 그 뒤 30초 안에 다시 시작한다
        val joined = run(engine, end, 50.0)
        assertEquals(1, first.scenes.count { it.principle == Principle.SIGNAL })
        assertEquals(2, joined.scenes.count { it.principle == Principle.SIGNAL })
        assertEquals(first.km * 2, joined.km, 0.02)
        assertEquals(CardTopic.SIGNAL, joined.card.lines[0].topic)
        assertEquals("card_praise_signal_all", joined.card.lines[0].key)
    }
}
