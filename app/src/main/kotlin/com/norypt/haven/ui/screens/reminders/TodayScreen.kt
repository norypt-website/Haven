package com.norypt.haven.ui.screens.reminders

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.R
import com.norypt.haven.alarm.AlarmReadiness
import com.norypt.haven.storage.content.Priority
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.ListSpacing
import com.norypt.haven.ui.components.PriorityChip
import com.norypt.haven.ui.components.PriorityStripe
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.theme.LocalHavenColors
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Today: what rings, what was missed, what is starred, what comes next (24 h, then the week) and
 * which tasks are due. Sections are ordered by urgency and stay calm: every state has an icon or
 * a label, never colour alone.
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
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = ScreenPadding, verticalArrangement = ListSpacing) {
            // ---- Banners: ringing, missed, readiness ----
            if (ringing > 0) item {
                Banner(
                    icon = Icons.Filled.NotificationsActive,
                    tint = MaterialTheme.colorScheme.primary,
                    title = if (ringing == 1) "A reminder is ringing" else "$ringing reminders are ringing",
                    detail = "Open to snooze or dismiss.",
                    onClick = { nav.navigate(Routes.RINGING) },
                )
            }
            if (missed > 0) item {
                Banner(
                    icon = Icons.Filled.AlarmOff,
                    tint = colors.warning,
                    title = if (missed == 1) "1 missed reminder" else "$missed missed reminders",
                    detail = "Review and acknowledge them.",
                    onClick = { nav.navigate(Routes.MISSED) },
                )
            }
            if (readiness != null && readiness.worst != AlarmReadiness.Status.OK) item {
                val problems = readiness.items.filter { it.status != AlarmReadiness.Status.OK }
                val blocked = readiness.worst == AlarmReadiness.Status.BLOCKED
                Banner(
                    icon = Icons.Filled.Warning,
                    tint = if (blocked) colors.danger else colors.warning,
                    title = if (blocked) "Alarms may not ring" else "Alarm readiness: ${problems.size} ${if (problems.size == 1) "warning" else "warnings"}",
                    detail = problems.joinToString(", ") { it.title } + ". Tap to check.",
                    onClick = { nav.navigate(Routes.ALARM_READINESS) },
                )
            }

            // ---- Starred ----
            if (starred.isNotEmpty()) item {
                SectionCard {
                    SectionHeader(Icons.Filled.Star, "Starred", colors.warning)
                    Spacer(Modifier.height(4.dp))
                    starred.forEach { s ->
                        when (s) {
                            is TodayViewModel.Starred.ReminderItem -> CompactRow(
                                icon = Icons.Filled.Alarm,
                                title = s.reminder.title,
                                detail = when {
                                    !s.reminder.enabled -> "Reminder · Off"
                                    s.next != null -> "Reminder · " + Fmt.occurrence(s.next, s.reminder.schedule)
                                    else -> "Reminder · No future occurrences"
                                },
                                priority = s.reminder.priority,
                                onClick = { nav.navigate(Routes.reminderDetail(s.reminder.id)) },
                            )
                            is TodayViewModel.Starred.TaskItem -> CompactRow(
                                icon = Icons.Filled.RadioButtonUnchecked,
                                title = s.task.title,
                                detail = if (s.due != null) "Task · Due " + Fmt.dateTime(s.due) else "Task · No due date",
                                priority = Priority.of(s.task.priority),
                                onClick = { nav.navigate(Routes.taskDetail(s.task.id)) },
                            )
                        }
                    }
                }
            }

            // ---- Next 24 hours ----
            item {
                SectionCard {
                    Text("Next 24 hours", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (upcoming.isEmpty()) {
                        Text("No reminders in the next 24 hours.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        upcoming.forEach { u -> UpcomingRow(u, vm, nav, showDate = true) }
                    }
                }
            }

            // ---- Coming up this week ----
            item {
                SectionCard {
                    Text("Coming up this week", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (week.isEmpty()) {
                        Text("No reminders or tasks due in the next 7 days.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        week.forEachIndexed { i, day ->
                            if (i > 0) Spacer(Modifier.height(8.dp))
                            Text(dayHeading(day.date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            day.items.forEach { u -> UpcomingRow(u, vm, nav, showDate = false) }
                            day.tasks.forEach { d ->
                                CompactRow(
                                    icon = Icons.Filled.RadioButtonUnchecked,
                                    title = d.task.title,
                                    detail = "Task · Due " + Fmt.time(d.due.toLocalTime()),
                                    priority = Priority.of(d.task.priority),
                                    onClick = { nav.navigate(Routes.taskDetail(d.task.id)) },
                                )
                            }
                        }
                    }
                }
            }

            // ---- Tasks due ----
            item {
                SectionCard {
                    Text("Tasks due", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (dueTasks.isEmpty()) {
                        Text("Nothing due today.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        dueTasks.forEach { d ->
                            val priority = Priority.of(d.task.priority)
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).height(IntrinsicSize.Min)
                                    .semantics { contentDescription = (if (d.overdue) "Overdue. " else "Due today. ") + d.task.title },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PriorityStripe(priority, Modifier.fillMaxHeight())
                                Checkbox(
                                    checked = false,
                                    onCheckedChange = { vm.completeTask(d.task.id, true) },
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Mark '${d.task.title}' complete" },
                                )
                                Column(Modifier.weight(1f).clickable { nav.navigate(Routes.taskDetail(d.task.id)) }.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (d.task.starred) {
                                            Icon(Icons.Filled.Star, contentDescription = "Starred", tint = colors.warning, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        Text(d.task.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (d.overdue) {
                                            Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.warning, modifier = Modifier.size(14.dp))
                                        }
                                        Text(
                                            (if (d.overdue) "Overdue · " else "Due · ") + Fmt.dateTime(d.due),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (d.overdue) colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        PriorityChip(priority)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- Quick actions ----
            item {
                SectionCard {
                    Text("Quick actions", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { nav.navigate(Routes.reminderEdit()) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("New reminder")
                        }
                        OutlinedButton(onClick = { nav.navigate(Routes.taskEdit()) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("New task")
                        }
                        OutlinedButton(onClick = { vm.lockNow() }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.Lock, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Lock now")
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
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

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

/** One-line row for the Starred section. */
@Composable
private fun CompactRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, priority: Priority, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).height(IntrinsicSize.Min).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PriorityStripe(priority, Modifier.fillMaxHeight())
        if (priority != Priority.NONE) Spacer(Modifier.width(8.dp))
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        PriorityChip(priority)
    }
}

/** An upcoming occurrence with its priority stripe/chip and a quick-move overflow. */
@Composable
private fun UpcomingRow(u: TodayViewModel.Upcoming, vm: TodayViewModel, nav: NavHostController, showDate: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    val r = u.reminder
    val colors = LocalHavenColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PriorityStripe(r.priority, Modifier.fillMaxHeight())
        if (r.priority != Priority.NONE) Spacer(Modifier.width(8.dp))
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp).clickable { nav.navigate(Routes.reminderDetail(r.id)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Alarm, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (r.starred) {
                        Icon(Icons.Filled.Star, contentDescription = "Starred", tint = colors.warning, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(r.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (showDate) Fmt.occurrence(u.occurrence, r.schedule) else Fmt.time(u.occurrence.instant.atZone(java.time.ZoneId.systemDefault()).toLocalTime()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PriorityChip(r.priority)
                }
            }
        }
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

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, title: String, detail: String, onClick: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
