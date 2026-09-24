@file:UseSerializers(DayOfWeekSerializer::class)

package com.norypt.haven.recurrence

import java.time.DayOfWeek
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** How often a [Schedule] repeats. */
@Serializable
public enum class Frequency { ONCE, DAILY, WEEKLY, MONTHLY, YEARLY }

/** What to do when a monthly/yearly rule lands on a day the month lacks (e.g. the 31st, or Feb 29). */
@Serializable
public enum class MonthlyDayPolicy { SKIP_MONTH, LAST_VALID_DAY }

/** Spring-forward gap: the nominal local time does not exist in the zone that day. */
@Serializable
public enum class DstGapPolicy { SHIFT_FORWARD, SKIP_OCCURRENCE }

/** Autumn overlap: the nominal local time exists twice in the zone that day. */
@Serializable
public enum class DstOverlapPolicy { EARLIER_OFFSET, LATER_OFFSET }

/** Which time zone the nominal local times of a [Schedule] are interpreted in. */
@Serializable
public sealed interface ZonePolicy {
    /** Interpret local times in whatever zone the device currently reports. */
    @Serializable
    @SerialName("device")
    public data object FollowDevice : ZonePolicy

    /** Interpret local times in a fixed IANA zone, regardless of the device zone. */
    @Serializable
    @SerialName("fixed")
    public data class Fixed(val zoneId: String) : ZonePolicy
}

/** When a series stops producing occurrences. */
@Serializable
public sealed interface RecurrenceEnd {
    @Serializable
    @SerialName("never")
    public data object Never : RecurrenceEnd

    /** Last nominal local date that may still produce an occurrence (ISO `yyyy-MM-dd`, inclusive). */
    @Serializable
    @SerialName("until")
    public data class UntilDate(val date: String) : RecurrenceEnd

    /**
     * Stop after [count] nominal occurrences. Nominal occurrences that the user deleted individually
     * ([Schedule.skippedKeys]) still consume a count, in the spirit of RFC 5545 EXDATE.
     */
    @Serializable
    @SerialName("count")
    public data class AfterCount(val count: Int) : RecurrenceEnd
}

/**
 * The repetition rule of a [Schedule]. Inspired by RFC 5545 RRULE, with explicit Haven policies;
 * this is not a strict RFC 5545 implementation.
 */
@Serializable
public data class RecurrenceRule(
    val frequency: Frequency,
    /** Every N days/weeks/months/years. Must be >= 1. */
    val interval: Int = 1,
    /** WEEKLY only. Empty means "the weekday of the start date". */
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthlyDayPolicy: MonthlyDayPolicy = MonthlyDayPolicy.LAST_VALID_DAY,
    val end: RecurrenceEnd = RecurrenceEnd.Never,
)

/**
 * Identity of one nominal occurrence: the ISO local date-time `yyyy-MM-ddTHH:mm` of its nominal
 * local time. It is stable across zone changes and across overrides (an override keeps the
 * original key). Build and parse keys with [OccurrenceKeys].
 */
@Serializable(with = OccurrenceKeySerializer::class)
@JvmInline
public value class OccurrenceKey(public val value: String)

/**
 * Timing-only description of a reminder series. It deliberately carries no title or other
 * content so it can live in the Direct-Boot schedule store as well as inside the encrypted vault.
 *
 * All strings are ISO-8601 local values: [startDate] and [extraDates] are `yyyy-MM-dd`, [time]
 * is `HH:mm`, override targets are `yyyy-MM-ddTHH:mm`.
 */
@Serializable
public data class Schedule(
    /** Local date of the first occurrence. */
    val startDate: String,
    /** Local wall-clock time of every occurrence, `HH:mm`. */
    val time: String,
    /** Additional independently chosen one-time dates; only used with [Frequency.ONCE]. */
    val extraDates: List<String> = emptyList(),
    val rule: RecurrenceRule = RecurrenceRule(Frequency.ONCE),
    val zonePolicy: ZonePolicy = ZonePolicy.FollowDevice,
    val dstGapPolicy: DstGapPolicy = DstGapPolicy.SHIFT_FORWARD,
    val dstOverlapPolicy: DstOverlapPolicy = DstOverlapPolicy.EARLIER_OFFSET,
    /** Occurrences the user deleted individually. They still consume an [RecurrenceEnd.AfterCount] slot. */
    val skippedKeys: Set<OccurrenceKey> = emptySet(),
    /** Occurrences the user moved: original key -> new nominal local date-time `yyyy-MM-ddTHH:mm`. */
    val overrides: Map<OccurrenceKey, String> = emptyMap(),
    /**
     * Early reminders: minutes before each occurrence at which an additional "coming up" alert
     * fires (e.g. 10, 60, 1440). Timing information only; expanded by the alarm runtime.
     */
    val earlyOffsetsMinutes: List<Int> = emptyList(),
)

/** Result of [RecurrenceEngine.splitSeries]. */
public data class SeriesSplit(val head: Schedule, val tail: Schedule)

internal object DayOfWeekSerializer : KSerializer<DayOfWeek> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.DayOfWeek", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: DayOfWeek) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): DayOfWeek = DayOfWeek.valueOf(decoder.decodeString())
}

internal object OccurrenceKeySerializer : KSerializer<OccurrenceKey> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.norypt.haven.recurrence.OccurrenceKey", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OccurrenceKey) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): OccurrenceKey = OccurrenceKey(decoder.decodeString())
}
