package com.example.playerapp.utils

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.playerapp.BuildConfig
import com.example.playerapp.data.BASE_URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Over-the-air self-update for rooted kiosk boxes.
 *
 * Checks GET /app-release/latest on start, every 6h and on the `app-update-available` socket event.
 * A newer APK is downloaded, verified (SHA-256, package name, higher versionCode, same signing cert),
 * then installed with root once the kiosk is idle via a detached script:
 *   pm install -r -g  (an update to a /system/priv-app app keeps SYSTEM/privileged status and app data)
 *   am start MainActivity
 * The result is reported to POST /app-release/report on the next start.
 *
 * Crash-loop rollback: 3 crashes within 5 min of an OTA -> `pm uninstall-system-updates`
 * (back to the /system baseline), and that versionCode is never retried.
 */
object OtaUpdater {
    private const val TAG = "OtaUpdater"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val IDLE_REQUIRED_MS = 60_000L         // kiosk must be idle this long before installing
    private const val ROLLBACK_WINDOW_MS = 5 * 60 * 1000L
    private const val ROLLBACK_CRASHES = 3
    private const val MAX_ATTEMPTS = 3                   // per release, then give up until a new one is published
    private const val MAIN_ACTIVITY = "com.example.playerapp.f3.MainActivity"
    private const val TMP_APK = "/data/local/tmp/w2d_ota.apk" // system_server can't read app_data_file
    private const val INSTALL_LOG = "/data/local/tmp/w2d_ota.log"

    // Fed by F3PlayerScreen: payment flow idle and nobody on the scale.
    @Volatile var isFlowIdle = true
    @Volatile private var lastWeightKg = 0f
    @Volatile private var lastWeightAt = 0L
    fun onWeight(kg: Float) { lastWeightKg = kg; lastWeightAt = System.currentTimeMillis() }
    private fun isIdle() = isFlowIdle &&
        (lastWeightKg < 3f || System.currentTimeMillis() - lastWeightAt > 15_000L)

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = Mutex()
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }
    private fun prefs(ctx: Context = app) = ctx.getSharedPreferences("ota", Context.MODE_PRIVATE)

    fun start(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        // Synchronous so a crash during the first seconds of a new version is already counted.
        val justInstalled = armCrashWatchIfUpdated()
        scope.launch {
            reportPreviousInstall(justInstalled)
            while (true) {
                runCatching { check() }.onFailure { Log.e(TAG, "check failed", it) }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun checkNow() {
        if (::app.isInitialized) scope.launch { runCatching { check() }.onFailure { Log.e(TAG, "check failed", it) } }
    }

    private suspend fun check() {
        if (!busy.tryLock()) return
        try {
            val screenId = getOrCreateMachineId(app)
            val body = http.newCall(Request.Builder()
                .url("$BASE_URL/app-release/latest?package=${app.packageName}&screenId=$screenId").build())
                .execute().use { if (!it.isSuccessful) return; it.body?.string() } ?: return
            val rel = JSONObject(body).optJSONObject("release") ?: return
            val versionCode = rel.getLong("versionCode")
            val sha256 = rel.getString("sha256").lowercase()
            val p = prefs()
            if (versionCode <= BuildConfig.VERSION_CODE || versionCode <= p.getLong("blocked_version", 0)) return
            val sameRelease = p.getString("attempt_sha", null) == sha256
            if (sameRelease && p.getInt("attempts", 0) >= MAX_ATTEMPTS) return
            Log.i(TAG, "Update available: $versionCode (installed ${BuildConfig.VERSION_CODE})")

            val apk = File(app.filesDir, "ota/update.apk")
            download(rel.getString("url"), apk)
            // Count only downloaded attempts, so a flaky network never exhausts them.
            p.edit().putString("attempt_sha", sha256)
                .putInt("attempts", if (sameRelease) p.getInt("attempts", 0) + 1 else 1).commit()
            val error = verify(apk, sha256, rel.optLong("size", -1))
            if (error != null) {
                apk.delete()
                report("failed", BuildConfig.VERSION_CODE.toLong(), error)
                return
            }

            var idleSince = 0L
            while (true) {
                val now = System.currentTimeMillis()
                if (!isIdle()) idleSince = 0L else if (idleSince == 0L) idleSince = now
                if (idleSince != 0L && now - idleSince >= IDLE_REQUIRED_MS) break
                delay(5_000)
            }

            p.edit().putLong("pending_version", versionCode).commit()
            Log.i(TAG, "Installing $versionCode")
            runDetached(app, "install", """
                echo started > $INSTALL_LOG
                cp '${apk.absolutePath}' $TMP_APK && chmod 644 $TMP_APK
                pm install -r -g $TMP_APK > $INSTALL_LOG 2>&1
                echo "exit=${'$'}?" >> $INSTALL_LOG
                rm -f $TMP_APK
                am start -n ${app.packageName}/$MAIN_ACTIVITY
            """)
            // Success kills this process. Still alive after the script finished -> install failed.
            repeat(36) {
                delay(5_000)
                val log = su("cat $INSTALL_LOG")
                if (log.contains("exit=0")) return // installed; the new version reports on start
                if (log.contains("exit=")) {
                    p.edit().remove("pending_version").commit()
                    apk.delete()
                    report("failed", BuildConfig.VERSION_CODE.toLong(), log.trim())
                    return
                }
            }
        } finally {
            busy.unlock()
        }
    }

    private fun download(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code} downloading $url")
            part.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
        }
        if (!part.renameTo(dest)) throw IllegalStateException("rename failed")
    }

    /** Returns null when the APK is safe to install, else the reason it is not. */
    private fun verify(apk: File, sha256: String, size: Long): String? {
        if (size >= 0 && apk.length() != size) return "size mismatch: ${apk.length()} != $size"
        val actual = sha256Hex(apk)
        if (actual != sha256) return "sha256 mismatch: $actual"
        val pm = app.packageManager
        val archive = packageInfo(pm, apk.absolutePath) ?: return "not a parseable APK"
        if (archive.packageName != app.packageName) return "package ${archive.packageName} != ${app.packageName}"
        val archiveVersion = versionCodeOf(archive)
        if (archiveVersion <= BuildConfig.VERSION_CODE) return "APK versionCode $archiveVersion is not higher than ${BuildConfig.VERSION_CODE}"
        val installed = signers(packageInfo(pm, null))
        val incoming = signers(archive)
        if (installed.isEmpty() || installed != incoming) return "signing certificate differs from installed app"
        return null
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager, archivePath: String?): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES
        return if (archivePath != null) pm.getPackageArchiveInfo(archivePath, flags)
        else pm.getPackageInfo(app.packageName, flags)
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo?): Set<String> {
        val sigs = (if (Build.VERSION.SDK_INT >= 28) info?.signingInfo?.apkContentsSigners else null)
            ?: info?.signatures ?: return emptySet()
        return sigs.map { sha256Hex(it.toByteArray()) }.toSet()
    }

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** First start of the OTA'd version: arms the crash-loop watch for the next 5 minutes. */
    private fun armCrashWatchIfUpdated(): Boolean {
        val p = prefs()
        val pending = p.getLong("pending_version", 0)
        if (pending == 0L || BuildConfig.VERSION_CODE < pending) return false
        p.edit().remove("pending_version").putLong("installed_at", System.currentTimeMillis())
            .putLong("installed_version", pending).remove("crashes").commit()
        return true
    }

    /** Report how the last OTA / rollback went. */
    private fun reportPreviousInstall(justInstalled: Boolean) {
        val p = prefs()
        val rolledBack = p.getLong("rolled_back_version", 0)
        if (rolledBack > 0 && report("rolled_back", BuildConfig.VERSION_CODE.toLong(), "crash loop after OTA to $rolledBack")) {
            p.edit().remove("rolled_back_version").commit()
        }
        if (justInstalled) {
            File(app.filesDir, "ota/update.apk").delete()
            report("installed", BuildConfig.VERSION_CODE.toLong(), null)
        }
    }

    private fun report(status: String, versionCode: Long, error: String?): Boolean = runCatching {
        val json = JSONObject().put("screenId", getOrCreateMachineId(app)).put("packageName", app.packageName)
            .put("status", status).put("versionCode", versionCode).put("error", error)
        http.newCall(Request.Builder().url("$BASE_URL/app-release/report")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build())
            .execute().use { it.isSuccessful }
    }.getOrElse { Log.e(TAG, "report failed", it); false }

    /**
     * Called from CrashRestartHandler on the crashing thread (sync, no coroutines).
     * 3+ crashes within 5 minutes of an OTA -> roll back to the /system baseline.
     */
    fun onCrash(context: Context) {
        val p = prefs(context)
        val installedAt = p.getLong("installed_at", 0)
        val now = System.currentTimeMillis()
        if (installedAt == 0L || now - installedAt > ROLLBACK_WINDOW_MS) return
        val crashes = p.getInt("crashes", 0) + 1
        p.edit().putInt("crashes", crashes).commit()
        if (crashes < ROLLBACK_CRASHES) return
        val bad = p.getLong("installed_version", 0)
        Log.e(TAG, "Crash loop after OTA to $bad, rolling back to /system baseline")
        p.edit().putLong("blocked_version", bad).putLong("rolled_back_version", bad)
            .remove("installed_at").remove("crashes").commit()
        runDetached(context, "rollback", """
            sleep 3
            pm uninstall-system-updates ${context.packageName} || pm uninstall -k ${context.packageName}
            am start -n ${context.packageName}/$MAIN_ACTIVITY
        """)
    }

    /** Runs a root script detached (nohup ... &) so it survives pm killing this process. */
    private fun runDetached(context: Context, name: String, body: String) {
        val script = File(context.filesDir, "ota/$name.sh")
        script.parentFile?.mkdirs()
        script.writeText("#!/system/bin/sh\n" + body.trimIndent() + "\n")
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "nohup sh '${script.absolutePath}' >/dev/null 2>&1 &"))
        p.waitFor(10, TimeUnit.SECONDS)
    }

    private fun su(cmd: String): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }.getOrDefault("")

    /** Backup relaunch in case the install script's `am start` didn't make it. */
    class PackageReplacedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            runCatching {
                context.startActivity(Intent().setComponent(ComponentName(context.packageName, MAIN_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.e(TAG, "relaunch failed", it) }
        }
    }
}
