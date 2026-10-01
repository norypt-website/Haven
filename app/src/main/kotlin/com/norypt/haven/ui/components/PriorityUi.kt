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
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
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

/** Tile fill for an item of this priority: red, orange or blue by urgency, the brand gradient when none is set. */
@Composable
fun priorityTileBrush(p: Priority): androidx.compose.ui.graphics.Brush = when (p) {
    Priority.NONE -> brandBrush()
    Priority.LOW -> tileBrush(EntryColor.BLUE.color)
    Priority.MEDIUM -> tileBrush(EntryColor.ORANGE.color)
    Priority.HIGH -> tileBrush(EntryColor.RED.color)
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
            val color = priorityColor(p)
            FilterChip(
                selected = selected,
                onClick = { onChange(p) },
                enabled = enabled,
                label = { Text(if (p == Priority.NONE) "None" else label, maxLines = 1, softWrap = false) },
                leadingIcon = if (p == Priority.NONE) null else {
                    { Icon(Icons.Filled.Flag, contentDescription = null, tint = color, modifier = Modifier.size(16.dp)) }
                },
                // Pills like the rest of the app; the chosen one is tinted and outlined in its own colour.
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    selectedContainerColor = color.copy(alpha = 0.16f),
                    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = enabled,
                    selected = selected,
                    borderColor = MaterialTheme.colorScheme.outline,
                    selectedBorderColor = color,
                    selectedBorderWidth = 1.5.dp,
                ),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Priority: $label" + if (selected) ", selected" else "" },
            )
        }
    }
}

private const val STAR_PATH = "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"

/** The star used everywhere: a gold fill with a darker edge, so it reads on light and dark surfaces. */
@Composable
fun StarIcon(modifier: Modifier = Modifier, size: Dp = 18.dp, contentDescription: String? = "Starred") {
    val colors = LocalHavenColors.current
    val star = remember(colors.starFill, colors.starEdge) {
        ImageVector.Builder(name = "HavenStar", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(STAR_PATH),
                fill = SolidColor(colors.starFill),
                stroke = SolidColor(colors.starEdge),
                strokeLineWidth = 1.5f,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }
    Icon(star, contentDescription = contentDescription, tint = Color.Unspecified, modifier = modifier.size(size))
}

/** 48dp star toggle with an explicit spoken state. */
@Composable
fun StarButton(starred: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onToggle, modifier = modifier.size(48.dp)) {
        if (starred) StarIcon(size = 24.dp, contentDescription = "Starred")
        else Icon(Icons.Outlined.StarBorder, contentDescription = "Not starred, tap to star", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Header pill for a priority: coloured flag and "High priority". Nothing for [Priority.NONE]. */
@Composable
fun PriorityPill(p: Priority) {
    if (p == Priority.NONE) return
    val color = priorityColor(p)
    TagPill(
        "${priorityLabel(p)} priority",
        large = true,
        container = color.copy(alpha = 0.14f),
        content = MaterialTheme.colorScheme.onSurface,
        icon = { Icon(Icons.Filled.Flag, contentDescription = null, tint = color, modifier = Modifier.size(15.dp)) },
    )
}
