package com.norypt.haven.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/** Where row text starts in a card (14 padding + 44 tile + 14 gap): dividers line up with it. */
val RowTextInset = 72.dp

/**
 * A row inside a [GroupCard]: a leading tile or control, the title (with a gold star when
 * starred), a detail line, an optional extra line [below], and trailing controls with their own
 * touch targets.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    detailColor: Color = Color.Unspecified,
    starred: Boolean = false,
    done: Boolean = false,
    titleStyle: TextStyle? = null,
    detailMaxLines: Int = 2,
    leadingSize: Dp = 44.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    leading: @Composable () -> Unit,
    below: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val click = when {
        onLongClick != null -> Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
        onClick != null -> Modifier.clickable(onClick = onClick)
        else -> Modifier
    }
    // Text starts at RowTextInset whatever the leading slot is: a 48dp control gets 12dp on each side.
    val side = 14.dp - (leadingSize - 44.dp) / 2
    Row(
        modifier.fillMaxWidth().then(click).heightIn(min = 64.dp).padding(start = side, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(side))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = titleStyle ?: MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (starred) {
                    Spacer(Modifier.width(6.dp))
                    StarIcon(size = 17.dp)
                }
            }
            if (!detail.isNullOrEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (detailColor.isSpecified) detailColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = detailMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (below != null) {
                Spacer(Modifier.height(4.dp))
                below()
            }
        }
        trailing()
    }
}

/** A calm line for an empty card section, so the section keeps its place and shape. */
@Composable
fun EmptyRow(text: String, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Chevron at the end of a row that opens another screen. */
@Composable
fun RowChevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 6.dp).size(22.dp),
    )
}

/**
 * Round completion check for tasks: a ring in the priority colour, filled blue with a tick when
 * done. 48dp touch target; the state is announced as a checkbox with [description].
 */
@Composable
fun RoundCheck(checked: Boolean, onCheckedChange: (Boolean) -> Unit, ring: Color, description: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Checkbox,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 24.dp),
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Box(Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            }
        } else {
            Box(Modifier.size(26.dp).border(2.dp, ring, CircleShape))
        }
    }
}

/** A tappable card holding one [ItemRow]-style line with a chevron: alerts and settings entries. */
@Composable
fun NavCard(title: String, detail: String?, icon: ImageVector, brush: Brush, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        ItemRow(title, detail = detail, titleStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), detailMaxLines = 5, leading = { IconTile(icon, brush) }) { RowChevron() }
    }
}

/** A square-ish shortcut card: tile on top, label below. Used in rows of two or three. */
@Composable
fun QuickActionCard(label: String, icon: ImageVector, brush: Brush, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 104.dp),
        shape = RoundedCornerShape(CardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon, brush, size = 40.dp, corner = 12.dp)
            Text(label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Icon, small label and value: one fact inside a [GroupCard], with optional actions on the right. */
@Composable
fun FieldRow(icon: ImageVector, label: String, value: @Composable () -> Unit, iconTint: Color = Color.Unspecified, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (iconTint.isSpecified) iconTint else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            value()
        }
        actions()
    }
}

/** The usual value text of a [FieldRow]. */
@Composable
fun FieldValue(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

/** Calendar badge for a date: short weekday over the day of the month, in a soft blue square. */
@Composable
fun DateTile(date: java.time.LocalDate, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val tint = MaterialTheme.colorScheme.primary
    // Sized in dp, not sp: a fixed badge whose date is also written out next to it.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val small = with(density) { 10.dp.toSp() }
    val big = with(density) { 17.dp.toSp() }
    Column(
        Modifier
            .size(size)
            .background(tint.copy(alpha = if (com.norypt.haven.ui.theme.LocalHavenColors.current.isDark) 0.16f else 0.10f), RoundedCornerShape(13.dp))
            .clearAndSetSemantics {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()).uppercase(java.util.Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = small, lineHeight = small),
            color = tint,
            maxLines = 1,
        )
        Text("${date.dayOfMonth}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = big, lineHeight = big), color = tint, maxLines = 1)
    }
}
