package com.norypt.haven.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Notifications carry NO private content: fixed strings only ("Haven reminder — unlock to view").
 * Sound is played by the ring service (USAGE_ALARM), so the channel itself is silent.
 */
public class AlarmNotifications internal constructor(private val runtime: AlarmRuntime) {
    private val context get() = runtime.appContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    public fun ensureChannels() {
        val alarms = NotificationChannel(CHANNEL_ALARMS, "Reminder alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Ringing reminders. Content is never shown until Haven is unlocked."
            setSound(null, null) // the service plays sound with USAGE_ALARM
            enableVibration(false)
            setBypassDnd(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC // text is generic by design
            setShowBadge(false)
        }
        val missed = NotificationChannel(CHANNEL_MISSED, "Missed reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Reminders that rang without an answer."
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val service = NotificationChannel(CHANNEL_SERVICE, "Alarm playback", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown only while a reminder is ringing."
            setSound(null, null)
        }
        manager.createNotificationChannels(listOf(alarms, missed, service))
    }

    public fun ringingNotification(occurrenceId: String): Notification {
        ensureChannels()
        val cfg = AlarmRuntime.config
        val fullScreen = cfg?.let {
            PendingIntent.getActivity(
                context, AlarmIntents.requestCode("fs|$occurrenceId"),
                Intent(context, it.ringActivity).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(AlarmIntents.EXTRA_OCCURRENCE_ID, occurrenceId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(TEXT_TITLE)
            .setContentText(TEXT_BODY)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
        // NOT setSilent(): a "silent" notification does not alert, and a non-alerting notification
        // never launches its full-screen intent. The channel itself has no sound, so the only
        // audio is the service's USAGE_ALARM playback.
        if (fullScreen != null) {
            builder.setContentIntent(fullScreen)
            builder.setFullScreenIntent(fullScreen, true)
        }
        if (runtime.prefs.lockedScreenActionsAllowed) {
            builder.addAction(0, "Snooze ${runtime.prefs.defaultSnoozeMinutes} min", AlarmIntents.actionPendingIntent(context, AlarmIntents.ACTION_SNOOZE, occurrenceId, runtime.prefs.defaultSnoozeMinutes))
            builder.addAction(0, "Dismiss", AlarmIntents.actionPendingIntent(context, AlarmIntents.ACTION_DISMISS, occurrenceId))
        } else {
            builder.setSubText("Unlock Haven to snooze or dismiss")
        }
        return builder.build()
    }

    public fun serviceNotification(count: Int): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (count == 1) "Haven reminder ringing" else "$count Haven reminders ringing")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    public fun showRinging(occurrenceId: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        runCatching { manager.notify(TAG_RINGING, AlarmIntents.requestCode(occurrenceId), ringingNotification(occurrenceId)) }
    }

    public fun cancelRinging(occurrenceId: String) {
        manager.cancel(TAG_RINGING, AlarmIntents.requestCode(occurrenceId))
    }

    public fun refreshMissedSummary() {
        val missed = runtime.store.occurrences().missed()
        if (missed.isEmpty()) {
            manager.cancel(TAG_MISSED, 0)
            return
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val cfg = AlarmRuntime.config
        val open = cfg?.let {
            PendingIntent.getActivity(context, 1, Intent(context, it.mainActivity).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (missed.size == 1) "Missed Haven reminder" else "${missed.size} missed Haven reminders")
            .setContentText("Unlock to view")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setSilent(true)
            .apply { if (open != null) setContentIntent(open) }
            .build()
        runCatching { manager.notify(TAG_MISSED, 0, n) }
    }

    public fun cancelMissed(@Suppress("UNUSED_PARAMETER") occurrenceId: String) {
        refreshMissedSummary()
    }

    public fun cancelForReminder(reminderId: String) {
        runtime.store.occurrences().byReminder(reminderId).forEach { cancelRinging(it.occurrenceId) }
        refreshMissedSummary()
    }

    public fun cancelAll() {
        manager.cancelAll()
    }

    public fun canUseFullScreenIntent(): Boolean =
        if (Build.VERSION.SDK_INT >= 34) manager.canUseFullScreenIntent() else true

    public companion object {
        public const val CHANNEL_ALARMS: String = "haven.alarms"
        public const val CHANNEL_MISSED: String = "haven.missed"
        public const val CHANNEL_SERVICE: String = "haven.alarm.service"
        public const val TEXT_TITLE: String = "Haven reminder"
        public const val TEXT_BODY: String = "Haven reminder — unlock to view."
        private const val TAG_RINGING = "ringing"
        private const val TAG_MISSED = "missed"
        public const val SERVICE_NOTIFICATION_ID: Int = 4001
    }
}
