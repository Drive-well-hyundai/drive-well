package com.driveproof.scoring

import com.driveproof.model.BasicKind
import com.driveproof.model.CardTopic
import com.driveproof.model.Consts
import com.driveproof.model.Exempt
import com.driveproof.model.Principle
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 한 주(또는 한 달)의 원칙 하나. [n] 기회 수, [sum] 부분 점수 합, [ok] 성공(s = 1) 수 */
data class PrincipleCount(val n: Int, val sum: Double, val ok: Int)

/**
 * 주간 요약(proof-report.md "주간 요약"). 추이 그래프, 주별 기록, 월별 기록의 총점은 이 값으로 만든다.
 * 주는 월요일 0시부터 일요일 24시까지다(차량 시각). [weekStart]는 그 월요일이다.
 *
 * - [avgOnDistanceM] 켠 거리 평균(켠 회전만), [avgMaxDecel] 회전·과속방지턱 앞 최대 감속 평균, [maxZoneKmh] 스쿨존 최고 속도
 * - [missedSignals] 깜빡이 없이 돈 회전 수
 * - [hardBrakes], [hardAccels], [overs]는 뺀 것을 제외한 횟수다
 * - [exemptTelltale] 경고등 구간이라 뺀 급감속, [exemptScene] 장면 구간이라 장면에만 반영한 급감속
 * - [fixes] 한 장에 고칠 점으로 나온 원칙별 횟수
 * - [total], [basic], [pSignal], [pDecel], [pZone]은 그 주 끝에서 183일 전까지의 기록으로 계산한 주 끝 점수다.
 *   기간 안에 주행이 없으면 null이다. 상수가 바뀌어도 다시 계산하지 않고 [ruleVersion]으로 구분한다
 */
data class WeeklySummary(
    val weekStart: LocalDate,
    val trips: Int,
    val km: Double,
    val signal: PrincipleCount,
    val decel: PrincipleCount,
    val zone: PrincipleCount,
    val avgOnDistanceM: Double?,
    val avgMaxDecel: Double?,
    val maxZoneKmh: Double?,
    val missedSignals: Int,
    val hardBrakes: Int,
    val hardAccels: Int,
    val overs: Int,
    val exemptTelltale: Int,
    val exemptScene: Int,
    val fixes: Map<CardTopic, Int>,
    val total: Double?,
    val basic: Double?,
    val pSignal: Double?,
    val pDecel: Double?,
    val pZone: Double?,
    val ruleVersion: String,
) {
    /** 그 주의 일요일 */
    val weekEnd: LocalDate get() = weekStart.plusDays(6)
}

/**
 * 월 집계(proof-report.md "월 집계"). 달력 월(차량 시각)로 센다.
 * [weeks]는 그달에 끝난 주의 수다. 4보다 작으면 월별 기록의 달 이름 옆에 주 수를 붙인다.
 * [total]은 그달에 끝난 마지막 주의 주 끝 총점이다. 그달에 끝난 주가 없으면 null이다.
 * [exemptTelltale]은 경고등 때문에 뺀 급감속만 센다. 장면 구간 것은 세지 않는다.
 */
data class MonthSummary(
    val year: Int,
    val month: Int,
    val weeks: Int,
    val trips: Int,
    val km: Double,
    val signal: PrincipleCount,
    val decel: PrincipleCount,
    val zone: PrincipleCount,
    val fixes: Map<CardTopic, Int>,
    val exemptTelltale: Int,
    val total: Double?,
)

private fun dateOf(t: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()

private fun startOfDay(d: LocalDate, zone: ZoneId): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()

/** [t]가 든 주의 월요일 */
fun weekStartOf(t: Long, zone: ZoneId): LocalDate = dateOf(t, zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

private fun count(scenes: List<SceneRow>, p: Principle): PrincipleCount {
    val list = scenes.filter { it.principle == p }
    return PrincipleCount(list.size, list.sumOf { it.s }, list.count { it.s >= 1.0 })
}

private fun fixCounts(trips: List<TripRow>): Map<CardTopic, Int> =
    CardTopic.entries.associateWith { topic -> trips.count { it.fix == topic } }

/**
 * 끝난 주의 주간 요약을 만든다. [from]이 든 주부터 [now]보다 먼저 끝난 주까지다.
 * 진행 중인 주는 만들지 않는다. 화면에서 [score]로 바로 계산해 추이선의 마지막 점으로 그린다.
 * 아직 쓰지 않은 지난 주를 채울 때는 [from]에 마지막으로 쓴 주의 다음 월요일을 준다. 주행이 없던 주도 한 줄 나온다.
 *
 * @param h 기록. 주 끝 점수가 183일을 거슬러 보므로 [from]보다 183일 앞선 것까지 있어야 한다
 * @param zone 차량 시각대
 */
fun weeklySummaries(h: History, from: Long, now: Long, zone: ZoneId, ruleVersion: String, c: Consts = Consts()): List<WeeklySummary> {
    val out = ArrayList<WeeklySummary>()
    var monday = weekStartOf(from, zone)
    while (true) {
        val start = startOfDay(monday, zone)
        val end = startOfDay(monday.plusDays(7), zone) // 일요일 24시
        if (end > now) break
        val trips = h.trips.filter { it.endAt in start until end }
        val scenes = h.scenes.filter { it.at in start until end }
        val basics = h.basics.filter { it.at in start until end }
        val counted = basics.filter { it.exempt == Exempt.NONE }
        val onDistances = scenes.filter { it.principle == Principle.SIGNAL }.mapNotNull { it.value }
        val decels = scenes.filter { it.principle == Principle.DECEL }.mapNotNull { it.value }
        val zoneSpeeds = scenes.filter { it.principle == Principle.ZONE }.mapNotNull { it.value }
        val atEnd = score(h, end - 1, c)
        out += WeeklySummary(
            weekStart = monday,
            trips = trips.size,
            km = trips.sumOf { it.km },
            signal = count(scenes, Principle.SIGNAL),
            decel = count(scenes, Principle.DECEL),
            zone = count(scenes, Principle.ZONE),
            avgOnDistanceM = onDistances.takeIf { it.isNotEmpty() }?.average(),
            avgMaxDecel = decels.takeIf { it.isNotEmpty() }?.average(),
            maxZoneKmh = zoneSpeeds.maxOrNull(),
            missedSignals = scenes.count { it.principle == Principle.SIGNAL && it.value == null },
            hardBrakes = counted.count { it.kind == BasicKind.DECEL },
            hardAccels = counted.count { it.kind == BasicKind.ACCEL },
            overs = counted.count { it.kind == BasicKind.OVER },
            exemptTelltale = basics.count { it.exempt == Exempt.TELLTALE },
            exemptScene = basics.count { it.exempt == Exempt.SCENE },
            fixes = fixCounts(trips),
            total = atEnd?.total,
            basic = atEnd?.basic,
            pSignal = atEnd?.signal?.p,
            pDecel = atEnd?.decel?.p,
            pZone = atEnd?.zone?.p,
            ruleVersion = ruleVersion,
        )
        monday = monday.plusDays(7)
    }
    return out
}

/**
 * 달마다 한 줄. 주행·장면·고칠 점·뺀 급감속은 그달의 기록에서, 달 끝 총점은 [weekly]에서 가져온다.
 * 기록도 끝난 주도 없는 달은 나오지 않는다. 오래된 달부터다.
 */
fun monthSummaries(h: History, weekly: List<WeeklySummary>, zone: ZoneId): List<MonthSummary> {
    data class Key(val year: Int, val month: Int)
    fun key(d: LocalDate) = Key(d.year, d.monthValue)
    val keys = (h.trips.map { key(dateOf(it.endAt, zone)) } + weekly.map { key(it.weekEnd) }).distinct()
        .sortedWith(compareBy<Key> { it.year }.thenBy { it.month })
    return keys.map { k ->
        val trips = h.trips.filter { key(dateOf(it.endAt, zone)) == k }
        val scenes = h.scenes.filter { key(dateOf(it.at, zone)) == k }
        val weeks = weekly.filter { key(it.weekEnd) == k }.sortedBy { it.weekStart }
        MonthSummary(
            year = k.year,
            month = k.month,
            weeks = weeks.size,
            trips = trips.size,
            km = trips.sumOf { it.km },
            signal = count(scenes, Principle.SIGNAL),
            decel = count(scenes, Principle.DECEL),
            zone = count(scenes, Principle.ZONE),
            fixes = fixCounts(trips),
            exemptTelltale = h.basics.count { it.exempt == Exempt.TELLTALE && key(dateOf(it.at, zone)) == k },
            total = weeks.lastOrNull()?.total,
        )
    }
}

/**
 * 이유 기록 [점수에서 제외]의 "넣었다면의 총점"(proof-report.md "이유 기록 항목").
 * 경고등 때문에 뺀 급감속을 기본 점수 B에 다시 넣어 계산한다. 장면 구간 것은 장면 점수에 이미 반영돼 있어 넣지 않는다.
 */
fun scoreCountingLampExempt(h: History, now: Long, c: Consts = Consts()): Score? =
    score(h.copy(basics = h.basics.map { if (it.exempt == Exempt.TELLTALE) it.copy(exempt = Exempt.NONE) else it }), now, c)
