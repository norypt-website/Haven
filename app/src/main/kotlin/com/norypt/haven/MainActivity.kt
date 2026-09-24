package com.norypt.haven

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.norypt.haven.alarm.AlarmIntents
import com.norypt.haven.security.SecureWindow
import com.norypt.haven.ui.HavenApp
import com.norypt.haven.ui.theme.HavenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds only: `adb shell am start ... --ez com.norypt.haven.debug.ALLOW_CAPTURE true` lets us take design screenshots.
        if (com.norypt.haven.BuildConfig.DEBUG && intent?.getBooleanExtra("com.norypt.haven.debug.ALLOW_CAPTURE", false) == true) havenApp.debugAllowCapture = true
        SecureWindow.apply(this, allowCapture = com.norypt.haven.BuildConfig.DEBUG && havenApp.debugAllowCapture)
        enableEdgeToEdge()
        val container = havenApp.container
        val ringingOccurrence = intent?.getStringExtra(AlarmIntents.EXTRA_OCCURRENCE_ID)?.takeIf { it.length <= 256 }
        setContent {
            val mode by container.prefs.observeTheme().collectAsState(initial = container.prefs.themeMode)
            HavenTheme(mode) { HavenApp(container, initialOccurrenceId = ringingOccurrence) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Debug-only capture switch can also arrive on an already-running activity.
        if (com.norypt.haven.BuildConfig.DEBUG && intent.getBooleanExtra("com.norypt.haven.debug.ALLOW_CAPTURE", false)) {
            havenApp.debugAllowCapture = true
            SecureWindow.apply(this, allowCapture = true)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) havenApp.container.lockController.touch()
        return super.dispatchTouchEvent(ev)
    }
}
