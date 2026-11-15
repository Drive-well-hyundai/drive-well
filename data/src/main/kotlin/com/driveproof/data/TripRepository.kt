package com.driveproof.data

import com.driveproof.model.BasicKind
import com.driveproof.model.CardTopic
import com.driveproof.model.Exempt
import com.driveproof.model.Principle
import com.driveproof.scoring.BasicRow
import com.driveproof.scoring.History
import com.driveproof.scoring.SceneRow
import com.driveproof.scoring.TripRow
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** 서비스와 화면은 같은 저장소를 입출력 스레드에서 읽는다. */
class TripRepository(val db: DriveDatabase, val demo: Boolean, val profile: String = DEFAULT_PROFILE) {
    fun history(from: Long, exclude: Long = -1): History = db.runInTransaction<History> {
        val trips = db.trips().since(profile, demo, from, exclude)
        val endById = trips.associate { it.id to requireNotNull(it.endAt) }
        History(
            trips = trips.map { TripRow(endById.getValue(it.id), it.km, cardFix(it.cardJson), it.ruleVersion) },
            scenes = db.scenes().since(profile, demo, from, exclude).mapNotNull { row ->
                val s = row.s ?: return@mapNotNull null
                val principle = Principle.valueOf(row.principle)
                val key = when (principle) {
                    Principle.SIGNAL -> "onDistanceM"
                    Principle.DECEL -> "maxDecel"
                    Principle.ZONE -> "maxKmh"
                    Principle.RISK -> return@mapNotNull null
                }
                SceneRow(
                    endById.getValue(row.tripId), principle, s, row.value(key),
                    isBump = row.kind == "SPEED_BUMP",
                    passKmh = if (row.kind == "SPEED_BUMP") row.value("passKmh") else null,
                    limitKmh = if (principle == Principle.ZONE) row.value("limitKmh") ?: row.criterion else null,
                )
            },
            basics = db.basics().since(profile, demo, from, exclude).map {
                BasicRow(endById.getValue(it.tripId), BasicKind.valueOf(it.kind), Exempt.valueOf(it.exempt))
            },
        )
    }

    fun trim(now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val old = date.minusMonths(24)
        db.runInTransaction {
            db.trips().deleteBefore(date.minusMonths(7).atStartOfDay(zone).toInstant().toEpochMilli())
            db.exempts().deleteBefore(old.atStartOfDay(zone).toInstant().toEpochMilli())
            db.weeks().deleteBefore(old.toString())
        }
    }

    fun clear(onlyDemo: Boolean) {
        db.runInTransaction {
            db.exempts().clear(profile, onlyDemo)
            db.weeks().clear(profile, onlyDemo)
            db.trips().clear(profile, onlyDemo)
        }
    }
}

private fun cardFix(card: String?): CardTopic? = runCatching {
    val lines = JSONObject(card ?: return null).getJSONArray("lines")
    (0 until lines.length()).asSequence().map(lines::getJSONObject)
        .firstOrNull { it.optString("kind") == "FIX" }
        ?.let { CardTopic.valueOf(it.getString("principle")) }
}.getOrNull()
