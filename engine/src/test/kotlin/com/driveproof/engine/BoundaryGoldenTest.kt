package com.driveproof.engine

import com.driveproof.model.BasicKind
import com.driveproof.model.CardTopic
import com.driveproof.model.Exempt
import com.driveproof.model.Principle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 경계 시나리오 6종(E-11). demo-sim.js와의 비교는 다른 골든 테스트가 `Golden.ids`로 함께 돈다
 * (칸, 기본 항목, 장면, 위험 장면, 한 장 문구).
 * 여기서는 시나리오마다 보려던 경계가 실제로 나오는지를 본다. 골든을 다시 뽑아도 뜻이 바뀌면 실패한다.
 */
class BoundaryGoldenTest {

    private fun run(id: String) = Golden.run(TripEngine(), Golden.signals(id))

    /** 회전 3번 중 2번 성공: 칭찬이 "모두"가 아니라 "3번 중 2번"이다 */
    @Test
    fun b1TwoOfThreeTurnsSignalledInTime() {
        val r = run("b1")
        val signals = r.scenes.filter { it.principle == Principle.SIGNAL }
        assertEquals(listOf(1.0, 1.0), signals.take(2).map { it.s })
        assertEquals(12.0, signals[2].onDistanceM!!, 0.5)
        assertTrue(r.scenes.filter { it.principle == Principle.DECEL }.all { it.s < 1.0 })

        val praise = r.card.lines[0]
        assertEquals(LineKind.PRAISE, praise.kind)
        assertEquals("card_praise_signal_some", praise.key)
        assertEquals(mapOf("turn" to CardKeys.WORD_TURN, "n" to 3, "ok" to 2), praise.args)
        assertEquals(CardTopic.DECEL, r.card.lines[1].topic)
    }

    /** 스쿨존 2곳: 곳마다 판정한다 */
    @Test
    fun b2TwoSchoolZonesAreJudgedSeparately() {
        val r = run("b2")
        assertEquals(listOf(SceneKind.SCHOOL_ZONE, SceneKind.SCHOOL_ZONE), r.scenes.map { it.kind })
        assertEquals(listOf(28.0, 41.0), r.scenes.map { it.maxKmh })
        assertEquals(listOf(1.0, 0.45), r.scenes.map { Math.round(it.s * 100) / 100.0 })
        val line = r.card.lines.single()
        assertEquals("card_praise_zone_some", line.key)
        assertEquals(mapOf("n" to 2, "ok" to 1, "limit" to 30), line.args)
    }

    /** 같은 방향 깜빡이를 켠 채 이어지는 회전: 둘째 회전의 켠 거리는 안내가 시작될 때의 남은 거리다 */
    @Test
    fun b3SignalLeftOnCarriesToTheNextTurn() {
        val signals = run("b3").scenes.filter { it.principle == Principle.SIGNAL }
        assertEquals(listOf(40.0, 120.0), signals.map { it.onDistanceM })
        assertEquals(listOf(SceneKind.LEFT_TURN, SceneKind.LEFT_TURN), signals.map { it.kind })
        assertEquals(listOf(1.0, 1.0), signals.map { it.s })
    }

    /** 추정 회전 2번이 50m 안: 앞 회전만 남는다 */
    @Test
    fun b4SecondEstimatedTurnWithinFiftyMetresIsDropped() {
        val r = run("b4")
        assertEquals(listOf(Principle.SIGNAL, Principle.DECEL), r.scenes.map { it.principle })
        assertTrue(r.scenes.all { it.kind == SceneKind.RIGHT_TURN && it.estimated })
        assertNull(r.scenes[0].onDistanceM)
        assertEquals(listOf(CardKeys.NOTE_ROUTE), r.card.notes)
        assertTrue(r.card.lines.all { it.estimated })
    }

    /** 기본 항목만 있는 주행: 상황 장면이 없을 때만 기본 항목이 고칠 점이 된다 */
    @Test
    fun b5OnlyBasicItems() {
        val r = run("b5")
        assertEquals(emptyList<SceneRec>(), r.scenes)
        assertEquals(listOf(BasicKind.DECEL, BasicKind.DECEL), r.basic.map { it.kind })
        assertTrue(r.basic.all { it.exempt == Exempt.NONE })
        val line = r.card.lines.single()
        assertEquals(CardTopic.BASIC, line.topic)
        assertEquals("card_fix_basic_decel", line.key)
        assertEquals(mapOf("n" to 2), line.args)
        assertEquals(listOf(CardKeys.NOTE_BASIC), r.card.notes)
    }

    /** 주행 시작 때 이미 켜져 있던 경고등: 면제가 없다 */
    @Test
    fun b6LampAlreadyOnDoesNotExempt() {
        val r = run("b6")
        assertEquals(emptyList<RiskRec>(), r.risks)
        assertEquals(listOf(Exempt.NONE), r.basic.map { it.exempt })
        assertEquals(emptyList<BasicRec>(), r.exempts)
        assertEquals(mapOf("n" to 1), r.card.lines.single().args)
    }
}
