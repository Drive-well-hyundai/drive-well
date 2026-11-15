package com.driveproof.scoring

import com.driveproof.model.BasicKind
import com.driveproof.model.Principle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 한 장의 점수 줄(scoring.md "화면에 보여 주는 값", "예외와 빈 상태") */
class ScoreLineTest {

    private val now = 1_800_000_000_000L

    private val steady = History(
        trips = List(40) { TripRow(now, 20.0) },
        scenes = List(60) { SceneRow(now, Principle.SIGNAL, 0.8) },
    )

    private fun line(before: History, trip: History) = scoreLine(score(before, now), score(before + trip, now)!!)

    @Test
    fun firstTripSaysItIsTheFirstRecord() {
        val l = line(History(), History(listOf(TripRow(now, 1.4)), listOf(SceneRow(now, Principle.SIGNAL, 0.5))))
        assertEquals("card_score_first", l.key)
        assertEquals(mapOf("n" to 1), l.args)
        assertNull(l.delta)
    }

    @Test
    fun betterTripShowsThePointsGained() {
        // 800km를 달린 기록에 12km, 잘한 회전 20번을 더한다
        val l = line(steady, History(listOf(TripRow(now, 12.0)), List(20) { SceneRow(now, Principle.SIGNAL, 1.0) }))
        assertEquals("card_score_up", l.key)
        assertEquals(mapOf("d" to l.delta), l.args)
        assertEquals(true, l.delta!! > 0)
    }

    @Test
    fun worseTripShowsThePointsLostAsAPositiveNumber() {
        val l = line(steady, History(listOf(TripRow(now, 12.0)), basics = List(8) { BasicRow(now, BasicKind.DECEL) }))
        assertEquals("card_score_down", l.key)
        assertEquals(mapOf("d" to -l.delta!!), l.args)
        assertEquals(true, l.delta!! < 0)
    }

    @Test
    fun sameRoundedScoreSaysNoChange() {
        val l = line(steady, History(listOf(TripRow(now, 3.0)), listOf(SceneRow(now, Principle.SIGNAL, 0.8))))
        assertEquals("card_score_same", l.key)
        assertEquals(0, l.delta)
        assertEquals(emptyMap<String, Any>(), l.args)
    }
}
