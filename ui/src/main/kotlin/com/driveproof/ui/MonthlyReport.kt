package com.driveproof.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import com.driveproof.data.ProofSnapshot
import com.driveproof.model.Consts
import com.driveproof.model.Principle
import com.driveproof.model.RULE_VERSION
import com.driveproof.scoring.MonthSummary
import com.driveproof.scoring.PrincipleCount
import com.driveproof.scoring.compareAfterRuleChange
import com.driveproof.scoring.monthSummaries
import com.driveproof.scoring.score
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.time.Instant
import java.time.YearMonth
import kotlin.math.roundToInt

internal fun MonthSummary.count(p: Principle): PrincipleCount = when (p) {
    Principle.SIGNAL -> signal
    Principle.DECEL -> decel
    Principle.ZONE -> zone
    Principle.RISK -> error("No risk score")
}

internal fun PrincipleCount.rate(): Int? = if (n == 0) null else (ok * 100.0 / n).roundToInt()

fun Ui.monthlyReport(snapshot: ProofSnapshot?, back: () -> Unit, close: () -> Unit): View {
    val date = snapshot?.let { Instant.ofEpochMilli(it.now).atZone(it.zone).toLocalDate() }
    val months = snapshot?.let { monthSummaries(it.history, it.weeks, it.zone) }.orEmpty()
    val current = date?.let(YearMonth::from)
    val latestComplete = current?.minusMonths(1)
    val month = months.lastOrNull { YearMonth.of(it.year, it.month) == latestComplete && it.trips > 0 }
        ?: months.lastOrNull { it.trips > 0 }
    val headerTitle = month?.let { string(R.string.monthly_report_title, it.month) }
        ?: string(R.string.monthly_title)
    return page(headerTitle, back, close) {
        if (snapshot == null || date == null || month == null) {
            add(text(string(R.string.empty_trip), 28, Theme.muted))
            return@page
        }

        val key = YearMonth.of(month.year, month.month)
        val before = months.find { YearMonth.of(it.year, it.month) == key.minusMonths(1) && it.trips > 0 }
        val comparison = compareAfterRuleChange(snapshot.history, key, snapshot.zone)
        val title = string(R.string.monthly_period, month.year, month.month)
        add(text(title, 36, bold = true))
        if (month.trips < 3) add(text(string(R.string.monthly_small_sample), 24, Theme.muted))
        val ruleNotice = comparison?.let { string(R.string.monthly_rule_change, it.changedOn.toString()) }
        ruleNotice?.let { add(text(it, 24, Theme.muted)) }
        space(12)
        val latestScore = if (key == current) score(snapshot.history, snapshot.now)?.total else month.total
        val changes = ArrayList<Pair<Principle, Int>>()
        val rows = coachingPrinciples.map { p ->
            val count = comparison?.current?.get(p) ?: month.count(p)
            val rate = count.rate()
            val previous = if (comparison != null) comparison.previous[p]?.rate() else before?.count(p)?.rate()
            if (rate != null && previous != null) changes += p to (rate - previous)
            val name = principleName(p)
            val label = when {
                rate == null -> "$name · " + string(R.string.monthly_no_opportunity)
                previous != null -> string(R.string.monthly_compare, name, previous, rate)
                else -> string(R.string.monthly_rate, name, rate)
            }
            val shown = if (comparison?.unavailable?.contains(p) == true) {
                "$label · " + string(R.string.monthly_comparison_unavailable)
            } else label
            shown to "$shown · " + string(R.string.monthly_chances, count.n)
        }
        changes.filter { it.second > 0 }.maxByOrNull { it.second }?.let {
            add(text(string(R.string.monthly_improved, principleName(it.first), it.second), 28, Theme.accent, true))
        }
        changes.filter { it.second < 0 }.minByOrNull { it.second }?.let {
            add(text(string(R.string.monthly_decreased, principleName(it.first), -it.second), 26, Theme.coaching))
        }
        rows.forEach { (label, _) -> space(10); add(text(label, 28)) }
        space(24)
        add(text(string(R.string.monthly_family), 24, Theme.muted))
        space(12)
        val headline = listOfNotNull(title, latestScore?.let { string(R.string.monthly_score, it.roundToInt()) },
            string(R.string.monthly_distance, displayKm(month.km))).joinToString(" · ")
        val version = comparison?.ruleVersion ?: snapshot.history.trips.lastOrNull {
            YearMonth.from(Instant.ofEpochMilli(it.endAt).atZone(snapshot.zone)) == key
        }?.ruleVersion ?: RULE_VERSION
        val created = string(R.string.monthly_created, date.toString(), version)
        val detail = (listOfNotNull(headline, ruleNotice) + rows.map { it.second } + created).joinToString("\n")
        // QR의 조밀도가 지나치게 높아지면 핵심 비교 줄만 남긴다.
        val summary = if (detail.toByteArray(Charsets.UTF_8).size <= 450) detail
            else (listOf(title) + rows.map { it.first } + created).joinToString("\n")
        val matrix = reportQr(summary, 360)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE
        }
        val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        add(ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = string(R.string.monthly_qr) + ": " + summary
        }, 340)
    }
}

internal fun reportQr(summary: String, size: Int) = QRCodeWriter().encode(
    summary, BarcodeFormat.QR_CODE, size, size,
    mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4),
)

fun Ui.scoringRules(back: () -> Unit, close: () -> Unit): View = page(string(R.string.rules_title), back, close) {
    val c = Consts()
    listOf(
        string(R.string.principle_signal) to string(R.string.rules_signal, c.signalRefM.toInt(), c.signalCapM.toInt()),
        string(R.string.principle_decel) to string(R.string.rules_decel, c.decelWindowM.toInt(), c.gentleDecel.toInt()),
        string(R.string.principle_zone) to string(R.string.rules_zone, c.zoneDefaultLimitKmh, c.zoneLengthM.toInt(), c.zoneSpanKmh.toInt()),
        string(R.string.rules_calculation) to string(R.string.rules_formulas),
        string(R.string.rules_constants) to string(R.string.rules_example, c.priorMean, c.priorChances.toInt(),
            c.basicPrior.toInt(), c.basicPriorKm.toInt(), c.periodDays, c.periodMinKm.toInt()),
        string(R.string.rules_version) to RULE_VERSION,
    ).forEach { (title, details) ->
        add(text(title, 32, Theme.accent, true))
        space(8)
        add(text(details, 26, Theme.ink))
        space(22)
        divider()
        space(18)
    }
}
