package com.example.playerapp.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        try {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (launch != null) {
                context.startActivity(launch)
            } else {
                Log.w("BootReceiver", "No launch intent for package")
            }
        } catch (t: Throwable) {
            Log.e("BootReceiver", "Failed to start app on boot: ${t.message}", t)
        }
    }
}
