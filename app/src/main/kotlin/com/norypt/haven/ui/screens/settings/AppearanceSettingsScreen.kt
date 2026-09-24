package com.norypt.haven.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.theme.ThemeMode
import com.norypt.haven.ui.up

@Composable
fun AppearanceSettingsScreen(nav: NavHostController) {
    val container = LocalAppContainer.current
    var mode by remember { mutableStateOf(container.prefs.themeMode) }

    Column(Modifier.fillMaxSize()) {
        HavenTopBar(title = "Appearance", onBack = { nav.up() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding)) {
            SectionCard {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (m, label) ->
                    RadioRow(label = label, selected = mode == m) { mode = m; container.prefs.themeMode = m }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Haven uses Norypt's palette and does not use wallpaper-derived colours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
