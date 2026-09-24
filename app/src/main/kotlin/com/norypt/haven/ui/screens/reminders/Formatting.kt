package com.norypt.haven.ui.screens.reminders

import com.norypt.haven.recurrence.Frequency
import com.norypt.haven.recurrence.MonthlyDayPolicy
import com.norypt.haven.recurrence.Occurrence
import com.norypt.haven.recurrence.RecurrenceEnd
import com.norypt.haven.recurrence.Schedule
import com.norypt.haven.recurrence.ZonePolicy
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Date/time formatting in the device locale. Nothing here touches content. */
object Fmt {
    private val dateFmt: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    private val timeFmt: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    private val dateTimeFmt: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())

    fun date(d: LocalDate): String = dateFmt.format(d)
    fun time(t: LocalTime): String = timeFmt.format(t)
    fun dateTime(dt: LocalDateTime): String = dateTimeFmt.format(dt)
    fun epochMs(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = dateTimeFmt.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun instant(i: Instant, zone: ZoneId = ZoneId.systemDefault()): String = dateTimeFmt.format(i.atZone(zone))

    /** Minutes after midnight -> "09:00" style text in the device locale. */
    fun minuteOfDay(minute: Int): String = time(LocalTime.of(minute / 60, minute % 60))

    /** "Today, 08:30" / "Tomorrow, 08:30" / full date-time, always in the device zone. */
    fun relative(i: Instant, now: LocalDate = LocalDate.now()): String {
        val zoned = i.atZone(ZoneId.systemDefault())
        val d = zoned.toLocalDate()
        return when (d) {
            now -> "Today, ${time(zoned.toLocalTime())}"
            now.plusDays(1) -> "Tomorrow, ${time(zoned.toLocalTime())}"
            now.minusDays(1) -> "Yesterday, ${time(zoned.toLocalTime())}"
            else -> dateTime(zoned.toLocalDateTime())
        }
    }

    /** The zone id to display, only when the schedule keeps a fixed zone that differs from the device zone. */
    fun zoneSuffix(schedule: Schedule): String? {
        val fixed = (schedule.zonePolicy as? ZonePolicy.Fixed)?.zoneId ?: return null
        return if (fixed == ZoneId.systemDefault().id) null else fixed
    }

    /** An occurrence in device time, plus the fixed zone's own wall time when it differs. */
    fun occurrence(o: Occurrence, schedule: Schedule): String {
        val base = relative(o.instant)
        val zone = zoneSuffix(schedule) ?: return base
        val there = o.instant.atZone(o.zone)
        return "$base ($zone ${time(there.toLocalTime())})"
    }

    fun weekday(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    private fun ordinalDay(day: Int): String {
        val suffix = when {
            day in 11..13 -> "th"
            day % 10 == 1 -> "st"
            day % 10 == 2 -> "nd"
            day % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$day$suffix"
    }

    /** "Every 2 weeks on Mon, Wed at 08:30 · Europe/Berlin · ends after 10 occurrences". */
    fun scheduleSummary(schedule: Schedule): String {
        val rule = schedule.rule
        val start = runCatching { LocalDate.parse(schedule.startDate) }.getOrNull()
        val time = runCatching { LocalTime.parse(schedule.time) }.getOrNull()?.let { time(it) } ?: schedule.time
        val n = rule.interval
        val repeat = when (rule.frequency) {
            Frequency.ONCE -> {
                val extra = schedule.extraDates.size
                val first = start?.let { date(it) } ?: schedule.startDate
                if (extra == 0) "Once on $first" else "On $first and $extra more ${if (extra == 1) "date" else "dates"}"
            }
            Frequency.DAILY -> if (n == 1) "Every day" else "Every $n days"
            Frequency.WEEKLY -> {
                val days = rule.weekdays.ifEmpty { setOfNotNull(start?.dayOfWeek) }.sortedBy { it.value }.joinToString(", ") { weekday(it) }
                (if (n == 1) "Every week" else "Every $n weeks") + if (days.isNotEmpty()) " on $days" else ""
            }
            Frequency.MONTHLY -> {
                val day = start?.dayOfMonth
                val policy = if (day != null && day >= 29) when (rule.monthlyDayPolicy) {
                    MonthlyDayPolicy.LAST_VALID_DAY -> " (last day in shorter months)"
                    MonthlyDayPolicy.SKIP_MONTH -> " (skipped in shorter months)"
                } else ""
                (if (n == 1) "Every month" else "Every $n months") + (day?.let { " on the ${ordinalDay(it)}" } ?: "") + policy
            }
            Frequency.YEARLY -> {
                val md = start?.let { "${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}" }
                val policy = if (start != null && start.monthValue == 2 && start.dayOfMonth == 29) when (rule.monthlyDayPolicy) {
                    MonthlyDayPolicy.LAST_VALID_DAY -> " (28 Feb in other years)"
                    MonthlyDayPolicy.SKIP_MONTH -> " (leap years only)"
                } else ""
                (if (n == 1) "Every year" else "Every $n years") + (md?.let { " on $it" } ?: "") + policy
            }
        }
        val parts = mutableListOf("$repeat at $time")
        zoneSuffix(schedule)?.let { parts += it }
        if (rule.frequency != Frequency.ONCE) {
            when (val end = rule.end) {
                RecurrenceEnd.Never -> Unit
                is RecurrenceEnd.UntilDate -> parts += "until " + (runCatching { date(LocalDate.parse(end.date)) }.getOrDefault(end.date))
                is RecurrenceEnd.AfterCount -> parts += "ends after ${end.count} ${if (end.count == 1) "occurrence" else "occurrences"}"
            }
        }
        val skipped = schedule.skippedKeys.size
        if (skipped > 0) parts += "$skipped ${if (skipped == 1) "occurrence" else "occurrences"} deleted"
        val moved = schedule.overrides.size
        if (moved > 0) parts += "$moved moved"
        return parts.joinToString(" · ")
    }

    /** Minutes -> "5 min", "1 hour", "3 hours", "1 day", "2 days". */
    fun minutesShort(minutes: Int): String = when {
        minutes >= 1440 && minutes % 1440 == 0 -> "${minutes / 1440} ${if (minutes == 1440) "day" else "days"}"
        minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60} ${if (minutes == 60) "hour" else "hours"}"
        else -> "$minutes min"
    }

    /** "Also 10 min and 1 hour before", or null when the schedule has no early reminders. */
    fun earlyOffsets(schedule: Schedule): String? {
        val offsets = schedule.earlyOffsetsMinutes.filter { it > 0 }.distinct().sorted()
        if (offsets.isEmpty()) return null
        val words = offsets.map { minutesShort(it) }
        val joined = when (words.size) {
            1 -> words[0]
            2 -> "${words[0]} and ${words[1]}"
            else -> words.dropLast(1).joinToString(", ") + " and " + words.last()
        }
        return "Also $joined before"
    }

    /** Short repeat text for list rows. */
    fun repeatShort(schedule: Schedule): String {
        val n = schedule.rule.interval
        return when (schedule.rule.frequency) {
            Frequency.ONCE -> if (schedule.extraDates.isEmpty()) "Once" else "Once, ${schedule.extraDates.size + 1} dates"
            Frequency.DAILY -> if (n == 1) "Daily" else "Every $n days"
            Frequency.WEEKLY -> if (n == 1) "Weekly" else "Every $n weeks"
            Frequency.MONTHLY -> if (n == 1) "Monthly" else "Every $n months"
            Frequency.YEARLY -> if (n == 1) "Yearly" else "Every $n years"
        }
    }
}
