package com.norypt.haven.ui.screens.reminders

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import com.norypt.haven.ui.components.ChoicePills
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.norypt.haven.recurrence.DstGapPolicy
import com.norypt.haven.recurrence.DstOverlapPolicy
import com.norypt.haven.recurrence.Frequency
import com.norypt.haven.recurrence.MonthlyDayPolicy
import com.norypt.haven.recurrence.Occurrence
import com.norypt.haven.recurrence.RecurrenceEnd
import com.norypt.haven.recurrence.RecurrenceRule
import com.norypt.haven.recurrence.Schedule
import com.norypt.haven.recurrence.ZonePolicy
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

enum class EndMode { NEVER, UNTIL, COUNT }

/**
 * Mutable editor state for one [Schedule]. Lives in a ViewModel (plain Compose state, never
 * saved-state), so nothing here is written to any bundle.
 */
class ScheduleDraft(initial: Schedule? = null) {
    var startDate: LocalDate by mutableStateOf(LocalDate.now())
    /** Null = the user has not chosen a time; the configured default is shown explicitly and used on save. */
    var time: LocalTime? by mutableStateOf(null)
    var extraDates: List<LocalDate> by mutableStateOf(emptyList())
    var frequency: Frequency by mutableStateOf(Frequency.ONCE)
    var intervalText: String by mutableStateOf("1")
    var weekdays: Set<DayOfWeek> by mutableStateOf(emptySet())
    var monthlyDayPolicy: MonthlyDayPolicy by mutableStateOf(MonthlyDayPolicy.LAST_VALID_DAY)
    var endMode: EndMode by mutableStateOf(EndMode.NEVER)
    var untilDate: LocalDate by mutableStateOf(LocalDate.now().plusMonths(1))
    var countText: String by mutableStateOf("10")
    var fixedZone: Boolean by mutableStateOf(false)
    var zoneId: String by mutableStateOf(ZoneId.systemDefault().id)
    var dstGap: DstGapPolicy by mutableStateOf(DstGapPolicy.SHIFT_FORWARD)
    var dstOverlap: DstOverlapPolicy by mutableStateOf(DstOverlapPolicy.EARLIER_OFFSET)
    /** Early "coming up" alerts, minutes before each occurrence. */
    var earlyOffsets: Set<Int> by mutableStateOf(emptySet())
    private var skippedKeys = initial?.skippedKeys ?: emptySet()
    private var overrides = initial?.overrides ?: emptyMap()

    init { if (initial != null) load(initial) }

    fun load(s: Schedule) {
        startDate = runCatching { LocalDate.parse(s.startDate) }.getOrDefault(LocalDate.now())
        time = runCatching { LocalTime.parse(s.time) }.getOrNull()
        extraDates = s.extraDates.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        frequency = s.rule.frequency
        intervalText = s.rule.interval.toString()
        weekdays = s.rule.weekdays
        monthlyDayPolicy = s.rule.monthlyDayPolicy
        when (val end = s.rule.end) {
            RecurrenceEnd.Never -> endMode = EndMode.NEVER
            is RecurrenceEnd.UntilDate -> { endMode = EndMode.UNTIL; untilDate = runCatching { LocalDate.parse(end.date) }.getOrDefault(untilDate) }
            is RecurrenceEnd.AfterCount -> { endMode = EndMode.COUNT; countText = end.count.toString() }
        }
        when (val z = s.zonePolicy) {
            ZonePolicy.FollowDevice -> { fixedZone = false; zoneId = ZoneId.systemDefault().id }
            is ZonePolicy.Fixed -> { fixedZone = true; zoneId = z.zoneId }
        }
        dstGap = s.dstGapPolicy
        dstOverlap = s.dstOverlapPolicy
        earlyOffsets = s.earlyOffsetsMinutes.filter { it > 0 }.toSet()
        skippedKeys = s.skippedKeys
        overrides = s.overrides
    }

    val interval: Int? get() = intervalText.trim().toIntOrNull()?.takeIf { it >= 1 }
    val count: Int? get() = countText.trim().toIntOrNull()?.takeIf { it >= 1 }

    /** Whether the monthly/yearly missing-day policy matters for the chosen start date. */
    val showsMonthlyPolicy: Boolean
        get() = when (frequency) {
            Frequency.MONTHLY -> startDate.dayOfMonth >= 29
            Frequency.YEARLY -> startDate.monthValue == 2 && startDate.dayOfMonth == 29
            else -> false
        }

    /** First validation problem in plain words, or null when the draft is a valid schedule. */
    fun problem(): String? = when {
        interval == null -> "Repeat interval must be a whole number of 1 or more."
        endMode == EndMode.COUNT && count == null -> "Number of occurrences must be 1 or more."
        endMode == EndMode.UNTIL && untilDate.isBefore(startDate) -> "The end date is before the start date."
        fixedZone && runCatching { ZoneId.of(zoneId) }.isFailure -> "Choose a valid time zone."
        else -> null
    }

    /** Builds the schedule, using [defaultMinuteOfDay] when no time was chosen. Null if invalid. */
    fun toSchedule(defaultMinuteOfDay: Int): Schedule? {
        if (problem() != null) return null
        val t = time ?: LocalTime.of(defaultMinuteOfDay / 60, defaultMinuteOfDay % 60)
        val end: RecurrenceEnd = when (endMode) {
            EndMode.NEVER -> RecurrenceEnd.Never
            EndMode.UNTIL -> RecurrenceEnd.UntilDate(untilDate.toString())
            EndMode.COUNT -> RecurrenceEnd.AfterCount(count ?: 1)
        }
        val once = frequency == Frequency.ONCE
        return Schedule(
            startDate = startDate.toString(),
            time = String.format(java.util.Locale.ROOT, "%02d:%02d", t.hour, t.minute),
            extraDates = if (once) extraDates.filter { it != startDate }.distinct().sorted().map { it.toString() } else emptyList(),
            rule = RecurrenceRule(
                frequency = frequency,
                interval = if (once) 1 else (interval ?: 1),
                weekdays = if (frequency == Frequency.WEEKLY) weekdays else emptySet(),
                monthlyDayPolicy = monthlyDayPolicy,
                end = if (once) RecurrenceEnd.Never else end,
            ),
            zonePolicy = if (fixedZone) ZonePolicy.Fixed(zoneId) else ZonePolicy.FollowDevice,
            dstGapPolicy = dstGap,
            dstOverlapPolicy = dstOverlap,
            skippedKeys = skippedKeys,
            overrides = overrides,
            earlyOffsetsMinutes = earlyOffsets.filter { it > 0 }.distinct().sorted(),
        )
    }
}

/** Everything about *when*: date(s), time, repeat, end, zone, DST and the live preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditor(draft: ScheduleDraft, defaultMinuteOfDay: Int, preview: List<Occurrence>, previewSchedule: Schedule?) {
    var pickStart by remember { mutableStateOf(false) }
    var pickExtra by remember { mutableStateOf(false) }
    var pickUntil by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var pickZone by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }
    var customEarlyOpen by remember { mutableStateOf(false) }
    var customEarlyText by remember { mutableStateOf("") }

    // ---- Date ----
    SectionTitle("Date")
    OutlinedButton(onClick = { pickStart = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(if (draft.frequency == Frequency.ONCE) Fmt.date(draft.startDate) else "Starts ${Fmt.date(draft.startDate)}")
    }
    if (draft.frequency == Frequency.ONCE) {
        if (draft.extraDates.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                draft.extraDates.sorted().forEach { d ->
                    InputChip(
                        selected = false,
                        onClick = { draft.extraDates = draft.extraDates - d },
                        label = { Text(Fmt.date(d)) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove ${Fmt.date(d)}", Modifier.size(18.dp)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        TextButton(onClick = { pickExtra = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add another date") }
    }

    // ---- Time ----
    SectionTitle("Time")
    OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        val t = draft.time
        Text(if (t != null) "Alarm time: ${Fmt.time(t)}" else "Alarm time: ${Fmt.minuteOfDay(defaultMinuteOfDay)} (your default — change in Settings)")
    }
    if (draft.time == null) Help("No time chosen yet. Tap to pick one, or keep your default alarm time.")

    // ---- Repeat ----
    SectionTitle("Repeat")
    Spacer(Modifier.height(4.dp))
    ChoicePills(options = Frequency.entries, label = ::frequencyLabel, isSelected = { draft.frequency == it }, onSelect = { draft.frequency = it })
    if (draft.frequency != Frequency.ONCE) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft.intervalText,
            onValueChange = { draft.intervalText = it.filter(Char::isDigit).take(4) },
            label = { Text("Every N ${unitLabel(draft.frequency)}") },
            singleLine = true,
            isError = draft.interval == null,
            supportingText = if (draft.interval == null) { { Text("Enter 1 or more.") } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (draft.frequency == Frequency.WEEKLY) {
        Spacer(Modifier.height(8.dp))
        Text("On these days", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        ChoicePills(
            options = DayOfWeek.entries, label = { Fmt.weekday(it) }, isSelected = { it in draft.weekdays },
            onSelect = { d -> draft.weekdays = if (d in draft.weekdays) draft.weekdays - d else draft.weekdays + d }, multiSelect = true,
        )
        if (draft.weekdays.isEmpty()) Help("No day selected: the weekday of the start date (${Fmt.weekday(draft.startDate.dayOfWeek)}) is used.")
    }
    if (draft.showsMonthlyPolicy) {
        Spacer(Modifier.height(8.dp))
        val what = if (draft.frequency == Frequency.YEARLY) "Not every year has a 29 February." else "Not every month has a ${draft.startDate.dayOfMonth}."
        Text(what, style = MaterialTheme.typography.bodyMedium)
        RadioRow("Use the last valid day of the month", draft.monthlyDayPolicy == MonthlyDayPolicy.LAST_VALID_DAY) { draft.monthlyDayPolicy = MonthlyDayPolicy.LAST_VALID_DAY }
        RadioRow("Skip that month", draft.monthlyDayPolicy == MonthlyDayPolicy.SKIP_MONTH) { draft.monthlyDayPolicy = MonthlyDayPolicy.SKIP_MONTH }
    }

    // ---- End ----
    if (draft.frequency != Frequency.ONCE) {
        SectionTitle("End")
        RadioRow("Never", draft.endMode == EndMode.NEVER) { draft.endMode = EndMode.NEVER }
        RadioRow("On date", draft.endMode == EndMode.UNTIL) { draft.endMode = EndMode.UNTIL }
        if (draft.endMode == EndMode.UNTIL) {
            OutlinedButton(onClick = { pickUntil = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Last day: ${Fmt.date(draft.untilDate)}") }
            if (draft.untilDate.isBefore(draft.startDate)) Help("The end date is before the start date.")
        }
        RadioRow("After a number of occurrences", draft.endMode == EndMode.COUNT) { draft.endMode = EndMode.COUNT }
        if (draft.endMode == EndMode.COUNT) {
            OutlinedTextField(
                value = draft.countText,
                onValueChange = { draft.countText = it.filter(Char::isDigit).take(5) },
                label = { Text("Number of occurrences") },
                singleLine = true,
                isError = draft.count == null,
                supportingText = if (draft.count == null) { { Text("Enter 1 or more.") } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // ---- Time zone ----
    SectionTitle("Time zone")
    RadioRow("Follow the device time zone", !draft.fixedZone) { draft.fixedZone = false }
    RadioRow("Keep in a fixed time zone", draft.fixedZone) { draft.fixedZone = true }
    if (draft.fixedZone) {
        OutlinedButton(onClick = { pickZone = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(draft.zoneId) }
    }
    Help("Your device is currently in ${ZoneId.systemDefault().id}. A fixed zone keeps the alarm at the same wall-clock time there even when you travel.")

    // ---- Early reminders ----
    SectionTitle("Early reminders")
    Help("Extra \"coming up\" alerts before each occurrence. They ring briefly and never count as missed.")
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EARLY_PRESETS.forEach { m ->
            val on = m in draft.earlyOffsets
            FilterChip(
                selected = on,
                onClick = { draft.earlyOffsets = if (on) draft.earlyOffsets - m else draft.earlyOffsets + m },
                label = { Text(Fmt.minutesShort(m)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        draft.earlyOffsets.filter { it !in EARLY_PRESETS }.sorted().forEach { m ->
            InputChip(
                selected = true,
                onClick = { draft.earlyOffsets = draft.earlyOffsets - m },
                label = { Text("${Fmt.minutesShort(m)} before") },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove ${Fmt.minutesShort(m)} before", Modifier.size(18.dp)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        FilterChip(
            selected = customEarlyOpen,
            onClick = { customEarlyOpen = !customEarlyOpen },
            label = { Text("Custom…") },
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }
    if (customEarlyOpen) {
        val custom = customEarlyText.trim().toIntOrNull()?.takeIf { it in 1..(60 * 24 * 30) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = customEarlyText,
                onValueChange = { customEarlyText = it.filter(Char::isDigit).take(5) },
                label = { Text("Minutes before") },
                singleLine = true,
                isError = customEarlyText.isNotEmpty() && custom == null,
                supportingText = if (customEarlyText.isNotEmpty() && custom == null) { { Text("Enter 1 to 43200 minutes.") } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { custom?.let { draft.earlyOffsets = draft.earlyOffsets + it }; customEarlyText = ""; customEarlyOpen = false },
                enabled = custom != null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Add") }
        }
    }
    if (draft.earlyOffsets.isEmpty()) Help("None chosen: only the reminder itself rings.")

    // ---- Advanced ----
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { advancedOpen = !advancedOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(if (advancedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text(if (advancedOpen) "Hide advanced" else "Advanced: daylight saving")
    }
    if (advancedOpen) {
        Text("When clocks go forward and the time does not exist that day", style = MaterialTheme.typography.labelLarge)
        RadioRow("Shift forward by the missing hour", draft.dstGap == DstGapPolicy.SHIFT_FORWARD) { draft.dstGap = DstGapPolicy.SHIFT_FORWARD }
        RadioRow("Skip that occurrence", draft.dstGap == DstGapPolicy.SKIP_OCCURRENCE) { draft.dstGap = DstGapPolicy.SKIP_OCCURRENCE }
        Spacer(Modifier.height(8.dp))
        Text("When clocks go back and the time happens twice", style = MaterialTheme.typography.labelLarge)
        RadioRow("Ring the first time (earlier)", draft.dstOverlap == DstOverlapPolicy.EARLIER_OFFSET) { draft.dstOverlap = DstOverlapPolicy.EARLIER_OFFSET }
        RadioRow("Ring the second time (later)", draft.dstOverlap == DstOverlapPolicy.LATER_OFFSET) { draft.dstOverlap = DstOverlapPolicy.LATER_OFFSET }
    }

    // ---- Preview ----
    SectionTitle("Next occurrences")
    val problem = draft.problem()
    when {
        problem != null -> Help(problem)
        preview.isEmpty() -> Text("No future occurrences", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> preview.forEach { o ->
            Text(
                if (previewSchedule != null) Fmt.occurrence(o, previewSchedule) else Fmt.relative(o.instant),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }

    // ---- Dialogs ----
    if (pickStart) HavenDatePickerDialog(draft.startDate, onPick = { draft.startDate = it; pickStart = false }, onDismiss = { pickStart = false })
    if (pickExtra) HavenDatePickerDialog(draft.startDate, onPick = { if (it != draft.startDate) draft.extraDates = (draft.extraDates + it).distinct(); pickExtra = false }, onDismiss = { pickExtra = false })
    if (pickUntil) HavenDatePickerDialog(draft.untilDate, onPick = { draft.untilDate = it; pickUntil = false }, onDismiss = { pickUntil = false })
    if (pickTime) {
        val initial = draft.time ?: LocalTime.of(defaultMinuteOfDay / 60, defaultMinuteOfDay % 60)
        HavenTimePickerDialog(initial, onPick = { draft.time = it; pickTime = false }, onDismiss = { pickTime = false })
    }
    if (pickZone) ZonePickerDialog(draft.zoneId, onPick = { draft.zoneId = it; pickZone = false }, onDismiss = { pickZone = false })
}

/** Early-reminder choices offered as chips: 5 min, 10 min, 30 min, 1 hour, 1 day. */
val EARLY_PRESETS: List<Int> = listOf(5, 10, 30, 60, 1440)

fun frequencyLabel(f: Frequency): String = when (f) {
    Frequency.ONCE -> "Once"
    Frequency.DAILY -> "Daily"
    Frequency.WEEKLY -> "Weekly"
    Frequency.MONTHLY -> "Monthly"
    Frequency.YEARLY -> "Yearly"
}

private fun unitLabel(f: Frequency): String = when (f) {
    Frequency.ONCE -> ""
    Frequency.DAILY -> "days"
    Frequency.WEEKLY -> "weeks"
    Frequency.MONTHLY -> "months"
    Frequency.YEARLY -> "years"
}

@Composable
fun SectionTitle(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
}

@Composable
fun Help(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@Composable
fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HavenDatePickerDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HavenTimePickerDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = DateFormat.is24HourFormat(context))
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Choose a time", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                TimePicker(state = state)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                    TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
                }
            }
        }
    }
}

/** Searchable list of every IANA zone the device knows. */
@Composable
fun ZonePickerDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val all = remember { ZoneId.getAvailableZoneIds().sorted() }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, all) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) all else all.filter { it.lowercase().contains(q) || it.replace('_', ' ').lowercase().contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time zone") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search, e.g. Berlin") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Device: ${ZoneId.systemDefault().id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it }) { id ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = id == current, onClick = { onPick(id) }, role = Role.RadioButton).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = id == current, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(id, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (filtered.isEmpty()) item { Text("No zone matches.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

/** Overflow-free small icon button with a guaranteed 48dp target. */
@Composable
fun SmallIconButton(onClick: () -> Unit, contentDescription: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(icon, contentDescription = contentDescription) }
}
