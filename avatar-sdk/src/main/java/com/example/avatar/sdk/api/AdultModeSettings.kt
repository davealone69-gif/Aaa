package com.example.avatar.sdk.api

import android.content.Context

/** Local application setting only; it is never treated as proof of identity or age. */
class AdultModeSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("adult_mode_settings", Context.MODE_PRIVATE)
    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)
    val ageGateConfirmed: Boolean get() = prefs.getBoolean(KEY_AGE_GATE, false)

    fun enableAfterExplicit18PlusConfirmation(confirmed: Boolean) {
        require(confirmed) { "Explicit 18+ confirmation is required" }
        prefs.edit().putBoolean(KEY_ENABLED, true).putBoolean(KEY_AGE_GATE, true).apply()
    }

    fun disable() { prefs.edit().clear().apply() }

    fun policy(): ContentPolicy = ContentPolicy(adultModeEnabled = enabled, ageGateConfirmed = ageGateConfirmed)

    private companion object { const val KEY_ENABLED = "enabled"; const val KEY_AGE_GATE = "age_gate_confirmed" }
}
