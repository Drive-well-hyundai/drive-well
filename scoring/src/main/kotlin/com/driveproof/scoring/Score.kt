package com.driveproof.scoring

import com.driveproof.model.BasicKind
import com.driveproof.model.CardTopic
import com.driveproof.model.Consts
import com.driveproof.model.Exempt
import com.driveproof.model.Principle

/**
 * 끝난 주행 하나. [endAt]은 달력 시각(ms)이다. SHORT 주행도 거리와 기본 항목은 넣는다.
 * [fix]는 그 주행의 한 장에 고칠 점으로 나온 원칙이다(`cardJson`의 FIX 줄). 없으면 null이다. 주간 요약과 월 집계에만 쓴다.
 */
data class TripRow(val endAt: Long, val km: Double, val fix: CardTopic? = null, val ruleVersion: String? = null)

/**
 * 장면 하나의 부분 점수. 위험 장면(RISK)은 넣어도 세지 않는다.
 * [value]는 그 장면의 행동 값이다. 미리 켜기는 켠 거리(m, 안 켰으면 null), 미리 줄이기는 최대 감속(km/h/s),
 * 구역 지키기는 구역 안 최고 속도(km/h)다. 주간 요약의 행동 평균에만 쓴다. 점수에는 쓰지 않는다.
 */
data class SceneRow(
    val at: Long,
    val principle: Principle,
    val s: Double,
    val value: Double? = null,
    val isBump: Boolean = false,
    val passKmh: Double? = null,
    val limitKmh: Double? = null,
)

/** 기본 항목 이벤트 하나. 면제된 것은 세지 않는다 */
data class BasicRow(val at: Long, val kind: BasicKind, val exempt: Exempt = Exempt.NONE)

/**
 * 점수의 입력. `:data`가 저장한 기록에서 만든다. 시각은 모두 달력 시각(ms)이다.
 * 기간(최근 183일)은 [score]가 거른다. 넉넉하게 넘겨도 된다.
 */
data class History(
    val trips: List<TripRow> = emptyList(),
    val scenes: List<SceneRow> = emptyList(),
    val basics: List<BasicRow> = emptyList(),
) {
    operator fun plus(other: History) = History(trips + other.trips, scenes + other.scenes, basics + other.basics)
}

/** 원칙 하나의 점수. [p]는 기회가 0번이면 null이다. [ok]는 성공(s = 1)한 장면 수다 */
data class PrincipleScore(val p: Double?, val n: Int, val sum: Double, val ok: Int)

/**
 * 점수(scoring.md "산정식"). 계산은 소수로 하고 화면에서 반올림한다.
 * [total] T, [basic] B, [basicObserved] B_obs, [situation] S(원칙이 모두 기회 0번이면 null이고 T = B다).
 * [qualified]가 false면 기간 안 주행거리가 500km에 못 미친다. "500km까지 · 현재 [km]km"로 보여 준다.
 */
data class Score(
    val total: Double,
    val basic: Double,
    val basicObserved: Double,
    val situation: Double?,
    val signal: PrincipleScore,
    val decel: PrincipleScore,
    val zone: PrincipleScore,
    val km: Double,
    val trips: Int,
    val hardBrakes: Int,
    val hardAccels: Int,
    val overs: Int,
    val qualified: Boolean,
) {
    fun of(principle: Principle): PrincipleScore? = when (principle) {
        Principle.SIGNAL -> signal
        Principle.DECEL -> decel
        Principle.ZONE -> zone
        Principle.RISK -> null
    }
}

private const val DAY_MS = 86_400_000L

/**
 * [now](달력 시각 ms)에서 본 점수. 최근 [Consts.periodDays]일의 기록만 쓴다.
 * 기간 안 주행거리가 0km면 null이다("기록 없음").
 *
 * ```
 * P = 100 × (Σs + m × μ) ÷ (n + m)
 * S = 기회가 1번 이상인 원칙들의 P 평균
 * B_obs = 100 − (5 × 급감속 + 4 × 급가속 + 3 × 과속), 100km당. 0~100으로 자른다
 * B = (주행거리 × B_obs + K × B0) ÷ (주행거리 + K)
 * T = 0.5 × B + 0.5 × S
 * ```
 */
fun score(h: History, now: Long, c: Consts = Consts()): Score? {
    val from = now - c.periodDays * DAY_MS
    val trips = h.trips.filter { it.endAt in from..now }
    val km = trips.sumOf { it.km }
    if (km <= 0.0) return null

    val scenes = h.scenes.filter { it.at in from..now }
    fun principle(p: Principle): PrincipleScore {
        val list = scenes.filter { it.principle == p }
        val sum = list.sumOf { it.s }
        val score = if (list.isEmpty()) null else 100 * (sum + c.priorChances * c.priorMean) / (list.size + c.priorChances)
        return PrincipleScore(score, list.size, sum, list.count { it.s >= 1.0 })
    }
    val signal = principle(Principle.SIGNAL)
    val decel = principle(Principle.DECEL)
    val zone = principle(Principle.ZONE)
    val ps = listOfNotNull(signal.p, decel.p, zone.p)
    val situation = if (ps.isEmpty()) null else ps.sum() / ps.size

    val counted = h.basics.filter { it.at in from..now && it.exempt == Exempt.NONE }
    val brakes = counted.count { it.kind == BasicKind.DECEL }
    val accels = counted.count { it.kind == BasicKind.ACCEL }
    val overs = counted.count { it.kind == BasicKind.OVER }
    fun per100(n: Int) = n * 100 / km
    val observed = (100 - (c.decelPenalty * per100(brakes) + c.accelPenalty * per100(accels) + c.overPenalty * per100(overs))).coerceIn(0.0, 100.0)
    val basic = (km * observed + c.basicPriorKm * c.basicPrior) / (km + c.basicPriorKm)

    return Score(
        total = if (situation == null) basic else 0.5 * basic + 0.5 * situation,
        basic = basic,
        basicObserved = observed,
        situation = situation,
        signal = signal,
        decel = decel,
        zone = zone,
        km = km,
        trips = trips.size,
        hardBrakes = brakes,
        hardAccels = accels,
        overs = overs,
        qualified = km >= c.periodMinKm,
    )
}
