package com.driveproof.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.driveproof.data.ExemptEventEntity
import com.driveproof.data.ProofSnapshot
import com.driveproof.data.SceneEntity
import com.driveproof.data.TripEntity
import com.driveproof.model.CardTopic
import com.driveproof.model.Principle
import com.driveproof.scoring.monthSummaries
import com.driveproof.scoring.score
import com.driveproof.scoring.scoreCountingLampExempt
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

fun Ui.principleDetail(snapshot: ProofSnapshot?, principle: Principle, scenes: List<SceneEntity>,
                       back: () -> Unit, close: () -> Unit): View {
    val weeks = snapshot.completedProofWeeks()
    val labels = weeks.mapIndexed { i, w ->
        if (i == 0 || weeks[i - 1].weekStart.month != w.weekStart.month) string(R.string.history_month, w.weekStart.monthValue) else ""
    }
    val values = weeks.map { it.behaviour(principle) }
    val (low, high, targets) = when (principle) {
        Principle.SIGNAL -> Triple(0.0, 45.0, listOf(30.0))
        Principle.DECEL -> Triple(0.0, 16.0, listOf(5.0, 15.0))
        else -> Triple(20.0, 45.0, listOf(30.0))
    }
    val ticks = when (principle) {
        Principle.SIGNAL -> listOf(0, 15, 30, 45)
        Principle.DECEL -> listOf(0, 5, 10, 15)
        else -> listOf(20, 30, 40)
    }
    val graph: LinearLayout.() -> Unit = {
        if (values.none { it != null }) add(text(string(R.string.history_no_measurements), 28, Theme.muted)) else {
            add(text(string(R.string.history_weekly_behaviour, behaviourUnit(principle)), 28, bold = true))
            add(row().apply {
                addView(column().apply {
                    ticks.asReversed().forEach { add(text(it.toString(), 22, Theme.muted)); if (it != ticks.first()) flex() }
                }, LinearLayout.LayoutParams(dp(46), MATCH))
                weighted(TrendView(this@principleDetail, values, low, high, targets, principle == Principle.ZONE, principle = principle))
            }, 220)
            add(ChartLabels(context, labels), 34)
            values.lastOrNull { it != null }?.let {
                add(text(string(R.string.history_last_behaviour, behaviourValue(principle, displayKm(it))), 24, Theme.accent))
            }
            val early = values.take(4).filterNotNull()
            val recent = values.takeLast(4).filterNotNull()
            if (weeks.size >= 8 && early.isNotEmpty() && recent.isNotEmpty()) {
                val before = behaviourValue(principle, displayKm(early.average()))
                val after = behaviourValue(principle, displayKm(recent.average()))
                add(text(string(R.string.history_behaviour_compare, before, after), 26))
            }
            if (principle == Principle.SIGNAL) add(text(string(R.string.history_missed_signals, weeks.sumOf { it.missedSignals }), 24, Theme.muted))
        }
    }
    val recent: LinearLayout.() -> Unit = {
        add(text(string(R.string.history_recent_scenes), 30, bold = true))
        space(12)
        if (scenes.isEmpty()) add(text(string(R.string.history_no_recent_scenes), 24, Theme.muted))
        scenes.take(10).forEach { scene ->
            val v = scene.value(when (principle) {
                Principle.SIGNAL -> "onDistanceM"
                Principle.DECEL -> "maxDecel"
                else -> "maxKmh"
            })
            val label = listOfNotNull(dateText(scene.at, snapshot?.zone ?: ZoneId.systemDefault()),
                sceneKind(scene.kind), v?.let { behaviourValue(principle, displayKm(it)) },
                if (scene.estimated) string(R.string.trip_estimated_suffix).trim().removePrefix("· ") else null).joinToString(" · ")
            add(text(label, 24), 68)
            divider()
        }
    }
    if (!wide) return page(principleName(principle), back, close) { graph(); space(28); recent() }
    return panel(column().apply {
        add(header(principleName(principle), back, close), 100)
        divider()
        add(row().apply {
            listOf(graph, recent).forEach { body ->
                weighted(ScrollView(context).apply {
                    addView(column().apply { pad(32, 24); body() }, FrameLayout.LayoutParams(MATCH, WRAP))
                })
            }
        }, MATCH)
    }, full = true)
}

fun Ui.historyLog(snapshot: ProofSnapshot?, monthly: Boolean, back: () -> Unit, close: () -> Unit,
                  select: (Boolean) -> Unit): View = page(
    string(if (monthly) R.string.history_monthly else R.string.history_weekly), back, close,
) {
    add(historyTabs(string(R.string.history_tab_week), string(R.string.history_tab_month), monthly, select))
    space(18)
    if (snapshot == null || snapshot.weeks.isEmpty()) add(text(string(R.string.empty_trip), 26, Theme.muted))
    if (snapshot == null) return@page
    if (monthly) {
        val months = monthSummaries(snapshot.history, snapshot.weeks, snapshot.zone)
        months.indices.reversed().forEach { i ->
            val item = months[i]
            val label = string(R.string.monthly_period, item.year, item.month) +
                if (item.weeks < 4) " · " + string(R.string.history_weeks_count, item.weeks) else ""
            val stats = coachingPrinciples.mapNotNull { p -> item.count(p).rate()?.let { string(R.string.monthly_rate, principleName(p), it) } }
            add(historyRecord(label, item.total, months.getOrNull(i - 1)?.total,
                if (item.trips > 0) listOf(string(R.string.history_trip_count, item.trips, displayKm(item.km))) + stats else stats))
            divider()
        }
    } else snapshot.weeks.indices.reversed().forEach { i ->
        val week = snapshot.weeks[i]
        val fixes = week.fixes.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.let {
            string(R.string.history_week_fix, topicName(it.key), it.value)
        }
        add(historyRecord("${week.weekStart} – ${week.weekEnd}", week.total, snapshot.weeks.getOrNull(i - 1)?.total,
            listOfNotNull(string(R.string.history_trip_count, week.trips, displayKm(week.km)), fixes)))
        divider()
    }
}

fun Ui.reasonLog(snapshot: ProofSnapshot?, trips: List<TripEntity>, exemptEvents: List<ExemptEventEntity>,
                 excluded: Boolean, back: () -> Unit, close: () -> Unit, select: (Boolean) -> Unit): View =
    page(string(R.string.reason_title), back, close) {
        add(historyTabs(string(R.string.reason_fix_tab), string(R.string.reason_excluded_tab), excluded, select))
        space(20)
        if (snapshot == null) return@page
        if (excluded) {
            val lamps = exemptEvents.filter { it.mode == "TELLTALE" }
            add(text(string(R.string.reason_excluded_count, lamps.size), 30, bold = true))
            val now = score(snapshot.history, snapshot.now)
            val compared = scoreCountingLampExempt(snapshot.history, snapshot.now)
            if (now != null && compared != null)
                add(text(string(R.string.reason_counterfactual, displayKm(now.total), displayKm(compared.total)), 26, Theme.muted))
            lamps.forEach { item ->
                val decel = string(R.string.decel_per_second, displayKm(item.decel))
                add(text(string(R.string.reason_lamp_row, dateText(item.date, snapshot.zone), decel, lampName(item.reason)), 24), 70)
                divider()
            }
            val scenes = exemptEvents.filter { it.mode == "SCENE" }
            if (scenes.isNotEmpty()) {
                space(22)
                add(text(string(R.string.reason_in_scene), 28, bold = true))
                scenes.forEach {
                    val decel = string(R.string.decel_per_second, displayKm(it.decel))
                    add(text(string(R.string.reason_scene_row, dateText(it.date, snapshot.zone), decel), 24), 62)
                    divider()
                }
            }
        } else {
            val month = YearMonth.from(Instant.ofEpochMilli(snapshot.now).atZone(snapshot.zone))
            val keys = (5L downTo 0L).map { month.minusMonths(it) }
            val byMonth = monthSummaries(snapshot.history, snapshot.weeks, snapshot.zone).associateBy { YearMonth.of(it.year, it.month) }
            val topics = coachingPrinciples.map { CardTopic.valueOf(it.name) }
            val counts = topics.map { p -> keys.map { byMonth[it]?.fixes?.get(p) ?: 0 } }
            val max = counts.flatten().maxOrNull()?.coerceAtLeast(1) ?: 1
            counts.forEachIndexed { i, values ->
                add(text(topicName(topics[i]), 27, bold = true))
                add(ReasonBars(context, values, max, keys.map { string(R.string.history_month, it.monthValue) }), 93)
                space(12)
            }
            add(text(string(R.string.reason_recent_fix), 28, bold = true))
            val recent = trips.filter { (it.endAt ?: 0) >= snapshot.now - 183L * 86_400_000L }.mapNotNull { it.fixText() }.take(2)
            if (recent.isEmpty()) add(text(string(R.string.reason_no_recent_fix), 24, Theme.muted))
            recent.forEach { add(text(it, 25)); space(10) }
        }
    }

private fun Ui.historyRecord(title: String, total: Double?, before: Double?, details: List<String>) = column().apply {
    pad(0, 16)
    add(row().apply {
        addView(text(title, 26, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        total?.let { addView(text(string(R.string.proof_score, it.roundToInt()), 30, Theme.accent, true)) }
    })
    if (total != null && before != null) {
        val delta = total.roundToInt() - before.roundToInt()
        add(text(string(R.string.history_delta, delta), 24, Theme.muted))
    }
    details.forEach { add(text(it, 24, Theme.muted)) }
}

private fun Ui.historyTabs(first: String, second: String, selected: Boolean, select: (Boolean) -> Unit) =
    row().apply {
        weighted(action(first, !selected) { select(false) }.apply { isSelected = !selected })
        weighted(action(second, selected) { select(true) }.apply { isSelected = selected })
    }.also { it.minimumHeight = dp(82) }

private fun Ui.topicName(topic: CardTopic) = if (topic == CardTopic.BASIC) string(R.string.history_basic)
    else principleName(Principle.valueOf(topic.name))

private fun dateText(at: Long, zone: ZoneId) =
    Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("M월 d일"))

private class ChartLabels(context: Context, val labels: List<String>) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.muted
        textAlign = Paint.Align.CENTER
        textSize = 20f * resources.displayMetrics.scaledDensity
    }
    override fun onDraw(canvas: Canvas) {
        val left = 46 * resources.displayMetrics.density
        labels.forEachIndexed { i, value ->
            val x = left + (width - left * 1.5f) * i / labels.lastIndex.coerceAtLeast(1)
            canvas.drawText(value, x, height * 0.75f, p)
        }
    }
}

private class ReasonBars(context: Context, val counts: List<Int>, val max: Int, val labels: List<String>) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    init { contentDescription = labels.zip(counts).joinToString(", ") { (label, count) -> "$label $count" } }
    override fun onDraw(canvas: Canvas) {
        val col = width / counts.size.toFloat()
        val y = height * 0.65f
        for (i in counts.indices) {
            val x = (i + 0.5f) * col
            p.color = Theme.accent
            val count = counts[i]
            val top = y - (y - 8f) * count / max
            if (count == 0) {
                p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 4f), 0f)
                canvas.drawLine(x - col * 0.24f, y, x + col * 0.24f, y, p)
                p.pathEffect = null
            } else canvas.drawRect(x - col * 0.24f, top, x + col * 0.24f, y, p)
            p.color = Theme.muted
            p.textSize = 21f * resources.displayMetrics.scaledDensity
            canvas.drawText(labels[i], x, height * 0.93f, p)
        }
    }
}
