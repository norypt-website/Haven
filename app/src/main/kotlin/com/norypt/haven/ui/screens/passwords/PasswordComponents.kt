package com.norypt.haven.ui.screens.passwords

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norypt.haven.ui.components.ColorTile
import com.norypt.haven.ui.components.EntryColor
import com.norypt.haven.ui.components.TagPill
import com.norypt.haven.ui.components.color
import com.norypt.haven.ui.components.entryInitials
import com.norypt.haven.ui.components.tileBrush

/**
 * The coloured square that identifies an entry: its initials (or a key when the title has none)
 * on its chosen or automatic colour. The letters scale with the tile, not with the font size
 * setting, so they always fit; the full title is always shown next to the tile.
 */
@Composable
internal fun EntryTile(
    title: String,
    colorId: Int,
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    contentDescription: String? = null,
) {
    val base = EntryColor.resolve(colorId, title).color
    val initials = entryInitials(title)
    ColorTile(tileBrush(base), size, corner, modifier, glow = if (glow) base else null, contentDescription = contentDescription) {
        if (initials.isEmpty()) {
            Icon(Icons.Filled.Key, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.45f))
        } else {
            val fontSize = with(LocalDensity.current) { (size * 0.36f).toSp() }
            Text(initials, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, maxLines = 1)
        }
    }
}

/** Folder label for list rows ([large] = false) and the entry header ([large] = true). */
@Composable
internal fun FolderTag(name: String, modifier: Modifier = Modifier, large: Boolean = false) {
    TagPill(name, modifier, large = large, icon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(if (large) 15.dp else 13.dp)) })
}
