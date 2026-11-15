package com.driveproof.service.trip

import com.driveproof.data.BasicEventEntity
import com.driveproof.data.ExemptEventEntity
import com.driveproof.data.SceneEntity
import com.driveproof.data.TripEntity
import com.driveproof.data.TripRepository
import com.driveproof.engine.BasicRec
import com.driveproof.engine.Confirmed
import com.driveproof.engine.RiskRec
import com.driveproof.engine.SceneRec
import com.driveproof.model.Exempt
import com.driveproof.model.Principle
import com.driveproof.model.RULE_VERSION
import org.json.JSONObject

/** 모든 함수는 서비스 입출력 스레드에서 호출한다. */
internal class TripStore(private val repo: TripRepository) {
    private val db get() = repo.db

    fun insertOpen(startAt: Long, source: String, odoStart: Double?): Long = db.trips().insertOpen(
        TripEntity(profileId = repo.profile, startAt = startAt, isDemo = repo.demo, source = source, odoStart = odoStart),
    )

    fun checkpoint(tripId: Long, km: Double, odoStart: Double?, limitKnownKm: Double) {
        db.trips().byId(tripId)?.takeIf { it.status == "OPEN" }?.let {
            db.trips().update(it.copy(km = km, odoStart = it.odoStart ?: odoStart, limitKnownKm = limitKnownKm))
        }
    }

    fun findOpen() = db.trips().findOpen(repo.profile, repo.demo)

    fun reopen(tripId: Long) {
        db.trips().byId(tripId)?.let { db.trips().update(it.copy(status = "OPEN", endAt = null, seenAt = null)) }
    }

    fun append(tripId: Long, records: List<Confirmed>, toEpoch: (Long) -> Long) {
        db.runInTransaction {
            if (db.trips().byId(tripId) != null) writeRecords(tripId, records, toEpoch)
        }
    }

    fun end(tripId: Long, at: Long, event: TripEvent.Parked, result: com.driveproof.engine.TripResult,
            offset: Long, maker: EngineCardMaker): CardText? = db.runInTransaction<CardText?> {
        val trip = db.trips().byId(tripId) ?: return@runInTransaction null
        val scenes = if (event.short) emptyList() else result.scenes
        if (event.short) db.scenes().clear(tripId)
        writeRecords(tripId, scenes + result.basic + if (event.short) emptyList() else result.risks) { it + offset }
        val savedScenes = if (event.short) emptyList() else db.scenes().byTrip(tripId)
        val turns = savedScenes.filter { it.principle == Principle.SIGNAL.name }
        val ended = trip.copy(
            endAt = at, km = event.km, kmSource = event.kmSource.name,
            odoStart = trip.odoStart ?: event.odoStart, limitKnownKm = event.limitKnownKm,
            status = if (event.short) "SHORT" else "ENDED", seenAt = null,
            ruleVersion = RULE_VERSION,
            hadGap = event.hadGap || result.hadGap,
            naviRatio = turns.takeIf { it.isNotEmpty() }?.let { list -> list.count { it.path == "NAVI" }.toDouble() / list.size },
        )
        val card = if (event.short) null else maker.make(ended, result.card, savedScenes, db.basics().byTrip(tripId))
        db.trips().update(ended.copy(cardJson = card?.json))
        card
    }

    internal fun writeRecords(tripId: Long, records: List<Confirmed>, toEpoch: (Long) -> Long) {
        val risks = records.filterIsInstance<RiskRec>()
        val scenes = records.filterIsInstance<SceneRec>().map { scene ->
            val values = JSONObject().apply {
                put("onDistanceM", scene.onDistanceM)
                put("maxDecel", scene.maxDecel)
                put("passKmh", scene.passKmh)
                put("maxKmh", scene.maxKmh)
                put("limitKmh", scene.limitKmh)
                put("hardDecel", scene.hardDecel)
            }
            SceneEntity(tripId = tripId, principle = scene.principle.name, kind = scene.kind.name,
                at = toEpoch(scene.at), valuesJson = values.toString(),
                criterion = scene.criterionM ?: scene.limitKmh?.toDouble() ?: scene.hardDecel,
                s = scene.s, path = scene.path.name, estimated = scene.estimated)
        } + risks.map { risk ->
            SceneEntity(tripId = tripId, principle = Principle.RISK.name, kind = risk.lamp,
                at = toEpoch(risk.at),
                valuesJson = JSONObject().put("maxDecel", risk.maxDecel).put("exempts", risk.exempts).toString(),
                criterion = null, s = null, path = null, estimated = false)
        }
        val basics = records.filterIsInstance<BasicRec>()
        db.scenes().insert(scenes)
        db.basics().insert(basics.map {
            BasicEventEntity(tripId = tripId, kind = it.kind.name, at = toEpoch(it.at),
                value = it.value, exempt = it.exempt.name)
        })
        basics.filter { it.exempt == Exempt.NONE }.forEach { db.exempts().deleteEvent(tripId, toEpoch(it.at)) }
        db.exempts().insert(basics.filter { it.exempt != Exempt.NONE }.map { b ->
            val lamp = risks.firstOrNull { it.exempts && kotlin.math.abs(it.at - b.at) <= 5000 }?.lamp
                ?: db.scenes().byTrip(tripId).firstOrNull {
                    it.principle == Principle.RISK.name && kotlin.math.abs(it.at - toEpoch(b.at)) <= 5000
                }?.kind
            ExemptEventEntity(tripId = tripId, profileId = repo.profile, date = toEpoch(b.at),
                decel = b.value, reason = if (b.exempt == Exempt.TELLTALE) lamp ?: "TELLTALE" else "SCENE",
                mode = b.exempt.name, isDemo = repo.demo)
        })
    }
}
