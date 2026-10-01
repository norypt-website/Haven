package com.norypt.haven.ui.screens.reminders

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.R
import com.norypt.haven.alarm.AlarmReadiness
import com.norypt.haven.storage.content.Priority
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.CardDivider
import com.norypt.haven.ui.components.EmptyRow
import com.norypt.haven.ui.components.EntryColor
import com.norypt.haven.ui.components.GroupCard
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.HeroCard
import com.norypt.haven.ui.components.IconTile
import com.norypt.haven.ui.components.ItemRow
import com.norypt.haven.ui.components.NavCard
import com.norypt.haven.ui.components.PriorityChip
import com.norypt.haven.ui.components.QuickActionCard
import com.norypt.haven.ui.components.RoundCheck
import com.norypt.haven.ui.components.RowTextInset
import com.norypt.haven.ui.components.SectionLabel
import com.norypt.haven.ui.components.StarIcon
import com.norypt.haven.ui.components.brandBrush
import com.norypt.haven.ui.components.color
import com.norypt.haven.ui.components.priorityColor
import com.norypt.haven.ui.components.priorityTileBrush
import com.norypt.haven.ui.components.tileBrush
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.theme.LocalHavenColors
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Today: what rings, what was missed, what is starred, what comes next (24 h, then the week) and
 * which tasks are due. A summary hero leads, then sections ordered by urgency. Every state has an
 * icon or a label, never colour alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(nav: NavHostController) {
    val container = LocalAppContainer.current
    if (container.session.contentOrNull() == null) return
    val vm: TodayViewModel = viewModel { TodayViewModel(container) }
    val ringing by vm.ringingCount.collectAsState()
    val missed by vm.missedCount.collectAsState()
    val starred by vm.starred.collectAsState()
    val upcoming by vm.upcoming.collectAsState()
    val week by vm.week.collectAsState()
    val dueTasks by vm.dueTasks.collectAsState()
    val readiness = vm.readiness
    val colors = LocalHavenColors.current

    LaunchedEffect(Unit) { vm.checkReadiness() }

    Scaffold(
        topBar = {
            HavenTopBar("Today") {
                Image(painterResource(R.drawable.norypt_shield), contentDescription = "Norypt", Modifier.padding(end = 16.dp).size(28.dp))
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { TodayHero(upcoming = upcoming, dueCount = dueTasks.size, starredCount = starred.size) }

            // ---- Alerts: ringing, missed, readiness ----
            if (ringing > 0) item {
                NavCard(
                    title = if (ringing == 1) "A reminder is ringing" else "$ringing reminders are ringing",
                    detail = "Open to snooze or dismiss.",
                    icon = Icons.Filled.NotificationsActive,
                    brush = brandBrush(),
                    onClick = { nav.navigate(Routes.RINGING) },
                )
            }
            if (missed > 0) item {
                NavCard(
                    title = if (missed == 1) "1 missed reminder" else "$missed missed reminders",
                    detail = "Review and acknowledge them.",
                    icon = Icons.Filled.AlarmOff,
                    brush = tileBrush(EntryColor.ORANGE.color),
                    onClick = { nav.navigate(Routes.MISSED) },
                )
            }
            if (readiness != null && readiness.worst != AlarmReadiness.Status.OK) item {
                val problems = readiness.items.filter { it.status != AlarmReadiness.Status.OK }
                val blocked = readiness.worst == AlarmReadiness.Status.BLOCKED
                NavCard(
                    title = if (blocked) "Alarms may not ring" else "Alarm readiness: ${problems.size} ${if (problems.size == 1) "warning" else "warnings"}",
                    detail = problems.joinToString(", ") { it.title } + ". Tap to check.",
                    icon = Icons.Filled.Warning,
                    brush = tileBrush(if (blocked) EntryColor.RED.color else EntryColor.ORANGE.color),
                    onClick = { nav.navigate(Routes.ALARM_READINESS) },
                )
            }

            // ---- Starred ----
            if (starred.isNotEmpty()) item {
                Column {
                    SectionLabel("Starred", count = starred.size, leading = { StarIcon(size = 15.dp, contentDescription = null) })
                    GroupCard {
                        starred.forEachIndexed { i, s ->
                            if (i > 0) CardDivider(RowTextInset)
                            when (s) {
                                is TodayViewModel.Starred.ReminderItem -> ItemRow(
                                    title = s.reminder.title,
                                    detail = when {
                                        !s.reminder.enabled -> "Reminder · Off"
                                        s.next != null -> "Reminder · " + Fmt.occurrence(s.next, s.reminder.schedule)
                                        else -> "Reminder · No future occurrences"
                                    },
                                    onClick = { nav.navigate(Routes.reminderDetail(s.reminder.id)) },
                                    leading = { ReminderTile(s.reminder.priority, s.reminder.enabled) },
                                ) { PriorityChip(s.reminder.priority, Modifier.padding(end = 8.dp)) }
                                is TodayViewModel.Starred.TaskItem -> ItemRow(
                                    title = s.task.title,
                                    detail = if (s.due != null) "Task · Due " + Fmt.dateTime(s.due) else "Task · No due date",
                                    onClick = { nav.navigate(Routes.taskDetail(s.task.id)) },
                                    leading = { IconTile(Icons.Filled.TaskAlt, priorityTileBrush(Priority.of(s.task.priority))) },
                                ) { PriorityChip(Priority.of(s.task.priority), Modifier.padding(end = 8.dp)) }
                            }
                        }
                    }
                }
            }

            // ---- Next 24 hours ----
            item {
                Column {
                    SectionLabel("Next 24 hours", count = upcoming.size.takeIf { it > 0 })
                    GroupCard {
                        if (upcoming.isEmpty()) {
                            EmptyRow("No reminders in the next 24 hours.", Icons.Filled.EventAvailable)
                        } else {
                            upcoming.forEachIndexed { i, u ->
                                if (i > 0) CardDivider(RowTextInset)
                                UpcomingRow(u, vm, nav, showDate = true)
                            }
                        }
                    }
                }
            }

            // ---- Coming up this week ----
            item {
                Column {
                    SectionLabel("Coming up this week")
                    GroupCard {
                        if (week.isEmpty()) {
                            EmptyRow("No reminders or tasks due in the next 7 days.", Icons.Filled.EventAvailable)
                        } else {
                            week.forEachIndexed { i, day ->
                                if (i > 0) CardDivider(16.dp)
                                Text(
                                    dayHeading(day.date),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                                )
                                day.items.forEachIndexed { j, u ->
                                    if (j > 0) CardDivider(RowTextInset)
                                    UpcomingRow(u, vm, nav, showDate = false)
                                }
                                day.tasks.forEachIndexed { j, d ->
                                    if (j > 0 || day.items.isNotEmpty()) CardDivider(RowTextInset)
                                    ItemRow(
                                        title = d.task.title,
                                        detail = "Task · Due " + Fmt.time(d.due.toLocalTime()),
                                        starred = d.task.starred,
                                        onClick = { nav.navigate(Routes.taskDetail(d.task.id)) },
                                        leading = { IconTile(Icons.Filled.TaskAlt, priorityTileBrush(Priority.of(d.task.priority))) },
                                    ) { PriorityChip(Priority.of(d.task.priority), Modifier.padding(end = 8.dp)) }
                                }
                            }
                        }
                    }
                }
            }

            // ---- Tasks due ----
            item {
                Column {
                    SectionLabel("Tasks due", count = dueTasks.size.takeIf { it > 0 })
                    GroupCard {
                        if (dueTasks.isEmpty()) {
                            EmptyRow("Nothing due today.", Icons.Filled.TaskAlt)
                        } else {
                            dueTasks.forEachIndexed { i, d ->
                                if (i > 0) CardDivider(RowTextInset)
                                val priority = Priority.of(d.task.priority)
                                ItemRow(
                                    title = d.task.title,
                                    detail = (if (d.overdue) "Overdue · " else "Due · ") + Fmt.dateTime(d.due),
                                    detailColor = if (d.overdue) colors.warning else Color.Unspecified,
                                    starred = d.task.starred,
                                    onClick = { nav.navigate(Routes.taskDetail(d.task.id)) },
                                    leadingSize = 48.dp,
                                    leading = {
                                        RoundCheck(
                                            checked = false,
                                            onCheckedChange = { vm.completeTask(d.task.id, true) },
                                            ring = priorityColor(priority),
                                            description = "Mark '${d.task.title}' complete",
                                        )
                                    },
                                ) {
                                    if (d.overdue) Icon(Icons.Filled.Warning, contentDescription = "Overdue", tint = colors.warning, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                                    PriorityChip(priority, Modifier.padding(end = 8.dp))
                                }
                            }
                        }
                    }
                }
            }

            // ---- Quick actions ----
            item {
                Column {
                    SectionLabel("Quick actions")
                    // Three across when they fit; on narrow screens or with large text a card moves to the next row.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3) {
                        val card = Modifier.weight(1f).widthIn(min = 104.dp).fillMaxRowHeight()
                        QuickActionCard("New reminder", Icons.Filled.Alarm, brandBrush(), onClick = { nav.navigate(Routes.reminderEdit()) }, modifier = card)
                        QuickActionCard("New task", Icons.Filled.Add, tileBrush(EntryColor.GREEN.color), onClick = { nav.navigate(Routes.taskEdit()) }, modifier = card)
                        QuickActionCard("Lock now", Icons.Filled.Lock, tileBrush(EntryColor.GRAPHITE.color), onClick = { vm.lockNow() }, modifier = card)
                    }
                }
            }
        }
    }
}

/** The summary card: today's date, the next reminder (or "All clear") and three small counts. */
@Composable
private fun TodayHero(upcoming: List<TodayViewModel.Upcoming>, dueCount: Int, starredCount: Int) {
    val today = LocalDate.now()
    val next = upcoming.firstOrNull()
    HeroCard {
        Text(longDate(today), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(6.dp))
        if (next != null) {
            val at = next.occurrence.instant.atZone(ZoneId.systemDefault())
            Text("Next at ${Fmt.time(at.toLocalTime())}", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(
                next.reminder.title + if (at.toLocalDate() == today) " · today" else " · tomorrow",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text("All clear", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text("Nothing rings in the next 24 hours.", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f))
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroStat(Icons.Filled.Alarm, "${upcoming.size} in 24 h")
            HeroStat(Icons.Filled.TaskAlt, if (dueCount == 1) "1 task due" else "$dueCount tasks due")
            if (starredCount > 0) HeroStat(null, "$starredCount starred")
        }
    }
}

@Composable
private fun HeroStat(icon: ImageVector?, text: String) {
    Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.16f), contentColor = Color.White) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp)) else StarIcon(size = 15.dp, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Alarm tile in the reminder's priority colour; grey with a crossed-out alarm while it is off. */
@Composable
internal fun ReminderTile(
    priority: Priority,
    enabled: Boolean,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    corner: androidx.compose.ui.unit.Dp = 13.dp,
    modifier: Modifier = Modifier,
) {
    if (enabled) IconTile(Icons.Filled.Alarm, priorityTileBrush(priority), modifier, size = size, corner = corner)
    else IconTile(Icons.Filled.AlarmOff, tileBrush(EntryColor.GRAPHITE.color), modifier, size = size, corner = corner)
}

/** An upcoming occurrence with its priority tile/chip and a quick-move overflow. */
@Composable
private fun UpcomingRow(u: TodayViewModel.Upcoming, vm: TodayViewModel, nav: NavHostController, showDate: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    val r = u.reminder
    ItemRow(
        title = r.title,
        detail = if (showDate) Fmt.occurrence(u.occurrence, r.schedule) else Fmt.time(u.occurrence.instant.atZone(ZoneId.systemDefault()).toLocalTime()),
        starred = r.starred,
        onClick = { nav.navigate(Routes.reminderDetail(r.id)) },
        leading = { ReminderTile(r.priority, enabled = true) },
    ) {
        PriorityChip(r.priority)
        Box {
            SmallIconButton(onClick = { menuOpen = true }, contentDescription = "Move ${r.title}, ${Fmt.relative(u.occurrence.instant)}", icon = Icons.Filled.MoreVert)
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                QuickMove.entries.forEach { m ->
                    DropdownMenuItem(text = { Text(m.label) }, onClick = { menuOpen = false; vm.quickMove(u, m) })
                }
            }
        }
    }
}

/** "Today", "Tomorrow", or "Thursday, 2 Oct 2026". */
private fun dayHeading(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) + ", " + Fmt.date(date)
    }
}

/** "Wednesday, 1 October" in the device's own order and language. */
private fun longDate(date: LocalDate): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd")
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(date)
}
