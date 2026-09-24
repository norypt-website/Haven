package com.norypt.haven.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * All PendingIntents are explicit (component set), immutable, and identified by a per-occurrence
 * data URI so AlarmManager treats each occurrence as a distinct alarm. Extras carry only the
 * occurrence id and revision — never content.
 */
public object AlarmIntents {
    public const val ACTION_FIRE: String = "com.norypt.haven.alarm.FIRE"
    public const val ACTION_SNOOZE: String = "com.norypt.haven.alarm.SNOOZE"
    public const val ACTION_DISMISS: String = "com.norypt.haven.alarm.DISMISS"
    public const val ACTION_RING_TIMEOUT: String = "com.norypt.haven.alarm.RING_TIMEOUT"
    public const val EXTRA_OCCURRENCE_ID: String = "occurrence_id"
    public const val EXTRA_REVISION: String = "revision"
    public const val EXTRA_SNOOZE_MINUTES: String = "snooze_minutes"

    private const val SCHEME = "haven-alarm"

    public fun occurrenceUri(occurrenceId: String): Uri = Uri.Builder().scheme(SCHEME).authority("occurrence").appendPath(occurrenceId).build()

    public fun fireIntent(context: Context, occurrenceId: String, revision: Long): Intent =
        Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(occurrenceUri(occurrenceId))
            .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
            .putExtra(EXTRA_REVISION, revision)

    public fun firePendingIntent(context: Context, occurrenceId: String, revision: Long, create: Boolean): PendingIntent? {
        val flags = PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, requestCode(occurrenceId), fireIntent(context, occurrenceId, revision), flags)
    }

    public fun actionPendingIntent(context: Context, action: String, occurrenceId: String, snoozeMinutes: Int? = null): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(action)
            .setData(occurrenceUri(occurrenceId))
            .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
        if (snoozeMinutes != null) intent.putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
        return PendingIntent.getBroadcast(context, requestCode("$action|$occurrenceId"), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Stable request code; collisions are harmless because the data URI also differentiates intents. */
    public fun requestCode(id: String): Int = id.hashCode() and 0x7fffffff
}
