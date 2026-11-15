package com.driveproof

import android.view.View
import android.widget.Toast
import com.driveproof.ui.Theme
import com.driveproof.ui.Ui

internal fun Ui.insurerEntry(open: () -> Unit): Pair<String, () -> Unit>? =
    string(R.string.insurer_entry) to open

internal fun Ui.insurerPreview(back: () -> Unit, close: () -> Unit): View? =
    page(string(R.string.insurer_title), back, close) {
        add(text(string(R.string.insurer_phase), 28, Theme.accent, true))
        space(16)
        add(text(string(R.string.insurer_recipient), 30, bold = true))
        add(text(string(R.string.insurer_period), 26, Theme.muted))
        space(20)
        divider()
        space(20)
        add(text(string(R.string.insurer_include), 28, bold = true))
        add(text(string(R.string.insurer_include_details), 26, Theme.muted))
        space(20)
        add(text(string(R.string.insurer_exclude), 28, bold = true))
        add(text(string(R.string.insurer_exclude_details), 26, Theme.muted))
        space(24)
        add(action(string(R.string.insurer_send), true) {
            Toast.makeText(context, string(R.string.insurer_simulated), Toast.LENGTH_LONG).show()
        })
    }
