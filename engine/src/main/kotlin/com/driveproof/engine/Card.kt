package com.driveproof.engine

import com.driveproof.model.CardTopic
import com.driveproof.model.BasicKind
import com.driveproof.model.Consts
import com.driveproof.model.Exempt
import com.driveproof.model.Principle

/** 한 장의 줄 종류. 칭찬, 해낸 것, 고칠 점(implementation.md "모듈 간 계약" `cardJson.kind`) */
enum class LineKind { PRAISE, PARTIAL, FIX }

/**
 * 한 장의 한 줄. 문장은 만들지 않는다. [key]는 `strings.xml`의 `card_*` 키이고 [args]는 그 틀에 넣을 값이다.
 * 값은 정수이거나 낱말 키(`card_word_*`)다. [estimated]면 추정 장면만으로 만든 줄이라 "추정"을 붙인다.
 */
data class CardLine(
    val kind: LineKind,
    val topic: CardTopic,
    val key: String,
    val args: Map<String, Any> = emptyMap(),
    val estimated: Boolean = false,
)

/** 오늘의 한 장. 줄은 최대 2개다. [notes]는 주의 줄의 키(`card_note_*`)다 */
data class Card(val lines: List<CardLine>, val notes: List<String> = emptyList()) {
    fun withNote(key: String): Card = if (key in notes) this else copy(notes = notes + key)
}

/** `strings.xml` 키. 규칙은 parking-feedback.md "문구 표" 아래에 있다 */
object CardKeys {
    const val WORD_LEFT_TURN = "card_word_left_turn"
    const val WORD_RIGHT_TURN = "card_word_right_turn"
    const val WORD_U_TURN = "card_word_u_turn"
    const val WORD_TURN = "card_word_turn"
    const val WORD_BUMP = "card_word_bump"
    const val WORD_TURN_AND_BUMP = "card_word_turn_and_bump"
    const val WORD_TIMES = "card_word_times"
    const val WORD_PLACES = "card_word_places"

    const val NOTE_ROUTE = "card_note_route"
    const val NOTE_BASIC = "card_note_basic"
    const val NOTE_NONE = "card_note_none"
    const val NOTE_GAP = "card_note_gap"
}

private class Stat(val topic: CardTopic, val scenes: List<SceneRec>) {
    val n = scenes.size
    val ok = scenes.count { it.s >= 1.0 }
    val rate = if (n == 0) 0.0 else ok.toDouble() / n
    val avg = if (n == 0) 0.0 else scenes.sumOf { it.s } / n
    val estimated = n > 0 && scenes.all { it.estimated }
}

private val PRAISE_ORDER = listOf(CardTopic.ZONE, CardTopic.SIGNAL, CardTopic.DECEL)
private val FIX_ORDER = listOf(CardTopic.SIGNAL, CardTopic.DECEL, CardTopic.ZONE)

/**
 * 한 장에 쓸 줄을 고른다. 규칙은 parking-feedback.md "한 장에 무엇을 고르나"이고 기준 구현은 demo-sim.js `buildCard`다.
 *
 * @param scenes 이 주행의 장면. 위험 장면은 쓰지 않는다
 * @param basic 이 주행의 기본 항목 이벤트. 면제된 것은 세지 않는다
 * @param guided 경로 안내를 받으며 달렸다
 * @param hadCaution 주의 지점 이벤트가 온 주행이다
 */
fun buildCard(scenes: List<SceneRec>, basic: List<BasicRec>, guided: Boolean, hadCaution: Boolean, c: Consts = Consts()): Card {
    val stats = listOf(
        Stat(CardTopic.SIGNAL, scenes.filter { it.principle == Principle.SIGNAL }),
        Stat(CardTopic.DECEL, scenes.filter { it.principle == Principle.DECEL }),
        Stat(CardTopic.ZONE, scenes.filter { it.principle == Principle.ZONE }),
    )
    val have = stats.filter { it.n > 0 }
    val counted = basic.filter { it.exempt == Exempt.NONE }

    if (have.isEmpty()) {
        // 상황 장면이 하나도 없을 때만 기본 항목을 고칠 점으로 쓴다
        return if (counted.isEmpty()) Card(emptyList(), listOf(CardKeys.NOTE_NONE))
        else Card(listOf(basicFix(counted)), listOf(CardKeys.NOTE_BASIC))
    }

    // 칭찬: 성공 비율이 가장 높은 원칙. 같으면 구역 → 미리 켜기 → 미리 줄이기
    val best = have.sortedWith(compareByDescending<Stat> { it.rate }.thenBy { PRAISE_ORDER.indexOf(it.topic) }).first()
    var first: Stat? = if (best.ok > 0) best else null
    var partial = false
    if (first == null) {
        // 성공 장면이 없으면 미리 켜기·미리 줄이기 중 부분 점수 평균이 높은 원칙의 "해낸 것"을 쓴다
        first = have.filter { it.topic != CardTopic.ZONE && it.avg >= c.partialPraise }
            .sortedWith(compareByDescending<Stat> { it.avg }.thenBy { FIX_ORDER.indexOf(it.topic) }).firstOrNull()
        partial = first != null
    }

    val rest = have.filter { it !== first }
    // 고칠 점: 성공 비율이 가장 낮고 1보다 작은 것. 같으면 부분 점수 평균이 낮은 것, 그다음 미리 켜기 → 미리 줄이기 → 구역
    val fix = rest.filter { it.rate < 1.0 }
        .sortedWith(compareBy<Stat> { it.rate }.thenBy { it.avg }.thenBy { FIX_ORDER.indexOf(it.topic) }).firstOrNull()

    val lines = ArrayList<CardLine>(2)
    if (first != null) lines += if (partial) partialLine(first) else praiseLine(first)
    if (fix != null) lines += fixLine(fix, c)
    else if (rest.isNotEmpty()) lines += praiseLine(rest.first()) // 남은 원칙이 모두 성공이면 두 번째 칭찬

    val notes = if (!guided && !hadCaution) listOf(CardKeys.NOTE_ROUTE) else emptyList()
    return Card(lines, notes)
}

private fun word(kind: SceneKind): String = when (kind) {
    SceneKind.LEFT_TURN -> CardKeys.WORD_LEFT_TURN
    SceneKind.RIGHT_TURN -> CardKeys.WORD_RIGHT_TURN
    SceneKind.U_TURN -> CardKeys.WORD_U_TURN
    SceneKind.SPEED_BUMP -> CardKeys.WORD_BUMP
    SceneKind.SCHOOL_ZONE -> CardKeys.WORD_TURN // 스쿨존은 낱말로 쓰지 않는다
}

/** 회전 종류가 하나면 그 이름, 여럿이면 "회전" */
private fun turnsWord(scenes: List<SceneRec>): String {
    val kinds = scenes.map { it.kind }.distinct()
    return if (kinds.size == 1) word(kinds[0]) else CardKeys.WORD_TURN
}

private fun round(x: Double?): Int = Math.round(x ?: 0.0).toInt()

private fun line(kind: LineKind, st: Stat, key: String, vararg args: Pair<String, Any>) =
    CardLine(kind, st.topic, key, mapOf(*args), st.estimated)

/** "모두"는 기회 전체가 성공했을 때만 쓴다. 아니면 "N번 중 M번" */
private fun praiseLine(st: Stat): CardLine {
    val sc = st.scenes
    val all = st.ok == st.n
    return when (st.topic) {
        CardTopic.SIGNAL -> when {
            st.n == 1 -> line(LineKind.PRAISE, st, "card_praise_signal_one", "turn" to word(sc[0].kind), "m" to round(sc[0].onDistanceM))
            all -> line(LineKind.PRAISE, st, "card_praise_signal_all", "turn" to turnsWord(sc), "n" to st.n)
            else -> line(LineKind.PRAISE, st, "card_praise_signal_some", "turn" to turnsWord(sc), "n" to st.n, "ok" to st.ok)
        }
        CardTopic.DECEL -> {
            val turns = sc.filter { it.kind != SceneKind.SPEED_BUMP }
            val bumps = sc.filter { it.kind == SceneKind.SPEED_BUMP }
            val both = turns.isNotEmpty() && bumps.isNotEmpty()
            val where = if (both) CardKeys.WORD_TURN_AND_BUMP else if (bumps.isNotEmpty()) CardKeys.WORD_BUMP else CardKeys.WORD_TURN
            val unit = if (bumps.isNotEmpty() && turns.isEmpty()) CardKeys.WORD_PLACES else CardKeys.WORD_TIMES
            when {
                st.n == 1 -> line(LineKind.PRAISE, st, "card_praise_decel_one", "where" to word(sc[0].kind))
                all && both -> line(LineKind.PRAISE, st, "card_praise_decel_all_both")
                all -> line(LineKind.PRAISE, st, "card_praise_decel_all", "where" to where, "n" to st.n, "unit" to unit)
                else -> line(LineKind.PRAISE, st, "card_praise_decel_some", "where" to where, "n" to st.n, "ok" to st.ok, "unit" to unit)
            }
        }
        else -> {
            val limit = sc[0].limitKmh ?: 30
            when {
                st.n == 1 -> line(LineKind.PRAISE, st, "card_praise_zone_one", "limit" to limit)
                all -> line(LineKind.PRAISE, st, "card_praise_zone_all", "n" to st.n, "limit" to limit)
                else -> line(LineKind.PRAISE, st, "card_praise_zone_some", "n" to st.n, "ok" to st.ok, "limit" to limit)
            }
        }
    }
}

private fun partialLine(st: Stat): CardLine =
    if (st.topic == CardTopic.SIGNAL) {
        line(LineKind.PARTIAL, st, "card_partial_signal", "turn" to turnsWord(st.scenes))
    } else {
        val best = st.scenes.maxByOrNull { it.s }!! // 같으면 앞 장면
        line(LineKind.PARTIAL, st, "card_partial_decel", "where" to word(best.kind))
    }

private fun fixLine(st: Stat, c: Consts): CardLine {
    val sc = st.scenes
    return when (st.topic) {
        CardTopic.SIGNAL -> {
            val miss = sc.filter { it.onDistanceM == null }
            if (miss.isNotEmpty()) return line(LineKind.FIX, st, "card_fix_signal_miss", "turn" to turnsWord(miss), "n" to miss.size)
            val avg = round(sc.sumOf { it.onDistanceM!! } / sc.size)
            when {
                // 평균은 기준을 넘었지만 늦게 켠 회전이 있다. 가장 늦게 켠 회전을 말한다
                avg >= c.signalRefM && sc.size > 1 -> {
                    val late = sc.minByOrNull { it.onDistanceM!! }!!
                    line(LineKind.FIX, st, "card_fix_signal_late", "turn" to word(late.kind), "m" to round(late.onDistanceM))
                }
                sc.size == 1 -> line(LineKind.FIX, st, "card_fix_signal_one", "turn" to word(sc[0].kind), "m" to avg)
                else -> line(LineKind.FIX, st, "card_fix_signal_avg", "avg" to avg)
            }
        }
        CardTopic.DECEL -> {
            val worst = sc.minByOrNull { it.s }!! // 같으면 앞 장면
            val bump = worst.kind == SceneKind.SPEED_BUMP
            when {
                bump && (worst.passScore ?: 1.0) < (worst.decelScore ?: 1.0) ->
                    line(LineKind.FIX, st, "card_fix_decel_bump_speed", "kmh" to round(worst.passKmh))
                bump -> line(LineKind.FIX, st, "card_fix_decel_bump", "a" to round(worst.maxDecel))
                else -> line(LineKind.FIX, st, "card_fix_decel_turn", "turn" to word(worst.kind), "a" to round(worst.maxDecel))
            }
        }
        else -> {
            val bad = sc.filter { it.s < 1.0 }
            val top = bad.maxByOrNull { it.maxKmh ?: 0.0 }!! // 같으면 앞 장면
            val limit = top.limitKmh ?: 30
            if (sc.size == 1) line(LineKind.FIX, st, "card_fix_zone_one", "max" to round(top.maxKmh), "limit" to limit)
            else line(LineKind.FIX, st, "card_fix_zone_many", "n" to bad.size, "max" to round(top.maxKmh), "limit" to limit)
        }
    }
}

/** 가장 많이 나온 기본 항목 하나만 쓴다. 같으면 급감속 → 급가속 → 과속 */
private fun basicFix(counted: List<BasicRec>): CardLine {
    val order = listOf(BasicKind.DECEL, BasicKind.ACCEL, BasicKind.OVER)
    val kind = order.maxByOrNull { k -> counted.count { it.kind == k } }!!
    val key = when (kind) {
        BasicKind.DECEL -> "card_fix_basic_decel"
        BasicKind.ACCEL -> "card_fix_basic_accel"
        BasicKind.OVER -> "card_fix_basic_over"
    }
    return CardLine(LineKind.FIX, CardTopic.BASIC, key, mapOf("n" to counted.count { it.kind == kind }))
}
