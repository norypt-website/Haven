package com.norypt.haven.recurrence

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One concrete alarm produced by expanding a [Schedule]. */
public data class Occurrence(
    /** Nominal identity, unchanged by overrides. */
    val key: OccurrenceKey,
    /** Local date-time after applying an override, before any DST adjustment. */
    val nominalLocal: LocalDateTime,
    /** Zone the local time was resolved in. */
    val zone: ZoneId,
    /** Resolved trigger instant. */
    val instant: Instant,
    /** 0-based position in the nominal series; skipped occurrences still occupy a position. */
    val index: Int,
)

/** Builds and parses [OccurrenceKey]s. Keys have minute resolution. */
public object OccurrenceKeys {
    public fun of(local: LocalDateTime): OccurrenceKey {
        val truncated = local.truncatedTo(ChronoUnit.MINUTES)
        val time = String.format(Locale.ROOT, "%02d:%02d", truncated.hour, truncated.minute)
        return OccurrenceKey("${truncated.toLocalDate()}T$time")
    }

    /** @throws IllegalArgumentException if the key is not a valid ISO local date-time. */
    public fun toLocal(key: OccurrenceKey): LocalDateTime = try {
        LocalDateTime.parse(key.value)
    } catch (e: DateTimeException) {
        throw IllegalArgumentException("Invalid occurrence key '${key.value}'", e)
    }
}
