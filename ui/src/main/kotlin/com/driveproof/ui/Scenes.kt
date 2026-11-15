package com.driveproof.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.driveproof.data.SceneEntity
import com.driveproof.data.TripEntity
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SCENE_LABEL_SP = 24

/** 장면은 한 장과 같은 주행의 확정 행만 표시한다. */
fun Ui.scenesPage(
    trip: TripEntity,
    records: List<SceneEntity>,
    back: () -> Unit,
    close: () -> Unit,
    tripBack: (() -> Unit)? = null,
    tripClose: (() -> Unit)? = null,
): View =
    if (!wide) panel(column().apply {
        add(header(string(R.string.scenes_title), back, close, showLogo = false), 88)
        addView(scenesBody(records), LinearLayout.LayoutParams(MATCH, 0, 1f))
        add(column().apply { pad(32, 0); add(action(string(R.string.scenes_done), click = back)); space(24) })
    }, glow = true) else panel(column().apply {
        addView(row().apply {
            weighted(tripCardBody(trip, false, tripBack, tripClose))
            weighted(column().apply {
                add(row().apply {
                    setPadding(dp(25), 0, dp(10), 0)
                    addView(text(string(R.string.scenes_title), 30, Theme.accent, weight = 800).apply {
                        setPadding(0, dp(6), 0, 0)
                    },
                        LinearLayout.LayoutParams(0, MATCH, 1f))
                    addView(icon(R.drawable.dp_close, string(R.string.nav_close), close))
                }, 92)
                addView(scenesBody(records), LinearLayout.LayoutParams(MATCH, 0, 1f))
                space(28)
            })
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        add(column().apply { pad(40, 0); add(action(string(R.string.scenes_done), click = back)); space(40) })
    }, full = true, glow = true)

private fun Ui.scenesBody(records: List<SceneEntity>) = column().apply {
    setPadding(dp(if (wide) 25 else 32), 0, dp(if (wide) 40 else 32), 0)
    val sections = listOf("SIGNAL" to R.string.principle_signal, "DECEL" to R.string.principle_decel,
        "ZONE" to R.string.principle_zone)
    val risk = records.filter { it.principle == "RISK" }
    if (records.isEmpty()) {
        add(text(string(R.string.trip_no_scenes), 26, Theme.muted))
        return@apply
    }
    sections.forEach { (key, title) ->
        val rows = records.filter { it.principle == key }
        val label = string(title)
        val summary = when (key) {
            "SIGNAL" -> {
                val values = rows.mapNotNull { it.value("onDistanceM") }
                when {
                    rows.isEmpty() -> string(R.string.scenes_no_signal_opportunity)
                    values.isEmpty() -> string(R.string.scenes_no_signal)
                    else -> string(R.string.scenes_signal_average, values.average().roundToInt())
                }
            }
            "DECEL" -> {
                val hard = rows.filter { (it.value("maxDecel") ?: 0.0) >= (it.value("hardDecel") ?: 15.0) }
                val kinds = hard.map { sceneKind(it.kind) }.distinct().joinToString(", ")
                when {
                    rows.isEmpty() -> string(R.string.scenes_no_decel_opportunity)
                    hard.isEmpty() -> string(R.string.scenes_no_hard_brake)
                    else -> string(R.string.scenes_hard_brake, hard.size, kinds)
                }
            }
            else -> if (rows.isEmpty()) string(R.string.scenes_no_zone_opportunity)
            else string(R.string.scenes_zone_top, rows.size, rows.mapNotNull { it.value("maxKmh") }.maxOrNull()?.roundToInt() ?: 0,
                rows.mapNotNull { it.value("limitKmh")?.roundToInt() }.distinct().joinToString("/"))
        }
        val tile = column().apply {
            background = rounded(Theme.tile, 40)
            pad(29, 8)
            gravity = Gravity.CENTER_VERTICAL
            add(text(string(R.string.scenes_counts, label, rows.count { it.s == 1.0 }, rows.size),
                SCENE_LABEL_SP, weight = 600))
            space(5)
            add(text(summary + if (rows.any { it.estimated }) string(R.string.trip_estimated_suffix) else "",
                SCENE_LABEL_SP, Theme.muted, weight = 600))
            if (key == "SIGNAL" && rows.isNotEmpty()) {
                val values = rows.mapNotNull { it.value("onDistanceM") }
                space(10)
                add(DistanceRuler(this@scenesBody, values.takeIf { it.isNotEmpty() }?.average()), 92)
            }
        }
        // 줄바꿈에 필요한 높이를 확보하고 남은 높이를 나눈다.
        addView(tile, LinearLayout.LayoutParams(MATCH, WRAP, 1f))
        space(if (wide) 16 else 12)
    }
    if (risk.isNotEmpty()) {
        val peak = risk.maxBy { it.value("maxDecel") ?: 0.0 }
        val titles = risk.groupingBy { it.kind }.eachCount().map { (kind, n) ->
            string(R.string.scenes_risk_count, lampName(kind), n)
        }.joinToString(" · ")
        val details = string(R.string.scenes_risk_detail, peak.value("maxDecel")?.roundToInt() ?: 0) +
            if (risk.any { JSONObject(it.valuesJson).optBoolean("exempts") }) ". " + string(R.string.scenes_risk_exempt) else ""
        addView(column().apply {
            background = rounded(Theme.surface, 40, Theme.line, dashed = true)
            pad(28, 8)
            gravity = Gravity.CENTER_VERTICAL
            add(text(titles, SCENE_LABEL_SP, Theme.muted))
            space(5)
            add(text(details, SCENE_LABEL_SP, Theme.muted))
        }, LinearLayout.LayoutParams(MATCH, WRAP, 1f))
    } else {
        addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 2f))
    }
}

internal fun Ui.lampName(kind: String) = when (kind) {
    "FCA" -> string(R.string.lamp_fca)
    "ABS" -> string(R.string.lamp_abs)
    "ESC" -> string(R.string.lamp_esc)
    "DRIVER_ATTENTION" -> string(R.string.lamp_attention)
    else -> string(R.string.lamp_generic)
}

internal fun Ui.sceneKind(kind: String) = string(when (kind) {
    "LEFT_TURN" -> R.string.scene_left
    "RIGHT_TURN" -> R.string.scene_right
    "U_TURN" -> R.string.scene_uturn
    "SPEED_BUMP" -> R.string.scene_bump
    "SCHOOL_ZONE" -> R.string.scene_zone
    else -> R.string.scene_generic
})

private class DistanceRuler(val ui: Ui, val average: Double?) : View(ui.context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        contentDescription = listOfNotNull(
            ui.string(R.string.scenes_ruler_description),
            average?.roundToInt()?.let { ui.string(R.string.scenes_ruler_average_description, it) },
        ).joinToString(" ")
    }

    override fun onDraw(canvas: Canvas) {
        val scale = maxOf(41.0, average ?: 0.0)
        val edge = ui.dp(17).toFloat()
        val baseline = height - ui.dp(9).toFloat()
        val x = { m: Double -> width - edge - (width - edge * 2) * (m / scale).toFloat() }
        paint.color = Theme.muted
        paint.strokeWidth = ui.dp(1).toFloat()
        canvas.drawLine(0f, baseline, width.toFloat(), baseline, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, SCENE_LABEL_SP.toFloat(), resources.displayMetrics,
        )
        paint.typeface = ui.face(500)

        class Mark(val at: Float, val label: String) {
            val halfWidth = paint.measureText(label) / 2
            val center = at.coerceIn(halfWidth, (width - halfWidth).coerceAtLeast(halfWidth))

            fun overlaps(other: Mark) = abs(center - other.center) < halfWidth + other.halfWidth + ui.dp(8)
        }

        val labelBaseline = baseline - ui.dp(27)
        fun drawMark(mark: Mark, dash: Boolean, raised: Boolean) {
            paint.pathEffect = if (dash) DashPathEffect(floatArrayOf(3f, 3f), 0f) else null
            canvas.drawLine(mark.at, baseline - ui.dp(18), mark.at, baseline, paint)
            paint.pathEffect = null
            val labelY = if (raised) labelBaseline - paint.fontSpacing - ui.dp(2) else labelBaseline
            canvas.drawText(mark.label, mark.center, labelY, paint)
        }

        val target = Mark(x(30.0), ui.string(R.string.scenes_ruler_target))
        val turn = Mark(x(0.0), ui.string(R.string.scenes_ruler_turn))
        val raiseTarget = target.overlaps(turn)
        drawMark(target, dash = true, raised = raiseTarget)
        average?.let {
            val avg = Mark(x(it), ui.string(R.string.scenes_ruler_average, it.roundToInt()))
            drawMark(avg, dash = false, raised = avg.overlaps(turn) || (!raiseTarget && avg.overlaps(target)))
        }
        drawMark(turn, dash = true, raised = false)
    }
}
