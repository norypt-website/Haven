package com.norypt.haven.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norypt.haven.ui.theme.LocalHavenColors
import com.norypt.haven.ui.theme.NoryptColors

/*
 * The app's shared surfaces: grouped cards, section labels, colour tiles, pills and the brand
 * hero. Every screen builds from these so the look stays the same everywhere.
 */

/** Corner radius of grouped cards and their row segments. */
val CardRadius: Dp = 18.dp

/** A grouped card: surface colour, hairline border, 18dp corners. Rows inside bring their own padding. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(content = content) }
}

/** Divider between rows of a [GroupCard], inset to line up with the row text. */
@Composable
fun CardDivider(start: Dp = 52.dp) {
    HorizontalDivider(Modifier.padding(start = start), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Small uppercase heading above a card or a group of rows, with an optional count on the right. */
@Composable
fun SectionLabel(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    description: String? = null,
    color: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = description ?: (title + (count?.let { ", $it" } ?: ""))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(7.dp))
        }
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp),
            color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text("$count", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Where a row sits in its section card, which decides the corners and edges it draws. */
enum class GroupPosition {
    SINGLE, FIRST, MIDDLE, LAST;

    val roundsTop: Boolean get() = this == SINGLE || this == FIRST
    val roundsBottom: Boolean get() = this == SINGLE || this == LAST

    companion object {
        fun of(index: Int, count: Int): GroupPosition = when {
            count == 1 -> SINGLE
            index == 0 -> FIRST
            index == count - 1 -> LAST
            else -> MIDDLE
        }
    }
}

/**
 * Draws this row's slice of one rounded section card while each row stays its own lazy item:
 * edges that continue into a neighbouring row are drawn outside the row and clipped away, and an
 * inset divider separates the row from the next.
 */
fun Modifier.cardSegment(position: GroupPosition, fill: Color, line: Color, dividerInset: Dp): Modifier {
    val top = if (position.roundsTop) CardRadius else 0.dp
    val bottom = if (position.roundsBottom) CardRadius else 0.dp
    return clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)).drawBehind {
        val r = CardRadius.toPx()
        val stroke = 1.dp.toPx()
        val y0 = if (position.roundsTop) 0f else -2 * r
        val y1 = if (position.roundsBottom) size.height else size.height + 2 * r
        drawRoundRect(fill, topLeft = Offset(0f, y0), size = Size(size.width, y1 - y0), cornerRadius = CornerRadius(r))
        drawRoundRect(
            line,
            topLeft = Offset(stroke / 2, y0 + stroke / 2),
            size = Size(size.width - stroke, y1 - y0 - stroke),
            cornerRadius = CornerRadius(r - stroke / 2),
            style = Stroke(stroke),
        )
        if (!position.roundsBottom) {
            drawLine(line, Offset(dividerInset.toPx(), size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
        }
    }
}

/** A barely lighter top-left corner gives a flat colour some depth without lowering contrast. */
fun tileBrush(base: Color): Brush = Brush.linearGradient(0f to lerp(base, Color.White, 0.10f), 0.72f to base)

/** Navy into Norypt blue: hero cards, badges and items without a colour of their own. */
@Composable
fun brandBrush(): Brush {
    val dark = LocalHavenColors.current.isDark
    return Brush.linearGradient(
        if (dark) listOf(Color(0xFF1F3F7A), NoryptColors.Blue) else listOf(NoryptColors.Navy, NoryptColors.BlueDark),
    )
}

/**
 * Rounded-square colour tile. Decorative: whatever it shows is also said in text next to it, so
 * TalkBack skips it unless [contentDescription] gives it something of its own to say.
 */
@Composable
fun ColorTile(
    brush: Brush,
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
    glow: Color? = null,
    contentDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .size(size)
            .then(if (glow != null) Modifier.shadow(elevation = 18.dp, shape = shape, ambientColor = glow, spotColor = glow) else Modifier)
            .background(brush, shape)
            .clearAndSetSemantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A colour tile with a white icon. */
@Composable
fun IconTile(icon: ImageVector, brush: Brush, modifier: Modifier = Modifier, size: Dp = 44.dp, corner: Dp = 13.dp, glow: Color? = null) {
    ColorTile(brush, size, corner, modifier, glow = glow) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** Round tinted icon button: a 40dp circle inside the standard 48dp touch target. */
@Composable
fun RoundAction(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = if (LocalHavenColors.current.isDark) 0.14f else 0.10f),
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) { Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp)) }
}

/** Label on the left, value on the right: compact facts inside a [GroupCard]. */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = valueColor,
            textAlign = TextAlign.End,
            // Label and value share the row evenly; the value sits at the right edge and wraps if long.
            modifier = Modifier.weight(1f),
        )
    }
}

/** Pill-shaped label: folders, states and counts. [large] for headers, small for list rows. */
@Composable
fun TagPill(
    text: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    icon: (@Composable () -> Unit)? = null,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = container, contentColor = content) {
        Row(
            Modifier.heightIn(min = if (large) 30.dp else 26.dp).padding(start = if (icon != null) (if (large) 10.dp else 8.dp) else 12.dp, end = if (large) 12.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text,
                style = if (large) MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (large) 180.dp else 96.dp),
            )
        }
    }
}

/** Gold "Starred" label for detail headers. */
@Composable
fun StarredPill() {
    val extras = LocalHavenColors.current
    TagPill(
        "Starred",
        large = true,
        container = extras.starFill.copy(alpha = if (extras.isDark) 0.14f else 0.18f),
        content = if (extras.isDark) extras.starFill else NoryptColors.StarGoldText,
        icon = { StarIcon(size = 16.dp, contentDescription = null) },
    )
}

/** Filter chip in the pill style: filled blue when selected, an optional count after the label. */
@Composable
fun PillChip(selected: Boolean, onClick: () -> Unit, label: String, count: Int? = null, leading: (@Composable () -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                if (count != null && count > 0) {
                    Spacer(Modifier.width(7.dp))
                    Text("$count", fontWeight = FontWeight.SemiBold, color = if (selected) scheme.onPrimary.copy(alpha = 0.85f) else scheme.onSurfaceVariant)
                }
            }
        },
        leadingIcon = leading,
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = scheme.surface,
            labelColor = scheme.onSurface,
            iconColor = scheme.onSurfaceVariant,
            selectedContainerColor = scheme.primary,
            selectedLabelColor = scheme.onPrimary,
            selectedLeadingIconColor = scheme.onPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = scheme.outline, selectedBorderColor = scheme.primary),
        // A minimum, not a fixed height: the chip keeps its 48dp touch target and grows with large text.
        modifier = Modifier.heightIn(min = 36.dp),
    )
}

/** The brand hero: a navy-to-blue card with white content, used at the top of overview screens. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color.Transparent, contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().background(brandBrush()).padding(20.dp), content = content)
    }
}

/** Stands in for a row's tile while the row is selected in selection mode. */
@Composable
fun SelectedTile(size: Dp = 44.dp, corner: Dp = 13.dp, contentDescription: String? = "Selected") {
    Box(
        Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(corner))
            .clearAndSetSemantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size * 0.5f))
    }
}
