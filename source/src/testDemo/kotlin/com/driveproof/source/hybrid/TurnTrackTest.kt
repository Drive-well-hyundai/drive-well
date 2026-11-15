package com.driveproof.source.hybrid

import com.driveproof.model.TurnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTrackTest {

    private val route = listOf(
        Turn(TurnType.TURN_LEFT, 80.0),     // 11/10 경로의 첫 회전
        Turn(TurnType.TURN_RIGHT, 300.0),
    )

    @Test
    fun remainingShrinksWithLiveSpeed() {
        val t = TurnTrack(route)
        // 30km/h = 8.33m/s. 0.1초씩 30번 = 3초 = 25m
        repeat(30) { assertTrue(t.advance(100, 30.0).isEmpty()) }
        assertEquals(55.0, t.remainingM, 1e-6)
        assertEquals(TurnType.TURN_LEFT, t.current?.type)
    }

    @Test
    fun stoppedCarKeepsDistance() {
        val t = TurnTrack(route)
        t.advance(5_000, 0.0)
        assertEquals(80.0, t.remainingM, 0.0)
    }

    @Test
    fun crossingZeroMovesToNextAndCarriesOverflow() {
        val t = TurnTrack(route)
        t.advance(9_000, 30.0) // 75m
        val passed = t.advance(1_000, 36.0) // 10m 더: 80 지점을 5m 넘음
        assertEquals(listOf(route[0]), passed)
        assertEquals(TurnType.TURN_RIGHT, t.current?.type)
        assertEquals(295.0, t.remainingM, 1e-6)
    }

    @Test
    fun endOfListStopsAtZero() {
        val t = TurnTrack(route)
        val passed = t.advance(100_000, 36.0) // 1000m
        assertEquals(route, passed)
        assertTrue(t.finished)
        assertEquals(0.0, t.remainingM, 0.0)
        assertTrue(t.advance(1_000, 36.0).isEmpty())
    }
}
