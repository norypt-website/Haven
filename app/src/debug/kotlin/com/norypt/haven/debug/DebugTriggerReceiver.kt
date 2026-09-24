package com.norypt.haven.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.norypt.haven.alarm.AlarmRuntime

/**
 * Debug-only test hook: `adb shell am broadcast -a com.norypt.haven.debug.TEST_ALARM --ei seconds 10 -n <pkg>/com.norypt.haven.debug.DebugTriggerReceiver`
 * schedules the same test alarm the Alarm readiness screen offers. Never compiled into release.
 */
class DebugTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.norypt.haven.debug.TEST_ALARM") return
        val seconds = intent.getIntExtra("seconds", 10).coerceIn(3, 600)
        val count = intent.getIntExtra("count", 1).coerceIn(1, 5)
        val runtime = AlarmRuntime.get(context)
        val result = goAsync()
        runtime.submit {
            try {
                repeat(count) { i -> runtime.actions.scheduleTestAlarm(seconds, if (i == 0) "" else "-$i") }
            } finally { result.finish() }
        }
    }
}
