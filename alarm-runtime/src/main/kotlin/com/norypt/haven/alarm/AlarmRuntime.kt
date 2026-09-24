package com.norypt.haven.alarm

import android.content.Context
import com.norypt.haven.alarm.store.AlarmStoreDatabase
import com.norypt.haven.recurrence.RecurrenceEngine
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Process-wide entry point for the alarm runtime. Usable before the user unlocks the device
 * (Direct Boot) and while Haven is locked. It never touches vault keys or content.
 *
 * All store mutations run on one single-thread executor so receiver, service and UI actions
 * are serialised; this is what makes snooze/dismiss/reschedule idempotent under duplicate
 * deliveries and repeated taps.
 */
public class AlarmRuntime private constructor(context: Context) {
    public val appContext: Context = context.applicationContext
    public val store: AlarmStoreDatabase = AlarmStoreDatabase.open(appContext)
    public val prefs: AlarmPreferences = AlarmPreferences(appContext)
    public val engine: RecurrenceEngine = RecurrenceEngine()
    public val scheduler: AlarmScheduler = AlarmScheduler(this)
    public val actions: AlarmActions = AlarmActions(this)
    public val notifications: AlarmNotifications = AlarmNotifications(this)
    @Volatile private var alarmThread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "haven-alarm").apply { isDaemon = false; alarmThread = this } }

    /** Runs [block] on the serial alarm thread. If already on it, runs inline (never deadlocks). */
    public fun submit(block: () -> Unit): Future<*> {
        if (Thread.currentThread() === alarmThread) {
            block()
            return java.util.concurrent.CompletableFuture.completedFuture(Unit)
        }
        return executor.submit(block)
    }

    /**
     * Runs [block] on the serial alarm thread and waits. Re-entrant: when called from the alarm
     * thread itself (receiver work calling actions, actions calling the scheduler) it runs inline,
     * which is what makes nested calls safe on a single-thread executor.
     */
    public fun runBlocking(block: () -> Unit) {
        if (Thread.currentThread() === alarmThread) block() else executor.submit(block).get()
    }

    public companion object {
        @Volatile private var instance: AlarmRuntime? = null

        /** Configured by the app: the activity to show for the status-bar alarm icon and full-screen alerts. */
        @Volatile public var config: AlarmRuntimeConfig? = null

        public fun get(context: Context): AlarmRuntime =
            instance ?: synchronized(this) { instance ?: AlarmRuntime(context).also { instance = it } }

        /** Test hook: drops the process-wide instance (Robolectric creates a new Application per test). */
        @androidx.annotation.VisibleForTesting
        public fun resetForTesting() {
            synchronized(this) {
                instance?.store?.close()
                instance = null
            }
        }
    }
}

/** App-provided wiring; alarm-runtime has no dependency on the app module. */
public class AlarmRuntimeConfig(
    /** Activity shown when the user taps the status-bar alarm indicator / notification body. */
    public val mainActivity: Class<*>,
    /** Full-screen (lock screen) ringing activity. Must be directBootAware and show no content. */
    public val ringActivity: Class<*>,
)
