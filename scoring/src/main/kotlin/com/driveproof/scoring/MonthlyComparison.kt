package com.driveproof.scoring

import com.driveproof.model.Consts
import com.driveproof.model.Principle
import com.driveproof.model.RULE_VERSION
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeParseException

data class MonthlyComparison(
    val ruleVersion: String,
    val changedOn: LocalDate,
    val previous: Map<Principle, PrincipleCount>,
    val current: Map<Principle, PrincipleCount>,
    val unavailable: Set<Principle>,
)

/** 월간 성공률에만 새 기준을 적용한다. 보관된 장면 점수와 주간 요약은 수정하지 않는다. */
fun compareAfterRuleChange(
    history: History,
    month: YearMonth,
    zone: ZoneId,
    activeVersion: String = RULE_VERSION,
    c: Consts = Consts(),
): MonthlyComparison? {
    val previousMonth = month.minusMonths(1)
    fun monthOf(at: Long) = YearMonth.from(Instant.ofEpochMilli(at).atZone(zone))
    val trips = history.trips.filter { monthOf(it.endAt) in previousMonth..month }
    val version = trips.filter { monthOf(it.endAt) == month }.maxByOrNull { it.endAt }?.ruleVersion ?: return null
    val change = try { LocalDate.parse(version) } catch (_: DateTimeParseException) { return null }
    if (YearMonth.from(change) != month || trips.none { it.ruleVersion != null && it.ruleVersion != version }) return null

    val scenes = history.scenes.filter { monthOf(it.at) in previousMonth..month }
    val unavailable = mutableSetOf<Principle>()
    val previous = mutableMapOf<Principle, PrincipleCount>()
    val current = mutableMapOf<Principle, PrincipleCount>()
    scenes.groupBy { it.principle }.forEach { (principle, records) ->
        val results = records.map { scene -> scene to if (version == activeVersion) scene.succeeded(c) else null }
        if (results.any { it.second == null }) {
            unavailable += principle
        } else {
            fun count(period: YearMonth): PrincipleCount {
                val rows = results.filter { monthOf(it.first.at) == period }
                val ok = rows.count { it.second == true }
                return PrincipleCount(rows.size, ok.toDouble(), ok)
            }
            previous[principle] = count(previousMonth)
            current[principle] = count(month)
        }
    }
    return MonthlyComparison(version, change, previous, current, unavailable)
}

private fun SceneRow.succeeded(c: Consts): Boolean? = when (principle) {
    Principle.SIGNAL -> value?.let { it >= c.signalRefM } ?: if (s == 0.0) false else null
    Principle.DECEL -> value?.let { decel ->
        if (isBump) passKmh?.let { it <= c.bumpOkKmh && decel <= c.gentleDecel + 1e-6 }
        else decel <= c.gentleDecel + 1e-6
    }
    Principle.ZONE -> value?.let { speed -> limitKmh?.let { speed <= it } }
    Principle.RISK -> null
}
