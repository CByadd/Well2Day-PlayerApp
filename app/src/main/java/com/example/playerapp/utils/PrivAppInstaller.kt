package com.example.playerapp.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Standalone Privileged App (Root) Installer
 *
 * Allows the Android application to elevate ITSELF into /system/priv-app/
 * directly on the device using root (su) access without requiring a PC or ADB connection.
 *
 * Key design decisions:
 *  - Script and XML are written to app's private filesDir FIRST, then executed via
 *    `su -c sh /path/to/script.sh`. This avoids heredoc/pipe escaping issues that
 *    caused silent failures when writing XML via DataOutputStream.
 *  - stdout + stderr from the shell are captured and returned to the UI so the user
 *    can see exactly what went wrong.
 *  - Files are VERIFIED to exist on disk before declaring success and rebooting.
 *  - SELinux context is set via chcon to avoid packages being ignored on SELinux-enforcing ROMs.
 *  - Magisk module path is tried in ADDITION to (not instead of) direct system write.
 *  - `pm uninstall` is NOT run — Android auto-prefers system copy over user copy after reboot.
 */
object PrivAppInstaller {

    private const val TAG = "PrivAppInstaller"
    private const val PRIV_APP_DIR = "/system/priv-app/PlayerApp"
    private const val TARGET_APK_PATH = "$PRIV_APP_DIR/PlayerApp.apk"
    private const val PERM_XML_PATH = "/system/etc/permissions/privapp-permissions-playerapp.xml"

    /**
     * Checks if the app is currently running as a Privileged System App (/system/priv-app).
     */
    fun isPrivilegedApp(context: Context): Boolean {
        val appInfo = context.applicationInfo
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isPrivApp = appInfo.sourceDir.startsWith("/system/priv-app") ||
                appInfo.sourceDir.startsWith("/product/priv-app") ||
                appInfo.sourceDir.startsWith("/system_ext/priv-app")
        Log.d(TAG, "isSystem=$isSystem, isPrivApp=$isPrivApp, sourceDir=${appInfo.sourceDir}")
        return isSystem || isPrivApp
    }

    /**
     * Checks if root (`su`) is available on the device.
     */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            process.waitFor()
            output != null && (output.contains("uid=0") || output.contains("root"))
        } catch (e: Exception) {
            Log.d(TAG, "Root check failed: ${e.message}")
            false
        }
    }

    /**
     * Runs a root shell command and returns Pair(exitCode, combined stdout+stderr output).
     */
    private fun runSuCommand(vararg cmd: String): Pair<Int, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c") + cmd)
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            val combined = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
            Pair(exitCode, combined)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "unknown error")
        }
    }

    /**
     * Installs the app as a Privileged App using local root (`su`) shell commands.
     *
     * Approach:
     *  1. Write the permissions XML and install script to app's private filesDir.
     *  2. Execute the script via `su -c sh /path/to/script.sh` — avoids pipe/heredoc issues.
     *  3. Capture all output and verify files exist before declaring success.
     *  4. Reboot only after successful verification.
     */
    suspend fun installAsPrivApp(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val currentApkPath = context.applicationInfo.sourceDir
            val packageName = context.packageName
            val filesDir = context.filesDir.absolutePath

            Log.d(TAG, "Starting self-elevation. APK: $currentApkPath")

            // ── Step 1: Write permissions XML to app private dir ─────────────
            val xmlContent = """<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="$packageName">
        <permission name="android.permission.MANAGE_USB" />
    </privapp-permissions>
</permissions>"""

            val xmlFile = File(context.filesDir, "privapp_permissions.xml")
            xmlFile.writeText(xmlContent)
            xmlFile.setReadable(true, false) // world-readable so su can read it

            // ── Step 2: Write install script to app private dir ──────────────
            // The script is written to a file to avoid heredoc/pipe escaping issues.
            // It tries direct /system write AND Magisk module injection.
            val scriptContent = buildString {
                appendLine("#!/bin/sh")
                appendLine("set -e")  // exit on first error
                appendLine()
                appendLine("RESULT_FILE=\"$filesDir/install_result.txt\"")
                appendLine("echo 'Starting priv-app installation...' > \"\$RESULT_FILE\"")
                appendLine()
                appendLine("# ── Try direct /system write ────────────────────────────────")
                appendLine("SYSTEM_WRITABLE=0")
                appendLine("mount -o remount,rw /system 2>/dev/null && SYSTEM_WRITABLE=1 || true")
                appendLine("if [ \"\$SYSTEM_WRITABLE\" = \"0\" ]; then")
                appendLine("    mount -o remount,rw / 2>/dev/null && SYSTEM_WRITABLE=1 || true")
                appendLine("fi")
                appendLine("echo \"System writable: \$SYSTEM_WRITABLE\" >> \"\$RESULT_FILE\"")
                appendLine()
                appendLine("if [ \"\$SYSTEM_WRITABLE\" = \"1\" ]; then")
                appendLine("    mkdir -p $PRIV_APP_DIR || { echo 'mkdir priv-app failed' >> \"\$RESULT_FILE\"; }")
                appendLine("    cp \"$currentApkPath\" \"$TARGET_APK_PATH\" || { echo 'cp APK failed' >> \"\$RESULT_FILE\"; }")
                appendLine("    chmod 644 \"$TARGET_APK_PATH\"")
                appendLine("    chcon u:object_r:system_file:s0 \"$TARGET_APK_PATH\" 2>/dev/null || true")
                appendLine("    mkdir -p /system/etc/permissions")
                appendLine("    cp \"$filesDir/privapp_permissions.xml\" \"$PERM_XML_PATH\" || { echo 'cp XML failed' >> \"\$RESULT_FILE\"; }")
                appendLine("    chmod 644 \"$PERM_XML_PATH\"")
                appendLine("    chcon u:object_r:system_file:s0 \"$PERM_XML_PATH\" 2>/dev/null || true")
                appendLine("    # Verify files exist")
                appendLine("    if [ -f \"$TARGET_APK_PATH\" ] && [ -f \"$PERM_XML_PATH\" ]; then")
                appendLine("        echo 'DIRECT_INSTALL_OK' >> \"\$RESULT_FILE\"")
                appendLine("    else")
                appendLine("        echo 'DIRECT_INSTALL_FAILED: files not found after copy' >> \"\$RESULT_FILE\"")
                appendLine("        SYSTEM_WRITABLE=0")
                appendLine("    fi")
                appendLine("else")
                appendLine("    echo 'Direct system write not available (read-only partition)' >> \"\$RESULT_FILE\"")
                appendLine("fi")
                appendLine()
                appendLine("# ── Magisk / KernelSU module injection ──────────────────────")
                appendLine("MAGISK_OK=0")
                appendLine("if [ -d \"/data/adb/modules\" ]; then")
                appendLine("    echo 'Magisk detected, creating module...' >> \"\$RESULT_FILE\"")
                appendLine("    MAGISK_MOD=\"/data/adb/modules/playerapp_privapp\"")
                appendLine("    mkdir -p \"\$MAGISK_MOD/system/priv-app/PlayerApp\"")
                appendLine("    mkdir -p \"\$MAGISK_MOD/system/etc/permissions\"")
                appendLine("    touch \"\$MAGISK_MOD/auto_mount\"")
                appendLine("    printf 'id=playerapp_privapp\\nname=PlayerApp PrivApp\\nversion=1.0\\nversionCode=1\\nauthor=Well2Day\\ndescription=Silent USB permission\\n' > \"\$MAGISK_MOD/module.prop\"")
                appendLine("    cp \"$currentApkPath\" \"\$MAGISK_MOD/system/priv-app/PlayerApp/PlayerApp.apk\"")
                appendLine("    cp \"$filesDir/privapp_permissions.xml\" \"\$MAGISK_MOD/system/etc/permissions/privapp-permissions-playerapp.xml\"")
                appendLine("    chmod 644 \"\$MAGISK_MOD/system/priv-app/PlayerApp/PlayerApp.apk\"")
                appendLine("    chmod 644 \"\$MAGISK_MOD/system/etc/permissions/privapp-permissions-playerapp.xml\"")
                appendLine("    if [ -f \"\$MAGISK_MOD/system/priv-app/PlayerApp/PlayerApp.apk\" ]; then")
                appendLine("        echo 'MAGISK_INSTALL_OK' >> \"\$RESULT_FILE\"")
                appendLine("        MAGISK_OK=1")
                appendLine("    else")
                appendLine("        echo 'MAGISK_INSTALL_FAILED' >> \"\$RESULT_FILE\"")
                appendLine("    fi")
                appendLine("else")
                appendLine("    echo 'Magisk not detected (/data/adb/modules missing)' >> \"\$RESULT_FILE\"")
                appendLine("fi")
                appendLine()
                appendLine("# ── Final check ─────────────────────────────────────────────")
                appendLine("if [ \"\$SYSTEM_WRITABLE\" = \"1\" ] || [ \"\$MAGISK_OK\" = \"1\" ]; then")
                appendLine("    echo 'INSTALL_SUCCESS' >> \"\$RESULT_FILE\"")
                appendLine("    sync")
                appendLine("    reboot")
                appendLine("else")
                appendLine("    echo 'INSTALL_FAILED: neither direct system write nor Magisk available' >> \"\$RESULT_FILE\"")
                appendLine("    exit 1")
                appendLine("fi")
            }

            val scriptFile = File(context.filesDir, "privapp_install.sh")
            scriptFile.writeText(scriptContent)
            scriptFile.setExecutable(true, false)
            scriptFile.setReadable(true, false)

            // Clean up any previous result file
            val resultFile = File(context.filesDir, "install_result.txt")
            resultFile.delete()

            // ── Step 3: Execute script via su ────────────────────────────────
            Log.d(TAG, "Executing install script: ${scriptFile.absolutePath}")
            val (exitCode, output) = runSuCommand("sh", scriptFile.absolutePath)

            Log.d(TAG, "Script exit code: $exitCode")
            Log.d(TAG, "Script output: $output")

            // ── Step 4: Read result file written by the script ───────────────
            val resultText = runCatching { resultFile.readText() }.getOrDefault("")
            Log.d(TAG, "Install result: $resultText")

            val fullLog = buildString {
                if (output.isNotBlank()) appendLine(output)
                if (resultText.isNotBlank()) appendLine(resultText)
            }.trim()

            when {
                resultText.contains("INSTALL_SUCCESS") -> {
                    Log.d(TAG, "Installation verified. Device rebooting.")
                    Pair(true, "Files verified and written. Device is rebooting...")
                }
                exitCode == 0 && resultText.contains("DIRECT_INSTALL_OK") -> {
                    Pair(true, "System copy written. Rebooting...")
                }
                exitCode == 0 && resultText.contains("MAGISK_INSTALL_OK") -> {
                    Pair(true, "Magisk module installed. Rebooting...")
                }
                else -> {
                    val errDetail = if (fullLog.isNotBlank()) "\n\n$fullLog" else " (no output captured)"
                    Pair(false, "Installation failed.$errDetail")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Self-installation error", e)
            Pair(false, "Installation error: ${e.message}")
        }
    }

    /**
     * Removes the app from /system/priv-app and the permissions XML, then reboots.
     */
    suspend fun removeFromPrivApp(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Removing ${context.packageName} from /system/priv-app")

            val script = """
                mount -o remount,rw /system 2>/dev/null || mount -o remount,rw / 2>/dev/null || true
                rm -rf $PRIV_APP_DIR
                rm -f $PERM_XML_PATH
                if [ -d "/data/adb/modules/playerapp_privapp" ]; then
                    rm -rf /data/adb/modules/playerapp_privapp
                fi
                sync
                reboot
            """.trimIndent()

            val scriptFile = File(context.filesDir, "privapp_remove.sh")
            scriptFile.writeText(script)
            scriptFile.setExecutable(true, false)
            scriptFile.setReadable(true, false)

            val (exitCode, output) = runSuCommand("sh", scriptFile.absolutePath)
            Log.d(TAG, "Remove exit=$exitCode output=$output")

            if (exitCode == 0) {
                Pair(true, "Removed from system. Device is rebooting...")
            } else {
                Pair(false, "Remove command failed (exit $exitCode)${if (output.isNotBlank()) ": $output" else ""}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Remove from priv-app error", e)
            Pair(false, "Remove error: ${e.message}")
        }
    }

    /**
     * Fully uninstalls the app using `pm uninstall` via root.
     * Required when the app is installed as a system app.
     * WARNING: Destructive — app and all its data will be removed.
     */
    suspend fun uninstallApp(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val packageName = context.packageName
            Log.d(TAG, "Uninstalling $packageName via root")

            val script = """
                mount -o remount,rw /system 2>/dev/null || mount -o remount,rw / 2>/dev/null || true
                rm -rf $PRIV_APP_DIR
                rm -f $PERM_XML_PATH
                if [ -d "/data/adb/modules/playerapp_privapp" ]; then
                    rm -rf /data/adb/modules/playerapp_privapp
                fi
                pm uninstall $packageName
                sync
            """.trimIndent()

            val scriptFile = File(context.filesDir, "privapp_uninstall.sh")
            scriptFile.writeText(script)
            scriptFile.setExecutable(true, false)
            scriptFile.setReadable(true, false)

            val (exitCode, output) = runSuCommand("sh", scriptFile.absolutePath)
            Log.d(TAG, "Uninstall exit=$exitCode output=$output")

            if (exitCode == 0) {
                Pair(true, "App uninstalled successfully.")
            } else {
                Pair(false, "Uninstall failed (exit $exitCode)${if (output.isNotBlank()) ": $output" else ""}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Uninstall error", e)
            Pair(false, "Uninstall error: ${e.message}")
        }
    }
}
