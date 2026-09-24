package com.norypt.haven.recurrence

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Deterministic expansion of a [Schedule] into concrete [Occurrence]s.
 *
 * Semantics are inspired by RFC 5545, with explicit Haven policies (see the module README); this
 * is not a strict RFC 5545 implementation. Every query is pure: the same schedule, instants and
 * device zone always give the same result. Invalid schedules (unparseable dates, interval < 1,
 * unknown zone id) raise [IllegalArgumentException]; callers are expected to validate on input.
 *
 * Long-running series are handled without walking their history: the first candidate period near
 * the query instant is computed arithmetically, and every scan is bounded by a hard iteration cap.
 */
public class RecurrenceEngine {

    /**
     * Occurrences with instant strictly after [after], ascending, at most [limit]. Skipped keys are
     * never returned. Overrides are applied and re-sorted by their new instant. [deviceZone] is used
     * when the schedule's zone policy is [ZonePolicy.FollowDevice].
     */
    public fun occurrencesAfter(schedule: Schedule, after: Instant, deviceZone: ZoneId, limit: Int): List<Occurrence> =
        expand(Series(schedule, deviceZone), after, null, limit)

    /** The single next occurrence after [after], or null if the series is exhausted. */
    public fun nextOccurrence(schedule: Schedule, after: Instant, deviceZone: ZoneId): Occurrence? =
        occurrencesAfter(schedule, after, deviceZone, 1).firstOrNull()

    /** Occurrences with instant in `[from, to)`, ascending, at most [limit]. */
    public fun occurrencesBetween(
        schedule: Schedule,
        from: Instant,
        to: Instant,
        deviceZone: ZoneId,
        limit: Int = 1000,
    ): List<Occurrence> {
        if (!from.isBefore(to)) return emptyList()
        // [from, to) == (from - 1ns, to)
        return expand(Series(schedule, deviceZone), from.minusNanos(1), to, limit)
    }

    /**
     * Resolves one nominal local date-time in [zone] under the given DST policies. Returns null
     * when the local time falls in a spring-forward gap and [gap] is [DstGapPolicy.SKIP_OCCURRENCE].
     */
    public fun resolve(local: LocalDateTime, zone: ZoneId, gap: DstGapPolicy, overlap: DstOverlapPolicy): Instant? =
        resolveLocal(local, zone, gap, overlap)

    /**
     * Splits a series for "edit this and all future occurrences" at [fromKey].
     *
     * The head keeps everything before [fromKey]: its end becomes [RecurrenceEnd.UntilDate] of the
     * day before (or the earlier of that and an existing until date), or for
     * [RecurrenceEnd.AfterCount] the number of nominal occurrences before [fromKey]. The tail starts
     * on [fromKey]'s date with the same rule and the remaining count. Skipped keys and overrides are
     * distributed by their nominal position; extra dates likewise.
     *
     * Note that the tail's start date is the nominal date of [fromKey]; if that date was produced by
     * [MonthlyDayPolicy.LAST_VALID_DAY] (say Feb 28 for a "31st" rule), the tail continues as a
     * "28th" rule.
     */
    public fun splitSeries(schedule: Schedule, fromKey: OccurrenceKey): SeriesSplit {
        val series = Series(schedule, ZoneOffset.UTC)
        val local = OccurrenceKeys.toLocal(fromKey)
        val date = local.toLocalDate()
        val before = series.countBefore(local)
        val dayBefore = date.minusDays(1)

        val headEnd: RecurrenceEnd = when (val end = schedule.rule.end) {
            RecurrenceEnd.Never -> RecurrenceEnd.UntilDate(dayBefore.toString())
            is RecurrenceEnd.UntilDate -> RecurrenceEnd.UntilDate(minOf(series.until!!, dayBefore).toString())
            is RecurrenceEnd.AfterCount -> RecurrenceEnd.AfterCount(minOf(end.count.toLong(), before).toInt())
        }
        val tailEnd: RecurrenceEnd = when (val end = schedule.rule.end) {
            is RecurrenceEnd.AfterCount -> RecurrenceEnd.AfterCount((end.count - before).coerceAtLeast(0).toInt())
            else -> end
        }
        val (headSkips, tailSkips) = schedule.skippedKeys.partition { OccurrenceKeys.toLocal(it).isBefore(local) }
        val (headOverrides, tailOverrides) = schedule.overrides.entries.partition { OccurrenceKeys.toLocal(it.key).isBefore(local) }
        val (headExtras, tailExtras) = schedule.extraDates.partition { LocalDate.parse(it).isBefore(date) }

        val head = schedule.copy(
            extraDates = headExtras,
            rule = schedule.rule.copy(end = headEnd),
            skippedKeys = headSkips.toSet(),
            overrides = headOverrides.associate { it.key to it.value },
        )
        val tail = schedule.copy(
            startDate = date.toString(),
            extraDates = tailExtras.filter { it != date.toString() },
            rule = schedule.rule.copy(end = tailEnd),
            skippedKeys = tailSkips.toSet(),
            overrides = tailOverrides.associate { it.key to it.value },
        )
        return SeriesSplit(head, tail)
    }

    /** True when the schedule can never produce another occurrence after [after]. */
    public fun isExhausted(schedule: Schedule, after: Instant, deviceZone: ZoneId): Boolean =
        nextOccurrence(schedule, after, deviceZone) == null

    private fun expand(series: Series, after: Instant, before: Instant?, limit: Int): List<Occurrence> {
        if (limit <= 0) return emptyList()
        val nominal = series.nominalOccurrences(after, before, limit)
        val moved = series.overriddenOccurrences(after, before)
        if (moved.isEmpty()) return nominal
        return (nominal + moved)
            .sortedWith(compareBy<Occurrence> { it.instant }.thenBy { it.nominalLocal }.thenBy { it.key.value })
            .take(limit)
    }

    internal companion object {
        fun resolveLocal(local: LocalDateTime, zone: ZoneId, gap: DstGapPolicy, overlap: DstOverlapPolicy): Instant? {
            val offsets = zone.rules.getValidOffsets(local)
            return when (offsets.size) {
                1 -> local.atOffset(offsets[0]).toInstant()
                0 -> when (gap) {
                    DstGapPolicy.SHIFT_FORWARD -> ZonedDateTime.ofLocal(local, zone, null).toInstant()
                    DstGapPolicy.SKIP_OCCURRENCE -> null
                }
                else -> {
                    val zoned = ZonedDateTime.ofLocal(local, zone, null)
                    when (overlap) {
                        DstOverlapPolicy.EARLIER_OFFSET -> zoned.withEarlierOffsetAtOverlap()
                        DstOverlapPolicy.LATER_OFFSET -> zoned.withLaterOffsetAtOverlap()
                    }.toInstant()
                }
            }
        }
    }
}
