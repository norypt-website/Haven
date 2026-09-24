package com.norypt.haven.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Live readiness facts. Computed every time it is asked; never cached, never "all good forever".
 */
public class AlarmReadiness(private val context: Context) {
    public enum class Status { OK, WARNING, BLOCKED }

    public data class Item(
        val id: String,
        val status: Status,
        val title: String,
        val detail: String,
        /** Settings intent the UI can launch to fix it, if any. */
        val fixIntent: Intent? = null,
    )

    public data class Report(val items: List<Item>, val nextArmedEpochMs: Long?, val systemNextAlarmEpochMs: Long?) {
        public val worst: Status get() = items.maxOfOrNull { it.status } ?: Status.OK
    }

    public fun check(runtime: AlarmRuntime): Report {
        val items = ArrayList<Item>()
        val pkg = context.packageName
        val am = context.getSystemService(AlarmManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)

        // Exact alarms
        val exact = am.canScheduleExactAlarms()
        items += Item(
            "exact_alarm",
            if (exact) Status.OK else Status.BLOCKED,
            "Exact alarms",
            if (exact) "Haven may schedule exact, Doze-exempt alarm-clock alarms." else "Without this permission alarms are delayed by the system and may arrive late or not at all in Doze.",
            if (exact) null else Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")),
        )

        // Notifications
        val notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val channel = nm.getNotificationChannel(AlarmNotifications.CHANNEL_ALARMS)
        val channelOk = channel == null || channel.importance >= NotificationManager.IMPORTANCE_HIGH
        items += Item(
            "notifications",
            when {
                !notificationsOn -> Status.BLOCKED
                !channelOk -> Status.WARNING
                else -> Status.OK
            },
            "Notifications",
            when {
                !notificationsOn -> "Notifications are off. The alarm will still make sound, but you cannot see, snooze or dismiss it from the shade."
                !channelOk -> "The 'Reminder alarms' channel importance was lowered; heads-up display may not appear."
                else -> "Alarm notifications are enabled with high importance."
            },
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
        )

        // Full-screen intent
        val fsi = runtime.notifications.canUseFullScreenIntent()
        items += Item(
            "full_screen",
            if (fsi) Status.OK else Status.WARNING,
            "Full-screen alerts",
            if (fsi) "Ringing reminders can take over the lock screen." else "Full-screen alerts are not allowed; the alarm sounds and shows a heads-up notification instead.",
            if (fsi || Build.VERSION.SDK_INT < 34) null else Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg")),
        )

        // Alarm volume
        val audio = context.getSystemService(AudioManager::class.java)
        val vol = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        items += Item(
            "alarm_volume",
            if (vol == 0) Status.BLOCKED else if (vol * 4 < max) Status.WARNING else Status.OK,
            "Alarm volume",
            if (vol == 0) "Alarm volume is muted: reminders will be silent." else "Alarm volume is $vol of $max.",
            Intent(Settings.ACTION_SOUND_SETTINGS),
        )

        // Do Not Disturb (alarms are allowed by default, but the user can exclude them)
        // Reading the DND policy needs notification-policy access, which Haven does not request; without it
        // we can only report the interruption filter honestly as "unknown".
        val filter = nm.currentInterruptionFilter
        val policyReadable = nm.isNotificationPolicyAccessGranted
        val dndState: Status
        val dndDetail: String
        when {
            filter == NotificationManager.INTERRUPTION_FILTER_NONE -> { dndState = Status.WARNING; dndDetail = "Do Not Disturb is set to total silence; alarms will not sound." }
            filter == NotificationManager.INTERRUPTION_FILTER_ALL -> { dndState = Status.OK; dndDetail = "Do Not Disturb is off." }
            policyReadable -> {
                val allowsAlarms = (nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0
                dndState = if (allowsAlarms) Status.OK else Status.WARNING
                dndDetail = if (allowsAlarms) "Alarms are allowed through Do Not Disturb." else "Do Not Disturb is set to silence alarms."
            }
            else -> { dndState = Status.WARNING; dndDetail = "Do Not Disturb is on. Haven cannot read whether alarms are allowed; check that 'Alarms' are permitted." }
        }
        items += Item("dnd", dndState, "Do Not Disturb", dndDetail, Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS))

        // Battery optimisation (informational: setAlarmClock is exempt, but OEM killers exist)
        val pm = context.getSystemService(PowerManager::class.java)
        val ignoring = pm.isIgnoringBatteryOptimizations(pkg)
        items += Item(
            "battery",
            if (ignoring) Status.OK else Status.WARNING,
            "Battery optimisation",
            if (ignoring) "Haven is exempt from battery optimisation." else "Alarm-clock alarms wake the device from Doze, but some devices restrict apps further. Exempting Haven is optional and improves reliability on aggressive OEM builds.",
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )

        val next = runtime.scheduler.nextArmed()
        val sys = runtime.scheduler.nextSystemAlarmClock()
        return Report(items, next?.let { it.snoozeUntil ?: it.triggerAt }, sys?.triggerTime)
    }
}
