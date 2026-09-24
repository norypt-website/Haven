package com.norypt.haven.ui.screens.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.norypt.haven.R
import com.norypt.haven.ui.components.FactLevel
import com.norypt.haven.ui.components.FactRow
import com.norypt.haven.ui.components.SectionCard

@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.norypt_shield),
            contentDescription = "Norypt",
            modifier = Modifier.width(96.dp).heightIn(max = 64.dp),
        )
        Spacer(Modifier.height(20.dp))
        Icon(
            painter = painterResource(R.drawable.ic_haven_mark),
            contentDescription = "Haven mark",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Haven", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text("A private place for what matters", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Passwords, reminders, and tasks—encrypted.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        SectionCard {
            FactRow(FactLevel.OK, "No account required")
            FactRow(FactLevel.OK, "No internet permission")
            FactRow(FactLevel.OK, "Private content is encrypted on this device")
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Set up Haven")
        }
    }
}
