package com.example.playerapp.stability

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global crash handler that restarts the app after an uncaught exception.
 *
 * - Installed once per process (idempotent).
 * - Schedules a restart via AlarmManager (2s delay), then terminates process.
 *
 * This is critical for kiosk uptime; it avoids leaving a half-dead process around.
 */
object CrashRestartHandler {
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // May roll back a freshly OTA'd version that keeps crashing.
            try { com.example.playerapp.utils.OtaUpdater.onCrash(appContext) } catch (_: Throwable) {}
            try {
                val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

                if (launchIntent != null) {
                    val pending = PendingIntent.getActivity(
                        appContext,
                        0,
                        launchIntent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    alarm.setExactAndAllowWhileIdle(
                        AlarmManager.RTC,
                        System.currentTimeMillis() + 2000,
                        pending
                    )
                }
            } catch (_: Throwable) {
                // Best-effort; fall through to default handler + kill.
            }

            // Let the default handler log/crash normally (tombstone, etc.).
            try {
                defaultHandler?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
                // ignore
            } finally {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }
}

