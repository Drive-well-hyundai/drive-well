package com.driveproof.ui

import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.driveproof.data.TripEntity
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class FeedbackLine(val kind: String, val text: String, val estimated: Boolean)

private val storedDecelUnit = Regex("""(\d+(?:\.\d+)?)(?:( → (?:이번 )?)(\d+(?:\.\d+)?))? ?km/h/s""")

private fun String.withReadableDecelUnit() = storedDecelUnit.replace(this) { match ->
    val before = "초당 ${match.groupValues[1]}km/h"
    val after = match.groups[3]?.value
    if (after == null) before else "$before${match.groupValues[2]}초당 ${after}km/h"
}

data class CardCopy(val lines: List<FeedbackLine>, val notes: List<String>, val total: Int?, val change: String?) {
    companion object {
        fun parse(source: String?): CardCopy? = runCatching {
            val card = JSONObject(source ?: return null)
            val lines = card.optJSONArray("lines")
            val notes = card.optJSONArray("notes")
            val score = card.optJSONObject("score")
            CardCopy(
                (0 until (lines?.length() ?: 0)).mapNotNull { i ->
                    lines?.optJSONObject(i)?.let {
                        val text = it.optString("text").takeIf(String::isNotBlank) ?: return@let null
                        FeedbackLine(it.optString("kind"), text.withReadableDecelUnit(), it.optBoolean("estimated"))
                    }
                }.take(2),
                (0 until (notes?.length() ?: 0)).mapNotNull { notes?.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) },
                score?.optDouble("T")?.takeIf(Double::isFinite)?.roundToInt(),
                score?.takeUnless { it.isNull("text") }?.optString("text")?.takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }
}

fun TripEntity.dateTime(zone: ZoneId = ZoneId.systemDefault()) = Instant.ofEpochMilli(endAt ?: startAt).atZone(zone)
fun TripEntity.fixText(): String? = CardCopy.parse(cardJson)?.lines?.firstOrNull { it.kind == "FIX" }?.text
fun displayKm(km: Double) = String.format(Locale.KOREA, "%.1f", km)

fun Ui.tripCard(trip: TripEntity?, preparing: Boolean, back: (() -> Unit)?, close: () -> Unit,
                scenes: (() -> Unit)? = null): View = panel(column().apply {
    add(tripCardBody(trip, preparing, back, close), MATCH)
    if (trip != null && scenes != null) {
        // 위 카드 본문과 아래 버튼이 한 창에 들어간다.
        getChildAt(0).layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        add(column().apply { pad(40, 0); add(action(string(R.string.trip_scenes), true, scenes)); space(40) })
    }
}, glow = true)

internal fun Ui.tripCardBody(trip: TripEntity?, preparing: Boolean, back: (() -> Unit)?,
                             close: (() -> Unit)?): LinearLayout = column().apply {
    add(header(string(R.string.brand_name), back, close), 115)
    divider()
    if (preparing || trip == null) {
        add(column().apply {
            gravity = Gravity.CENTER
            pad(40)
            add(text(string(if (preparing) R.string.trip_preparing else R.string.empty_trip), 30, Theme.muted, true))
        }, MATCH)
        return@apply
    }
    val card = CardCopy.parse(trip.cardJson)
    val end = trip.dateTime()
    val date = end.toLocalDate()
    val title = if (date == LocalDate.now(end.zone)) string(R.string.trip_today)
        else string(R.string.trip_date, date.monthValue, date.dayOfMonth)
    val time = end.format(DateTimeFormatter.ofPattern("HH:mm"))
    add(row().apply {
        pad(40, 12)
        addView(column().apply {
            add(text(title, 32, weight = 800))
            space(8)
            add(text(string(R.string.trip_time_distance, time, displayKm(trip.km)) +
                if (trip.isDemo) string(R.string.trip_demo_suffix) else "", 24, Theme.muted))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        card?.total?.let { total ->
            addView(column().apply {
                gravity = Gravity.END
                add(row().apply {
                    gravity = Gravity.BOTTOM
                    addView(text(string(R.string.trip_total), 24, Theme.muted).apply {
                        setPadding(0, 0, dp(12), dp(7))
                    })
                    addView(text(total.toString(), 66, Theme.accent, weight = 800))
                })
                card.change?.let { add(text(it, 24, Theme.muted).apply { gravity = Gravity.END }) }
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
    }, 138)
    divider()
    card?.lines.orEmpty().forEachIndexed { index, line ->
        val fix = line.kind == "FIX"
        val style = if (fix) Theme.coaching else Theme.muted
        add(column().apply {
            pad(if (fix) 30 else 40, 12)
            minimumHeight = dp(130)
            gravity = Gravity.CENTER_VERTICAL
            val label = when (line.kind) {
                "FIX" -> R.string.trip_fix
                "PARTIAL" -> R.string.trip_partial
                else -> R.string.trip_praise
            }
            add(text(string(label), 24, style, weight = 600))
            space(8)
            add(row().apply {
                gravity = Gravity.TOP
                addView(ImageView(context).apply {
                    setImageResource(if (fix) R.drawable.dp_coach else R.drawable.dp_check)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(if (fix) 18 else 20), dp(if (fix) 22 else 19)).apply {
                    topMargin = dp(4)
                    marginEnd = dp(18)
                })
                addView(text(line.text + if (line.estimated) string(R.string.trip_estimated_suffix) else "", 36),
                    LinearLayout.LayoutParams(0, WRAP, 1f))
            })
        })
        if (index < card!!.lines.lastIndex) divider()
    }
    if (card == null || (card.lines.isEmpty() && card.notes.isEmpty())) {
        add(column().apply { pad(40, 20); add(text(string(R.string.trip_no_scenes), 26, Theme.muted)) })
    }
    if (!card?.notes.isNullOrEmpty()) add(column().apply {
        pad(40, 8)
        card?.notes?.forEach { add(text(it, 24, Theme.muted)) }
    })
}
