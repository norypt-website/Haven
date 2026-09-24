package com.norypt.haven.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.norypt.haven.storage.content.Priority
import com.norypt.haven.ui.theme.LocalHavenColors

/** Colour + label + icon for a priority. Never colour alone. */
@Composable
fun priorityColor(p: Priority): Color = when (p) {
    Priority.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    Priority.LOW -> MaterialTheme.colorScheme.primary
    Priority.MEDIUM -> LocalHavenColors.current.warning
    Priority.HIGH -> LocalHavenColors.current.danger
}

fun priorityLabel(p: Priority): String = when (p) {
    Priority.NONE -> "No priority"
    Priority.LOW -> "Low"
    Priority.MEDIUM -> "Medium"
    Priority.HIGH -> "High"
}

/** Small chip: coloured flag icon + label. Renders nothing for [Priority.NONE]. */
@Composable
fun PriorityChip(p: Priority, modifier: Modifier = Modifier) {
    if (p == Priority.NONE) return
    val color = priorityColor(p)
    val label = priorityLabel(p)
    Row(
        modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = "Priority: $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Flag, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Row of four filter chips, one per priority level. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun PrioritySelector(value: Priority, onChange: (Priority) -> Unit, enabled: Boolean = true) {
    // FlowRow: chips keep their natural width and wrap to a second line on narrow screens instead
    // of squeezing the last chip (which made "High" wrap inside its chip).
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Priority.entries.forEach { p ->
            val label = priorityLabel(p)
            val selected = value == p
            FilterChip(
                selected = selected,
                onClick = { onChange(p) },
                enabled = enabled,
                label = { Text(if (p == Priority.NONE) "None" else label, maxLines = 1, softWrap = false) },
                leadingIcon = if (p == Priority.NONE) null else {
                    { Icon(Icons.Filled.Flag, contentDescription = null, tint = priorityColor(p), modifier = Modifier.size(16.dp)) }
                },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Priority: $label" + if (selected) ", selected" else "" },
            )
        }
    }
}

/** 48dp star toggle with an explicit spoken state. */
@Composable
fun StarButton(starred: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onToggle, modifier = modifier.size(48.dp)) {
        Icon(
            if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = if (starred) "Starred" else "Not starred, tap to star",
            tint = if (starred) LocalHavenColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 4dp-wide vertical bar in the priority colour for list rows. Nothing for [Priority.NONE]. */
@Composable
fun PriorityStripe(p: Priority, modifier: Modifier = Modifier) {
    if (p == Priority.NONE) return
    Spacer(
        modifier
            .width(4.dp)
            .fillMaxHeight()
            .heightIn(min = 24.dp)
            .background(priorityColor(p), RoundedCornerShape(2.dp))
            .semantics { contentDescription = "Priority: ${priorityLabel(p)}" },
    )
}
