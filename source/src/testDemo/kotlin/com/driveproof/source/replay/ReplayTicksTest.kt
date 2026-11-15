package com.driveproof.source.replay

import com.driveproof.model.Blink
import com.driveproof.model.Speed
import com.driveproof.model.Tbt
import com.driveproof.model.TurnSignal
import com.driveproof.model.TurnType
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayTicksTest {
    @Test
    fun groupsByTimeAndKeepsOrderWithinTick() {
        val tbt = Tbt(100, TurnType.TURN_LEFT, 15.0)
        val blink = TurnSignal(100, Blink.LEFT)
        val signals = listOf(Speed(0, 0.0), tbt, blink, Speed(200, 40.0))

        val ticks = ticksOf(signals)

        assertEquals(listOf(0L, 100L, 200L), ticks.map { it.t })
        // 같은 줄에서는 tbt를 먼저 내보내야 켠 거리가 그 줄의 distance가 된다
        assertEquals(listOf(tbt, blink), ticks[1].signals)
    }

    @Test
    fun emptyInputGivesNoTicks() {
        assertEquals(emptyList<ReplayTick>(), ticksOf(emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTimeGoingBackwards() {
        ticksOf(listOf(Speed(200, 0.0), Speed(100, 0.0)))
    }
}
