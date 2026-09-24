package com.norypt.haven.security

import android.util.Log

/**
 * The only logger Haven uses. Release builds compile [enabled] to false (and R8 strips
 * android.util.Log). Messages must never include titles, notes, passwords, keys or file paths.
 */
public object SafeLog {
    @Volatile public var enabled: Boolean = false

    public fun d(tag: String, message: String) { if (enabled) Log.d(tag, message) }
    public fun w(tag: String, message: String) { if (enabled) Log.w(tag, message) }
    /** Logs the exception class only, never its message (messages may echo user input). */
    public fun e(tag: String, message: String, t: Throwable? = null) {
        if (enabled) Log.e(tag, message + (t?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }
}
