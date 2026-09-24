package com.norypt.haven.data

import android.content.Context
import android.content.SharedPreferences
import com.norypt.haven.security.LockPolicy
import com.norypt.haven.ui.theme.ThemeMode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * NON-SENSITIVE app preferences (theme, lock policy, onboarding flags). Plain SharedPreferences in
 * credential-encrypted storage. Nothing private is ever written here; private settings live in
 * the content vault's `vault_meta` table.
 */
class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("haven_prefs", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM)
        set(v) = prefs.edit().putString(KEY_THEME, v.name).apply()

    var lockPolicy: LockPolicy
        get() = LockPolicy(
            inactivityTimeoutMs = prefs.getLong(KEY_TIMEOUT, LockPolicy().inactivityTimeoutMs),
            lockOnBackground = prefs.getBoolean(KEY_LOCK_BG, true),
            backgroundGraceMs = prefs.getLong(KEY_BG_GRACE, LockPolicy().backgroundGraceMs),
            lockOnScreenOff = prefs.getBoolean(KEY_LOCK_SCREEN_OFF, true),
        )
        set(v) = prefs.edit()
            .putLong(KEY_TIMEOUT, v.inactivityTimeoutMs)
            .putBoolean(KEY_LOCK_BG, v.lockOnBackground)
            .putLong(KEY_BG_GRACE, v.backgroundGraceMs)
            .putBoolean(KEY_LOCK_SCREEN_OFF, v.lockOnScreenOff)
            .apply()

    var requireDeviceAuth: Boolean
        get() = prefs.getBoolean(KEY_DEVICE_AUTH, false)
        set(v) = prefs.edit().putBoolean(KEY_DEVICE_AUTH, v).apply()

    var clipboardClearSeconds: Int
        get() = prefs.getInt(KEY_CLIP_SECONDS, 45)
        set(v) = prefs.edit().putInt(KEY_CLIP_SECONDS, v.coerceIn(10, 300)).apply()

    fun <T> observe(key: String, read: () -> T): Flow<T> = callbackFlow {
        trySend(read())
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == key || k == null) trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(l)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }.distinctUntilChanged()

    fun observeTheme(): Flow<ThemeMode> = observe(KEY_THEME) { themeMode }

    /** Wrong-password throttle state (a count and a timestamp, nothing secret). Written synchronously so a kill right after a failure cannot lose it. */
    var throttleFailures: Int
        get() = prefs.getInt(KEY_THROTTLE_FAILURES, 0)
        set(v) { prefs.edit().putInt(KEY_THROTTLE_FAILURES, v).commit() }
    var throttleLockedUntil: Long
        get() = prefs.getLong(KEY_THROTTLE_UNTIL, 0L)
        set(v) { prefs.edit().putLong(KEY_THROTTLE_UNTIL, v).commit() }

    fun clearAll() = prefs.edit().clear().apply()

    companion object {
        const val KEY_THROTTLE_FAILURES = "throttle_failures"
        const val KEY_THROTTLE_UNTIL = "throttle_locked_until"
        const val KEY_THEME = "theme_mode"
        const val KEY_TIMEOUT = "lock_timeout_ms"
        const val KEY_LOCK_BG = "lock_on_background"
        const val KEY_BG_GRACE = "background_grace_ms"
        const val KEY_LOCK_SCREEN_OFF = "lock_on_screen_off"
        const val KEY_DEVICE_AUTH = "require_device_auth"
        const val KEY_CLIP_SECONDS = "clipboard_clear_seconds"
    }
}
