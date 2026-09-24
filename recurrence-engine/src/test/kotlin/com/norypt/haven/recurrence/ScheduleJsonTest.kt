package com.norypt.haven.recurrence

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import org.junit.Assert.assertThrows
import org.junit.Test

class ScheduleJsonTest {

    private val full = Schedule(
        startDate = "2026-01-07",
        time = "07:45",
        extraDates = listOf("2026-02-01"),
        rule = RecurrenceRule(
            frequency = Frequency.WEEKLY,
            interval = 2,
            weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            monthlyDayPolicy = MonthlyDayPolicy.SKIP_MONTH,
            end = RecurrenceEnd.AfterCount(12),
        ),
        zonePolicy = ZonePolicy.Fixed("Europe/Berlin"),
        dstGapPolicy = DstGapPolicy.SKIP_OCCURRENCE,
        dstOverlapPolicy = DstOverlapPolicy.LATER_OFFSET,
        skippedKeys = setOf(OccurrenceKey("2026-01-09T07:45")),
        overrides = mapOf(OccurrenceKey("2026-01-19T07:45") to "2026-01-20T08:00"),
    )

    @Test
    fun roundTrip_preservesEverything() {
        val json = ScheduleJson.encode(full)
        assertThat(json).startsWith("{\"v\":1,")
        assertThat(ScheduleJson.decode(json)).isEqualTo(full)
    }

    @Test
    fun roundTrip_defaultsAndOtherEnds() {
        val minimal = Schedule(startDate = "2026-01-01", time = "09:00")
        assertThat(ScheduleJson.decode(ScheduleJson.encode(minimal))).isEqualTo(minimal)
        val until = minimal.copy(rule = RecurrenceRule(Frequency.MONTHLY, end = RecurrenceEnd.UntilDate("2026-12-31")))
        assertThat(ScheduleJson.decode(ScheduleJson.encode(until))).isEqualTo(until)
    }

    @Test
    fun encodedForm_containsNoContentFields_onlyTiming() {
        val json = ScheduleJson.encode(full)
        assertThat(json).contains("\"zonePolicy\":{\"type\":\"fixed\",\"zoneId\":\"Europe/Berlin\"}")
        assertThat(json).contains("\"end\":{\"type\":\"count\",\"count\":12}")
        assertThat(json).contains("\"weekdays\":[\"MONDAY\",\"FRIDAY\"]")
        assertThat(json).contains("\"overrides\":{\"2026-01-19T07:45\":\"2026-01-20T08:00\"}")
    }

    @Test
    fun unknownVersion_isRejected() {
        val json = ScheduleJson.encode(full).replaceFirst("{\"v\":1,", "{\"v\":2,")
        assertThrows(IllegalArgumentException::class.java) { ScheduleJson.decode(json) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleJson.decode("{\"startDate\":\"2026-01-01\",\"time\":\"09:00\"}") }
    }

    @Test
    fun unknownKeys_areIgnored() {
        val json = "{\"v\":1,\"startDate\":\"2026-01-01\",\"time\":\"09:00\",\"futureField\":{\"x\":1}," +
            "\"rule\":{\"frequency\":\"DAILY\",\"newRuleField\":true}}"
        val decoded = ScheduleJson.decode(json)
        assertThat(decoded.startDate).isEqualTo("2026-01-01")
        assertThat(decoded.rule.frequency).isEqualTo(Frequency.DAILY)
        assertThat(decoded.rule.interval).isEqualTo(1)
    }

    @Test
    fun malformedOrInvalidContent_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { ScheduleJson.decode("not json") }
        assertThrows(IllegalArgumentException::class.java) { ScheduleJson.decode("[1,2]") }
        assertThrows(IllegalArgumentException::class.java) {
            ScheduleJson.decode("{\"v\":1,\"startDate\":\"2026-01-01\",\"time\":\"09:00\",\"zonePolicy\":{\"type\":\"fixed\",\"zoneId\":\"Nowhere/Land\"}}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScheduleJson.decode("{\"v\":1,\"startDate\":\"2026-02-30\",\"time\":\"09:00\"}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScheduleJson.decode("{\"v\":1,\"startDate\":\"2026-01-01\",\"time\":\"09:00\",\"rule\":{\"frequency\":\"DAILY\",\"interval\":0}}")
        }
    }
}
