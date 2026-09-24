package com.norypt.haven.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import com.norypt.haven.alarm.store.OccurrenceEntity
import com.norypt.haven.security.SafeLog
import com.norypt.haven.alarm.store.OccurrenceKind
import com.norypt.haven.alarm.store.OccurrenceState
import com.norypt.haven.alarm.store.ScheduledReminderEntity
import com.norypt.haven.recurrence.Schedule
import com.norypt.haven.recurrence.ScheduleJson
import java.time.Instant
import java.time.ZoneId

/**
 * Turns the Direct Boot schedule store into AlarmManager alarms and back.
 *
 * Scheduling intent (the store) and Android's alarm table are reconciled, never assumed to be in
 * sync: [reconcile] is safe to call at any time (boot, time change, permission change, app
 * start, after every store mutation) and always converges to "one armed alarm per PENDING or
 * SNOOZED occurrence, nothing else".
 *
 * Alarms are one-shot `setAlarmClock` alarms (exact, user-visible, Doze-exempt). Recurrence is
 * expanded by the recurrence engine; Android repeating alarms are never used.
 */
public class AlarmScheduler internal constructor(private val runtime: AlarmRuntime) {
    private val context get() = runtime.appContext
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)
    private val store get() = runtime.store

    /** Grace period: a PENDING occurrence found this late is still fired; later ones are marked MISSED. */
    public var missedGraceMs: Long = 30 * 60_000L

    /** Called by the app whenever a reminder's timing changes. Content never passes through here. */
    public fun upsertReminder(reminderId: String, scheduleJson: String, enabled: Boolean, revision: Long) {
        // Validate before storing so the store never holds undecodable JSON.
        ScheduleJson.decode(scheduleJson)
        runtime.runBlocking {
            store.reminders().upsert(ScheduledReminderEntity(reminderId, scheduleJson, enabled, revision, System.currentTimeMillis()))
            // Any armed occurrence for this reminder is now stale: drop PENDING, keep RINGING/SNOOZED (revision check decides later).
            store.occurrences().byReminder(reminderId).forEach { occ ->
                if (occ.state == OccurrenceState.PENDING || (occ.state == OccurrenceState.SNOOZED && !enabled)) {
                    cancelAlarm(occ)
                    store.occurrences().delete(occ.occurrenceId)
                }
            }
            reconcileLocked()
        }
    }

    public fun removeReminder(reminderId: String) {
        runtime.runBlocking {
            val rows = store.occurrences().byReminder(reminderId)
            rows.forEach { cancelAlarm(it) }
            runtime.notifications.cancelForReminder(reminderId)
            store.occurrences().deleteByReminder(reminderId)
            store.reminders().delete(reminderId)
            // A deleted series must stop ringing as well.
            rows.filter { it.state == OccurrenceState.RINGING }.forEach { AlarmRingService.stopFor(context, it.occurrenceId) }
            runtime.notifications.refreshMissedSummary()
        }
    }

    public fun removeAll() {
        runtime.runBlocking {
            val rows = store.occurrences().all()
            rows.forEach { cancelAlarm(it) }
            store.occurrences().deleteAll()
            store.reminders().deleteAll()
            runtime.notifications.cancelAll()
            rows.filter { it.state == OccurrenceState.RINGING }.forEach { AlarmRingService.stopFor(context, it.occurrenceId) }
        }
    }

    /** Public entry: reconcile on the serial thread. */
    public fun reconcile() {
        runtime.runBlocking { reconcileLocked() }
    }

    public fun reconcileAsync() {
        runtime.submit { reconcileLocked() }
    }

    internal fun reconcileLocked() {
        val now = System.currentTimeMillis()
        val nowInstant = Instant.ofEpochMilli(now)
        val deviceZone = ZoneId.systemDefault()
        val reminders = store.reminders().all().associateBy { it.reminderId }
        val occurrences = store.occurrences().all()
        val bootToken = runtime.prefs.currentBootToken()

        // 1. Drop occurrences whose reminder vanished; cancel their alarms.
        for (occ in occurrences) {
            if (occ.reminderId !in reminders) {
                SafeLog.d(TAG, "drop orphan ${occ.occurrenceId}")
                cancelAlarm(occ)
                store.occurrences().delete(occ.occurrenceId)
            }
        }

        // 2. Handle armed occurrences that are overdue (process was dead / alarm lost / permission revoked).
        for (occ in store.occurrences().byStates(listOf(OccurrenceState.PENDING, OccurrenceState.SNOOZED))) {
            val reminder = reminders[occ.reminderId] ?: continue
            if (!reminder.enabled) {
                SafeLog.d(TAG, "drop disabled ${occ.occurrenceId}")
                cancelAlarm(occ)
                store.occurrences().delete(occ.occurrenceId)
                continue
            }
            val due = if (occ.state == OccurrenceState.SNOOZED) (occ.snoozeUntil ?: occ.triggerAt) else occ.triggerAt
            if (due <= now) {
                if (now - due <= missedGraceMs) {
                    // Fire now (late but within grace).
                    context.sendBroadcast(AlarmIntents.fireIntent(context, occ.occurrenceId, occ.revision))
                } else {
                    markMissed(occ, now)
                }
            }
        }

        // 3. Ensure each enabled reminder has its next nominal occurrence armed.
        for (reminder in reminders.values) {
            if (!reminder.enabled) continue
            val schedule = runCatching { ScheduleJson.decode(reminder.scheduleJson) }.getOrNull() ?: continue
            val existing = store.occurrences().byReminder(reminder.reminderId)
            val armedNominal = existing.filter { it.state == OccurrenceState.PENDING && it.kind == OccurrenceKind.MAIN }
            // The next nominal occurrence must come after everything already dealt with (ringing/snoozed/dismissed/missed).
            val lastHandled = existing.filter { it.state != OccurrenceState.PENDING && it.kind == OccurrenceKind.MAIN }.maxOfOrNull { it.triggerAt } ?: 0L
            val after = Instant.ofEpochMilli(maxOf(now, lastHandled))
            val next = runtime.engine.nextOccurrence(schedule, after, deviceZone)
            // Cancel PENDING rows that do not match the computed next occurrence (time-zone or clock change).
            // Rows that are already due (triggerAt <= now) are never touched here: step 2 fires or
            // misses them, and their delivery may be in flight on this very thread (two reminders in
            // the same minute would otherwise lose the second one).
            for (p in armedNominal) {
                if (p.triggerAt <= now) continue
                val stillValid = next != null && p.occurrenceKey == next.key.value && p.triggerAt == next.instant.toEpochMilli() && p.revision == reminder.revision
                if (!stillValid) {
                    SafeLog.d(TAG, "drop stale pending ${p.occurrenceId} next=${next?.key?.value}")
                    cancelAlarm(p)
                    store.occurrences().delete(p.occurrenceId)
                }
            }
            if (next != null) {
                val id = occurrenceId(reminder.reminderId, next.key.value)
                val current = store.occurrences().byId(id)
                if (current == null) {
                    store.occurrences().upsert(
                        OccurrenceEntity(
                            occurrenceId = id, reminderId = reminder.reminderId, occurrenceKey = next.key.value,
                            triggerAt = next.instant.toEpochMilli(), zoneId = next.zone.id, state = OccurrenceState.PENDING,
                            revision = reminder.revision, snoozeUntil = null, snoozeElapsedDeadline = null, snoozeBootToken = null,
                            snoozeCount = 0, ringStartedElapsed = null, updatedAt = now,
                        ),
                    )
                }
                // Early "coming up" alerts for this occurrence: one PENDING row per offset still in the future.
                val wantedEarly = schedule.earlyOffsetsMinutes.filter { it > 0 }.distinct()
                    .map { m -> m to (next.instant.toEpochMilli() - m * 60_000L) }
                    .filter { (_, at) -> at > now }
                val earlyRows = existing.filter { it.kind == OccurrenceKind.EARLY }
                for (row in earlyRows) {
                    val stillWanted = wantedEarly.any { (m, at) -> row.occurrenceKey == next.key.value && row.earlyMinutes == m && row.triggerAt == at }
                    if (row.state == OccurrenceState.PENDING && !stillWanted) {
                        cancelAlarm(row); store.occurrences().delete(row.occurrenceId)
                    }
                }
                for ((m, at) in wantedEarly) {
                    val eid = earlyOccurrenceId(reminder.reminderId, next.key.value, m)
                    if (store.occurrences().byId(eid) == null) {
                        store.occurrences().upsert(
                            OccurrenceEntity(
                                occurrenceId = eid, reminderId = reminder.reminderId, occurrenceKey = next.key.value,
                                triggerAt = at, zoneId = next.zone.id, state = OccurrenceState.PENDING, revision = reminder.revision,
                                snoozeUntil = null, snoozeElapsedDeadline = null, snoozeBootToken = null, snoozeCount = 0,
                                ringStartedElapsed = null, updatedAt = now, kind = OccurrenceKind.EARLY, earlyMinutes = m,
                            ),
                        )
                    }
                }
            } else {
                existing.filter { it.kind == OccurrenceKind.EARLY && it.state == OccurrenceState.PENDING }.forEach { cancelAlarm(it); store.occurrences().delete(it.occurrenceId) }
            }
        }

        // 4. Arm AlarmManager for every PENDING/SNOOZED row (idempotent: same PendingIntent replaces).
        for (occ in store.occurrences().byStates(listOf(OccurrenceState.PENDING, OccurrenceState.SNOOZED))) {
            armAlarm(occ, bootToken)
        }

        // 5. Stale RINGING rows: the ring service died (force-stop, crash, reboot) before the ring
        //    timeout. If the ring started in a previous boot or longer ago than the timeout, resolve
        //    it (MISSED for main occurrences, DISMISSED for early alerts); otherwise the service
        //    resumes it.
        val timeoutMs = runtime.prefs.ringTimeoutMinutes * 60_000L
        val elapsedNow = SystemClock.elapsedRealtime()
        for (occ in store.occurrences().ringing()) {
            val started = occ.ringStartedElapsed
            val stale = started == null || started > elapsedNow || elapsedNow - started > timeoutMs
            if (stale) {
                val end = if (occ.kind == OccurrenceKind.EARLY) OccurrenceState.DISMISSED else OccurrenceState.MISSED
                store.occurrences().upsert(occ.copy(state = end, ringStartedElapsed = null, updatedAt = now))
                runtime.notifications.cancelRinging(occ.occurrenceId)
            }
        }
        store.occurrences().pruneFinished(now - KEEP_FINISHED_MS)
        runtime.notifications.refreshMissedSummary()
    }

    private fun markMissed(occ: OccurrenceEntity, now: Long) {
        cancelAlarm(occ)
        store.occurrences().upsert(occ.copy(state = OccurrenceState.MISSED, updatedAt = now, ringStartedElapsed = null))
    }

    internal fun armAlarm(occ: OccurrenceEntity, bootToken: String) {
        val pi = AlarmIntents.firePendingIntent(context, occ.occurrenceId, occ.revision, create = true) ?: return
        val show = showIntent()
        val am = alarmManager
        if (occ.state == OccurrenceState.SNOOZED && occ.snoozeBootToken == bootToken && occ.snoozeElapsedDeadline != null) {
            // Same boot: elapsed time is immune to wall-clock changes.
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, occ.snoozeElapsedDeadline, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, occ.snoozeElapsedDeadline, pi)
            }
            return
        }
        val at = if (occ.state == OccurrenceState.SNOOZED) (occ.snoozeUntil ?: occ.triggerAt) else occ.triggerAt
        if (am.canScheduleExactAlarms() && show != null) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else {
            // Degraded mode (permission revoked): inexact but still delivered; readiness screen explains.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    internal fun cancelAlarm(occ: OccurrenceEntity) {
        val pi = AlarmIntents.firePendingIntent(context, occ.occurrenceId, occ.revision, create = false) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun showIntent(): PendingIntent? {
        val cfg = AlarmRuntime.config ?: return null
        val intent = Intent(context, cfg.mainActivity).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Next armed occurrence according to the store (not AlarmManager). */
    public fun nextArmed(): OccurrenceEntity? = store.occurrences().nextArmed()

    /** What AlarmManager reports as the next alarm-clock for any app. */
    public fun nextSystemAlarmClock(): AlarmManager.AlarmClockInfo? = alarmManager.nextAlarmClock

    public fun canScheduleExact(): Boolean = alarmManager.canScheduleExactAlarms()

    public fun elapsedNow(): Long = SystemClock.elapsedRealtime()

    public companion object {
        private const val TAG = "AlarmScheduler"
        public const val KEEP_FINISHED_MS: Long = 30L * 24 * 60 * 60_000L
        public fun occurrenceId(reminderId: String, key: String): String = "$reminderId|$key"
        public fun earlyOccurrenceId(reminderId: String, key: String, minutes: Int): String = "$reminderId|$key~early$minutes"
        public fun reminderIdOf(occurrenceId: String): String = occurrenceId.substringBefore('|')
    }
}
