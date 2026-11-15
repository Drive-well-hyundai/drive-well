package com.driveproof.data

import com.driveproof.model.CardTopic
import com.driveproof.model.RULE_VERSION
import com.driveproof.scoring.History
import com.driveproof.scoring.PrincipleCount
import com.driveproof.scoring.WeeklySummary
import com.driveproof.scoring.weeklySummaries
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 이미 쓴 주는 보존한다. 진행 중인 주는 화면에서만 계산한다. */
fun TripRepository.refreshWeeks(now: Long, zone: ZoneId = ZoneId.systemDefault()): List<WeeklySummary> =
    db.runInTransaction<List<WeeklySummary>> {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val retained = date.minusMonths(24)
        val records = history(date.minusMonths(7).atStartOfDay(zone).toInstant().toEpochMilli())
        val first = records.trips.firstOrNull()?.endAt
        val last = db.weeks().lastWritten(profile, demo)?.let(LocalDate::parse)
        val from = last?.plusWeeks(1)?.coerceAtLeast(retained)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: first
        if (from != null) {
            val missing = weeklySummaries(records, from, now, zone, RULE_VERSION)
            db.weeks().upsert(missing.map { WeeklySummaryEntity(it.weekStart.toString(), profile, demo, it.encode(), it.ruleVersion) })
        }
        db.weeks().range(profile, demo, retained.toString(), date.toString()).map { it.decode() }
    }

data class ProofSnapshot(val history: History, val weeks: List<WeeklySummary>, val now: Long, val zone: ZoneId)

fun TripRepository.proofSnapshot(now: Long, zone: ZoneId = ZoneId.systemDefault()): ProofSnapshot =
    db.runInTransaction<ProofSnapshot> {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        ProofSnapshot(history(date.minusMonths(7).atStartOfDay(zone).toInstant().toEpochMilli()),
            refreshWeeks(now, zone), now, zone)
    }

private fun PrincipleCount.array() = JSONArray().put(n).put(sum).put(ok)

private fun WeeklySummary.encode() = JSONObject().apply {
    put("trips", trips)
    put("km", km)
    put("signal", signal.array())
    put("decel", decel.array())
    put("zone", zone.array())
    put("on", avgOnDistanceM)
    put("slow", avgMaxDecel)
    put("max", maxZoneKmh)
    put("missed", missedSignals)
    put("brakes", hardBrakes)
    put("accels", hardAccels)
    put("overs", overs)
    put("telltale", exemptTelltale)
    put("scene", exemptScene)
    put("fix", JSONObject(fixes.mapKeys { it.key.name }))
    put("total", total)
    put("basic", basic)
    put("pSignal", pSignal)
    put("pDecel", pDecel)
    put("pZone", pZone)
}.toString()

private fun WeeklySummaryEntity.decode(): WeeklySummary {
    val j = JSONObject(summaryJson)
    fun count(key: String) = j.getJSONArray(key).let { PrincipleCount(it.getInt(0), it.getDouble(1), it.getInt(2)) }
    fun nullable(key: String) = if (j.isNull(key)) null else j.getDouble(key)
    return WeeklySummary(
        LocalDate.parse(weekStart), j.getInt("trips"), j.getDouble("km"), count("signal"), count("decel"), count("zone"),
        nullable("on"), nullable("slow"), nullable("max"), j.getInt("missed"), j.getInt("brakes"), j.getInt("accels"),
        j.getInt("overs"), j.getInt("telltale"), j.getInt("scene"),
        CardTopic.entries.associateWith { j.getJSONObject("fix").optInt(it.name) },
        nullable("total"), nullable("basic"), nullable("pSignal"), nullable("pDecel"), nullable("pZone"), ruleVersion,
    )
}
