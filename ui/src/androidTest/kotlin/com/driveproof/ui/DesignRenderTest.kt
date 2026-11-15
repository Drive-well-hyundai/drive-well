package com.driveproof.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.driveproof.data.SceneEntity
import com.driveproof.data.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class DesignRenderTest {
    @Test fun renderDesignExampleAtBothWindowSizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            densityDpi = 160
            fontScale = 1f
        })
        val at = LocalDate.now().atTime(18, 32).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val trip = TripEntity(
            id = 1, startAt = at - 60_000, endAt = at, km = 1.4, status = "ENDED", isDemo = true, source = "REPLAY",
            cardJson = """{"lines":[{"kind":"PRAISE","text":"스쿨존 3곳 모두 30km/h 이하로 달렸어요"},
                {"kind":"FIX","text":"깜빡이를 평균 15m 전에 켰어요. 회전 30m 전부터 켜 주세요"}],"notes":[],"score":{"T":87}}""",
        )
        fun scene(principle: String, kind: String, value: String, score: Double?) = SceneEntity(
            tripId = 1, principle = principle, kind = kind, at = at, valuesJson = value, criterion = null,
            s = score, path = "NAVI", estimated = false,
        )
        val scenes = listOf(30, 10, 5, 15, null).map {
            scene("SIGNAL", "LEFT_TURN", """{"onDistanceM":$it}""", if (it == 30) 1.0 else 0.0)
        } + (0 until 5).map {
            scene("DECEL", "LEFT_TURN", """{"maxDecel":${if (it < 3) 5 else 15}}""", if (it < 3) 1.0 else 0.0)
        } + listOf(24, 26, 27).map {
            scene("ZONE", "SCHOOL_ZONE", """{"maxKmh":$it,"limitKmh":30}""", 1.0)
        } + scene("RISK", "ABS", """{"maxDecel":15,"exempts":true}""", null)
        instrumentation.runOnMainSync {
            render(context, "trip-portrait", 628, 855, Ui(context, 628, 855).tripCard(trip, false, null, {}, {}))
            render(context, "scenes-landscape", 1274, 855, Ui(context, 1274, 855).scenesPage(trip, scenes, {}, {}))
        }
    }

    private fun render(context: Context, name: String, width: Int, height: Int, view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        assertEquals(width, view.measuredWidth)
        assertEquals(height, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xff131417.toInt())
        view.draw(canvas)
        File(context.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
