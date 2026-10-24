package com.reelguard.app

import android.content.Context
import android.content.SharedPreferences

/** Small on-device settings store. Nothing here ever leaves the phone. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("reelguard", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    val blockedCount: Int
        get() = sp.getInt(KEY_BLOCKED, 0)

    val allowedCount: Int
        get() = sp.getInt(KEY_ALLOWED, 0)

    fun incBlocked() = sp.edit().putInt(KEY_BLOCKED, blockedCount + 1).apply()

    fun incAllowed() = sp.edit().putInt(KEY_ALLOWED, allowedCount + 1).apply()

    fun resetCounters() = sp.edit().putInt(KEY_BLOCKED, 0).putInt(KEY_ALLOWED, 0).apply()

    /** Human-readable description of the last decision the service made. */
    var lastEvent: String
        get() = sp.getString(KEY_LAST_EVENT, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_EVENT, value).apply()

    /** When true, the service saves the view IDs of the next Instagram screen it sees. */
    var captureRequested: Boolean
        get() = sp.getBoolean(KEY_CAPTURE_REQ, false)
        set(value) = sp.edit().putBoolean(KEY_CAPTURE_REQ, value).apply()

    var capturedIds: String
        get() = sp.getString(KEY_CAPTURED, "") ?: ""
        set(value) = sp.edit().putString(KEY_CAPTURED, value).apply()

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_BLOCKED = "blocked_count"
        const val KEY_ALLOWED = "allowed_count"
        const val KEY_LAST_EVENT = "last_event"
        const val KEY_CAPTURE_REQ = "capture_requested"
        const val KEY_CAPTURED = "captured_ids"
    }
}
