package com.norypt.haven.security

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/**
 * Window-level hardening for activities that can show private content:
 * FLAG_SECURE (no screenshots/screen recording/non-secure displays, obscured recents preview),
 * hidden overlay windows (Android 12+), and touch filtering when obscured (tapjacking).
 */
public object SecureWindow {
    /**
     * [allowCapture] must only ever be true in debug builds (design screenshots); release builds
     * pass false unconditionally.
     */
    public fun apply(activity: Activity, allowCapture: Boolean = false) {
        val window = activity.window
        if (allowCapture) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Requires android.permission.HIDE_OVERLAY_WINDOWS (declared by the app); never crash a
            // vault screen over a hardening extra.
            runCatching { window.setHideOverlayWindows(true) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(false)
        }
        window.decorView.filterTouchesWhenObscured = true
    }
}
