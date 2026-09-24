package com.norypt.haven.session

import com.norypt.haven.alarm.AlarmActions
import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.storage.content.ContentDatabase

/**
 * Keeps the Direct Boot schedule store equal to the vault's reminders (timing only).
 * Run after every unlock and after restores; per-reminder updates happen in the repository.
 */
object ScheduleSync {
    suspend fun syncAll(db: ContentDatabase, runtime: AlarmRuntime) {
        val reminders = db.reminders().all()
        val store = runtime.store
        val known = store.reminders().all().map { it.reminderId }.toSet()
        for (r in reminders) {
            val existing = store.reminders().byId(r.id)
            if (existing == null || existing.revision != r.revision || existing.enabled != r.enabled || existing.scheduleJson != r.scheduleJson) {
                runtime.scheduler.upsertReminder(r.id, r.scheduleJson, r.enabled, r.revision)
            }
        }
        val vaultIds = reminders.map { it.id }.toSet()
        val followUpIds = db.tasks().withFollowUp().map { "task:" + it.id }.toSet()
        for (id in known) {
            if (id !in vaultIds && id !in followUpIds && !id.startsWith(AlarmActions.TEST_REMINDER_ID)) runtime.scheduler.removeReminder(id)
        }
        // Task follow-up alerts (one-time alarms at due + N minutes for incomplete tasks).
        for (t in db.tasks().withFollowUp()) {
            val due = runCatching { java.time.LocalDateTime.parse(t.dueLocal!!) }.getOrNull() ?: continue
            val at = due.plusMinutes((t.followUpMinutes ?: continue).toLong()).withSecond(0).withNano(0)
            val id = "task:" + t.id
            if (at.isBefore(java.time.LocalDateTime.now())) { runtime.scheduler.removeReminder(id); continue }
            val json = com.norypt.haven.recurrence.ScheduleJson.encode(com.norypt.haven.recurrence.Schedule(at.toLocalDate().toString(), at.toLocalTime().toString()))
            val existing = store.reminders().byId(id)
            if (existing == null || existing.scheduleJson != json) runtime.scheduler.upsertReminder(id, json, true, t.updatedAt)
        }
        runtime.scheduler.reconcile()
    }
}
