package com.driveproof.ui

import android.app.AlertDialog
import android.view.View
import com.driveproof.data.RecordingConsent
import com.driveproof.data.TripEntity

fun Ui.tripList(trips: List<TripEntity>, back: () -> Unit, close: () -> Unit, open: (Long) -> Unit): View =
    page(string(R.string.history_title), back, close) {
        if (trips.isEmpty()) add(text(string(R.string.empty_trip), 28, Theme.muted))
        trips.forEach { trip -> add(tripRow(trip, open)); divider() }
    }

fun Ui.settingsPage(
    consent: RecordingConsent,
    demo: Boolean,
    back: () -> Unit,
    close: () -> Unit,
    change: (RecordingConsent) -> Unit,
    history: () -> Unit,
    clear: (Boolean) -> Unit,
    locationAvailable: Boolean = false,
    locationPermission: (() -> Unit)? = null,
): View = page(string(R.string.proof_settings), back, close) {
    add(consentGroups(consent, locationAvailable, change))
    locationPermission?.let {
        space(12)
        add(text(string(R.string.consent_location_background), 24, Theme.muted))
        add(action(string(R.string.consent_location_settings), click = it))
    }
    space(20)
    add(action(string(R.string.settings_history), click = history))
    space(12)
    add(action(string(R.string.settings_clear)) { confirmClear(false, clear) })
    if (demo) {
        space(12)
        add(action(string(R.string.settings_clear_demo)) { confirmClear(true, clear) })
    }
}

private fun Ui.confirmClear(onlyDemo: Boolean, clear: (Boolean) -> Unit) {
    AlertDialog.Builder(context)
        .setTitle(string(if (onlyDemo) R.string.settings_confirm_demo else R.string.settings_confirm_all))
        .setMessage(string(R.string.settings_confirm_detail))
        .setNegativeButton(R.string.settings_cancel, null)
        .setPositiveButton(R.string.settings_delete) { _, _ -> clear(onlyDemo) }
        .show()
}
