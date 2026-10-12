package com.norypt.haven.ui.screens.vault

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.norypt.haven.ui.components.PillChip

enum class VaultSegment(val label: String) { PASSWORDS("Passwords"), PHOTOS("Photos") }

/**
 * The Vault's two halves as pill chips (the same style as the password filters), each with its
 * count after the label. The row scrolls sideways if large text makes it wider than the screen.
 */
@Composable
fun VaultSegments(
    selected: VaultSegment,
    onSelect: (VaultSegment) -> Unit,
    modifier: Modifier = Modifier,
    passwordCount: Int? = null,
    photoCount: Int? = null,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VaultSegment.entries.forEach { s ->
            PillChip(
                selected = s == selected,
                onClick = { onSelect(s) },
                label = s.label,
                count = if (s == VaultSegment.PASSWORDS) passwordCount else photoCount,
            ) {
                Icon(if (s == VaultSegment.PASSWORDS) Icons.Filled.Key else Icons.Filled.Photo, contentDescription = null, modifier = Modifier.size(17.dp))
            }
        }
    }
}
