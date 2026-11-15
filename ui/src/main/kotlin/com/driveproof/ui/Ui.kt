package com.driveproof.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

object Theme {
    val surface = Color.rgb(29, 30, 33)
    val tile = Color.rgb(49, 50, 54)
    val ink = Color.rgb(247, 248, 250)
    val muted = Color.rgb(163, 168, 175)
    val coaching = Color.rgb(143, 164, 194)
    val accent = Color.rgb(254, 169, 58)
    val line = Color.rgb(57, 59, 64)
}

const val WIDE_DP = 1000
const val MAX_SINGLE_DP = 760
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** 앱 창 안에서만 쓰는 화면 구성 요소. 시스템 영역은 그리지 않는다. */
class Ui(val context: Context, val widthDp: Int, val heightDp: Int) {
    val wide get() = widthDp >= WIDE_DP
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun text(value: String, sp: Int = 28, color: Int = Theme.ink, bold: Boolean = false,
             weight: Int = if (bold) 700 else 400): TextView =
        TextView(context).apply {
            text = value
            textSize = sp.toFloat()
            setTextColor(color)
            typeface = face(weight)
            letterSpacing = -0.01f
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }

    fun rounded(color: Int, radius: Int = 40, stroke: Int? = null, dashed: Boolean = false) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) {
                if (dashed) setStroke(dp(2), stroke, dp(4).toFloat(), dp(4).toFloat())
                else setStroke(dp(1), stroke)
            }
        }

    fun View.pad(h: Int, v: Int = h) = setPadding(dp(h), dp(v), dp(h), dp(v))
    fun LinearLayout.add(item: View, height: Int = WRAP) =
        addView(item, LinearLayout.LayoutParams(MATCH, if (height < 0) height else dp(height)))

    fun LinearLayout.space(height: Int) = add(View(context), height)
    fun LinearLayout.flex() = addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))
    fun LinearLayout.divider() = add(View(context).apply { setBackgroundColor(Theme.line) }, 1)
    fun LinearLayout.weighted(item: View, weight: Float = 1f) =
        addView(item, LinearLayout.LayoutParams(0, MATCH, weight))

    fun action(label: String, primary: Boolean = false, click: () -> Unit): TextView =
        text(label, 28, if (primary) Theme.accent else Theme.ink, true).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(82)
            pad(16, 10)
            val edge = if (primary) 0x1afea93a else Theme.line
            background = RippleDrawable(ColorStateList.valueOf(0x28FFFFFF), rounded(Theme.surface, 30, edge), null)
            isFocusable = true
            setOnClickListener { click() }
        }

    fun icon(drawable: Int, label: String, click: () -> Unit) = ImageView(context).apply {
        setImageResource(drawable)
        contentDescription = label
        scaleType = ImageView.ScaleType.FIT_CENTER
        pad(if (drawable == R.drawable.dp_close) 27 else 26)
        if (drawable == R.drawable.dp_close) setColorFilter(Theme.muted)
        background = RippleDrawable(ColorStateList.valueOf(0x28FFFFFF), null, rounded(Color.WHITE, 38))
        isFocusable = true
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(dp(76), dp(76))
    }

    fun header(title: String, onBack: (() -> Unit)? = null, onClose: (() -> Unit)?,
               suffix: View? = null, showLogo: Boolean = true) = row().apply {
        setPadding(dp(if (onBack == null) 40 else 12), 0, dp(10), 0)
        if (onBack != null) addView(icon(R.drawable.dp_back, string(R.string.nav_back), onBack))
        else if (showLogo) addView(ImageView(context).apply {
            setImageResource(R.drawable.dp_logo)
            contentDescription = null
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(36) }
        })
        addView(text(title, 28, weight = 600), LinearLayout.LayoutParams(0, MATCH, 1f))
        suffix?.let(::addView)
        if (onClose != null) addView(icon(R.drawable.dp_close, string(R.string.nav_close), onClose))
    }

    fun panel(content: View, full: Boolean = false, glow: Boolean = false) = FrameLayout(context).apply {
        val w = if (!full && wide) dp(MAX_SINGLE_DP.coerceAtMost(widthDp)) else MATCH
        addView(content, FrameLayout.LayoutParams(w, MATCH, Gravity.CENTER))
        val base = rounded(Theme.surface)
        content.background = if (glow) {
            val layer = context.getDrawable(if (wide && full) R.drawable.dp_glow_wide else R.drawable.dp_glow_single) as BitmapDrawable
            layer.gravity = Gravity.FILL
            LayerDrawable(arrayOf(base, layer))
        } else base
        content.clipToOutline = true
    }

    fun page(title: String, back: (() -> Unit)? = null, close: () -> Unit,
             scroll: Boolean = true, body: LinearLayout.() -> Unit) = panel(column().apply {
        add(header(title, back, close), 100)
        divider()
        val contents = column().apply { pad(32, 24); body() }
        if (scroll) add(ScrollView(context).apply {
            isFillViewport = true
            addView(contents, FrameLayout.LayoutParams(MATCH, WRAP))
        }, MATCH) else add(contents, MATCH)
    })

    internal fun face(weight: Int): Typeface = synchronized(fonts) {
        fonts.getOrPut(weight) {
            Typeface.Builder(context.assets, "fonts/asta_sans.ttf")
                .setFontVariationSettings("'wght' $weight")
                .setWeight(weight)
                .build() ?: Typeface.create(Typeface.DEFAULT, weight, false)
        }
    }

    private companion object {
        val fonts = mutableMapOf<Int, Typeface>()
    }
}
