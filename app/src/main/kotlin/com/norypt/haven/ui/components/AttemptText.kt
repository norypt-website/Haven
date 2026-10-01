package com.norypt.haven.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.norypt.haven.security.GuessThrottle
import com.norypt.haven.session.UnlockResult
import com.norypt.haven.ui.theme.LocalHavenColors

/** A wait in words, rounded up to whole seconds: "45 s", "1 min", "29 min 59 s". */
fun waitText(ms: Long): String {
    val total = (ms + 999) / 1000
    val minutes = total / 60
    val seconds = total % 60
    return when {
        minutes == 0L -> "$total s"
        seconds == 0L -> "$minutes min"
        else -> "$minutes min $seconds s"
    }
}

/** The warning while wrong passwords count toward the automatic erase; null when it is off. */
fun attemptsLeftText(attemptsLeft: Int?): String? = when {
    attemptsLeft == null -> null
    attemptsLeft <= 1 -> "Last attempt: one more wrong password erases this vault."
    else -> "$attemptsLeft attempts left before Haven erases this vault."
}

/**
 * One line for a refused password in a settings dialog: what went wrong and how many attempts
 * remain. The wait itself is shown live by [PasswordWaitLine]. Null when there is nothing to show.
 */
fun refusalText(result: UnlockResult): String? = when (result) {
    UnlockResult.Success, UnlockResult.Erased -> null
    is UnlockResult.WrongPassword -> listOfNotNull("Wrong password.", attemptsLeftText(result.attemptsLeft)).joinToString(" ")
    is UnlockResult.Throttled -> "Too many wrong passwords. Try again in ${waitText(result.waitMs)}."
    UnlockResult.DeviceAuthRequired -> "Confirm your device screen lock, then try again."
    is UnlockResult.Unrecoverable -> result.reason
    is UnlockResult.Failed -> result.message
}

/**
 * Milliseconds left in the wait after wrong passwords, re-read four times a second while a password
 * prompt is on screen, so a wait that starts after a refused attempt shows at once.
 */
@Composable
fun rememberPasswordWaitMs(throttle: GuessThrottle): Long {
    val wait by produceState(throttle.remainingDelayMs(), throttle) {
        while (true) {
            value = throttle.remainingDelayMs()
            delay(250)
        }
    }
    return wait
}

/** "Try again in 12 s" under a password field while a wait runs; nothing otherwise. */
@Composable
fun PasswordWaitLine(waitMs: Long, modifier: Modifier = Modifier) {
    if (waitMs > 0) {
        Text("Try again in ${waitText(waitMs)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
    }
}

/**
 * Warning under a password field while wrong passwords count toward the automatic erase: amber
 * while attempts remain, red for the last one. Announced by TalkBack whenever it changes.
 */
@Composable
fun AttemptsWarning(attemptsLeft: Int, modifier: Modifier = Modifier) {
    val colors = LocalHavenColors.current
    val last = attemptsLeft <= 1
    val tint = if (last) colors.danger else colors.warning
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = if (colors.isDark) 0.16f else 0.10f), shape)
            .border(1.dp, tint.copy(alpha = 0.5f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            attemptsLeftText(attemptsLeft).orEmpty(),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (last) FontWeight.SemiBold else FontWeight.Medium),
            color = if (last) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
