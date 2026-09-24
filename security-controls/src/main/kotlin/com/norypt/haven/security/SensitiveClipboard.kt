package com.norypt.haven.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copies secrets to the clipboard only on explicit user action, marks them sensitive
 * (hidden from the Android 13+ clipboard preview) and attempts to clear them after
 * [clearAfterMs].
 *
 * Limits (also shown in the UI): clearing cannot retract what another app or a clipboard
 * history feature already read; on some devices the system or keyboard keeps its own history.
 * If the user copies something else before the timeout, Haven must not wipe it: the change
 * listener cancels the pending clear.
 */
public class SensitiveClipboard(context: Context, private val clearAfterMs: () -> Long = { 45_000L }) {
    private val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var pendingClear: Runnable? = null
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var ownClipSetAt: Long = 0L

    public fun copy(label: String, secret: CharSequence) {
        cancelPending()
        val clip = ClipData.newPlainText(label, secret).apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        clipboard.setPrimaryClip(clip)
        ownClipSetAt = System.currentTimeMillis()
        val l = ClipboardManager.OnPrimaryClipChangedListener {
            // Fires for our own set as well (within a few ms); anything later means the user copied something new.
            if (System.currentTimeMillis() - ownClipSetAt > 1_000L) cancelPending()
        }
        listener = l
        clipboard.addPrimaryClipChangedListener(l)
        val r = Runnable {
            runCatching { clipboard.clearPrimaryClip() }
            detachListener()
            pendingClear = null
        }
        pendingClear = r
        handler.postDelayed(r, clearAfterMs())
    }

    /** Clears immediately if the clip is still ours (called on lock). */
    public fun clearIfOwn() {
        if (pendingClear != null) {
            runCatching { clipboard.clearPrimaryClip() }
            cancelPending()
        }
    }

    private fun cancelPending() {
        pendingClear?.let(handler::removeCallbacks)
        pendingClear = null
        detachListener()
    }

    private fun detachListener() {
        listener?.let { runCatching { clipboard.removePrimaryClipChangedListener(it) } }
        listener = null
    }
}
