package com.driveproof.ui

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import com.driveproof.data.RecordingConsent

fun Ui.consentPage(initial: RecordingConsent, close: () -> Unit, locationAvailable: Boolean = false,
                   start: (RecordingConsent) -> Unit): View =
    page(string(R.string.consent_title), close = close) {
        var draft = initial
        add(text(string(R.string.consent_local), 24, Theme.muted))
        space(16)
        add(consentGroups(initial, locationAvailable) { draft = it })
        space(20)
        add(action(string(R.string.consent_start), true) { start(draft.copy(accepted = true)) })
    }

internal fun Ui.consentGroups(initial: RecordingConsent, locationAvailable: Boolean, change: (RecordingConsent) -> Unit) = column().apply {
    var value = initial
    add(text(string(R.string.consent_speed), 26, bold = true), 42)
    add(text(string(R.string.consent_speed_detail), 24, Theme.muted), 34)
    divider()
    fun group(label: Int, detail: Int, off: Int, enabled: Boolean, setter: (RecordingConsent, Boolean) -> RecordingConsent) {
        val subtitle = text(string(if (enabled) detail else off), 24, Theme.muted)
        add(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(column().apply {
                add(text(string(label), 26, bold = true))
                space(4)
                add(subtitle)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(Switch(context).apply {
                contentDescription = string(label)
                isChecked = enabled
                minimumHeight = dp(76)
                minimumWidth = dp(76)
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Theme.accent, Theme.muted),
                )
                setOnCheckedChangeListener { _, checked ->
                    value = setter(value, checked)
                    subtitle.text = string(if (checked) detail else off)
                    change(value)
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        })
        divider()
    }
    group(R.string.consent_controls, R.string.consent_controls_detail, R.string.consent_controls_off, initial.controls) { v, b -> v.copy(controls = b) }
    group(R.string.consent_navi, R.string.consent_navi_detail, R.string.consent_navi_off, initial.navigation) { v, b -> v.copy(navigation = b) }
    group(R.string.consent_lamps, R.string.consent_lamps_detail, R.string.consent_lamps_off, initial.telltales) { v, b -> v.copy(telltales = b) }
    if (locationAvailable) group(R.string.consent_location, R.string.consent_location_detail, R.string.consent_location_off,
        initial.location) { v, b -> v.copy(location = b) }
    else add(row().apply {
        addView(column().apply {
            add(text(string(R.string.consent_location), 26, Theme.muted, true))
            add(text(string(R.string.consent_unavailable), 24, Theme.muted))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(Switch(context).apply {
            contentDescription = string(R.string.consent_location)
            minimumHeight = dp(76)
            minimumWidth = dp(76)
            isEnabled = false
        })
    })
}
