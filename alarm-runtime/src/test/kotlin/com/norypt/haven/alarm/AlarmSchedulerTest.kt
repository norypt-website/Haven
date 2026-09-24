package com.norypt.haven.alarm

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.norypt.haven.alarm.store.OccurrenceState
import com.norypt.haven.recurrence.Frequency
import com.norypt.haven.recurrence.RecurrenceRule
import com.norypt.haven.recurrence.Schedule
import com.norypt.haven.recurrence.ScheduleJson
import com.norypt.haven.recurrence.ZonePolicy
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AlarmSchedulerTest {
    private lateinit var app: Application
    private lateinit var runtime: AlarmRuntime
    private lateinit var shadowAlarms: ShadowAlarmManager

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        AlarmRuntime.config = AlarmRuntimeConfig(mainActivity = Any::class.java, ringActivity = Any::class.java)
        AlarmRuntime.resetForTesting()
        runtime = AlarmRuntime.get(app)
        runtime.scheduler.removeAll()
        shadowAlarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun dailySchedule(daysFromNow: Long = 1, time: LocalTime = LocalTime.of(8, 30), zone: ZonePolicy = ZonePolicy.FollowDevice): Schedule =
        Schedule(startDate = LocalDate.now().plusDays(daysFromNow).toString(), time = time.toString(), rule = RecurrenceRule(Frequency.DAILY), zonePolicy = zone)

    @Test fun upsertArmsExactlyOnePendingOccurrenceWithAnAlarmClock() {
        runtime.scheduler.upsertReminder("r1", ScheduleJson.encode(dailySchedule()), enabled = true, revision = 1)
        val occ = runtime.store.occurrences().all()
        assertThat(occ).hasSize(1)
        assertThat(occ[0].state).isEqualTo(OccurrenceState.PENDING)
        val expected = ZonedDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(8, 30), ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        assertThat(occ[0].triggerAt).isEqualTo(expected)
        assertThat(shadowAlarms.scheduledAlarms).hasSize(1)
        assertThat(shadowAlarms.scheduledAlarms[0].triggerAtMs).isEqualTo(expected)
        assertThat(shadowAlarms.scheduledAlarms[0].type).isEqualTo(AlarmManager.RTC_WAKEUP)
        assertThat(runtime.scheduler.nextArmed()!!.occurrenceId).isEqualTo(occ[0].occurrenceId)
    }

    @Test fun reconcileIsIdempotent() {
        runtime.scheduler.upsertReminder("r1", ScheduleJson.encode(dailySchedule()), true, 1)
        repeat(3) { runtime.scheduler.reconcile() }
        assertThat(runtime.store.occurrences().all()).hasSize(1)
        assertThat(shadowAlarms.scheduledAlarms).hasSize(1)
    }

    @Test fun disablingRemovesPendingAndCancelsAlarm() {
        runtime.scheduler.upsertReminder("r1", ScheduleJson.encode(dailySchedule()), true, 1)
        runtime.scheduler.upsertReminder("r1", ScheduleJson.encode(dailySchedule()), false, 2)
        assertThat(runtime.store.occurrences().all()).isEmpty()
        assertThat(shadowAlarms.scheduledAlarms).isEmpty()
    }

    @Test fun removingReminderCancelsEverything() {
        runtime.scheduler.upsertReminder("r1", ScheduleJson.encode(dailySchedule()), true, 1)
        runtime.scheduler.removeReminder("r1")
        assertThat(runtime.store.occurrences().all()).isEmpty()
        assertThat(runtime.store.reminders().all()).isEmpty()
        assertThat(shadowAlarms.scheduledAlarms).isEmpty()
    }

    @Test fun firedWithCurrentRevisionRingsAndArmsNext() {
        runtime.scheduler.upsertReminder("r1", ScheduleJson.encode(dailySchedule()), true, 1)
        val occ = runtime.store.occurrences().all().single()
        runtime.actions.onFired(occ.occurrenceId, revision = 1)
        val after = runtime.store.occurrences().byId(occ.occurrenceId)!!
        assertThat(after.state).isEqualTo(OccurrenceState.RINGING)
        assertThat(after.ringStartedElapsed).isNotNull()
        // the ring service was started
        val started: Intent? = shadowOf(app).nextStartedService
        assertThat(started?.component?.className).isEqualTo(AlarmRingService::class.java.name)
        // and the following day's occurrence is already armed
        val pending = runtime.store.occurrences().byStates(listOf(OccurrenceState.PENDING))
        assertThat(pending).hasSize(1)
        assertThat(pending[0].triggerAt).isEqualTo(occ.triggerAt + 24 * 3600_000L)
    }

    @Test fun staleRevisionIsIgnored() {
        runtime.scheduler.upsertReminder("r1", ScheduleJson.encode(dailySchedule()), true, 1)
        val occ = runtime.store.occurrences().all().single()
        runtime.actions.onFired(occ.occurrenceId, revision = 0)
        assertThat(runtime.store.occurrences().byId(occ.occurrenceId)!!.state).isEqualTo(OccurrenceState.PENDING)
        assertThat(shadowOf(app).nextStartedService).isNull()
    }

    @Test fun snoozeUsesElapsedAlarmWithinSameBootAndIsIdempotent() {
        runtime.scheduler.upsertReminder("r1", ScheduleJson.encode(dailySchedule()), true, 1)
        val occ = runtime.store.occurrences().all().single()
        runtime.actions.onFired(occ.occurrenceId, 1)
        runtime.actions.snooze(occ.occurrenceId, 10)
        val snoozed = runtime.store.occurrences().byId(occ.occurrenceId)!!
        assertThat(snoozed.state).isEqualTo(OccurrenceState.SNOOZED)
        assertThat(snoozed.snoozeBootToken).isEqualTo(runtime.prefs.currentBootToken())
        assertThat(snoozed.snoozeCount).isEqualTo(1)
        val elapsedAlarm = shadowAlarms.scheduledAlarms.firstOrNull { it.type == AlarmManager.ELAPSED_REALTIME_WAKEUP }
        assertThat(elapsedAlarm).isNotNull()
        assertThat(elapsedAlarm!!.triggerAtMs).isEqualTo(snoozed.snoozeElapsedDeadline)
        // a duplicate snooze on a SNOOZED occurrence re-snoozes (count 2) but a dismiss twice is a no-op
        runtime.actions.dismiss(occ.occurrenceId)
        runtime.actions.dismiss(occ.occurrenceId)
        assertThat(runtime.store.occurrences().byId(occ.occurrenceId)!!.state).isEqualTo(OccurrenceState.DISMISSED)
    }

    @Test fun snoozeAfterRebootFallsBackToWallClock() {
        runtime.scheduler.upsertReminder("r1", ScheduleJson.encode(dailySchedule()), true, 1)
        val occ = runtime.store.occurrences().all().single()
        runtime.actions.onFired(occ.occurrenceId, 1)
        runtime.actions.snooze(occ.occurrenceId, 10)
        val snoozed = runtime.store.occurrences().byId(occ.occurrenceId)!!
        // simulate reboot: new boot token, alarms gone
        runtime.prefs.newBootToken()
        runtime.scheduler.reconcile()
        val rtc = shadowAlarms.scheduledAlarms.firstOrNull { it.type == AlarmManager.RTC_WAKEUP && it.triggerAtMs == snoozed.snoozeUntil }
        assertThat(rtc).isNotNull()
    }

    @Test fun dismissArmsNextOccurrenceAndNeverTouchesOtherReminders() {
        runtime.scheduler.upsertReminder("r1", ScheduleJson.encode(dailySchedule()), true, 1)
        runtime.scheduler.upsertReminder("r2", ScheduleJson.encode(dailySchedule(daysFromNow = 2)), true, 1)
        val occ1 = runtime.store.occurrences().byReminder("r1").single()
        runtime.actions.onFired(occ1.occurrenceId, 1)
        runtime.actions.dismiss(occ1.occurrenceId)
        val r1 = runtime.store.occurrences().byReminder("r1")
        assertThat(r1.map { it.state }).containsExactly(OccurrenceState.DISMISSED, OccurrenceState.PENDING)
        assertThat(runtime.store.occurrences().byReminder("r2").single().state).isEqualTo(OccurrenceState.PENDING)
    }

    @Test fun overdueBeyondGraceIsMarkedMissed() {
        // Yesterday 08:30, once.
        val s = Schedule(startDate = LocalDate.now().minusDays(1).toString(), time = "08:30")
        runtime.scheduler.upsertReminder("r1", ScheduleJson.encode(s), true, 1)
        // The engine only produces future occurrences, so inject an overdue PENDING row manually.
        val past = ZonedDateTime.now().minusHours(3).toInstant().toEpochMilli()
        runtime.store.occurrences().upsert(
            com.norypt.haven.alarm.store.OccurrenceEntity("r1|old", "r1", "old", past, "Europe/Berlin", OccurrenceState.PENDING, 1, null, null, null, 0, null, past),
        )
        runtime.scheduler.reconcile()
        assertThat(runtime.store.occurrences().byId("r1|old")!!.state).isEqualTo(OccurrenceState.MISSED)
        assertThat(runtime.store.occurrences().missed()).hasSize(1)
        runtime.actions.acknowledgeMissed("r1|old")
        assertThat(runtime.store.occurrences().missed()).isEmpty()
    }

    @Test fun overdueWithinGraceFiresNow() {
        val s = Schedule(startDate = LocalDate.now().minusDays(1).toString(), time = "08:30")
        runtime.scheduler.upsertReminder("r1", ScheduleJson.encode(s), true, 1)
        val past = ZonedDateTime.now().minusMinutes(5).toInstant().toEpochMilli()
        runtime.store.occurrences().upsert(
            com.norypt.haven.alarm.store.OccurrenceEntity("r1|late", "r1", "late", past, "Europe/Berlin", OccurrenceState.PENDING, 1, null, null, null, 0, null, past),
        )
        runtime.scheduler.reconcile()
        val fired = shadowOf(app).broadcastIntents.firstOrNull { it.action == AlarmIntents.ACTION_FIRE }
        assertThat(fired).isNotNull()
        assertThat(fired!!.getStringExtra(AlarmIntents.EXTRA_OCCURRENCE_ID)).isEqualTo("r1|late")
    }

    @Test fun timeZoneChangeRecomputesFollowDeviceButNotFixedZone() {
        runtime.scheduler.upsertReminder("dev", ScheduleJson.encode(dailySchedule()), true, 1)
        runtime.scheduler.upsertReminder("fix", ScheduleJson.encode(dailySchedule(zone = ZonePolicy.Fixed("Europe/Berlin"))), true, 1)
        val devBefore = runtime.store.occurrences().byReminder("dev").single().triggerAt
        val fixBefore = runtime.store.occurrences().byReminder("fix").single().triggerAt
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        runtime.scheduler.reconcile()
        val devAfter = runtime.store.occurrences().byReminder("dev").single().triggerAt
        val fixAfter = runtime.store.occurrences().byReminder("fix").single().triggerAt
        assertThat(devAfter).isEqualTo(devBefore + 6 * 3600_000L) // 08:30 New York is 6 h later than 08:30 Berlin (September)
        assertThat(fixAfter).isEqualTo(fixBefore)
    }

    @Test fun staleRingingRowBecomesMissedOnReconcile() {
        runtime.scheduler.upsertReminder("r1", ScheduleJson.encode(dailySchedule()), true, 1)
        val occ = runtime.store.occurrences().all().single()
        runtime.actions.onFired(occ.occurrenceId, 1)
        // Pretend the ring started long before the timeout (e.g. before a force-stop).
        val ringing = runtime.store.occurrences().byId(occ.occurrenceId)!!
        runtime.store.occurrences().upsert(ringing.copy(ringStartedElapsed = ringing.ringStartedElapsed!! - 60 * 60_000L))
        runtime.scheduler.reconcile()
        assertThat(runtime.store.occurrences().byId(occ.occurrenceId)!!.state).isEqualTo(OccurrenceState.MISSED)
    }

    @Test fun earlyOffsetsProduceEarlyOccurrences() {
        val s = dailySchedule().copy(earlyOffsetsMinutes = listOf(10, 60))
        runtime.scheduler.upsertReminder("r1", ScheduleJson.encode(s), true, 1)
        val rows = runtime.store.occurrences().byReminder("r1")
        assertThat(rows).hasSize(3)
        val main = rows.single { it.kind == com.norypt.haven.alarm.store.OccurrenceKind.MAIN }
        val early = rows.filter { it.kind == com.norypt.haven.alarm.store.OccurrenceKind.EARLY }.sortedBy { it.earlyMinutes }
        assertThat(early.map { it.earlyMinutes }).containsExactly(10, 60).inOrder()
        assertThat(early[0].triggerAt).isEqualTo(main.triggerAt - 10 * 60_000L)
        assertThat(shadowAlarms.scheduledAlarms).hasSize(3)
        // Early alert timing out is dismissed silently, never "missed".
        runtime.actions.onFired(early[0].occurrenceId, 1)
        runtime.actions.onRingTimeout(early[0].occurrenceId)
        assertThat(runtime.store.occurrences().byId(early[0].occurrenceId)!!.state).isEqualTo(OccurrenceState.DISMISSED)
        assertThat(runtime.store.occurrences().missed()).isEmpty()
    }

    @Test fun twoRemindersInTheSameMinuteBothRing() {
        // Both due "now": fire the first; the second's pending row must survive the reconcile and ring too.
        val past = ZonedDateTime.now().minusSeconds(20)
        val s = Schedule(startDate = past.toLocalDate().toString(), time = past.toLocalTime().withSecond(0).withNano(0).toString())
        runtime.scheduler.upsertReminder("a", ScheduleJson.encode(s), true, 1)
        runtime.scheduler.upsertReminder("b", ScheduleJson.encode(s), true, 1)
        val now = System.currentTimeMillis()
        for (id in listOf("a", "b")) {
            runtime.store.occurrences().upsert(
                com.norypt.haven.alarm.store.OccurrenceEntity("$id|k", id, "k", now - 20_000L, "Europe/Berlin", OccurrenceState.PENDING, 1, null, null, null, 0, null, now),
            )
        }
        runtime.actions.onFired("a|k", 1)
        assertThat(runtime.store.occurrences().byId("a|k")!!.state).isEqualTo(OccurrenceState.RINGING)
        assertThat(runtime.store.occurrences().byId("b|k")).isNotNull()
        runtime.actions.onFired("b|k", 1)
        assertThat(runtime.store.occurrences().byId("b|k")!!.state).isEqualTo(OccurrenceState.RINGING)
        assertThat(runtime.store.occurrences().ringing()).hasSize(2)
    }

    @Test fun testAlarmIsArmedUnderReservedId() {
        runtime.actions.scheduleTestAlarm(10)
        val occ = runtime.store.occurrences().byReminder(AlarmActions.TEST_REMINDER_ID).single()
        assertThat(occ.state).isEqualTo(OccurrenceState.PENDING)
        assertThat(occ.triggerAt - System.currentTimeMillis()).isIn(com.google.common.collect.Range.closed(5_000L, 11_000L))
        assertThat(shadowAlarms.scheduledAlarms).hasSize(1)
    }

    @Test fun pendingIntentsAreImmutableAndExplicit() {
        val pi = AlarmIntents.firePendingIntent(app, "x|y", 1, create = true)!!
        val shadowPi = shadowOf(pi)
        assertThat(shadowPi.flags and android.app.PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
        assertThat(shadowPi.savedIntent.component!!.className).isEqualTo(AlarmReceiver::class.java.name)
        assertThat(shadowPi.savedIntent.extras!!.keySet()).containsExactly(AlarmIntents.EXTRA_OCCURRENCE_ID, AlarmIntents.EXTRA_REVISION)
    }

    @Test fun notificationsNeverContainContent() {
        val n = runtime.notifications.ringingNotification("r1|k")
        val title = n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString()
        val text = n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertThat(title).isEqualTo(AlarmNotifications.TEXT_TITLE)
        assertThat(text).isEqualTo(AlarmNotifications.TEXT_BODY)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AlarmRuntimeReentrancyTest {
    @Test(timeout = 10_000)
    fun nestedBlockingCallsFromTheAlarmThreadDoNotDeadlock() {
        val app: Application = ApplicationProvider.getApplicationContext()
        AlarmRuntime.config = AlarmRuntimeConfig(Any::class.java, Any::class.java)
        AlarmRuntime.resetForTesting()
        val runtime = AlarmRuntime.get(app)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // Receiver-style: submit work that itself calls the blocking action API.
        runtime.submit {
            runtime.scheduler.upsertReminder("r", ScheduleJson.encode(Schedule(LocalDate.now().plusDays(1).toString(), "07:00")), true, 1)
            val occ = runtime.store.occurrences().all().single()
            runtime.actions.onFired(occ.occurrenceId, 1)
            runtime.actions.snooze(occ.occurrenceId, 5)
            runtime.actions.dismiss(occ.occurrenceId)
        }.get()
        assertThat(runtime.store.occurrences().byReminder("r").map { it.state }).contains(OccurrenceState.DISMISSED)
    }
}
