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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import com.norypt.haven.ui.components.FieldRow
import com.norypt.haven.ui.components.FieldValue
import com.norypt.haven.ui.components.GroupCard
import com.norypt.haven.ui.components.SectionLabel
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
            else -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    // Live preview: the tile takes the priority colour chosen below.
                    ReminderTile(vm.priority, vm.enabled, size = 56.dp, corner = 16.dp, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.width(14.dp))
                    OutlinedTextField(
                        value = vm.title,
                        onValueChange = { vm.title = it; if (it.isNotBlank()) vm.showTitleError = false },
                        label = { Text("Title") },
                        singleLine = true,
                        isError = vm.showTitleError,
                        supportingText = if (vm.showTitleError) { { Text("A title is required.") } } else null,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = vm.notes,
                    onValueChange = { vm.notes = it },
                    label = { Text("Notes") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionLabel("Priority")
                PrioritySelector(value = vm.priority, onChange = { vm.priority = it })
                if (vm.isEdit) {
                    SectionLabel("Ringing")
                    GroupCard {
                        FieldRow(
                            if (vm.enabled) Icons.Filled.Alarm else Icons.Filled.AlarmOff,
                            if (vm.enabled) "Enabled" else "Disabled",
                            value = { FieldValue(if (vm.enabled) "This reminder will ring." else "This reminder is kept but will not ring.") },
                        ) {
                            Switch(checked = vm.enabled, onCheckedChange = { vm.enabled = it }, modifier = Modifier.padding(end = 10.dp).semantics { contentDescription = "Reminder enabled" })
                        }
                    }
                }
                SectionLabel("Schedule")
                GroupCard {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        ScheduleEditor(vm.draft, defaultMinute, preview, schedule)
                    }
                }
                vm.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = { vm.save { nav.up() } }, enabled = !vm.saving, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(
                        if (vm.saving) "Saving…" else if (vm.isEdit) "Save changes" else "Create reminder",
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}
