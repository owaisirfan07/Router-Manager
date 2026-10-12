package com.veyronmonitor.app.data

import android.content.Context

/**
 * Stores the i.Solar account credentials locally on-device so the user
 * doesn't have to re-type them every launch.
 *
 * Note: stored in plain SharedPreferences for simplicity. This is fine
 * for a personal single-device tool, but if you ever share this app,
 * switch to androidx.security EncryptedSharedPreferences instead.
 */
object CredentialStore {
    private const val PREFS = "veyron_monitor_prefs"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"

    fun save(context: Context, username: String, password: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun load(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val u = prefs.getString(KEY_USERNAME, null)
        val p = prefs.getString(KEY_PASSWORD, null)
        return if (u != null && p != null) u to p else null
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
