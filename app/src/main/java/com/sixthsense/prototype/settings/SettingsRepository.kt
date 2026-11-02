package com.sixthsense.prototype.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "sixth_sense_settings")

data class AppSettings(
    val confidenceThreshold: Float = 0.60f,
    val cooldownMillis: Int = 700,
    val pulseDurationMillis: Int = 180,
    val showDebugLabels: Boolean = false,
)

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { values ->
        AppSettings(
            values[CONFIDENCE] ?: 0.60f,
            values[COOLDOWN] ?: 700,
            values[PULSE_DURATION] ?: 180,
            values[DEBUG_LABELS] ?: false,
        )
    }

    suspend fun save(settings: AppSettings) = context.settingsDataStore.edit { values ->
        values[CONFIDENCE] = settings.confidenceThreshold.coerceIn(0.1f, 0.95f)
        values[COOLDOWN] = settings.cooldownMillis.coerceIn(100, 5_000)
        values[PULSE_DURATION] = settings.pulseDurationMillis.coerceIn(50, 1_000)
        values[DEBUG_LABELS] = settings.showDebugLabels
    }

    private companion object {
        val CONFIDENCE = floatPreferencesKey("confidence_threshold")
        val COOLDOWN = intPreferencesKey("haptic_cooldown_millis")
        val PULSE_DURATION = intPreferencesKey("pulse_duration_millis")
        val DEBUG_LABELS = booleanPreferencesKey("debug_labels")
    }
}
