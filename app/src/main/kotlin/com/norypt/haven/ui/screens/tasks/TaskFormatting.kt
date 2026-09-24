package com.norypt.haven.ui.screens.tasks

import com.norypt.haven.data.TaskRepository
import com.norypt.haven.recurrence.Occurrence
import com.norypt.haven.storage.content.Priority
import com.norypt.haven.storage.content.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Time used when a due date is chosen without a time. The edit screen always says so explicitly. */
internal val DefaultDueTime: LocalTime = LocalTime.of(9, 0)

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** `dueLocal` is an ISO local date-time; anything unparsable is treated as "no due date". */
internal fun parseDue(dueLocal: String?): LocalDateTime? = dueLocal?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

internal fun formatDate(date: LocalDate): String = dateFormatter.format(date)
internal fun formatTime(time: LocalTime): String = timeFormatter.format(time)
internal fun formatDateTime(dateTime: LocalDateTime): String = "${formatDate(dateTime.toLocalDate())}, ${formatTime(dateTime.toLocalTime())}"
internal fun formatEpochMillis(millis: Long): String = formatDateTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()))
internal fun formatOccurrence(occurrence: Occurrence): String = formatDateTime(LocalDateTime.ofInstant(occurrence.instant, occurrence.zone))

internal fun TaskEntity.due(): LocalDateTime? = parseDue(dueLocal)

/** The stored level as an enum; unknown levels read as [Priority.NONE]. */
internal fun TaskEntity.priorityEnum(): Priority = Priority.of(priority)

/** Overdue = has a due date-time in the past and is not completed. */
internal fun TaskEntity.isOverdue(now: LocalDateTime = LocalDateTime.now()): Boolean {
    val d = due() ?: return false
    return !completed && d.isBefore(now)
}

/** Case-insensitive match on title or notes, used for the in-list search. A blank query matches everything. */
internal fun TaskEntity.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return title.contains(q, ignoreCase = true) || notes.contains(q, ignoreCase = true)
}

internal fun taskCount(n: Int): String = if (n == 1) "1 task" else "$n tasks"

/** Short label for a follow-up choice as shown in radio lists: "Off", "1 hour later", ... */
internal fun followUpChoiceLabel(minutes: Int?): String {
    if (minutes == null || minutes <= 0) return "Off"
    TaskRepository.FOLLOW_UP_CHOICES.firstOrNull { it.first == minutes }?.let { return it.second }
    return "$minutes minutes later"
}

/** Sentence fragment for the detail screen: "Off" or "1 hour after due", "Next day after due". */
internal fun followUpDescription(minutes: Int?): String {
    if (minutes == null || minutes <= 0) return "Off"
    val label = followUpChoiceLabel(minutes)
    return if (label.endsWith(" later")) "${label.removeSuffix(" later")} after due" else "$label after due"
}
