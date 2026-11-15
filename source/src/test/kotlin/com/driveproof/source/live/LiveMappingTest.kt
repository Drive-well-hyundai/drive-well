package com.driveproof.source.live

import com.driveproof.model.Blink
import com.driveproof.model.CautionType
import com.driveproof.model.GearPos
import com.driveproof.model.Lamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMappingTest {
    @Test
    fun speedIsMetersPerSecondTimes3_6() {
        // sdk.md 11/10: 콘솔 30km/h → 8.33m/s
        assertEquals(30.0, LiveMapping.kmh(8.333333f), 0.01)
    }

    @Test
    fun gearUsesAaosValues() {
        assertEquals(GearPos.D, LiveMapping.gear(8))
        assertEquals(GearPos.P, LiveMapping.gear(4))
        assertEquals(GearPos.R, LiveMapping.gear(2))
        assertEquals(GearPos.N, LiveMapping.gear(1))
        assertEquals(GearPos.D, LiveMapping.gear(16)) // 수동 1단
        assertNull(LiveMapping.gear(0))
    }

    @Test
    fun blinkUsesPleosNames() {
        assertEquals(Blink.LEFT, LiveMapping.blink("LEFT"))
        assertEquals(Blink.OFF, LiveMapping.blink("OFF"))
        assertNull(LiveMapping.blink(null))
    }

    @Test
    fun steeringKeepsSdkSignByDefault() {
        assertEquals(-300.0, LiveMapping.steeringDeg(-300f), 0.0)
    }

    @Test
    fun telltaleListDiffEmitsChangesAndOffForMissing() {
        // 11/10 실측: ABS ON, ESC BLINK. 끄면 빈 목록
        val (s1, c1) = LiveMapping.telltaleChanges(emptyMap(), listOf("ABS" to "ON", "ESC" to "BLINK"))
        assertEquals(listOf("ABS" to Lamp.ON, "ESC" to Lamp.BLINK), c1)
        val (s2, c2) = LiveMapping.telltaleChanges(s1, listOf("ABS" to "ON", "ESC" to "BLINK"))
        assertTrue(c2.isEmpty())
        val (s3, c3) = LiveMapping.telltaleChanges(s2, listOf("ESC" to "ON"))
        assertEquals(listOf("ESC" to Lamp.ON, "ABS" to Lamp.OFF), c3)
        val (s4, c4) = LiveMapping.telltaleChanges(s3, emptyList())
        assertEquals(listOf("ESC" to Lamp.OFF), c4)
        assertTrue(s4.isEmpty())
    }

    @Test
    fun nearestCautionKeepsSchoolZoneAndBumpOnly() {
        val pts = listOf("RAILROAD_CROSSING" to 20, "SCHOOL_ZONE" to 150, "SPEED_BUMP" to 80)
        assertEquals(CautionType.SPEED_BUMP to 80, LiveMapping.nearestCaution(pts))
        assertNull(LiveMapping.nearestCaution(listOf("FOG_AREA" to 10)))
        assertNull(LiveMapping.nearestCaution(emptyList()))
    }

    @Test
    fun schoolZoneLimitFromSchoolCamerasOnly() {
        val cams = listOf(
            Triple("SPEED_CAMERA_FIX", 60, 50),
            Triple("SPEED_CAMERA_SCHOOL_ZONE_TRAFFIC_LIGHT", 30, 200),
            Triple("SPEED_CAMERA_SCHOOL_ZONE", 40, 120),
        )
        assertEquals(40, LiveMapping.schoolZoneLimit(cams))
        assertNull(LiveMapping.schoolZoneLimit(listOf(Triple("SPEED_CAMERA_SCHOOL_ZONE", 0, 10))))
    }

    @Test
    fun routeDriveOnlyForRouteDrive() {
        assertTrue(LiveMapping.routeDrive("ROUTE_DRIVE"))
        assertFalse(LiveMapping.routeDrive("FREE_DRIVE"))
    }
}
