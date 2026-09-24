package com.norypt.haven.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.norypt.haven.alarm.store.OccurrenceState

/**
 * Foreground service that keeps ringing until every ringing occurrence is snoozed, dismissed
 * or times out. Multiple simultaneous occurrences share one sound; each has its own
 * notification and its own snooze/dismiss.
 *
 * Direct-boot aware; started only from [AlarmReceiver]/[SystemEventReceiver] (exact-alarm
 * deliveries are exempt from background foreground-service start restrictions). If the process
 * is killed mid-ring, START_STICKY restarts it and it resumes from the store's RINGING rows.
 */
public class AlarmRingService : Service() {
    private lateinit var runtime: AlarmRuntime
    private var soundPlayer: AlarmSoundPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ringing = LinkedHashSet<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private val timeoutRunnables = HashMap<String, Runnable>()

    override fun onCreate() {
        super.onCreate()
        runtime = AlarmRuntime.get(this)
        val soundRes = resources.getIdentifier("haven_alarm", "raw", packageName)
        soundPlayer = AlarmSoundPlayer(this, soundRes)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground promptly regardless of the intent.
        startForeground(
            AlarmNotifications.SERVICE_NOTIFICATION_ID,
            runtime.notifications.serviceNotification(maxOf(1, ringing.size)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        acquireWakeLock()
        val id = intent?.getStringExtra(AlarmIntents.EXTRA_OCCURRENCE_ID)
        runtime.submit {
            val ids = if (id != null) listOf(id) else runtime.store.occurrences().ringing().map { it.occurrenceId }
            val live = ids.filter { runtime.store.occurrences().byId(it)?.state == OccurrenceState.RINGING }
            handler.post { onRingingSet(live, intent?.getStringExtra(EXTRA_STOP_ID)) }
        }
        return START_STICKY
    }

    private fun onRingingSet(live: List<String>, stopId: String?) {
        if (stopId != null) {
            ringing.remove(stopId)
            timeoutRunnables.remove(stopId)?.let(handler::removeCallbacks)
        }
        for (id in live) {
            if (ringing.add(id)) {
                runtime.notifications.showRinging(id)
                val row = runtime.store.occurrences().byId(id)
                val timeoutMs = if (row?.kind == com.norypt.haven.alarm.store.OccurrenceKind.EARLY) 2 * 60_000L else runtime.prefs.ringTimeoutMinutes * 60_000L
                val started = runtime.store.occurrences().byId(id)?.ringStartedElapsed ?: SystemClock.elapsedRealtime()
                val remaining = (started + timeoutMs - SystemClock.elapsedRealtime()).coerceAtLeast(1_000L)
                val r = Runnable { runtime.submit { runtime.actions.onRingTimeout(id) } }
                timeoutRunnables[id] = r
                handler.postDelayed(r, remaining)
            }
        }
        // Re-validate: anything no longer RINGING in the store leaves the set.
        val stillRinging = runtime.store.occurrences().ringing().map { it.occurrenceId }.toSet()
        ringing.retainAll(stillRinging)
        if (ringing.isEmpty()) {
            stopRinging()
            stopSelf()
            return
        }
        soundPlayer?.start(vibrate = runtime.prefs.vibrate)
        // Without notification permission there is no full-screen intent; attempt a direct launch of
        // the ring activity (Android may refuse background activity starts; the sound still plays).
        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            AlarmRuntime.config?.let { cfg ->
                runCatching {
                    startActivity(
                        Intent(this, cfg.ringActivity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra(AlarmIntents.EXTRA_OCCURRENCE_ID, ringing.first()),
                    )
                }
            }
        }
        startForeground(
            AlarmNotifications.SERVICE_NOTIFICATION_ID,
            runtime.notifications.serviceNotification(ringing.size),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "haven:alarm").apply { acquire(15 * 60_000L) }
    }

    private fun stopRinging() {
        soundPlayer?.stop()
        timeoutRunnables.values.forEach(handler::removeCallbacks)
        timeoutRunnables.clear()
        ringing.clear()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    public companion object {
        private const val EXTRA_STOP_ID = "stop_id"

        public fun start(context: Context, occurrenceId: String) {
            val intent = Intent(context, AlarmRingService::class.java).putExtra(AlarmIntents.EXTRA_OCCURRENCE_ID, occurrenceId)
            runCatching { context.startForegroundService(intent) }
        }

        /** Tells a running service that [occurrenceId] stopped ringing; the service stops itself when none remain. */
        public fun stopFor(context: Context, occurrenceId: String) {
            val intent = Intent(context, AlarmRingService::class.java).putExtra(EXTRA_STOP_ID, occurrenceId)
            runCatching { context.startForegroundService(intent) }
        }
    }
}
