package com.driveproof.service.trip

import com.driveproof.model.Gear
import com.driveproof.model.GearPos
import com.driveproof.model.Odometer
import com.driveproof.model.RoadLimit
import com.driveproof.model.Speed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStateMachineTest {

    private fun machine() = TripStateMachine(minKm = 0.2)

    @Test
    fun parkedTripKeepsStartOdometerAndCountsOnlyFreshSpeedWithAKnownLimit() {
        val m = machine()
        m.onSignal(Odometer(0, 10.0))
        m.onSignal(Gear(0, GearPos.D))
        m.onSignal(Speed(0, 36.0))
        m.onSignal(RoadLimit(1000, 30))
        m.onSignal(Speed(2000, 36.0))
        m.onSignal(RoadLimit(3000, 0))
        m.onSignal(Speed(4000, 36.0))
        m.onSignal(RoadLimit(4000, 30))
        m.onSignal(Speed(8000, 0.0))
        m.onSignal(Odometer(8000, 10.08))
        val trip = m.onSignal(Gear(8000, GearPos.P)).single() as TripEvent.Parked
        assertEquals(10.0, requireNotNull(trip.odoStart), 1e-9)
        assertEquals(0.02, trip.limitKnownKm, 1e-9)
    }

    @Test
    fun startsWhenNotParkedAndMoving() {
        val m = machine()
        assertTrue(m.onSignal(Gear(0, GearPos.D)).isEmpty()) // 속도 0
        val ev = m.onSignal(Speed(100, 10.0))
        assertEquals(listOf(TripEvent.Started(100)), ev)
        assertEquals(TripState.RECORDING, m.state)
    }

    @Test
    fun doesNotStartInParkEvenWithSpeed() {
        val m = machine()
        m.onSignal(Gear(0, GearPos.P))
        assertTrue(m.onSignal(Speed(100, 10.0)).isEmpty())
        assertEquals(TripState.IDLE, m.state)
    }

    @Test
    fun parkThenResumeWithin30sIsSameTrip() {
        val m = machine()
        m.onSignal(Gear(0, GearPos.D)); m.onSignal(Speed(0, 36.0))
        m.onSignal(Speed(10_000, 0.0))
        assertTrue(m.onSignal(Gear(10_000, GearPos.P)).single() is TripEvent.Parked)
        m.onSignal(Gear(20_000, GearPos.D))
        assertEquals(listOf(TripEvent.Resumed(25_000)), m.onSignal(Speed(25_000, 10.0)))
        assertEquals(TripState.RECORDING, m.state)
    }

    @Test
    fun endsAfter30sOfParkBySignalTime() {
        val m = machine()
        m.onSignal(Gear(0, GearPos.D)); m.onSignal(Speed(0, 36.0)) // 10m/s
        m.onSignal(Speed(60_000, 0.0)) // 60초 × 36km/h = 0.6km
        val parked = m.onSignal(Gear(60_000, GearPos.P)).single() as TripEvent.Parked
        assertEquals(0.6, parked.km, 1e-9)
        assertFalse(parked.short)
        val ev = m.onSignal(Speed(95_000, 0.0)).single() as TripEvent.Ended
        assertEquals(90_000, ev.t)
        assertEquals(0L, ev.startAt)
        assertEquals(0.6, ev.km, 1e-9)
        assertEquals(KmSource.INTEGRAL, ev.kmSource)
        assertFalse(ev.short)
        assertEquals(TripState.IDLE, m.state)
    }

    @Test
    fun expireEndsWhenSignalsStop() {
        val m = machine()
        m.onSignal(Gear(0, GearPos.D)); m.onSignal(Speed(0, 36.0))
        m.onSignal(Speed(10_000, 0.0)); m.onSignal(Gear(10_000, GearPos.P))
        val ev = m.expire().single() as TripEvent.Ended
        assertEquals(40_000, ev.t)
        assertTrue(ev.short) // 0.1km < 0.2km
        assertTrue(m.expire().isEmpty())
    }

    @Test
    fun odometerDifferenceWinsWhenItMoves() {
        val m = machine()
        m.onSignal(Odometer(0, 100.0))
        m.onSignal(Gear(0, GearPos.D)); m.onSignal(Speed(0, 36.0))
        m.onSignal(Odometer(50_000, 101.5))
        m.onSignal(Speed(60_000, 0.0)); m.onSignal(Gear(60_000, GearPos.P))
        val ev = m.expire().single() as TripEvent.Ended
        assertEquals(1.5, ev.km, 1e-9)
        assertEquals(KmSource.ODOMETER, ev.kmSource)
    }

    @Test
    fun odometerStuckFallsBackToIntegral() {
        // 에뮬레이터는 주행거리가 0에서 움직이지 않았다(sdk.md 11/11)
        val m = machine()
        m.onSignal(Odometer(0, 0.0))
        m.onSignal(Gear(0, GearPos.D)); m.onSignal(Speed(0, 36.0))
        m.onSignal(Odometer(30_000, 0.0))
        m.onSignal(Speed(60_000, 0.0)); m.onSignal(Gear(60_000, GearPos.P))
        val ev = m.expire().single() as TripEvent.Ended
        assertEquals(0.6, ev.km, 1e-9)
        assertEquals(KmSource.INTEGRAL, ev.kmSource)
    }

    @Test
    fun restoreContinuesWhenNotParkedAndAddsSavedKm() {
        // 비정상 종료 전 0.3km를 저장했다. 다시 뜬 뒤 기어 D로 60초 36km/h = 0.6km
        val m = machine()
        m.restore(startAt = 1_000, kmSoFar = 0.3)
        assertEquals(TripState.RECORDING, m.state)
        assertTrue(m.onSignal(Gear(100_000, GearPos.D)).isEmpty())
        assertTrue(m.onSignal(Speed(100_000, 36.0)).isEmpty()) // 새 주행으로 시작하지 않는다
        m.onSignal(Speed(160_000, 0.0))
        val parked = m.onSignal(Gear(160_000, GearPos.P)).single() as TripEvent.Parked
        assertEquals(1_000, parked.startAt)
        assertEquals(0.9, parked.km, 1e-9)
        assertTrue(parked.hadGap)
        val ended = m.expire().single() as TripEvent.Ended
        assertTrue(ended.hadGap)
    }

    @Test
    fun restoreEndsAtOnceWhenParked() {
        val m = machine()
        m.restore(startAt = 1_000, kmSoFar = 0.5)
        val parked = m.onSignal(Gear(50_000, GearPos.P)).single() as TripEvent.Parked
        assertEquals(0.5, parked.km, 1e-9)
        assertFalse(parked.short)
        assertTrue(parked.hadGap)
    }

    @Test
    fun hadGapIsClearedForTheNextTrip() {
        val m = machine()
        m.restore(startAt = 0, kmSoFar = 0.5)
        m.onSignal(Gear(10_000, GearPos.P))
        m.expire()
        m.onSignal(Gear(100_000, GearPos.D)); m.onSignal(Speed(100_000, 36.0))
        m.onSignal(Speed(130_000, 0.0))
        val parked = m.onSignal(Gear(130_000, GearPos.P)).single() as TripEvent.Parked
        assertFalse(parked.hadGap)
        assertEquals(0.3, parked.km, 1e-9)
    }

    @Test
    fun newTripAfterEndStartsFromZero() {
        val m = machine()
        m.onSignal(Gear(0, GearPos.D)); m.onSignal(Speed(0, 36.0))
        m.onSignal(Speed(60_000, 0.0)); m.onSignal(Gear(60_000, GearPos.P))
        m.expire()
        m.onSignal(Gear(200_000, GearPos.D))
        assertEquals(listOf(TripEvent.Started(201_000)), m.onSignal(Speed(201_000, 18.0)))
        m.onSignal(Speed(211_000, 0.0)); m.onSignal(Gear(211_000, GearPos.P))
        val ev = m.expire().single() as TripEvent.Ended
        assertEquals(0.05, ev.km, 1e-9)
    }
}
