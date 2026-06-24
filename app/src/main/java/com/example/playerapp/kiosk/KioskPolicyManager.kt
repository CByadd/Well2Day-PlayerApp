package com.example.playerapp.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build

/**
 * Centralized kiosk policy utilities.
 *
 * Notes:
 * - True "full lockdown" requires the app to be provisioned as **Device Owner**.
 * - When Device Owner, we can allowlist the app for lock task, making `startLockTask()`
 *   non-dismissible by the user and preventing status/nav escape.
 */
object KioskPolicyManager {
    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, KioskDeviceAdminReceiver::class.java)

    /**
     * If Device Owner, allowlist this app for Lock Task mode.
     *
     * Call on every launch; it's idempotent when already configured.
     */
    fun ensureLockTaskAllowlisted(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        val admin = adminComponent(context)
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))

        // Optional: avoid screensaver/lock where supported
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dpm.setKeyguardDisabled(admin, true)
            dpm.setStatusBarDisabled(admin, true)
        }
    }

    /**
     * Enter lock task if possible. When Device Owner + allowlisted, this becomes "pinned kiosk".
     */
    fun enterLockTask(activity: Activity) {
        try {
            activity.startLockTask()
        } catch (_: Throwable) {
            // Non-DO devices may throw; immersive is still applied elsewhere.
        }
    }
}

