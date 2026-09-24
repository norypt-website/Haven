package com.norypt.haven

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.alarm.AlarmRuntimeConfig
import com.norypt.haven.di.AppContainer
import com.norypt.haven.security.SafeLog
import com.norypt.haven.ui.ring.RingActivity

class HavenApplication : Application() {
    /**
     * Debug-only: once a launch intent carries the ALLOW_CAPTURE extra, screenshots stay enabled for
     * the process (activities get recreated on theme changes and lose intent extras). Always false
     * in release builds; the extra is ignored there.
     */
    @Volatile var debugAllowCapture: Boolean = false

    /** Created on first use after the user has unlocked the device (credential-encrypted storage). */
    val container: AppContainer by lazy { AppContainer(this) }

    private fun userUnlocked(): Boolean = getSystemService(UserManager::class.java).isUserUnlocked

    override fun onCreate() {
        super.onCreate()
        SafeLog.enabled = BuildConfig.DEBUG
        AlarmRuntime.config = AlarmRuntimeConfig(mainActivity = MainActivity::class.java, ringActivity = RingActivity::class.java)
        val runtime = AlarmRuntime.get(this)
        runtime.submit { runtime.notifications.ensureChannels() }
        // Force-stop recovery and general self-healing: every process start reconciles alarms.
        runtime.scheduler.reconcileAsync()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { if (userUnlocked()) container.lockController.onAppForeground() }
                override fun onStop(owner: LifecycleOwner) { if (userUnlocked()) container.lockController.onAppBackground() }
            },
        )
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF && userUnlocked()) container.lockController.onScreenOff()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            RECEIVER_NOT_EXPORTED,
        )
    }
}

val Context.havenApp: HavenApplication get() = applicationContext as HavenApplication
