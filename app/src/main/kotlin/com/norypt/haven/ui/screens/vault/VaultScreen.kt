package com.norypt.haven.ui.screens.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.HeroCard
import com.norypt.haven.ui.components.HeroIcon
import com.norypt.haven.ui.components.HeroStat
import com.norypt.haven.ui.screens.passwords.PasswordVaultGate
import com.norypt.haven.ui.screens.passwords.PasswordsContent
import com.norypt.haven.ui.screens.photos.PhotosContent
import com.norypt.haven.ui.screens.photos.observeCountSafely
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * The Vault tab: passwords and hidden photos, both behind the keeper password. A brand hero with
 * both counts and the segment chips head whichever half is shown; the lock stays in the top bar.
 */
@Composable
fun VaultScreen(nav: NavHostController) {
    PasswordVaultGate(nav) {
        val container = LocalAppContainer.current
        var segment by rememberSaveable { mutableStateOf(VaultSegment.PASSWORDS) }
        // Built lazily and caught, like the list view models: a closed keeper shows no count instead of crashing.
        val passwordCount by remember { flow<Int?> { emitAll(container.passwords.observeCount()) }.catch { emit(null) } }.collectAsState(initial = null)
        val photoCount by remember { container.photos.observeCountSafely() }.collectAsState(initial = null)
        val header: @Composable () -> Unit = {
            Column {
                VaultHero(passwordCount, photoCount)
                Spacer(Modifier.height(10.dp))
                VaultSegments(segment, { segment = it }, passwordCount = passwordCount, photoCount = photoCount)
            }
        }
        when (segment) {
            VaultSegment.PASSWORDS -> PasswordsContent(nav, header)
            VaultSegment.PHOTOS -> PhotosContent(nav, header)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VaultHero(passwordCount: Int?, photoCount: Int?) {
    HeroCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Vault", style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.semantics { heading() })
                Text("Locked with your keeper password", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f))
            }
            Spacer(Modifier.width(12.dp))
            HeroIcon(Icons.Filled.Shield)
        }
        if (passwordCount != null || photoCount != null) {
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                passwordCount?.let { HeroStat(Icons.Filled.Key, if (it == 1) "1 password" else "$it passwords") }
                photoCount?.let { HeroStat(Icons.Filled.Photo, if (it == 1) "1 photo" else "$it photos") }
            }
        }
    }
}
