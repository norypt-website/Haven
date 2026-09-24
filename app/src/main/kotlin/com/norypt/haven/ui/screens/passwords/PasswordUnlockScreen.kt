package com.norypt.haven.ui.screens.passwords

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.PasswordField
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.navigation.Routes
import kotlinx.coroutines.delay

/**
 * Fresh password entry for the password keeper. The keeper has its own key and is never opened
 * as a side effect of unlocking reminders. The typed password lives only in this composition
 * and is cleared as soon as the screen leaves.
 */
@Composable
fun PasswordUnlockScreen(nav: NavHostController) {
    val container = LocalAppContainer.current
    val vm = rememberPasswordUnlockViewModel(container)
    val status by vm.status.collectAsState()
    var password by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { password = "" } }

    // Backup & restore sends the user here to unlock the keeper for a restore; in that case both
    // success and cancel return to the half-filled restore form instead of the password list.
    val cameFromBackup = remember { nav.previousBackStackEntry?.destination?.route == Routes.BACKUP }
    // Both effects below can observe the same unlock; navigate exactly once.
    var navigated by remember { mutableStateOf(false) }
    fun leave() {
        if (navigated) return
        navigated = true
        if (cameFromBackup) nav.popBackStack(Routes.BACKUP, inclusive = false)
        else nav.navigate(Routes.TODAY) { popUpTo(Routes.TODAY) { inclusive = false }; launchSingleTop = true }
    }
    fun enterKeeper() {
        if (navigated) return
        navigated = true
        if (cameFromBackup) nav.popBackStack(Routes.BACKUP, inclusive = false)
        else nav.navigate(Routes.PASSWORDS) { popUpTo(Routes.PASSWORD_UNLOCK) { inclusive = true }; launchSingleTop = true }
    }
    BackHandler { leave() }

    // Already open (for example after a configuration change during navigation): go straight in.
    LaunchedEffect(Unit) {
        if (container.session.passwordsOrNull() != null) enterKeeper()
    }
    LaunchedEffect(status) {
        if (status is PasswordUnlockViewModel.Status.Done) {
            password = ""
            vm.consumeDone()
            enterKeeper()
        }
    }

    // Countdown for throttled attempts.
    val wait = status as? PasswordUnlockViewModel.Status.Wait
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(wait) {
        if (wait == null) return@LaunchedEffect
        while (System.currentTimeMillis() < wait.untilMs) { now = System.currentTimeMillis(); delay(500) }
        vm.waitOver()
    }
    val remainingSeconds = wait?.let { ((it.untilMs - now + 999) / 1000).coerceAtLeast(1) }
    val busy = status is PasswordUnlockViewModel.Status.Busy
    val canSubmit = password.isNotEmpty() && !busy && wait == null

    fun submit() {
        if (!canSubmit) return
        val chars = password.toCharArray()
        password = ""
        vm.unlock(chars)
    }

    Scaffold(topBar = { HavenTopBar("Password keeper", onBack = { leave() }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding).padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text("Enter your Haven password", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "The password keeper uses its own key and is opened separately from reminders.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            PasswordField(
                value = password,
                onValueChange = { if (!busy) password = it },
                label = "Haven password",
                imeAction = ImeAction.Done,
                onDone = { submit() },
                isError = status is PasswordUnlockViewModel.Status.Message,
                supportingText = when (val s = status) {
                    is PasswordUnlockViewModel.Status.Message -> s.text
                    is PasswordUnlockViewModel.Status.Wait -> "${s.prefix} Try again in ${remainingSeconds ?: 1} s."
                    PasswordUnlockViewModel.Status.Busy -> "Deriving the key…"
                    else -> null
                },
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { submit() }, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Open keeper")
            }
        }
    }
}
