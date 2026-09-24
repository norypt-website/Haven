# recurrence-engine

Pure Kotlin/JVM module (`java.time` only, no Android) that expands a stored `Schedule` into
concrete alarm `Occurrence`s. The same engine serves the encrypted vault and the Direct-Boot
schedule store, so a `Schedule` holds timing information only: no title, no notes.

The rules below are **Haven policy**, inspired by RFC 5545 (RRULE / EXDATE) but deliberately
not a strict RFC 5545 implementation. Where the RFC is silent or ambiguous, Haven picks one
behaviour and tests it.

## Model

| Field | Meaning |
| --- | --- |
| `startDate`, `time` | ISO local date and `HH:mm` wall-clock time of the first occurrence. |
| `extraDates` | Extra one-time dates; used by `ONCE` only. Deduplicated and sorted with `startDate`. |
| `rule` | `frequency`, `interval` (>= 1), `weekdays` (WEEKLY), `monthlyDayPolicy`, `end`. |
| `zonePolicy` | `FollowDevice` (device zone passed to every query) or `Fixed(zoneId)`. |
| `dstGapPolicy` / `dstOverlapPolicy` | What to do when the local time does not exist / exists twice. |
| `skippedKeys` | Occurrences the user deleted individually. |
| `overrides` | Occurrences the user moved: original key -> new local date-time. |

An `OccurrenceKey` is the nominal local date-time `yyyy-MM-ddTHH:mm`. It never changes, even
when the occurrence is moved or the zone changes, so it is safe to persist alongside the
schedule as the identity of one alarm.

## Expansion rules

- **ONCE**: `startDate` plus every `extraDate`, all at `time`.
- **DAILY**: every `interval` days from `startDate`.
- **WEEKLY**: weeks are Monday-based and counted from the week of `startDate`; every `interval`-th
  week emits the selected `weekdays` (or the weekday of `startDate` if the set is empty). Nothing
  before `startDate` is ever emitted.
- **MONTHLY**: every `interval` months on `startDate.dayOfMonth`. When a month lacks that day,
  `SKIP_MONTH` produces nothing that month and `LAST_VALID_DAY` uses the last day of the month.
- **YEARLY**: every `interval` years on `startDate`'s month and day; Feb 29 follows the same
  policy (`SKIP_MONTH` = leap years only, `LAST_VALID_DAY` = Feb 28 otherwise).
- **End**: `Never`; `UntilDate` is inclusive by nominal local date; `AfterCount` counts nominal
  occurrences. A skipped occurrence still consumes a count (like an RFC 5545 EXDATE), a month
  skipped by `SKIP_MONTH` does not.
- **Index**: each occurrence carries its 0-based position in the nominal series; skipped
  occurrences keep their position.

## Zones and DST

- `FollowDevice` interprets local times in the zone passed to the query; `Fixed` always uses the
  stored IANA zone. An unknown zone id is an `IllegalArgumentException` (at decode and in the engine).
- Spring-forward gap (e.g. Europe/Berlin 2026-03-29 02:30): `SHIFT_FORWARD` moves the alarm
  forward by the length of the gap (02:30 becomes 03:30), `SKIP_OCCURRENCE` omits it.
- Autumn overlap (e.g. Europe/Berlin 2026-10-25 02:30): `EARLIER_OFFSET` fires at the first
  02:30 (still summer time), `LATER_OFFSET` at the second.

## Skips, overrides, splits

- A skipped key is never returned, and an override cannot bring it back.
- An override moves the occurrence's nominal local date-time; the key stays the original one and
  the moved occurrence is re-sorted among the others by its new instant. Overrides whose key is not
  part of the series are ignored.
- `splitSeries(schedule, fromKey)` implements "edit this and future": the head ends the day before
  `fromKey` (or with the count of occurrences before it), the tail starts on `fromKey`'s date with
  the same rule and the remaining count; skips and overrides go to the side they belong to.

## Determinism and bounds

Queries are pure functions of (schedule, instants, device zone). The first candidate period near
the query instant is computed arithmetically, so long-running series are not walked from the
start, and every scan stops after 100 000 periods; a schedule that yields nothing within that
window is reported as exhausted.

`ScheduleJson` writes `{"v":1, ...}`, rejects other versions with `IllegalArgumentException`
and ignores unknown keys so newer builds can add fields without breaking older readers.
