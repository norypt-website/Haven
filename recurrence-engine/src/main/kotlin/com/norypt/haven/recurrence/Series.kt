package com.norypt.haven.recurrence

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * A fully parsed and validated [Schedule] bound to a concrete zone, with the period arithmetic
 * the engine needs.
 *
 * A series is divided into *periods* numbered from 0: one day (DAILY), one Monday-based week
 * (WEEKLY), one month (MONTHLY), one year (YEARLY), each `interval` units apart; ONCE has a single
 * period 0 that holds all its dates. Every period yields zero or more nominal dates in ascending
 * order, and periods never overlap, so the nominal series is ascending by construction.
 */
internal class Series(val schedule: Schedule, deviceZone: ZoneId) {
    val start: LocalDate = parse("startDate") { LocalDate.parse(schedule.startDate) }
    val time: LocalTime = parse("time") { LocalTime.parse(schedule.time) }.also {
        require(it.second == 0 && it.nano == 0) { "time must have minute resolution: '${schedule.time}'" }
    }
    val rule: RecurrenceRule = schedule.rule
    val frequency: Frequency = rule.frequency
    val interval: Long = rule.interval.toLong().also { require(it >= 1) { "interval must be >= 1, was $it" } }
    val until: LocalDate? = (rule.end as? RecurrenceEnd.UntilDate)?.let { parse("end.date") { LocalDate.parse(it.date) } }
    val count: Long? = (rule.end as? RecurrenceEnd.AfterCount)?.count?.toLong()?.also {
        require(it >= 0) { "count must be >= 0, was $it" }
    }
    val zone: ZoneId = when (val policy = schedule.zonePolicy) {
        ZonePolicy.FollowDevice -> deviceZone
        is ZonePolicy.Fixed -> parse("zonePolicy.zoneId") { ZoneId.of(policy.zoneId) }
    }
    val skipped: Set<OccurrenceKey> = schedule.skippedKeys.onEach { OccurrenceKeys.toLocal(it) }
    val overrides: Map<OccurrenceKey, LocalDateTime> = schedule.overrides.entries.associate { (key, target) ->
        OccurrenceKeys.toLocal(key)
        key to parse("override target '$target'") { LocalDateTime.parse(target) }
    }

    private val onceDates: List<LocalDate> =
        (listOf(start) + schedule.extraDates.map { parse("extraDate '$it'") { LocalDate.parse(it) } })
            .distinct().sorted()
    private val weekdays: List<DayOfWeek> =
        (rule.weekdays.ifEmpty { setOf(start.dayOfWeek) }).sortedBy { it.value }
    private val weekAnchor: LocalDate = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private val startMonthIndex: Long = start.year.toLong() * 12 + (start.monthValue - 1)

    // ---- period arithmetic ------------------------------------------------------------------

    /** Period that contains [date]; negative when [date] lies before period 0. */
    fun periodOf(date: LocalDate): Long = when (frequency) {
        Frequency.ONCE -> 0
        Frequency.DAILY -> Math.floorDiv(ChronoUnit.DAYS.between(start, date), interval)
        Frequency.WEEKLY -> Math.floorDiv(Math.floorDiv(ChronoUnit.DAYS.between(weekAnchor, date), 7L), interval)
        Frequency.MONTHLY -> {
            val monthIndex = date.year.toLong() * 12 + (date.monthValue - 1)
            Math.floorDiv(monthIndex - startMonthIndex, interval)
        }
        Frequency.YEARLY -> Math.floorDiv(date.year.toLong() - start.year, interval)
    }

    /** Whether period [p] exists at all (ONCE has only period 0). */
    fun hasPeriod(p: Long): Boolean = p >= 0 && (frequency != Frequency.ONCE || p == 0L)

    /** First calendar day of period [p]; used to stop before iterating past an UntilDate. */
    fun periodStart(p: Long): LocalDate = when (frequency) {
        Frequency.ONCE -> onceDates.first()
        Frequency.DAILY -> start.plusDays(Math.multiplyExact(p, interval))
        Frequency.WEEKLY -> weekAnchor.plusWeeks(Math.multiplyExact(p, interval))
        Frequency.MONTHLY -> start.withDayOfMonth(1).plusMonths(Math.multiplyExact(p, interval))
        Frequency.YEARLY -> start.withDayOfYear(1).plusYears(Math.multiplyExact(p, interval))
    }

    /** Nominal dates in period [p], ascending, never before [start]. Empty when the policy skips it. */
    fun datesIn(p: Long): List<LocalDate> = when (frequency) {
        Frequency.ONCE -> if (p == 0L) onceDates else emptyList()
        Frequency.DAILY -> listOf(periodStart(p))
        Frequency.WEEKLY -> {
            val monday = periodStart(p)
            weekdays.map { monday.plusDays((it.value - 1).toLong()) }.filter { !it.isBefore(start) }
        }
        Frequency.MONTHLY -> dayInMonth(YearMonth.from(periodStart(p)))
        Frequency.YEARLY -> dayInMonth(YearMonth.of(periodStart(p).year, start.month))
    }

    private fun dayInMonth(month: YearMonth): List<LocalDate> {
        val day = start.dayOfMonth
        return when {
            month.isValidDay(day) -> listOf(month.atDay(day))
            rule.monthlyDayPolicy == MonthlyDayPolicy.LAST_VALID_DAY -> listOf(month.atEndOfMonth())
            else -> emptyList()
        }
    }

    /** Number of nominal occurrences in all periods before [p] (the index of the first date in [p]). */
    fun indexAtStart(p: Long): Long {
        if (p <= 0) return 0
        return when (frequency) {
            Frequency.ONCE -> 0
            Frequency.DAILY -> p
            Frequency.WEEKLY -> datesIn(0).size + (p - 1) * weekdays.size
            Frequency.MONTHLY, Frequency.YEARLY -> p - emptyPeriodsBefore(p)
        }
    }

    /**
     * Periods in `[0, p)` that yield no date under [MonthlyDayPolicy.SKIP_MONTH]. Validity of a
     * day-of-month repeats with the Gregorian cycle, so one cycle is counted by iteration and the
     * rest is multiplied out; this keeps the work bounded (at most 4800 probes) however far
     * ahead [p] lies.
     */
    private fun emptyPeriodsBefore(p: Long): Long {
        if (rule.monthlyDayPolicy != MonthlyDayPolicy.SKIP_MONTH) return 0
        val day = start.dayOfMonth
        val unitCycle: Long = when (frequency) {
            Frequency.MONTHLY -> when {
                day <= 28 -> return 0
                day == 29 -> 4800L // months in the 400-year leap cycle
                else -> 12L
            }
            Frequency.YEARLY -> if (start.month == Month.FEBRUARY && day == 29) 400L else return 0
            else -> return 0
        }
        val cycle = unitCycle / gcd(interval, unitCycle)
        val fullCycles = p / cycle
        val remainder = p % cycle
        val emptyPerCycle = if (fullCycles == 0L) 0L else (0 until cycle).count { datesIn(it).isEmpty() }.toLong()
        val firstOfTail = fullCycles * cycle
        val emptyInTail = (firstOfTail until firstOfTail + remainder).count { datesIn(it).isEmpty() }.toLong()
        return fullCycles * emptyPerCycle + emptyInTail
    }

    // ---- queries ----------------------------------------------------------------------------

    /** Number of nominal occurrences whose nominal local date-time is strictly before [local]. */
    fun countBefore(local: LocalDateTime): Long {
        val p = periodOf(local.toLocalDate())
        if (p < 0) return 0
        val inPeriod = datesIn(p).count { it.atTime(time).isBefore(local) }
        return indexAtStart(p) + inPeriod
    }

    /** Index of the nominal occurrence identified by [key], or null if the series never produces it. */
    fun indexOf(key: OccurrenceKey): Long? {
        val local = OccurrenceKeys.toLocal(key)
        if (local.toLocalTime() != time) return null
        val date = local.toLocalDate()
        val p = periodOf(date)
        if (!hasPeriod(p)) return null
        val position = datesIn(p).indexOf(date)
        if (position < 0) return null
        val index = indexAtStart(p) + position
        if (until != null && date.isAfter(until)) return null
        if (count != null && index >= count) return null
        return index
    }

    fun resolve(local: LocalDateTime): Instant? =
        RecurrenceEngine.resolveLocal(local, zone, schedule.dstGapPolicy, schedule.dstOverlapPolicy)

    /**
     * Nominal (non-overridden, non-skipped) occurrences with instant strictly after [after] and,
     * when [before] is given, strictly before it; ascending; at most [limit].
     */
    fun nominalOccurrences(after: Instant, before: Instant?, limit: Int): List<Occurrence> {
        val out = ArrayList<Occurrence>(minOf(limit, 64))
        if (limit <= 0) return out
        var p = firstCandidatePeriod(after)
        var index = indexAtStart(p)
        var iterations = 0
        while (iterations++ < MAX_ITERATIONS) {
            if (!hasPeriod(p)) return out
            val periodStart = try {
                periodStart(p)
            } catch (_: DateTimeException) {
                return out
            } catch (_: ArithmeticException) {
                return out
            }
            if (until != null && periodStart.isAfter(until)) return out
            if (count != null && index >= count) return out
            for (date in datesIn(p)) {
                if (until != null && date.isAfter(until)) return out
                if (count != null && index >= count) return out
                val position = index++
                val local = date.atTime(time)
                val key = OccurrenceKeys.of(local)
                if (key in skipped || key in overrides) continue
                val instant = resolve(local) ?: continue
                if (!instant.isAfter(after)) continue
                if (before != null && !instant.isBefore(before)) return out
                out += Occurrence(key, local, zone, instant, position.toInt())
                if (out.size >= limit) return out
            }
            p++
        }
        return out
    }

    /** Overridden occurrences (moved by the user) whose new instant falls in the requested window. */
    fun overriddenOccurrences(after: Instant, before: Instant?): List<Occurrence> =
        overrides.mapNotNull { (key, target) ->
            if (key in skipped) return@mapNotNull null
            val index = indexOf(key) ?: return@mapNotNull null
            val instant = resolve(target) ?: return@mapNotNull null
            if (!instant.isAfter(after)) return@mapNotNull null
            if (before != null && !instant.isBefore(before)) return@mapNotNull null
            Occurrence(key, target, zone, instant, index.toInt())
        }

    /**
     * Period to start scanning from so that nothing with an instant after [after] is missed. Two
     * days of slack cover every zone offset and DST shift.
     */
    private fun firstCandidatePeriod(after: Instant): Long {
        val date = try {
            after.atZone(zone).toLocalDate().minusDays(2)
        } catch (_: DateTimeException) {
            return 0
        }
        return periodOf(date).coerceAtLeast(0)
    }

    private inline fun <T> parse(label: String, block: () -> T): T = try {
        block()
    } catch (e: DateTimeException) {
        throw IllegalArgumentException("Invalid $label: ${e.message}", e)
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    companion object {
        /** Hard cap on periods visited per query; a series that yields nothing within it is treated as exhausted. */
        const val MAX_ITERATIONS: Int = 100_000
    }
}
