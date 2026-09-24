package com.norypt.haven.alarm

import android.content.Intent
import android.os.SystemClock
import com.norypt.haven.alarm.store.OccurrenceEntity
import com.norypt.haven.alarm.store.OccurrenceState

/**
 * User actions on occurrences. Each is idempotent: repeating it (double tap, duplicate
 * broadcast) has no further effect. None of them touches tasks: completing a task is a
 * separate, authenticated action in the app.
 */
public class AlarmActions internal constructor(private val runtime: AlarmRuntime) {
    private val store get() = runtime.store
    private val context get() = runtime.appContext

    /** Alarm fired: PENDING/SNOOZED -> RINGING and start the ring service. Stale revisions are ignored. */
    public fun onFired(occurrenceId: String, revision: Long) {
        runtime.runBlocking {
            val occ = store.occurrences().byId(occurrenceId) ?: return@runBlocking
            val reminder = store.reminders().byId(occ.reminderId)
            if (reminder == null || !reminder.enabled || occ.revision != revision || reminder.revision != revision) {
                // Stale delivery: reconcile will clean up.
                runtime.scheduler.reconcileLocked()
                return@runBlocking
            }
            when (occ.state) {
                OccurrenceState.PENDING, OccurrenceState.SNOOZED -> {
                    store.occurrences().upsert(occ.copy(state = OccurrenceState.RINGING, ringStartedElapsed = SystemClock.elapsedRealtime(), updatedAt = System.currentTimeMillis()))
                    AlarmRingService.start(context, occurrenceId)
                    // Arm the *next* nominal occurrence right away so a reboot mid-ring does not lose the series.
                    runtime.scheduler.reconcileLocked()
                }
                OccurrenceState.RINGING -> AlarmRingService.start(context, occurrenceId) // duplicate delivery: make sure the service is up
                OccurrenceState.DISMISSED, OccurrenceState.MISSED -> Unit
            }
        }
    }

    /** Snooze for [minutes] from now. */
    public fun snooze(occurrenceId: String, minutes: Int) {
        val ms = minutes.coerceIn(1, 24 * 60) * 60_000L
        snoozeUntil(occurrenceId, System.currentTimeMillis() + ms, SystemClock.elapsedRealtime() + ms)
    }

    /** Snooze until a wall-clock time chosen by the user. */
    public fun snoozeUntil(occurrenceId: String, untilEpochMs: Long) {
        val delta = untilEpochMs - System.currentTimeMillis()
        snoozeUntil(occurrenceId, untilEpochMs, SystemClock.elapsedRealtime() + delta.coerceAtLeast(1_000L))
    }

    private fun snoozeUntil(occurrenceId: String, untilEpochMs: Long, elapsedDeadline: Long) {
        runtime.runBlocking {
            val occ = store.occurrences().byId(occurrenceId) ?: return@runBlocking
            if (occ.state != OccurrenceState.RINGING && occ.state != OccurrenceState.SNOOZED) return@runBlocking
            val token = runtime.prefs.currentBootToken()
            store.occurrences().upsert(
                occ.copy(
                    state = OccurrenceState.SNOOZED, snoozeUntil = untilEpochMs, snoozeElapsedDeadline = elapsedDeadline,
                    snoozeBootToken = token, snoozeCount = occ.snoozeCount + 1, ringStartedElapsed = null, updatedAt = System.currentTimeMillis(),
                ),
            )
            runtime.scheduler.armAlarm(store.occurrences().byId(occurrenceId)!!, token)
            runtime.notifications.cancelRinging(occurrenceId)
            AlarmRingService.stopFor(context, occurrenceId)
        }
    }

    /** Dismiss: the occurrence is over. Does NOT complete any task and does NOT disable the series. */
    public fun dismiss(occurrenceId: String) {
        runtime.runBlocking {
            val occ = store.occurrences().byId(occurrenceId) ?: return@runBlocking
            if (occ.state == OccurrenceState.DISMISSED) return@runBlocking
            runtime.scheduler.cancelAlarm(occ)
            store.occurrences().upsert(occ.copy(state = OccurrenceState.DISMISSED, ringStartedElapsed = null, snoozeUntil = null, snoozeElapsedDeadline = null, updatedAt = System.currentTimeMillis()))
            runtime.notifications.cancelRinging(occurrenceId)
            runtime.notifications.cancelMissed(occurrenceId)
            AlarmRingService.stopFor(context, occurrenceId)
            runtime.scheduler.reconcileLocked()
        }
    }

    /** Ring timeout: unanswered -> MISSED (sound stops, a missed notification remains). Early alerts are simply dismissed. */
    public fun onRingTimeout(occurrenceId: String) {
        runtime.runBlocking {
            val occ = store.occurrences().byId(occurrenceId) ?: return@runBlocking
            if (occ.state != OccurrenceState.RINGING) return@runBlocking
            val endState = if (occ.kind == com.norypt.haven.alarm.store.OccurrenceKind.EARLY) OccurrenceState.DISMISSED else OccurrenceState.MISSED
            store.occurrences().upsert(occ.copy(state = endState, ringStartedElapsed = null, updatedAt = System.currentTimeMillis()))
            runtime.notifications.cancelRinging(occurrenceId)
            runtime.notifications.refreshMissedSummary()
            AlarmRingService.stopFor(context, occurrenceId)
        }
    }

    /** Acknowledge a missed occurrence (from the app after unlock). */
    public fun acknowledgeMissed(occurrenceId: String) {
        runtime.runBlocking {
            val occ = store.occurrences().byId(occurrenceId) ?: return@runBlocking
            if (occ.state != OccurrenceState.MISSED) return@runBlocking
            store.occurrences().upsert(occ.copy(state = OccurrenceState.DISMISSED, updatedAt = System.currentTimeMillis()))
            runtime.notifications.refreshMissedSummary()
        }
    }

    public fun acknowledgeAllMissed() {
        runtime.runBlocking {
            store.occurrences().missed().forEach { occ ->
                store.occurrences().upsert(occ.copy(state = OccurrenceState.DISMISSED, updatedAt = System.currentTimeMillis()))
            }
            runtime.notifications.refreshMissedSummary()
        }
    }

    /** Test alarm: a one-off occurrence [inSeconds] from now under a reserved reminder id ([suffix] allows several at once). */
    public fun scheduleTestAlarm(inSeconds: Int = 10, suffix: String = "") {
        val reminderId = TEST_REMINDER_ID + suffix
        val at = java.time.ZonedDateTime.now().plusSeconds(inSeconds.toLong())
        val schedule = com.norypt.haven.recurrence.Schedule(
            startDate = at.toLocalDate().toString(),
            time = at.toLocalTime().withSecond(0).withNano(0).toString(),
        )
        // The engine works at minute resolution; arm the occurrence directly for precise timing.
        runtime.runBlocking {
            val now = System.currentTimeMillis()
            store.reminders().upsert(com.norypt.haven.alarm.store.ScheduledReminderEntity(reminderId, com.norypt.haven.recurrence.ScheduleJson.encode(schedule), true, now, now))
            store.occurrences().byReminder(reminderId).forEach { runtime.scheduler.cancelAlarm(it) }
            store.occurrences().deleteByReminder(reminderId)
            val key = at.toLocalDateTime().withSecond(0).withNano(0).toString()
            val occ = OccurrenceEntity(
                occurrenceId = AlarmScheduler.occurrenceId(reminderId, key), reminderId = reminderId, occurrenceKey = key,
                triggerAt = at.toInstant().toEpochMilli(), zoneId = at.zone.id, state = OccurrenceState.PENDING, revision = now,
                snoozeUntil = null, snoozeElapsedDeadline = null, snoozeBootToken = null, snoozeCount = 0, ringStartedElapsed = null, updatedAt = now,
            )
            store.occurrences().upsert(occ)
            runtime.scheduler.armAlarm(occ, runtime.prefs.currentBootToken())
        }
    }

    public fun isTestReminder(reminderId: String): Boolean = reminderId.startsWith(TEST_REMINDER_ID)

    public fun currentlyRinging(): List<OccurrenceEntity> = store.occurrences().ringing()

    /** Broadcast helper used by notification actions. */
    internal fun handleActionIntent(intent: Intent) {
        val id = intent.getStringExtra(AlarmIntents.EXTRA_OCCURRENCE_ID) ?: return
        when (intent.action) {
            AlarmIntents.ACTION_SNOOZE -> snooze(id, intent.getIntExtra(AlarmIntents.EXTRA_SNOOZE_MINUTES, runtime.prefs.defaultSnoozeMinutes))
            AlarmIntents.ACTION_DISMISS -> dismiss(id)
            AlarmIntents.ACTION_RING_TIMEOUT -> onRingTimeout(id)
        }
    }

    public companion object {
        public const val TEST_REMINDER_ID: String = "haven-test-alarm"
    }
}
