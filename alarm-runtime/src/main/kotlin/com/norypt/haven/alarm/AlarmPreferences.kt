package com.norypt.haven.alarm

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.util.UUID

/**
 * Non-sensitive alarm settings in device-protected storage so the ringing UI can honour them
 * before first unlock. Nothing here is private content.
 */
public class AlarmPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)

    /** Whether snooze/dismiss are offered before Haven is unlocked. Default: allowed (usability), explained in Security settings. */
    public var lockedScreenActionsAllowed: Boolean
        get() = prefs.getBoolean(KEY_LOCKED_ACTIONS, true)
        set(v) = prefs.edit().putBoolean(KEY_LOCKED_ACTIONS, v).apply()

    public var vibrate: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(v) = prefs.edit().putBoolean(KEY_VIBRATE, v).apply()

    public var defaultSnoozeMinutes: Int
        get() = prefs.getInt(KEY_DEFAULT_SNOOZE, 10)
        set(v) = prefs.edit().putInt(KEY_DEFAULT_SNOOZE, v.coerceIn(1, 24 * 60)).apply()

    /** Comma-separated minute presets. */
    public var snoozePresetsMinutes: List<Int>
        get() = prefs.getString(KEY_SNOOZE_PRESETS, "5,10,15,30,60")!!.split(',').mapNotNull { it.trim().toIntOrNull() }
        set(v) = prefs.edit().putString(KEY_SNOOZE_PRESETS, v.joinToString(",")).apply()

    /** Ring timeout in minutes before an unanswered alarm is marked missed. */
    public var ringTimeoutMinutes: Int
        get() = prefs.getInt(KEY_RING_TIMEOUT, 10)
        set(v) = prefs.edit().putInt(KEY_RING_TIMEOUT, v.coerceIn(1, 60)).apply()

    /** Default alarm time (minutes after midnight) used for date-only reminders; shown explicitly in the UI. */
    public var defaultAlarmMinuteOfDay: Int
        get() = prefs.getInt(KEY_DEFAULT_TIME, 9 * 60)
        set(v) = prefs.edit().putInt(KEY_DEFAULT_TIME, v.coerceIn(0, 24 * 60 - 1)).apply()

    /**
     * Boot token: a random id regenerated whenever a new boot is detected (by the boot receiver
     * or by elapsedRealtime going backwards). Snoozes record it so elapsed-time deadlines are only
     * trusted within the boot that created them.
     */
    public fun currentBootToken(): String {
        val stored = prefs.getString(KEY_BOOT_TOKEN, null)
        val elapsedAtWrite = prefs.getLong(KEY_BOOT_ELAPSED, Long.MAX_VALUE)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (stored != null && nowElapsed >= elapsedAtWrite) {
            prefs.edit().putLong(KEY_BOOT_ELAPSED, nowElapsed).apply()
            return stored
        }
        return newBootToken()
    }

    public fun newBootToken(): String {
        val token = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_BOOT_TOKEN, token).putLong(KEY_BOOT_ELAPSED, SystemClock.elapsedRealtime()).apply()
        return token
    }

    public fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_LOCKED_ACTIONS = "locked_actions_allowed"
        const val KEY_VIBRATE = "vibrate"
        const val KEY_DEFAULT_SNOOZE = "default_snooze_min"
        const val KEY_SNOOZE_PRESETS = "snooze_presets"
        const val KEY_RING_TIMEOUT = "ring_timeout_min"
        const val KEY_DEFAULT_TIME = "default_alarm_minute"
        const val KEY_BOOT_TOKEN = "boot_token"
        const val KEY_BOOT_ELAPSED = "boot_elapsed"
    }
}
