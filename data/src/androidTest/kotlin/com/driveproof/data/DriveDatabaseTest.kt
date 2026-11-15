package com.driveproof.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class DriveDatabaseTest {
    private lateinit var db: DriveDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, DriveDatabase::class.java).build()
    }

    @After fun close() = db.close()

    @Test
    fun unseenSelectsOnlyCompletedCardsInCurrentPartitionAndDoesNotMarkReplacedCard() {
        run {
                val trips = db.trips()
                val id = trips.insertOpen(TripEntity(startAt = 1, endAt = 2, status = "ENDED",
                    isDemo = true, source = "REPLAY", cardJson = "{}"))
                trips.insertOpen(TripEntity(startAt = 3, endAt = 4, status = "ENDED",
                    isDemo = false, source = "LIVE", cardJson = "{}"))
                trips.insertOpen(TripEntity(startAt = 5, endAt = 6, status = "SHORT",
                    isDemo = true, source = "REPLAY"))
                assertEquals(id, trips.latestUnseen(DEFAULT_PROFILE, true)?.id)
                trips.update(requireNotNull(trips.byId(id)).copy(endAt = 8))
                trips.markSeen(id, 9, 2)
                assertEquals(id, trips.latestUnseen(DEFAULT_PROFILE, true)?.id)
                trips.markSeen(id, 9, 8)
                assertNull(trips.latestUnseen(DEFAULT_PROFILE, true))
            }
    }

    @Test
    fun expiredTripsKeepExemptEventsUntilExplicitClear() {
        run {
                val id = db.trips().insertOpen(TripEntity(startAt = 1, endAt = 2, status = "ENDED",
                    isDemo = true, source = "REPLAY"))
                db.scenes().insert(listOf(SceneEntity(tripId = id, principle = "SIGNAL", kind = "LEFT_TURN",
                    at = 2, valuesJson = "{}", criterion = 30.0, s = 1.0, path = "NAVI", estimated = false)))
                db.exempts().insert(listOf(ExemptEventEntity(tripId = id, date = 2, decel = 15.0,
                    reason = "ABS", mode = "TELLTALE", isDemo = true)))
                db.trips().deleteBefore(3)
                assertTrue(db.scenes().byTrip(id).isEmpty())
                assertNull(db.exempts().since(DEFAULT_PROFILE, true, 0, "TELLTALE").single().tripId)
                TripRepository(db, demo = true).clear(onlyDemo = true)
                assertTrue(db.exempts().since(DEFAULT_PROFILE, true, 0, "TELLTALE").isEmpty())
            }
    }
}
