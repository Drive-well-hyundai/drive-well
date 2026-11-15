package com.driveproof.service.trip

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.driveproof.data.DriveDatabase
import com.driveproof.data.SceneEntity
import com.driveproof.data.TripEntity
import com.driveproof.data.TripRepository
import com.driveproof.engine.Card
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatedCoachingTest {
    @Test fun fourthEligibleTripShowsChangeAndADifferentFixBreaksTheRun() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DriveDatabase::class.java).build()
        try {
            val maker = EngineCardMaker(context, TripRepository(db, demo = true))
            fun card(text: String, meters: Int = 12) =
                """{"lines":[{"kind":"FIX","principle":"SIGNAL","key":"card_fix_signal_one",
                    "args":{"turn":"card_word_left_turn","m":$meters},"text":"$text"}]}"""
            val oldCard = card("좌회전할 때 깜빡이를 12m 전에 켜셨어요. 30m 전부터 켜 보세요")
            val newCard = card("좌회전 12m 전에 깜빡이를 켰어요. 다음엔 30m 전부터 켜 주세요")
            val start = 1_795_000_000_000L
            fun trip(index: Int, km: Double = 1.0, demo: Boolean = true, source: String = "REPLAY") =
                TripEntity(startAt = start + index * 100_000, endAt = start + index * 100_000 + 80_000,
                    status = "ENDED", isDemo = demo, source = source, km = km, cardJson = oldCard)
            fun scene(tripId: Long, at: Long, meters: Double) = SceneEntity(
                tripId = tripId, principle = "SIGNAL", kind = "LEFT_TURN", at = at,
                valuesJson = JSONObject().put("onDistanceM", meters).toString(),
                criterion = 30.0, s = (meters / 30.0).coerceAtMost(1.0), path = "NAVI", estimated = false,
            )
            val prior = (1..3).map { index ->
                val row = trip(index)
                val id = db.trips().insertOpen(row)
                db.scenes().insert(listOf(scene(id, row.startAt, 12.0 + index)))
                row.copy(id = id)
            }
            db.trips().insertOpen(trip(4, km = 0.1))
            db.trips().insertOpen(trip(5, demo = false))
            val seed = trip(6, source = "SEED")
            db.trips().insertOpen(seed.copy(cardJson = null))
            val current = trip(7)
            fun render(trip: TripEntity = current) = requireNotNull(maker.make(trip.copy(id = 10), Card(emptyList(), emptyList()),
                listOf(scene(10, trip.startAt, 34.0)), emptyList()))
            val expected = "깜빡이를 켠 거리 · 이전 평균 14m → 이번 34m"
            assertEquals(expected, render().firstLine)
            db.trips().update(prior[1].copy(cardJson = newCard))
            assertEquals(expected, render().firstLine)
            assertEquals(oldCard, db.trips().byId(prior[0].id)?.cardJson)
            assertEquals(null, render(seed).firstLine)
            db.trips().update(prior[1].copy(cardJson = card("좌회전 15m 전에 깜빡이를 켰어요. 다음엔 30m 전부터 켜 주세요", 15)))
            assertEquals(null, render().firstLine)
            db.trips().update(prior[1].copy(cardJson = """{"lines":[{"kind":"FIX","principle":"SIGNAL","text":"회전 전에 더 미리 켜 보세요"}]}"""))
            assertEquals(null, render().firstLine)
        } finally {
            db.close()
        }
    }
}
