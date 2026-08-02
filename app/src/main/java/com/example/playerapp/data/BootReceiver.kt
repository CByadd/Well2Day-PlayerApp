package com.example.playerapp.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * BootReceiver — Starts MainActivity automatically after device boot.
 *
 * Works in both installation modes:
 *
 *  User app (/data/app):
 *    Standard BOOT_COMPLETED fires after the user unlocks for the first time.
 *    getLaunchIntentForPackage() resolves correctly and starts the app.
 *
 *  Priv-app (/system/priv-app):
 *    BOOT_COMPLETED and LOCKED_BOOT_COMPLETED both fire earlier, before the
 *    user unlock screen.  getLaunchIntentForPackage() can return null at this
 *    stage because PackageManager hasn't fully settled yet.  We use an explicit
 *    ComponentName intent to MainActivity to bypass this — priv-apps are always
 *    known to PM during early boot.  A 5-second delay lets the system finish
 *    mounting storage before the Activity tries to read assets/config.
 *
 *  The USB_DEVICE_ATTACHED intent-filter on MainActivity handles the case where
 *  the BMI sensor is plugged in AFTER boot.  This receiver covers the case where
 *  the device reboots with the sensor already plugged in, or for screen-only
 *  kiosk deployments where no USB sensor is present.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        // Delay before launching — gives system time to finish mounting /data and
        // settling PackageManager, especially important for priv-app early boot.
        private const val LAUNCH_DELAY_MS = 5000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        Log.d(TAG, "Boot signal received: $action — scheduling launch in ${LAUNCH_DELAY_MS}ms")

        val pendingResult = goAsync()

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Explicit ComponentName is used instead of getLaunchIntentForPackage()
                // because priv-app installs can cause PM to return null from the latter
                // during early boot stages (LOCKED_BOOT_COMPLETED).
                val launch = Intent().apply {
                    component = ComponentName(
                        context.packageName,
                        "com.example.playerapp.f3.MainActivity"
                    )
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                }
                context.startActivity(launch)
                Log.d(TAG, "MainActivity started successfully after boot")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start app on boot: ${t.message}", t)
                // Fallback: try getLaunchIntentForPackage in case component name changed
                try {
                    val fallback = context.packageManager
                        .getLaunchIntentForPackage(context.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    if (fallback != null) {
                        context.startActivity(fallback)
                        Log.d(TAG, "Fallback launch intent succeeded")
                    } else {
                        Log.w(TAG, "Fallback launch intent also returned null — app will not auto-start")
                    }
                } catch (t2: Throwable) {
                    Log.e(TAG, "Fallback launch also failed: ${t2.message}", t2)
                }
            } finally {
                pendingResult.finish()
            }
        }, LAUNCH_DELAY_MS)
    }
}
