package com.driveproof.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ValuesTest {
    @Test
    fun turnTypeKeepsJudgedNamesAndFoldsTheRest() {
        assertEquals(TurnType.TURN_LEFT, TurnType.fromName("TURN_LEFT"))
        assertEquals(TurnType.TURN_STRAIGHT, TurnType.fromName("TURN_STRAIGHT"))
        assertEquals(TurnType.OTHER, TurnType.fromName("TURN_ENTER_IC_L"))
    }

    @Test
    fun cautionTypeKeepsSchoolZoneAndSpeedBump() {
        assertEquals(CautionType.SCHOOL_ZONE, CautionType.fromName("SCHOOL_ZONE"))
        assertEquals(CautionType.SPEED_BUMP, CautionType.fromName("SPEED_BUMP"))
        assertEquals(CautionType.OTHER, CautionType.fromName("RAILROAD_CROSSING"))
    }
}
