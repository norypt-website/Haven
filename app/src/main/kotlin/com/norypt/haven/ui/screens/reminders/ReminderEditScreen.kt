package com.norypt.haven.ui.screens.reminders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.EmptyState
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.PrioritySelector
import com.norypt.haven.ui.components.StarButton
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.up

@Composable
fun ReminderEditScreen(nav: NavHostController, id: String?, taskId: String?) {
    val container = LocalAppContainer.current
    if (container.session.contentOrNull() == null) return
    val vm: ReminderEditViewModel = viewModel(key = "reminder-edit-${id ?: "new"}-${taskId ?: ""}") { ReminderEditViewModel(container, id, taskId) }
    val defaultMinute = vm.defaultMinuteOfDay
    val schedule = vm.draft.toSchedule(defaultMinute)
    val preview = remember(schedule) { schedule?.let { vm.preview(it) } ?: emptyList() }

    Scaffold(
        topBar = {
            HavenTopBar(if (vm.isEdit) "Edit reminder" else "New reminder", onBack = { nav.up() }) {
                if (vm.loaded && !vm.missing) StarButton(starred = vm.starred, onToggle = { vm.starred = !vm.starred })
                TextButton(onClick = { vm.save { nav.up() } }, enabled = vm.loaded && !vm.saving && !vm.missing, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
            }
        },
    ) { padding ->
        when {
            !vm.loaded -> Spacer(Modifier.fillMaxSize().padding(padding))
            vm.missing -> Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState("Reminder not found", "It may have been deleted.", action = { Button(onClick = { nav.up() }) { Text("Back") } })
            }
            else -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(ScreenPadding)) {
                SectionCard {
                    OutlinedTextField(
                        value = vm.title,
                        onValueChange = { vm.title = it; if (it.isNotBlank()) vm.showTitleError = false },
                        label = { Text("Title") },
                        singleLine = true,
                        isError = vm.showTitleError,
                        supportingText = if (vm.showTitleError) { { Text("A title is required.") } } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vm.notes,
                        onValueChange = { vm.notes = it },
                        label = { Text("Notes") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Priority", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    PrioritySelector(value = vm.priority, onChange = { vm.priority = it })
                    if (vm.isEdit) {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (vm.enabled) "Enabled" else "Disabled", style = MaterialTheme.typography.bodyLarge)
                                Text(if (vm.enabled) "This reminder will ring." else "This reminder is kept but will not ring.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = vm.enabled, onCheckedChange = { vm.enabled = it }, modifier = Modifier.semantics { contentDescription = "Reminder enabled" })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SectionCard {
                    ScheduleEditor(vm.draft, defaultMinute, preview, schedule)
                }
                vm.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.save { nav.up() } }, enabled = !vm.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (vm.saving) "Saving…" else if (vm.isEdit) "Save changes" else "Create reminder")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
