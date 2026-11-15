package com.driveproof.ui

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.driveproof.data.ExemptEventEntity
import com.driveproof.data.ProofSnapshot
import com.driveproof.data.RecordingConsent
import com.driveproof.data.SceneEntity
import com.driveproof.data.TripEntity
import com.driveproof.model.Principle
import com.driveproof.model.RULE_VERSION
import com.driveproof.scoring.History
import com.driveproof.scoring.TripRow
import com.driveproof.scoring.score
import com.driveproof.scoring.weeklySummaries
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.pow
import kotlin.math.roundToInt

class WindowQualityTest {
    @Test fun proofAndPrincipleDetailUseTheSameCompletedWeeks() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val zone = ZoneOffset.UTC
        val monday = LocalDate.of(2026, 6, 1)
        val first = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = monday.plusWeeks(27).atStartOfDay(zone).toInstant().toEpochMilli()
        val history = History(trips = listOf(TripRow(now - 1, 2.0)))
        val weeks = weeklySummaries(history, first, now, zone, RULE_VERSION).mapIndexed { i, week ->
            week.copy(
                total = if (i == 4) 75.0 else 76.0,
                avgOnDistanceM = when (i) {
                    in 1..4 -> 13.0
                    in 23..26 -> 31.0
                    else -> 17.0
                },
                avgMaxDecel = if (i < 23) 12.0 else 5.0,
                maxZoneKmh = if (i < 23) 37.0 else 30.0,
            )
        }
        val snapshot = ProofSnapshot(history, weeks, now, zone)
        val currentScore = requireNotNull(score(history, now)).total.roundToInt()
        val context = instrument.targetContext
        instrument.runOnMainSync {
            for (width in listOf(628, 1274)) {
                val ui = Ui(context, width, 855)
                val proof = ui.proofPage(snapshot, emptyList(), true, {}, {}, {})
                val detail = ui.principleDetail(snapshot, Principle.SIGNAL, emptyList(), {}, {})
                assertTrue(proof.hasText(ui.string(R.string.proof_change, 75, currentScore)))
                assertTrue(proof.hasText("켠 거리 13m → 31m"))
                assertTrue(detail.hasText("6개월 전 4주 13.0m → 최근 4주 31.0m"))
                assertTrue(proof.hasText("최대 감속 초당 12km/h → 초당 5km/h"))
                assertTrue(proof.hasText("스쿨존 제한속도 준수"))
                assertTrue(proof.hasText("스쿨존 최고 속도 37km/h → 30km/h"))
                val decel = ui.principleDetail(snapshot, Principle.DECEL, emptyList(), {}, {})
                assertTrue(decel.hasText("주별 변화 (초당 km/h)"))
                assertTrue(decel.hasText("6개월 전 4주 초당 12.0km/h → 최근 4주 초당 5.0km/h"))
                assertTrue(decel.descendants().any { it.contentDescription == "그래프의 최근 값은 초당 5km/h입니다." })
                val rules = ui.scoringRules({}, {})
                assertTrue(rules.hasText("스쿨존 제한속도 준수"))
                assertTrue(rules.descendants().filterIsInstance<TextView>().any {
                    it.text.contains("최대 감속이 초당 5km/h")
                })
            }
        }
    }

    @Test fun cardsWithLongFeedbackKeepTextAndActionVisibleInBothWindows() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val base = instrument.targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            densityDpi = 160
            fontScale = 1f
        })
        val trip = TripEntity(
            startAt = 1_795_000_000_000, endAt = 1_795_000_080_000, status = "ENDED", isDemo = true, source = "REPLAY", km = 1.4,
            cardJson = """{"lines":[{"kind":"PARTIAL","text":"좌회전할 때 깜빡이를 켰어요","estimated":true},
                {"kind":"FIX","text":"좌회전 전 최대 감속 12km/h/s. 회전 50m 전부터 속도를 줄여 주세요"}],
                "notes":[{"text":"내비 안내가 없어 회전 장면만 평가했어요"},{"text":"기록이 끊긴 구간은 점수에서 뺐어요"}],
                "score":{"T":79,"text":"점수 변화 없음"}}""",
        )
        val risk = SceneEntity(tripId = 1, principle = "RISK", kind = "ABS", at = 0,
            valuesJson = """{"maxDecel":15,"exempts":true}""", criterion = null, s = null, path = null, estimated = false)
        val signal = risk.copy(principle = "SIGNAL", kind = "LEFT_TURN", valuesJson = """{"onDistanceM":29}""", s = 0.97)
        val scenes = listOf(signal, risk) + listOf("FCA", "ESC", "DRIVER_ATTENTION").map { risk.copy(kind = it) }
        val storedComparison = trip.copy(cardJson = """{"lines":[{"kind":"FIX","text":"최대 감속 · 이전 평균 12 → 이번 5km/h/s"}]}""")
        assertEquals("최대 감속 · 이전 평균 초당 12km/h → 이번 초당 5km/h", storedComparison.fixText())
        instrument.runOnMainSync {
            for (width in listOf(628, 1274)) {
                val ui = Ui(context, width, 855)
                var scenesBacks = 0
                var scenesCloses = 0
                var tripBacks = 0
                var tripCloses = 0
                listOf(
                    ui.tripCard(trip, false, null, {}, {}) to R.string.trip_scenes,
                    ui.scenesPage(trip, scenes, { scenesBacks++ }, { scenesCloses++ },
                        tripBack = { tripBacks++ }, tripClose = { tripCloses++ }) to R.string.scenes_done,
                ).forEach { (root, action) ->
                    root.measure(exact(width), exact(855))
                    root.layout(0, 0, width, 855)
                    val views = root.descendants().toList()
                    assertFalse(views.any { it is ScrollView })
                    val labels = views.filterIsInstance<TextView>()
                    val button = labels.single { it.text == context.getText(action) }
                    assertTrue(button.height >= 76)
                    val cardLines = labels.filter { it.text.startsWith("좌회전") }
                    assertEquals(if (action == R.string.scenes_done && width == 628) 0 else 2, cardLines.size)
                    cardLines.forEach { assertEquals(36f, it.textSize, 0.01f) }
                    if (cardLines.isNotEmpty()) assertTrue(cardLines.any { it.text.contains("최대 감속 초당 12km/h") })
                    if (action == R.string.scenes_done) {
                        assertTrue(root.hasText("이번 주행 장면"))
                        assertTrue(root.hasText("스쿨존 제한속도 준수 · 성공 0 / 기회 0"))
                        assertTrue(labels.any { it.text.contains("최대 감속 초당 15km/h") })
                        assertEquals(24f, labels.single { it.text == "미리 켜기 · 성공 0 / 기회 1" }.textSize, 0.01f)
                        assertEquals(24f, labels.single { it.text.contains("평균 29m 전") }.textSize, 0.01f)
                        assertEquals(24f, labels.single { it.text.contains("최대 감속 초당 15km/h") }.textSize, 0.01f)
                        assertTrue(views.any { it.contentDescription?.contains("평균 거리는 29미터") == true })
                        views.single { it.contentDescription == "뒤로" }.performClick()
                        val closes = views.filter { it.contentDescription == "닫기" }
                        assertEquals(if (ui.wide) 2 else 1, closes.size)
                        closes.forEach { it.performClick() }
                        assertEquals(if (ui.wide) 1 else 0, tripBacks)
                        assertEquals(if (ui.wide) 0 else 1, scenesBacks)
                        assertEquals(if (ui.wide) 1 else 0, tripCloses)
                        assertEquals(1, scenesCloses)
                    }
                    labels.filter { it.text.isNotBlank() }.forEach { label ->
                        assertTrue("${label.text}: height ${label.height}, content ${label.layout.height}",
                            label.height - label.totalPaddingTop - label.totalPaddingBottom >= label.layout.height)
                        val rect = Rect(0, 0, label.width, label.height)
                        (root as ViewGroup).offsetDescendantRectToMyCoords(label, rect)
                        assertTrue("${label.text}: $rect", rect.top >= 0 && rect.bottom <= 855)
                        if (label != button) assertTrue("${label.text}: $rect overlaps $action", rect.bottom <= button.topIn(root))
                    }
                }
            }
        }
    }

    @Test fun emptyStatesAndMonthlyQrUseTheCopyForEachScreen() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val base = instrument.targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            densityDpi = 160
            fontScale = 1f
        })
        val zone = ZoneOffset.UTC
        val now = LocalDate.of(2026, 11, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        val history = History(trips = listOf(TripRow(now - 60 * 60 * 1_000, 92.4)))
        val snapshot = ProofSnapshot(history, emptyList(), now, zone)
        val october = LocalDate.of(2026, 10, 25).atStartOfDay(zone).toInstant().toEpochMilli()
        val previousMonth = snapshot.copy(history = history.copy(trips = history.trips + TripRow(october, 75.0)))
        val lamp = ExemptEventEntity(tripId = null, date = now, decel = 15.0, reason = "ABS", mode = "TELLTALE", isDemo = true)
        val scene = lamp.copy(decel = 14.5, mode = "SCENE")
        instrument.runOnMainSync {
            for (width in listOf(628, 1274)) {
                val ui = Ui(context, width, 855)
                val proof = ui.proofPage(snapshot, emptyList(), false, {}, {}, {})
                val detail = ui.principleDetail(snapshot, Principle.SIGNAL, emptyList(), {}, {})
                val reasons = ui.reasonLog(snapshot, emptyList(), emptyList(), false, {}, {}, {})
                val excluded = ui.reasonLog(snapshot, emptyList(), listOf(lamp, scene), true, {}, {}, {})
                val report = ui.monthlyReport(snapshot, {}, {})
                assertTrue(proof.hasText("속도 권한이 없어 주행 기록을 멈췄어요"))
                assertTrue(proof.hasText("평가할 기록 없음"))
                assertTrue(detail.hasText("측정값이 없어요"))
                assertTrue(detail.hasText("최근에 기록한 장면이 없어요"))
                assertTrue(reasons.hasText("최근 기록에 고칠 점이 없어요"))
                assertTrue(excluded.hasText("11월 15일 · 초당 15.0km/h · ABS 경고등 앞뒤 5초"))
                assertTrue(excluded.hasText("11월 15일 · 초당 14.5km/h"))
                assertTrue(report.hasText("11월 리포트"))
                assertTrue(ui.monthlyReport(previousMonth, {}, {}).hasText("10월 리포트"))
                assertTrue(ui.monthlyReport(snapshot.copy(history = History()), {}, {}).hasText("월간 리포트"))
                assertTrue(report.hasText("미리 켜기 · 이 달에 평가할 장면 없음"))
                val consent = ui.consentPage(RecordingConsent(controls = false, navigation = false, telltales = false), {}, start = {})
                val settings = ui.settingsPage(RecordingConsent(location = true), true, {}, {}, {}, {}, {}, true, {})
                listOf(proof, detail, reasons, report, consent, settings).forEach { root ->
                    root.measure(exact(width), exact(855))
                    root.layout(0, 0, width, 855)
                    root.descendants().filterIsInstance<TextView>().filter { it.text.isNotBlank() }.forEach { label ->
                        assertTrue("${label.text}: height ${label.height}, content ${label.layout.height}",
                            label.height - label.totalPaddingTop - label.totalPaddingBottom >= label.layout.height)
                    }
                }

                val qr = report.descendants().filterIsInstance<ImageView>().single {
                    it.contentDescription?.startsWith("월간 리포트 QR: ") == true
                }
                val bitmap = (qr.drawable as BitmapDrawable).bitmap
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
                val decoded = QRCodeReader().decode(image).text
                assertTrue(decoded.contains("미리 켜기 · 이 달에 평가할 장면 없음 · 평가 기회 0회"))
                assertTrue(decoded.contains("스쿨존 제한속도 준수 · 이 달에 평가할 장면 없음 · 평가 기회 0회"))
                assertTrue(decoded.contains("만든 날짜 2026-11-15 · 점수 기준 $RULE_VERSION"))
                assertEquals("월간 리포트 QR: $decoded", qr.contentDescription)
            }
        }
    }

    @Test fun readableThemePairsMeetMinimumContrast() {
        for (surface in listOf(Theme.surface, Theme.tile)) {
            for (foreground in listOf(Theme.ink, Theme.muted, Theme.accent, Theme.coaching)) {
                assertTrue(contrast(foreground, surface) >= 4.5)
            }
        }
    }

    private fun exact(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)
    private fun View.hasText(text: String) = descendants().filterIsInstance<TextView>().any { it.text.toString() == text }
    private fun View.topIn(root: ViewGroup): Int = Rect(0, 0, width, height).also {
        root.offsetDescendantRectToMyCoords(this, it)
    }.top
    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
    }

    private fun contrast(foreground: Int, background: Int): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        fun luminance(color: Int) = 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color)) +
            0.0722 * linear(Color.blue(color))
        val a = luminance(foreground)
        val b = luminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
