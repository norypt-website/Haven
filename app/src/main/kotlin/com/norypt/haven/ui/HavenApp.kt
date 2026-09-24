package com.norypt.haven.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.norypt.haven.di.AppContainer
import com.norypt.haven.security.SessionState
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.screens.onboarding.SetupScreen
import com.norypt.haven.ui.screens.onboarding.UnlockScreen
import com.norypt.haven.ui.screens.onboarding.WelcomeScreen
import com.norypt.haven.ui.screens.passwords.PasswordDetailScreen
import com.norypt.haven.ui.screens.passwords.PasswordEditScreen
import com.norypt.haven.ui.screens.passwords.PasswordGeneratorScreen
import com.norypt.haven.ui.screens.passwords.PasswordUnlockScreen
import com.norypt.haven.ui.screens.passwords.PasswordsScreen
import com.norypt.haven.ui.screens.reminders.MissedScreen
import com.norypt.haven.ui.screens.reminders.ReminderDetailScreen
import com.norypt.haven.ui.screens.reminders.ReminderEditScreen
import com.norypt.haven.ui.screens.reminders.RemindersScreen
import com.norypt.haven.ui.screens.reminders.RingingScreen
import com.norypt.haven.ui.screens.reminders.TodayScreen
import com.norypt.haven.ui.screens.settings.AboutScreen
import com.norypt.haven.ui.screens.settings.AlarmReadinessScreen
import com.norypt.haven.ui.screens.settings.AppearanceSettingsScreen
import com.norypt.haven.ui.screens.settings.BackupScreen
import com.norypt.haven.ui.screens.settings.SecuritySettingsScreen
import com.norypt.haven.ui.screens.settings.SettingsScreen
import com.norypt.haven.ui.screens.tasks.TaskDetailScreen
import com.norypt.haven.ui.screens.tasks.TaskEditScreen
import com.norypt.haven.ui.screens.tasks.TaskListScreen
import com.norypt.haven.ui.screens.tasks.TasksScreen

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab(Routes.TODAY, "Today", Icons.Filled.Today),
    Tab(Routes.REMINDERS, "Reminders", Icons.Filled.Alarm),
    Tab(Routes.TASKS, "Tasks", Icons.Filled.CheckCircle),
    Tab(Routes.PASSWORDS, "Passwords", Icons.Filled.Key),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

/**
 * Root of the UI. Route guards:
 *  - not initialised  -> Welcome / Setup
 *  - Locked           -> Unlock (no private content is composed while locked)
 *  - Unlocked         -> tabs; the password tab additionally requires its own unlock.
 */
@Composable
fun HavenApp(container: AppContainer, initialOccurrenceId: String?) {
    val nav = rememberNavController()
    val state by container.session.state.collectAsState()
    val ringing by container.alarmRuntime.store.occurrences().observeRinging().collectAsState(initial = emptyList())

    // Locking from anywhere pops everything back to the unlock screen.
    LaunchedEffect(state) {
        when (state) {
            is SessionState.Locked, is SessionState.UnrecoverableKeyError -> {
                val current = nav.currentDestination?.route
                val publicRoutes = setOf(Routes.WELCOME, Routes.SETUP, Routes.UNLOCK, Routes.ABOUT)
                if (current != null && current !in publicRoutes) {
                    if (!container.session.isInitialised()) nav.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                    else nav.navigate(Routes.UNLOCK) { popUpTo(0) { inclusive = true } }
                }
            }
            else -> Unit
        }
    }
    // A ringing occurrence surfaces as soon as the content vault is open.
    LaunchedEffect(state, ringing.isNotEmpty()) {
        val open = (state as? SessionState.Unlocked)?.contentOpen == true
        if (open && ringing.isNotEmpty() && nav.currentDestination?.route != Routes.RINGING) nav.navigate(Routes.RINGING)
    }

    // Notification permission (Android 13+): ask once the vault is open. Alarms still make sound
    // without it; the readiness screen explains what is missing if the user declines.
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { container.lockController.endSystemInteraction() }
    LaunchedEffect(state is SessionState.Unlocked) {
        if (state is SessionState.Unlocked &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            container.lockController.beginSystemInteraction()
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Haven open but LOCKED while something rings: Android shows only a heads-up notification when
    // the device is in use, so Haven presents the generic ringing screen itself (no content).
    if (ringing.isNotEmpty() && state !is SessionState.Unlocked && state !is SessionState.BackupOperation) {
        val prefs = container.alarmRuntime.prefs
        val ids = ringing.map { it.occurrenceId }
        com.norypt.haven.ui.ring.RingScreen(
            count = ids.size,
            allowActions = prefs.lockedScreenActionsAllowed,
            snoozeMinutes = prefs.defaultSnoozeMinutes,
            presets = prefs.snoozePresetsMinutes,
            onSnooze = { minutes -> ids.forEach { id -> container.alarmRuntime.submit { container.alarmRuntime.actions.snooze(id, minutes) } } },
            onDismiss = { ids.forEach { id -> container.alarmRuntime.submit { container.alarmRuntime.actions.dismiss(id) } } },
            onOpen = { /* already in Haven: the unlock screen is right behind this overlay */ },
            openLabel = "Unlock to view",
            showOpen = false,
        )
        return
    }

    CompositionLocalProvider(LocalAppContainer provides container) {
        val backStack by nav.currentBackStackEntryAsState()
        val currentRoute = backStack?.destination?.route
        val showTabs = tabs.any { it.route == currentRoute } && state is SessionState.Unlocked
        Scaffold(
            // Screens own their top bars (which apply the status-bar inset themselves); the outer
            // scaffold only accounts for the bottom bar / navigation inset.
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showTabs) {
                    // At large font scales five labels no longer fit; show only the selected label
                    // (icons keep their content descriptions for TalkBack) and never break words.
                    val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = { nav.navigate(tab.route) { popUpTo(Routes.TODAY) { saveState = true }; launchSingleTop = true; restoreState = true } },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                                alwaysShowLabel = !largeText,
                            )
                        }
                    }
                }
            },
        ) { padding ->
            // Decided once. A changing startDestination makes NavHost rebuild its graph and drop the
            // whole back stack, which the transient Unlocking state (e.g. opening the keeper from the
            // restore form) used to trigger. Locking is routed by the LaunchedEffect above instead.
            val start = remember {
                when {
                    !container.session.isInitialised() -> Routes.WELCOME
                    state !is SessionState.Unlocked -> Routes.UNLOCK
                    else -> Routes.TODAY
                }
            }
            NavHost(navController = nav, startDestination = start, modifier = Modifier.padding(padding)) {
                composable(Routes.WELCOME) { WelcomeScreen(onContinue = { nav.navigate(Routes.SETUP) }) }
                composable(Routes.SETUP) { SetupScreen(onDone = { nav.navigate(Routes.TODAY) { popUpTo(0) { inclusive = true } } }) }
                composable(Routes.UNLOCK) { UnlockScreen(onUnlocked = { nav.navigate(Routes.TODAY) { popUpTo(0) { inclusive = true } } }, onAbout = { nav.navigate(Routes.ABOUT) }) }

                composable(Routes.TODAY) { TodayScreen(nav) }
                composable(Routes.REMINDERS) { RemindersScreen(nav) }
                composable(Routes.REMINDER_DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { ReminderDetailScreen(nav, it.arguments!!.getString("id")!!) }
                composable(
                    Routes.REMINDER_EDIT,
                    arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" }, navArgument("taskId") { type = NavType.StringType; defaultValue = "" }),
                ) { ReminderEditScreen(nav, it.arguments?.getString("id")?.ifEmpty { null }, it.arguments?.getString("taskId")?.ifEmpty { null }) }
                composable(Routes.RINGING) { RingingScreen(nav) }
                composable(Routes.MISSED) { MissedScreen(nav) }

                composable(Routes.TASKS) { TasksScreen(nav) }
                composable(Routes.TASK_LIST, arguments = listOf(navArgument("listId") { type = NavType.StringType })) { TaskListScreen(nav, it.arguments!!.getString("listId")!!) }
                composable(Routes.TASK_DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { TaskDetailScreen(nav, it.arguments!!.getString("id")!!) }
                composable(
                    Routes.TASK_EDIT,
                    arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" }, navArgument("listId") { type = NavType.StringType; defaultValue = "" }),
                ) { TaskEditScreen(nav, it.arguments?.getString("id")?.ifEmpty { null }, it.arguments?.getString("listId")?.ifEmpty { null }) }

                composable(Routes.PASSWORDS) { PasswordsScreen(nav) }
                composable(Routes.PASSWORD_UNLOCK) { PasswordUnlockScreen(nav) }
                composable(Routes.PASSWORD_DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { PasswordDetailScreen(nav, it.arguments!!.getString("id")!!) }
                composable(Routes.PASSWORD_EDIT, arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })) { PasswordEditScreen(nav, it.arguments?.getString("id")?.ifEmpty { null }) }
                composable(Routes.PASSWORD_GENERATOR) { PasswordGeneratorScreen(nav) }

                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(Routes.SETTINGS_SECURITY) { SecuritySettingsScreen(nav) }
                composable(Routes.SETTINGS_APPEARANCE) { AppearanceSettingsScreen(nav) }
                composable(Routes.BACKUP) { BackupScreen(nav) }
                composable(Routes.ALARM_READINESS) { AlarmReadinessScreen(nav) }
                composable(Routes.ABOUT) { AboutScreen(nav) }
            }
        }
    }
}

/** Convenience for screens: navigate up if possible. */
fun NavHostController.up() { if (!popBackStack()) navigate(Routes.TODAY) }
