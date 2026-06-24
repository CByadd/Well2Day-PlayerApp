package com.example.playerapp.kiosk

import android.app.admin.DeviceAdminReceiver

/**
 * Required entry point for Device Owner / Device Admin kiosk provisioning.
 *
 * This receiver does nothing by itself; [KioskPolicyManager] uses the admin component
 * to apply lock-task allowlisting when the app is provisioned as Device Owner.
 */
class KioskDeviceAdminReceiver : DeviceAdminReceiver()

