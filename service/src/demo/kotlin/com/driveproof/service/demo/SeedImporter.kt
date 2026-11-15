package com.driveproof.service.demo

import android.content.Context
import com.driveproof.data.DriveDatabase
import com.driveproof.data.ExemptEventEntity
import com.driveproof.data.TripEntity
import com.driveproof.data.TripRepository
import com.driveproof.data.refreshWeeks
import com.driveproof.engine.parseSeed
import com.driveproof.engine.seedRecords
import com.driveproof.service.trip.EngineCardMaker
import com.driveproof.service.trip.TripStore
import java.time.LocalDate
import java.time.ZoneId

internal fun importSeed(context: Context, name: String?, now: String?): Int {
    require(name == "m6")
    val zone = ZoneId.systemDefault()
    val date = now?.let(LocalDate::parse) ?: LocalDate.now(zone)
    val records = context.assets.open("m6.json").bufferedReader().use { seedRecords(parseSeed(it.readText()), date, zone) }
    val repo = TripRepository(DriveDatabase.get(context), demo = true)
    val db = repo.db
    val store = TripStore(repo)
    val maker = EngineCardMaker(context, repo)
    db.runInTransaction {
        db.exempts().clearSeed(repo.profile)
        db.trips().clearSeed(repo.profile)
        db.weeks().clear(repo.profile, onlyDemo = true)
        records.forEach { r ->
            val trip = TripEntity(startAt = r.endAt, isDemo = true, source = "SEED")
            val id = db.trips().insertOpen(trip)
            // 시드 장면의 날짜는 같지만 같은 종류의 기회도 모두 남긴다.
            val scenes = r.scenes.mapIndexed { i, scene -> scene.copy(at = r.endAt - i) }
            val basic = r.basic.mapIndexed { i, event -> event.copy(at = r.endAt - i) }
            store.writeRecords(id, scenes + basic) { it }
            val exemptDates = basic.filter { it.exempt != com.driveproof.model.Exempt.NONE }.map { it.at }
            db.exempts().insert(r.exempts.mapIndexed { i, e ->
                ExemptEventEntity(tripId = id, date = exemptDates.getOrNull(i) ?: r.endAt - i, decel = e.decel,
                    reason = e.reason, mode = e.mode.name, isDemo = true)
            })
            val ended = trip.copy(id = id, endAt = r.endAt, km = r.km, status = "ENDED", seenAt = r.endAt)
            val card = maker.make(ended, r.card, db.scenes().byTrip(id), db.basics().byTrip(id))
            db.trips().update(ended.copy(cardJson = card?.json))
        }
        repo.refreshWeeks(date.atStartOfDay(zone).toInstant().toEpochMilli(), zone)
    }
    return records.size
}
