package com.driveproof.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.driveproof.data.ProofSnapshot
import com.driveproof.data.TripEntity
import com.driveproof.model.Principle
import com.driveproof.model.RULE_VERSION
import com.driveproof.scoring.WeeklySummary
import com.driveproof.scoring.score
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal val coachingPrinciples = listOf(Principle.SIGNAL, Principle.DECEL, Principle.ZONE)
private const val PROOF_WEEKS = 26

internal fun ProofSnapshot?.completedProofWeeks() = this?.weeks.orEmpty().takeLast(PROOF_WEEKS)

internal fun Ui.principleName(p: Principle) = string(when (p) {
    Principle.SIGNAL -> R.string.principle_signal
    Principle.DECEL -> R.string.principle_decel
    Principle.ZONE -> R.string.principle_zone
    Principle.RISK -> R.string.lamp_generic
})

internal fun WeeklySummary.behaviour(p: Principle) = when (p) {
    Principle.SIGNAL -> avgOnDistanceM
    Principle.DECEL -> avgMaxDecel
    Principle.ZONE -> maxZoneKmh
    Principle.RISK -> null
}

fun Ui.proofPage(
    snapshot: ProofSnapshot?,
    trips: List<TripEntity>,
    speedGranted: Boolean,
    retry: () -> Unit,
    close: () -> Unit,
    openTrip: (Long) -> Unit,
    settings: (() -> Unit)? = null,
    openPrinciple: ((Principle) -> Unit)? = null,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
): View = panel(column().apply {
    val suffix = settings?.let { action(string(R.string.proof_settings), click = it) }
    add(header(string(R.string.brand_name), onClose = close, suffix = suffix), 100)
    divider()
    val body = column().apply {
        pad(32, 24)
        if (!speedGranted) {
            add(text(string(R.string.proof_speed_off), 26, Theme.muted))
            add(action(string(R.string.speed_permission_retry), click = retry))
            space(20)
        }
        val total = snapshot?.let { score(it.history, it.now) }
        if (total == null) {
            add(text(string(R.string.empty_trip), 30, Theme.muted, true))
        } else {
            val weeks = snapshot.completedProofWeeks()
            // 추이선의 마지막 한 칸은 진행 중인 주에 쓴다.
            val trendWeeks = weeks.takeLast(PROOF_WEEKS - 1)
            val values = trendWeeks.map { it.total } + total.total
            val versions = trendWeeks.map { it.ruleVersion } + RULE_VERSION
            val changes = versions.indices.drop(1).filter { versions[it] != versions[it - 1] }.toSet()
            add(row().apply {
                addView(column().apply {
                    add(text(string(R.string.proof_period), 24, Theme.muted))
                    add(text(string(R.string.proof_score, total.total.roundToInt()), 60, Theme.accent, true))
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(column().apply {
                    add(TrendView(this@proofPage, values, 60.0, 100.0, ruleChanges = changes), 68)
                    weeks.getOrNull(3)?.total?.let { earlier ->
                        add(text(string(R.string.proof_change, earlier.roundToInt(), total.total.roundToInt()), 24, Theme.muted))
                    }
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
            })
            if (changes.isNotEmpty()) {
                add(text(string(R.string.proof_rule_change, changes.map { versions[it] }.distinct().joinToString(", ")),
                    24, Theme.muted))
            }
            if (total.situation == null) add(text(string(R.string.proof_situation_pending), 24, Theme.muted))
            space(12)
            coachingPrinciples.forEach { principle ->
                val detail = total.of(principle) ?: return@forEach
                val scores = detail.p?.let { string(R.string.proof_score, it.roundToInt()) }
                    ?: string(R.string.proof_no_opportunity)
                val early = weeks.take(4).mapNotNull { it.behaviour(principle) }
                val recent = weeks.takeLast(4).mapNotNull { it.behaviour(principle) }
                add(column().apply {
                    pad(16, 12)
                    background = rounded(Theme.tile, 20)
                    add(row().apply {
                        addView(text(principleName(principle), 28, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
                        addView(text(scores, 28, Theme.accent, true))
                    })
                    add(text(string(R.string.proof_opportunities, detail.ok, detail.n), 24, Theme.muted))
                    if (weeks.size >= 8 && early.isNotEmpty() && recent.isNotEmpty()) {
                        val format = when (principle) {
                            Principle.SIGNAL -> R.string.proof_behaviour_signal
                            Principle.DECEL -> R.string.proof_behaviour_decel
                            Principle.ZONE -> R.string.proof_behaviour_zone
                            Principle.RISK -> error("No risk behaviour trend")
                        }
                        val before = behaviourValue(principle, early.average().roundToInt().toString())
                        val after = behaviourValue(principle, recent.average().roundToInt().toString())
                        add(text(string(format, before, after), 24, Theme.muted))
                    }
                    if (openPrinciple != null) {
                        isFocusable = true
                        setOnClickListener { openPrinciple(principle) }
                    }
                })
                space(10)
            }
            space(8)
            add(text(string(R.string.proof_basic, total.basic.roundToInt()), 28, bold = true))
            fun per100(count: Int) = displayKm(100 * count / total.km)
            add(text(string(R.string.proof_basic_counts, per100(total.hardAccels), per100(total.hardBrakes), per100(total.overs)), 24, Theme.muted))
            space(14)
            add(text(string(if (total.qualified) R.string.proof_met else R.string.proof_pending, total.km.roundToInt()), 26, bold = true))
            add(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 500
                progress = total.km.roundToInt().coerceIn(0, 500)
                progressTintList = android.content.res.ColorStateList.valueOf(Theme.accent)
                contentDescription = string(R.string.proof_progress)
            }, 24)
        }
        actions.forEach { (label, callback) ->
            space(12)
            add(action(label, click = callback))
        }
    }
    val left = ScrollView(context).apply {
        isFillViewport = true
        addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
    }
    if (wide) add(row().apply {
        weighted(left)
        weighted(ScrollView(context).apply {
            addView(column().apply {
                pad(32, 24)
                add(text(string(R.string.proof_recent), 32, bold = true))
                space(12)
                if (trips.isEmpty()) add(text(string(R.string.empty_trip), 24, Theme.muted))
                trips.take(5).forEach { add(tripRow(it, openTrip)); divider() }
            }, FrameLayout.LayoutParams(MATCH, WRAP))
        })
    }, MATCH) else add(left, MATCH)
}, full = true)

internal fun Ui.tripRow(trip: TripEntity, open: (Long) -> Unit) = column().apply {
    pad(12, 16)
    minimumHeight = dp(76)
    val date = trip.dateTime().format(DateTimeFormatter.ofPattern("M월 d일 · HH:mm"))
    add(text(string(R.string.proof_trip_row, date, displayKm(trip.km)), 27, bold = true))
    trip.fixText()?.let { add(text(it, 24, Theme.muted)) }
    isFocusable = true
    setOnClickListener { open(trip.id) }
}

internal fun Ui.behaviourUnit(principle: Principle) = when (principle) {
    Principle.SIGNAL -> "m"
    Principle.DECEL -> string(R.string.history_decel_unit)
    Principle.ZONE -> "km/h"
    Principle.RISK -> ""
}

internal fun Ui.behaviourValue(principle: Principle, value: String): String =
    if (principle == Principle.DECEL) string(R.string.decel_per_second, value) else value + behaviourUnit(principle)

internal class TrendView(
    val ui: Ui,
    private val values: List<Double?>,
    private val low: Double,
    private val high: Double,
    private val targets: List<Double> = emptyList(),
    private val pointsOnly: Boolean = false,
    private val ruleChanges: Set<Int> = emptySet(),
    principle: Principle? = null,
) : View(ui.context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = values.lastOrNull { it != null }?.let {
            val value = it.roundToInt().toString()
            ui.string(R.string.proof_chart_last, principle?.let { ui.behaviourValue(it, value) } ?: value)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val space = ui.dp(7).toFloat()
        fun y(value: Double) = height - space - (height - 2 * space) * ((value.coerceIn(low, high) - low) / (high - low)).toFloat()
        paint.strokeWidth = ui.dp(2).toFloat()
        paint.color = Theme.muted
        paint.pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
        targets.forEach { canvas.drawLine(space, y(it), width - space, y(it), paint) }
        paint.pathEffect = null
        var previous: Pair<Float, Float>? = null
        val last = values.indexOfLast { it != null }
        values.forEachIndexed { index, value ->
            val x = space + (width - 2 * space) * index / values.lastIndex.coerceAtLeast(1)
            if (index in ruleChanges) {
                paint.color = Theme.muted
                paint.pathEffect = DashPathEffect(floatArrayOf(space, space), 0f)
                canvas.drawLine(x, space, x, height - space, paint)
                paint.pathEffect = null
                previous = null
            }
            if (value == null) {
                previous = null
            } else {
                val y = y(value)
                paint.color = Theme.muted
                if (!pointsOnly) previous?.let { canvas.drawLine(it.first, it.second, x, y, paint) }
                paint.color = if (index == last) Theme.accent else Theme.muted
                canvas.drawCircle(x, y, ui.dp(if (index == last) 5 else 3).toFloat(), paint)
                previous = x to y
            }
        }
    }
}
