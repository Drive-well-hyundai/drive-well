package com.driveproof.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.consentPreferences by preferencesDataStore("drive-proof-consent")

data class RecordingConsent(
    val accepted: Boolean = false,
    val controls: Boolean = true,
    val navigation: Boolean = true,
    val telltales: Boolean = true,
    val location: Boolean = false,
)

class ConsentStore(context: Context, profile: String = DEFAULT_PROFILE) {
    private val dataStore = context.applicationContext.consentPreferences
    private val accepted = booleanPreferencesKey("${profile}_accepted")
    private val controls = booleanPreferencesKey("${profile}_controls")
    private val navigation = booleanPreferencesKey("${profile}_navigation")
    private val telltales = booleanPreferencesKey("${profile}_telltales")
    private val location = booleanPreferencesKey("${profile}_location")

    val values: Flow<RecordingConsent> = dataStore.data.map {
        RecordingConsent(it[accepted] ?: false, it[controls] ?: true, it[navigation] ?: true,
            it[telltales] ?: true, it[location] ?: false)
    }

    suspend fun save(value: RecordingConsent) {
        dataStore.edit {
            it[accepted] = value.accepted
            it[controls] = value.controls
            it[navigation] = value.navigation
            it[telltales] = value.telltales
            it[location] = value.location
        }
    }
}
