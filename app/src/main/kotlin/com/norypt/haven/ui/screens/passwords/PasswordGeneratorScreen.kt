package com.norypt.haven.ui.screens.passwords

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.norypt.haven.data.PasswordGenerator
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.up
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PasswordGeneratorScreen(nav: NavHostController) {
    PasswordVaultGate(nav) { GeneratorContent(nav) }
}

private enum class Mode { RANDOM, PASSPHRASE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratorContent(nav: NavHostController) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var mode by remember { mutableStateOf(Mode.RANDOM) }
    var length by remember { mutableIntStateOf(20) }
    var lower by remember { mutableStateOf(true) }
    var upper by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var excludeAmbiguous by remember { mutableStateOf(true) }
    var words by remember { mutableIntStateOf(6) }
    var dashSeparator by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CharArray?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    val options = PasswordGenerator.Options(length, lower, upper, digits, symbols, excludeAmbiguous)
    val classCount = listOf(lower, upper, digits, symbols).count { it }
    val separator = if (dashSeparator) "-" else " "

    // Generated on first composition and whenever an option changes; the previous value is wiped.
    LaunchedEffect(mode, options, words, dashSeparator, tick) {
        result?.fill('\u0000')
        result = if (mode == Mode.RANDOM) PasswordGenerator.generate(options) else PasswordGenerator.passphrase(words, separator)
    }
    DisposableEffect(Unit) { onDispose { result?.fill('\u0000'); result = null } }

    val bits = if (mode == Mode.RANDOM) PasswordGenerator.entropyBits(options) else PasswordGenerator.passphraseEntropyBits(words)
    val shown = result?.let { String(it) } ?: ""

    Scaffold(
        topBar = { HavenTopBar("Password generator", onBack = { nav.up() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = mode == Mode.RANDOM, onClick = { mode = Mode.RANDOM }, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) { Text("Random characters") }
                SegmentedButton(selected = mode == Mode.PASSPHRASE, onClick = { mode = Mode.PASSPHRASE }, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) { Text("Passphrase") }
            }

            SectionCard {
                Text(shown, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
                Spacer(Modifier.height(8.dp))
                Text("~${bits.roundToInt()} bits of entropy", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Generated with SecureRandom on this device. Nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { tick++ }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null); Spacer(Modifier.padding(horizontal = 4.dp)); Text("Regenerate")
                    }
                    OutlinedButton(
                        onClick = {
                            val r = result ?: return@OutlinedButton
                            copySecret(container, String(r))
                            scope.launch { snackbar.showSnackbar(copiedMessage(container)) }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Icon(Icons.Filled.ContentCopy, contentDescription = null); Spacer(Modifier.padding(horizontal = 4.dp)); Text("Copy") }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val r = result ?: return@Button
                        GeneratedPasswordHandoff.put(r)
                        r.fill('\u0000'); result = null
                        nav.up()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use this password") }
            }

            if (mode == Mode.RANDOM) {
                SectionCard {
                    Text("Length: $length", style = MaterialTheme.typography.titleSmall)
                    Slider(value = length.toFloat(), onValueChange = { length = it.roundToInt() }, valueRange = 8f..64f, steps = 55)
                    ToggleRow("Lowercase letters", lower, enabled = !lower || classCount > 1) { lower = it }
                    ToggleRow("Uppercase letters", upper, enabled = !upper || classCount > 1) { upper = it }
                    ToggleRow("Digits", digits, enabled = !digits || classCount > 1) { digits = it }
                    ToggleRow("Symbols", symbols, enabled = !symbols || classCount > 1) { symbols = it }
                    ToggleRow("Exclude look-alikes (O, 0, I, l, 1, |)", excludeAmbiguous) { excludeAmbiguous = it }
                }
            } else {
                SectionCard {
                    Text("Words: $words", style = MaterialTheme.typography.titleSmall)
                    Slider(value = words.toFloat(), onValueChange = { words = it.roundToInt() }, valueRange = 4f..10f, steps = 5)
                    Text("Separator", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = !dashSeparator, onClick = { dashSeparator = false }, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) { Text("Space") }
                        SegmentedButton(selected = dashSeparator, onClick = { dashSeparator = true }, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) { Text("Dash") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
