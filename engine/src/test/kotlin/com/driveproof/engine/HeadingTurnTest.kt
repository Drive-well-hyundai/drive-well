package com.driveproof.engine

import com.driveproof.model.Consts
import com.driveproof.model.Gear
import com.driveproof.model.GearPos
import com.driveproof.model.Heading
import com.driveproof.model.Principle
import com.driveproof.model.Speed
import com.driveproof.model.Steering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingTurnTest {
    @Test fun headingThresholdsComeFromConsts() {
        val turn = HeadingTurn(Consts(headingDeadDegPerS = 10.0, headingMaxDegPerS = 20.0))
        turn.observe(Heading(0, 0.0))
        turn.observe(Heading(1000, 9.0))
        assertEquals(0 to 0.0, turn.consume(1000))
        turn.observe(Heading(2000, 19.0))
        assertEquals(1 to 10.0, turn.consume(2000))
        turn.observe(Heading(3000, 39.0))
        assertEquals(1 to 20.0, turn.consume(3000))
        turn.observe(Heading(4000, 60.0))
        assertNull(turn.consume(4000))
    }

    @Test fun headingAcrossNorthCountsAsRightTurnWhenSteeringIsUnavailable() {
        val turn = runTrip(steeringAvailable = false)
        assertEquals(SceneKind.RIGHT_TURN, turn.single().kind)
        assertTrue(turn.single().estimated)
    }

    @Test fun steeringWinsAndMissingHeadingDropsTheEpisode() {
        assertTrue(runTrip(steeringAvailable = true).isEmpty())
        assertTrue(runTrip(steeringAvailable = false, losesHeading = true).isEmpty())
    }

    private fun runTrip(steeringAvailable: Boolean, losesHeading: Boolean = false): List<SceneRec> {
        val engine = TripEngine(Consts(minKm = 0.2))
        engine.onSignal(Gear(0, GearPos.D))
        if (steeringAvailable) engine.onSignal(Steering(0, 0.0))
        for (t in 0L..70_000L step 100L) {
            engine.onSignal(Speed(t, 30.0))
            if (t % 1000 == 0L) {
                val deg = when {
                    t <= 31_000 -> 350.0
                    t == 32_000L -> 15.0
                    t == 33_000L -> 40.0
                    losesHeading -> null
                    else -> 65.0
                }
                engine.onSignal(Heading(t, deg))
            }
        }
        return engine.end(70_000).scenes.filter { it.principle == Principle.DECEL }
    }
}
