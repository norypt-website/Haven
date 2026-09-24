package com.norypt.haven.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives alarm deliveries and notification actions. Not exported; every intent is explicit.
 * Direct-boot aware so alarms ring before the first unlock. Work runs on the serial alarm
 * thread via goAsync().
 */
public class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runtime = AlarmRuntime.get(context)
        val result = goAsync()
        runtime.submit {
            try {
                when (intent.action) {
                    AlarmIntents.ACTION_FIRE -> {
                        val id = intent.getStringExtra(AlarmIntents.EXTRA_OCCURRENCE_ID)
                        val rev = intent.getLongExtra(AlarmIntents.EXTRA_REVISION, -1L)
                        if (id != null && id.length <= 256) runtime.actions.onFired(id, rev)
                    }
                    AlarmIntents.ACTION_SNOOZE, AlarmIntents.ACTION_DISMISS, AlarmIntents.ACTION_RING_TIMEOUT -> {
                        if (intent.action == AlarmIntents.ACTION_SNOOZE || intent.action == AlarmIntents.ACTION_DISMISS) {
                            // Notification actions honour the locked-screen policy; the app path bypasses this check after authentication.
                            if (!runtime.prefs.lockedScreenActionsAllowed && !intent.getBooleanExtra(EXTRA_AUTHENTICATED, false)) return@submit
                        }
                        runtime.actions.handleActionIntent(intent)
                    }
                }
            } finally {
                result.finish()
            }
        }
    }

    public companion object {
        /** Set only by in-process, authenticated app code (never trusted from outside: the receiver is not exported). */
        public const val EXTRA_AUTHENTICATED: String = "authenticated"
    }
}

/**
 * Restores alarms after boot (LOCKED_BOOT_COMPLETED arrives before the user unlocks the device;
 * BOOT_COMPLETED afterwards — both call reconcile, which is idempotent), after app updates,
 * clock / time-zone changes and exact-alarm permission changes.
 *
 * Boot receivers only re-arm AlarmManager; they never start playback themselves.
 */
public class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val relevant = action in setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
        if (!relevant) return
        val runtime = AlarmRuntime.get(context)
        val result = goAsync()
        runtime.submit {
            try {
                if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED || action == Intent.ACTION_BOOT_COMPLETED) runtime.prefs.newBootToken()
                runtime.notifications.ensureChannels()
                runtime.scheduler.reconcileLocked()
                // If the process died while ringing, resume the ring service for RINGING rows.
                runtime.store.occurrences().ringing().forEach { AlarmRingService.start(context, it.occurrenceId) }
            } finally {
                result.finish()
            }
        }
    }
}
