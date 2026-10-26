package com.reelguard.app

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small on-device settings store. Nothing here ever leaves the phone. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("reelguard", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** All-time scrolls stopped. */
    val blockedCount: Int
        get() = sp.getInt(KEY_BLOCKED, 0)

    /** Scrolls stopped today (resets at midnight). */
    val todayBlocked: Int
        get() = if (sp.getString(KEY_TODAY_DATE, null) == today()) sp.getInt(KEY_TODAY_BLOCKED, 0) else 0

    val allowedCount: Int
        get() = sp.getInt(KEY_ALLOWED, 0)

    fun incBlocked() {
        val date = today()
        val todaySoFar = if (sp.getString(KEY_TODAY_DATE, null) == date) sp.getInt(KEY_TODAY_BLOCKED, 0) else 0
        sp.edit()
            .putInt(KEY_BLOCKED, blockedCount + 1)
            .putString(KEY_TODAY_DATE, date)
            .putInt(KEY_TODAY_BLOCKED, todaySoFar + 1)
            .apply()
    }

    fun incAllowed() = sp.edit().putInt(KEY_ALLOWED, allowedCount + 1).apply()

    fun resetCounters() = sp.edit()
        .putInt(KEY_BLOCKED, 0)
        .putInt(KEY_ALLOWED, 0)
        .putInt(KEY_TODAY_BLOCKED, 0)
        .apply()

    /** Human-readable description of the last decision the service made. */
    val lastEvent: String
        get() = sp.getString(KEY_LAST_EVENT, "") ?: ""

    /** The last [HISTORY_SIZE] decisions, newest first – shown under Fix detection. */
    val history: String
        get() = sp.getString(KEY_HISTORY, "") ?: ""

    /** Records a decision (with the time) as the "last" event and at the top of the history. */
    fun log(text: String) {
        val line = "${timeNow()} · $text"
        val lines = listOf(line) + history.lines().filter { it.isNotBlank() }.take(HISTORY_SIZE - 1)
        sp.edit()
            .putString(KEY_LAST_EVENT, line)
            .putString(KEY_HISTORY, lines.joinToString("\n"))
            .apply()
    }

    /** When true, the service saves the view IDs of the Instagram screen it sees (blocking paused). */
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

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        private const val HISTORY_SIZE = 20

        fun timeNow(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        const val KEY_HISTORY = "history"
        const val KEY_ENABLED = "enabled"
        const val KEY_BLOCKED = "blocked_count"
        const val KEY_TODAY_BLOCKED = "today_blocked"
        const val KEY_TODAY_DATE = "today_date"
        const val KEY_ALLOWED = "allowed_count"
        const val KEY_LAST_EVENT = "last_event"
        const val KEY_CAPTURE_REQ = "capture_requested"
        const val KEY_CAPTURED = "captured_ids"
    }
}
