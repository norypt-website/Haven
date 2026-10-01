package com.norypt.haven.ui.screens.passwords

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.AttemptsWarning
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.waitText
import com.norypt.haven.ui.components.brandBrush
import com.norypt.haven.ui.components.PasswordField
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.theme.LocalHavenColors
import com.norypt.haven.ui.theme.NoryptColors
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
    val attemptsLeft by vm.attemptsLeft.collectAsState()
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
    val remainingMs = wait?.let { (it.untilMs - now).coerceAtLeast(1) }
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
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding).padding(top = 28.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KeeperBadge()
            Spacer(Modifier.height(30.dp))
            Text(
                "Enter your Haven password",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "The password keeper uses its own key and opens separately from reminders.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 340.dp),
            )
            Spacer(Modifier.height(28.dp))
            PasswordField(
                value = password,
                onValueChange = { if (!busy) password = it },
                label = "Haven password",
                imeAction = ImeAction.Done,
                onDone = { submit() },
                isError = status is PasswordUnlockViewModel.Status.Message || status is PasswordUnlockViewModel.Status.Wait,
                supportingText = when (val s = status) {
                    is PasswordUnlockViewModel.Status.Message -> s.text
                    is PasswordUnlockViewModel.Status.Wait -> "${s.prefix} Try again in ${waitText(remainingMs ?: 1)}."
                    PasswordUnlockViewModel.Status.Busy -> "Deriving the key…"
                    else -> null
                },
            )
            attemptsLeft?.let { left ->
                Spacer(Modifier.height(12.dp))
                AttemptsWarning(left)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { submit() }, enabled = canSubmit, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Open keeper", style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                }
            }
            Spacer(Modifier.height(28.dp))
            KeeperFacts()
        }
    }
}

/** Navy-to-blue key badge with a small lock: the keeper's mark on its locked screen. */
@Composable
private fun KeeperBadge() {
    val dark = LocalHavenColors.current.isDark
    val glow = (if (dark) NoryptColors.Blue else NoryptColors.BlueDark).copy(alpha = if (dark) 0.45f else 0.55f)
    val shape = RoundedCornerShape(28.dp)
    Box(Modifier.size(96.dp)) {
        Box(
            Modifier
                .size(96.dp)
                .shadow(elevation = 20.dp, shape = shape, ambientColor = glow, spotColor = glow)
                .background(brandBrush(), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Key, contentDescription = null, tint = Color.White, modifier = Modifier.size(46.dp))
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 8.dp, y = 8.dp)
                .size(36.dp)
                .background(MaterialTheme.colorScheme.background, CircleShape)
                .padding(3.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
        }
    }
}

/** Three short, true statements about the keeper. */
@Composable
private fun KeeperFacts() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            KeeperFact(Icons.Filled.Key, "Its own key, separate from reminders")
            HorizontalDivider(Modifier.padding(start = 34.dp), color = MaterialTheme.colorScheme.outlineVariant)
            KeeperFact(Icons.Filled.CloudOff, "No network access. Nothing is sent anywhere.")
            HorizontalDivider(Modifier.padding(start = 34.dp), color = MaterialTheme.colorScheme.outlineVariant)
            KeeperFact(Icons.Filled.Lock, "Locks again whenever Haven locks")
        }
    }
}

@Composable
private fun KeeperFact(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
