package com.norypt.haven.recurrence

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertThrows
import org.junit.Test

class RecurrenceEngineTest {

    private val engine = RecurrenceEngine()
    private val utc: ZoneId = ZoneOffset.UTC
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val farPast: Instant = Instant.parse("1970-01-01T00:00:00Z")

    private fun rule(frequency: Frequency, interval: Int = 1, end: RecurrenceEnd = RecurrenceEnd.Never) =
        RecurrenceRule(frequency, interval, end = end)

    private fun schedule(start: String, time: String = "09:00", rule: RecurrenceRule = RecurrenceRule(Frequency.ONCE)) =
        Schedule(startDate = start, time = time, rule = rule)

    private fun List<Occurrence>.locals() = map { it.nominalLocal.toString() }
    private fun List<Occurrence>.dates() = map { it.nominalLocal.toLocalDate().toString() }
    private fun key(local: String) = OccurrenceKey(local)

    // ---- frequencies ----------------------------------------------------------------------

    @Test
    fun once_emitsStartAndExtraDates_sortedAndDeduplicated() {
        val s = schedule("2026-03-10").copy(extraDates = listOf("2026-03-01", "2026-03-10", "2026-04-02", "2026-03-01"))
        val result = engine.occurrencesAfter(s, farPast, utc, 10)
        assertThat(result.dates()).containsExactly("2026-03-01", "2026-03-10", "2026-04-02").inOrder()
        assertThat(result.map { it.index }).containsExactly(0, 1, 2).inOrder()
        assertThat(result.first().instant).isEqualTo(Instant.parse("2026-03-01T09:00:00Z"))
        assertThat(engine.occurrencesAfter(s, Instant.parse("2026-04-02T09:00:00Z"), utc, 10)).isEmpty()
    }

    @Test
    fun daily_everyThreeDays() {
        val s = schedule("2026-01-01", rule = rule(Frequency.DAILY, interval = 3))
        val result = engine.occurrencesAfter(s, farPast, utc, 4)
        assertThat(result.dates()).containsExactly("2026-01-01", "2026-01-04", "2026-01-07", "2026-01-10").inOrder()
        assertThat(result.map { it.index }).containsExactly(0, 1, 2, 3).inOrder()
    }

    @Test
    fun weekly_weekdaySet_everySecondWeek_neverBeforeStart() {
        // 2026-01-07 is a Wednesday; week 0 is Mon 2026-01-05 .. Sun 2026-01-11.
        val s = schedule(
            "2026-01-07",
            rule = RecurrenceRule(Frequency.WEEKLY, interval = 2, weekdays = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)),
        )
        val result = engine.occurrencesAfter(s, farPast, utc, 5)
        assertThat(result.dates())
            .containsExactly("2026-01-09", "2026-01-19", "2026-01-23", "2026-02-02", "2026-02-06").inOrder()
        assertThat(result.map { it.index }).containsExactly(0, 1, 2, 3, 4).inOrder()
    }

    @Test
    fun weekly_emptyWeekdays_usesWeekdayOfStart() {
        val s = schedule("2026-01-07", rule = rule(Frequency.WEEKLY))
        val result = engine.occurrencesAfter(s, farPast, utc, 3)
        assertThat(result.dates()).containsExactly("2026-01-07", "2026-01-14", "2026-01-21").inOrder()
        assertThat(result.all { it.nominalLocal.dayOfWeek == DayOfWeek.WEDNESDAY }).isTrue()
    }

    @Test
    fun monthly_31st_lastValidDay() {
        val s = schedule("2026-01-31", rule = RecurrenceRule(Frequency.MONTHLY, monthlyDayPolicy = MonthlyDayPolicy.LAST_VALID_DAY))
        val result = engine.occurrencesAfter(s, farPast, utc, 4)
        assertThat(result.dates()).containsExactly("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30").inOrder()
        assertThat(result.map { it.index }).containsExactly(0, 1, 2, 3).inOrder()
    }

    @Test
    fun monthly_31st_skipMonth_skippedMonthsDoNotConsumeCount() {
        val s = schedule(
            "2026-01-31",
            rule = RecurrenceRule(Frequency.MONTHLY, monthlyDayPolicy = MonthlyDayPolicy.SKIP_MONTH, end = RecurrenceEnd.AfterCount(3)),
        )
        val result = engine.occurrencesAfter(s, farPast, utc, 10)
        assertThat(result.dates()).containsExactly("2026-01-31", "2026-03-31", "2026-05-31").inOrder()
        assertThat(result.map { it.index }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun monthly_intervalTwo() {
        val s = schedule("2026-01-15", rule = rule(Frequency.MONTHLY, interval = 2))
        assertThat(engine.occurrencesAfter(s, farPast, utc, 3).dates())
            .containsExactly("2026-01-15", "2026-03-15", "2026-05-15").inOrder()
    }

    @Test
    fun yearly_feb29_skipMonth_onlyLeapYears() {
        val s = schedule("2024-02-29", rule = RecurrenceRule(Frequency.YEARLY, monthlyDayPolicy = MonthlyDayPolicy.SKIP_MONTH))
        val result = engine.occurrencesAfter(s, farPast, utc, 3)
        assertThat(result.dates()).containsExactly("2024-02-29", "2028-02-29", "2032-02-29").inOrder()
        assertThat(result.map { it.index }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun yearly_feb29_lastValidDay_usesFeb28() {
        val s = schedule("2024-02-29", rule = RecurrenceRule(Frequency.YEARLY, monthlyDayPolicy = MonthlyDayPolicy.LAST_VALID_DAY))
        assertThat(engine.occurrencesAfter(s, farPast, utc, 3).dates())
            .containsExactly("2024-02-29", "2025-02-28", "2026-02-28").inOrder()
    }

    @Test
    fun yearly_intervalTwo() {
        val s = schedule("2026-06-15", rule = rule(Frequency.YEARLY, interval = 2))
        assertThat(engine.occurrencesAfter(s, farPast, utc, 3).dates())
            .containsExactly("2026-06-15", "2028-06-15", "2030-06-15").inOrder()
    }

    // ---- ends -----------------------------------------------------------------------------

    @Test
    fun untilDate_isInclusiveByNominalLocalDate() {
        val s = schedule("2026-01-01", rule = rule(Frequency.DAILY, end = RecurrenceEnd.UntilDate("2026-01-03")))
        assertThat(engine.occurrencesAfter(s, farPast, utc, 10).dates())
            .containsExactly("2026-01-01", "2026-01-02", "2026-01-03").inOrder()
        assertThat(engine.isExhausted(s, Instant.parse("2026-01-03T09:00:00Z"), utc)).isTrue()
        assertThat(engine.isExhausted(s, Instant.parse("2026-01-03T08:59:59Z"), utc)).isFalse()
    }

    @Test
    fun afterCount_skippedKeysConsumeCount() {
        val s = schedule("2026-01-01", rule = rule(Frequency.DAILY, end = RecurrenceEnd.AfterCount(3)))
            .copy(skippedKeys = setOf(key("2026-01-02T09:00")))
        val result = engine.occurrencesAfter(s, farPast, utc, 10)
        assertThat(result.dates()).containsExactly("2026-01-01", "2026-01-03").inOrder()
        assertThat(result.map { it.index }).containsExactly(0, 2).inOrder()
    }

    @Test
    fun afterCount_zero_producesNothing() {
        val s = schedule("2026-01-01", rule = rule(Frequency.DAILY, end = RecurrenceEnd.AfterCount(0)))
        assertThat(engine.occurrencesAfter(s, farPast, utc, 10)).isEmpty()
    }

    // ---- skips and overrides ---------------------------------------------------------------

    @Test
    fun overrides_moveOccurrenceAndResort_keepingOriginalKey() {
        val s = schedule("2026-01-01", rule = rule(Frequency.DAILY, end = RecurrenceEnd.AfterCount(3)))
            .copy(overrides = mapOf(key("2026-01-01T09:00") to "2026-01-02T20:00"))
        val result = engine.occurrencesAfter(s, farPast, utc, 10)
        assertThat(result.locals()).containsExactly("2026-01-02T09:00", "2026-01-02T20:00", "2026-01-03T09:00").inOrder()
        assertThat(result[1].key).isEqualTo(key("2026-01-01T09:00"))
        assertThat(result[1].index).isEqualTo(0)
        assertThat(result[1].instant).isEqualTo(Instant.parse("2026-01-02T20:00:00Z"))
    }

    @Test
    fun overrides_movedBeforeQueryWindow_areExcluded() {
        val s = schedule("2026-01-01", rule = rule(Frequency.DAILY, end = RecurrenceEnd.AfterCount(3)))
            .copy(overrides = mapOf(key("2026-01-03T09:00") to "2025-12-25T09:00"))
        val result = engine.occurrencesAfter(s, Instant.parse("2025-12-31T00:00:00Z"), utc, 10)
        assertThat(result.locals()).containsExactly("2026-01-01T09:00", "2026-01-02T09:00").inOrder()
        val all = engine.occurrencesAfter(s, farPast, utc, 10)
        assertThat(all.locals()).containsExactly("2025-12-25T09:00", "2026-01-01T09:00", "2026-01-02T09:00").inOrder()
    }

    @Test
    fun overrides_cannotResurrectSkippedKey() {
        val s = schedule("2026-01-01", rule = rule(Frequency.DAILY, end = RecurrenceEnd.AfterCount(3)))
            .copy(
                skippedKeys = setOf(key("2026-01-02T09:00")),
                overrides = mapOf(key("2026-01-02T09:00") to "2026-01-05T09:00"),
            )
        assertThat(engine.occurrencesAfter(s, farPast, utc, 10).dates()).containsExactly("2026-01-01", "2026-01-03").inOrder()
    }

    @Test
    fun overrides_forKeysOutsideSeries_areIgnored() {
        val s = schedule("2026-01-01", rule = rule(Frequency.DAILY, interval = 2, end = RecurrenceEnd.AfterCount(2)))
            .copy(
                overrides = mapOf(
                    key("2026-01-02T09:00") to "2026-01-10T09:00", // not on the every-2-days grid
                    key("2026-01-01T10:00") to "2026-01-11T09:00", // wrong time
                    key("2026-01-05T09:00") to "2026-01-12T09:00", // beyond count
                ),
            )
        assertThat(engine.occurrencesAfter(s, farPast, utc, 10).dates()).containsExactly("2026-01-01", "2026-01-03").inOrder()
    }

    @Test
    fun occurrencesBetween_isHalfOpen_andIncludesMovedOverrides() {
        val s = schedule("2026-01-01", rule = rule(Frequency.DAILY))
            .copy(overrides = mapOf(key("2026-01-20T09:00") to "2026-01-03T12:00"))
        val from = Instant.parse("2026-01-02T09:00:00Z")
        val to = Instant.parse("2026-01-04T09:00:00Z")
        val result = engine.occurrencesBetween(s, from, to, utc)
        assertThat(result.locals()).containsExactly("2026-01-02T09:00", "2026-01-03T09:00", "2026-01-03T12:00").inOrder()
        assertThat(engine.occurrencesBetween(s, from, to, utc, limit = 2).locals())
            .containsExactly("2026-01-02T09:00", "2026-01-03T09:00").inOrder()
        assertThat(engine.occurrencesBetween(s, to, from, utc)).isEmpty()
    }

    // ---- DST ------------------------------------------------------------------------------

    @Test
    fun resolve_springForwardGap_berlin() {
        val local = LocalDateTime.of(2026, 3, 29, 2, 30)
        val shifted = engine.resolve(local, berlin, DstGapPolicy.SHIFT_FORWARD, DstOverlapPolicy.EARLIER_OFFSET)
        assertThat(shifted).isEqualTo(Instant.parse("2026-03-29T01:30:00Z")) // 03:30+02:00
        assertThat(engine.resolve(local, berlin, DstGapPolicy.SKIP_OCCURRENCE, DstOverlapPolicy.EARLIER_OFFSET)).isNull()
    }

    @Test
    fun dailySeries_springForwardGap_shiftForward_vs_skip() {
        val base = schedule("2026-03-28", time = "02:30", rule = rule(Frequency.DAILY)).copy(zonePolicy = ZonePolicy.Fixed("Europe/Berlin"))
        val shifted = engine.occurrencesAfter(base.copy(dstGapPolicy = DstGapPolicy.SHIFT_FORWARD), farPast, utc, 3)
        assertThat(shifted.dates()).containsExactly("2026-03-28", "2026-03-29", "2026-03-30").inOrder()
        assertThat(shifted[1].instant).isEqualTo(Instant.parse("2026-03-29T01:30:00Z"))
        assertThat(shifted[1].nominalLocal).isEqualTo(LocalDateTime.of(2026, 3, 29, 2, 30))
        assertThat(shifted[1].key).isEqualTo(key("2026-03-29T02:30"))

        val skipped = engine.occurrencesAfter(base.copy(dstGapPolicy = DstGapPolicy.SKIP_OCCURRENCE), farPast, utc, 3)
        assertThat(skipped.dates()).containsExactly("2026-03-28", "2026-03-30", "2026-03-31").inOrder()
        assertThat(skipped.map { it.index }).containsExactly(0, 2, 3).inOrder()
    }

    @Test
    fun resolve_autumnOverlap_berlin() {
        val local = LocalDateTime.of(2026, 10, 25, 2, 30)
        assertThat(engine.resolve(local, berlin, DstGapPolicy.SHIFT_FORWARD, DstOverlapPolicy.EARLIER_OFFSET))
            .isEqualTo(Instant.parse("2026-10-25T00:30:00Z")) // 02:30+02:00
        assertThat(engine.resolve(local, berlin, DstGapPolicy.SHIFT_FORWARD, DstOverlapPolicy.LATER_OFFSET))
            .isEqualTo(Instant.parse("2026-10-25T01:30:00Z")) // 02:30+01:00
    }

    @Test
    fun dailySeries_autumnOverlap_bothPolicies() {
        val base = schedule("2026-10-24", time = "02:30", rule = rule(Frequency.DAILY)).copy(zonePolicy = ZonePolicy.Fixed("Europe/Berlin"))
        val earlier = engine.occurrencesAfter(base.copy(dstOverlapPolicy = DstOverlapPolicy.EARLIER_OFFSET), farPast, utc, 2)
        assertThat(earlier[1].instant).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"))
        val later = engine.occurrencesAfter(base.copy(dstOverlapPolicy = DstOverlapPolicy.LATER_OFFSET), farPast, utc, 2)
        assertThat(later[1].instant).isEqualTo(Instant.parse("2026-10-25T01:30:00Z"))
    }

    @Test
    fun resolve_normalTime_isUnaffectedByPolicies() {
        val local = LocalDateTime.of(2026, 6, 1, 9, 0)
        assertThat(engine.resolve(local, berlin, DstGapPolicy.SKIP_OCCURRENCE, DstOverlapPolicy.LATER_OFFSET))
            .isEqualTo(Instant.parse("2026-06-01T07:00:00Z"))
    }

    // ---- zones -----------------------------------------------------------------------------

    @Test
    fun zonePolicy_followDevice_vs_fixed() {
        val s = schedule("2026-06-01")
        val device = engine.nextOccurrence(s, farPast, newYork)!!
        assertThat(device.zone).isEqualTo(newYork)
        assertThat(device.instant).isEqualTo(Instant.parse("2026-06-01T13:00:00Z"))

        val fixed = engine.nextOccurrence(s.copy(zonePolicy = ZonePolicy.Fixed("Europe/Berlin")), farPast, newYork)!!
        assertThat(fixed.zone).isEqualTo(berlin)
        assertThat(fixed.instant).isEqualTo(Instant.parse("2026-06-01T07:00:00Z"))
        assertThat(fixed.key).isEqualTo(device.key)
    }

    @Test
    fun invalidFixedZone_throwsFromEngine() {
        val s = schedule("2026-06-01").copy(zonePolicy = ZonePolicy.Fixed("Mars/Olympus_Mons"))
        assertThrows(IllegalArgumentException::class.java) { engine.occurrencesAfter(s, farPast, utc, 1) }
    }

    @Test
    fun invalidInputs_throwIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) {
            engine.occurrencesAfter(schedule("2026-01-01", rule = rule(Frequency.DAILY, interval = 0)), farPast, utc, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.occurrencesAfter(schedule("2026-13-01"), farPast, utc, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.occurrencesAfter(schedule("2026-01-01", time = "25:00"), farPast, utc, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.occurrencesAfter(schedule("2026-01-01").copy(skippedKeys = setOf(key("nope"))), farPast, utc, 1)
        }
    }

    // ---- split -----------------------------------------------------------------------------

    @Test
    fun splitSeries_afterCount_distributesCountSkipsAndOverrides() {
        val s = schedule("2026-01-01", rule = rule(Frequency.DAILY, end = RecurrenceEnd.AfterCount(10))).copy(
            skippedKeys = setOf(key("2026-01-03T09:00"), key("2026-01-06T09:00")),
            overrides = mapOf(key("2026-01-08T09:00") to "2026-01-08T18:00"),
        )
        val (head, tail) = engine.splitSeries(s, key("2026-01-05T09:00"))

        assertThat(head.startDate).isEqualTo("2026-01-01")
        assertThat(head.rule.end).isEqualTo(RecurrenceEnd.AfterCount(4))
        assertThat(head.skippedKeys).containsExactly(key("2026-01-03T09:00"))
        assertThat(head.overrides).isEmpty()
        assertThat(engine.occurrencesAfter(head, farPast, utc, 100).dates())
            .containsExactly("2026-01-01", "2026-01-02", "2026-01-04").inOrder()

        assertThat(tail.startDate).isEqualTo("2026-01-05")
        assertThat(tail.rule).isEqualTo(s.rule.copy(end = RecurrenceEnd.AfterCount(6)))
        assertThat(tail.skippedKeys).containsExactly(key("2026-01-06T09:00"))
        assertThat(tail.overrides).containsExactly(key("2026-01-08T09:00"), "2026-01-08T18:00")
        assertThat(engine.occurrencesAfter(tail, farPast, utc, 100).locals())
            .containsExactly("2026-01-05T09:00", "2026-01-07T09:00", "2026-01-08T18:00", "2026-01-09T09:00", "2026-01-10T09:00")
            .inOrder()
    }

    @Test
    fun splitSeries_never_headEndsDayBefore() {
        val s = schedule("2026-01-01", rule = rule(Frequency.DAILY))
        val (head, tail) = engine.splitSeries(s, key("2026-01-05T09:00"))
        assertThat(head.rule.end).isEqualTo(RecurrenceEnd.UntilDate("2026-01-04"))
        assertThat(tail.rule.end).isEqualTo(RecurrenceEnd.Never)
        assertThat(tail.startDate).isEqualTo("2026-01-05")
        assertThat(engine.nextOccurrence(head, Instant.parse("2026-01-04T09:00:00Z"), utc)).isNull()
        assertThat(engine.nextOccurrence(tail, farPast, utc)!!.nominalLocal.toString()).isEqualTo("2026-01-05T09:00")
    }

    @Test
    fun splitSeries_untilDate_keepsEarlierUntil_andWeeklyPhase() {
        val s = schedule(
            "2026-01-07",
            rule = RecurrenceRule(
                Frequency.WEEKLY,
                interval = 2,
                weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                end = RecurrenceEnd.UntilDate("2026-01-02"),
            ),
        )
        val split = engine.splitSeries(s.copy(rule = s.rule.copy(end = RecurrenceEnd.UntilDate("2026-03-31"))), key("2026-01-23T09:00"))
        assertThat(split.head.rule.end).isEqualTo(RecurrenceEnd.UntilDate("2026-01-22"))
        assertThat(split.tail.rule.end).isEqualTo(RecurrenceEnd.UntilDate("2026-03-31"))
        assertThat(engine.occurrencesAfter(split.tail, farPast, utc, 3).dates())
            .containsExactly("2026-01-23", "2026-02-02", "2026-02-06").inOrder()
        assertThat(engine.splitSeries(s, key("2026-01-23T09:00")).head.rule.end).isEqualTo(RecurrenceEnd.UntilDate("2026-01-02"))
    }

    @Test
    fun splitSeries_once_distributesExtraDates() {
        val s = schedule("2026-01-01").copy(extraDates = listOf("2026-01-10", "2026-01-20"))
        val (head, tail) = engine.splitSeries(s, key("2026-01-10T09:00"))
        assertThat(engine.occurrencesAfter(head, farPast, utc, 10).dates()).containsExactly("2026-01-01")
        assertThat(engine.occurrencesAfter(tail, farPast, utc, 10).dates()).containsExactly("2026-01-10", "2026-01-20").inOrder()
    }

    // ---- exhaustion and bounds -------------------------------------------------------------

    @Test
    fun isExhausted() {
        val once = schedule("2026-01-01")
        assertThat(engine.isExhausted(once, Instant.parse("2026-01-01T08:59:59Z"), utc)).isFalse()
        assertThat(engine.isExhausted(once, Instant.parse("2026-01-01T09:00:00Z"), utc)).isTrue()
        assertThat(engine.isExhausted(schedule("2026-01-01", rule = rule(Frequency.DAILY)), Instant.parse("2999-01-01T00:00:00Z"), utc)).isFalse()
        val counted = schedule("2026-01-01", rule = rule(Frequency.DAILY, end = RecurrenceEnd.AfterCount(2)))
        assertThat(engine.isExhausted(counted, Instant.parse("2026-01-02T09:00:00Z"), utc)).isTrue()
    }

    @Test(timeout = 5_000)
    fun loopBound_ruleThatProducesNothingForLong_terminatesQuickly() {
        val feb29 = schedule(
            "2024-02-29",
            rule = RecurrenceRule(Frequency.YEARLY, monthlyDayPolicy = MonthlyDayPolicy.SKIP_MONTH, end = RecurrenceEnd.UntilDate("2027-12-31")),
        )
        assertThat(engine.occurrencesAfter(feb29, Instant.parse("2024-03-01T00:00:00Z"), utc, 5)).isEmpty()
        assertThat(engine.isExhausted(feb29, Instant.parse("2024-03-01T00:00:00Z"), utc)).isTrue()

        // Every 100 years from a leap day: 2100, 2200, 2300 are skipped, 2400 exists.
        val centuries = schedule("2000-02-29", rule = RecurrenceRule(Frequency.YEARLY, interval = 100, monthlyDayPolicy = MonthlyDayPolicy.SKIP_MONTH))
        val next = engine.nextOccurrence(centuries, Instant.parse("2000-03-01T00:00:00Z"), utc)!!
        assertThat(next.nominalLocal.toLocalDate().toString()).isEqualTo("2400-02-29")
        assertThat(next.index).isEqualTo(1)

        // Never-ending rule queried absurdly far ahead still returns within the iteration cap.
        val daily = schedule("2026-01-01", rule = rule(Frequency.DAILY))
        assertThat(engine.occurrencesAfter(daily, Instant.parse("+900000000-01-01T00:00:00Z"), utc, 1)).isNotNull()
    }

    @Test(timeout = 5_000)
    fun longRunningSeries_jumpsToQueryInstant_withCorrectIndex() {
        val s = schedule("2000-01-01", rule = rule(Frequency.DAILY, interval = 3))
        val after = Instant.parse("2026-01-01T00:00:00Z")
        val next = engine.nextOccurrence(s, after, utc)!!
        val days = ChronoUnit.DAYS.between(LocalDate.parse("2000-01-01"), next.nominalLocal.toLocalDate())
        assertThat(days % 3).isEqualTo(0)
        assertThat(next.nominalLocal.toLocalDate().toString()).isEqualTo("2026-01-02")
        assertThat(next.index).isEqualTo((days / 3).toInt())
    }

    @Test(timeout = 20_000)
    fun arithmeticIndex_matchesSequentialEnumeration() {
        val start = Instant.parse("1896-01-01T00:00:00Z")
        val cut = Instant.parse("2026-01-01T00:00:00Z")
        val schedules = listOf(
            schedule("1896-01-01", rule = RecurrenceRule(Frequency.WEEKLY, interval = 3, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))),
            schedule("1896-01-31", rule = RecurrenceRule(Frequency.MONTHLY, interval = 1, monthlyDayPolicy = MonthlyDayPolicy.SKIP_MONTH)),
            schedule("1896-03-30", rule = RecurrenceRule(Frequency.MONTHLY, interval = 5, monthlyDayPolicy = MonthlyDayPolicy.SKIP_MONTH)),
            schedule("1896-01-29", rule = RecurrenceRule(Frequency.MONTHLY, interval = 7, monthlyDayPolicy = MonthlyDayPolicy.SKIP_MONTH)),
            schedule("1896-02-29", rule = RecurrenceRule(Frequency.YEARLY, interval = 1, monthlyDayPolicy = MonthlyDayPolicy.SKIP_MONTH)),
            schedule("1896-02-29", rule = RecurrenceRule(Frequency.YEARLY, interval = 3, monthlyDayPolicy = MonthlyDayPolicy.SKIP_MONTH)),
        )
        for (s in schedules) {
            val history = engine.occurrencesBetween(s, start, cut, utc, limit = 100_000)
            assertThat(history).isNotEmpty()
            assertThat(history.map { it.index }).isEqualTo(history.indices.toList())
            val next = engine.nextOccurrence(s, cut.minusSeconds(1), utc)!!
            assertThat(next.index).isEqualTo(history.size)
            assertThat(next.instant).isGreaterThan(history.last().instant)
        }
    }

    @Test
    fun occurrenceKeys_roundTrip_minuteResolution() {
        val local = LocalDateTime.of(2026, 1, 5, 9, 7, 33)
        val key = OccurrenceKeys.of(local)
        assertThat(key.value).isEqualTo("2026-01-05T09:07")
        assertThat(OccurrenceKeys.toLocal(key)).isEqualTo(LocalDateTime.of(2026, 1, 5, 9, 7))
        assertThrows(IllegalArgumentException::class.java) { OccurrenceKeys.toLocal(OccurrenceKey("garbage")) }
    }

    @Test
    fun limitZero_returnsEmpty() {
        assertThat(engine.occurrencesAfter(schedule("2026-01-01", rule = rule(Frequency.DAILY)), farPast, utc, 0)).isEmpty()
    }
}
