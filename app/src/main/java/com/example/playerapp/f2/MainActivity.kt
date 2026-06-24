package com.example.playerapp.f2

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.playerapp.utils.getOrCreateMachineId
import com.example.playerapp.ui.PlayerScreen
import com.example.playerapp.data.Repository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val screenId = getOrCreateMachineId(this)

        // Update player status on boot
        Repository.updatePlayerStatus(screenId) { ok, err ->
            android.util.Log.i("PlayerApp", "Status update: ${'$'}ok ${'$'}err")
        }

        // Register device on startup
        val resolution = resources.displayMetrics.let { "${'$'}{it.widthPixels}x${'$'}{it.heightPixels}" }
        val os = "Android ${'$'}{Build.VERSION.RELEASE}"
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "unknown" }
        Repository.registerScreen(
            machineId = screenId,
            screenId = screenId,
            resolution = resolution,
            os = os,
            appVersion = "Android App 1.01.7"
        ) { ok, err ->
            // No UI, just log
            android.util.Log.i("PlayerApp", "register: ${'$'}ok ${'$'}err")
            // Socket.IO removed - updates now handled via periodic HTTP polling
        }

        setContent {
            MaterialTheme {
                PlayerScreen(screenId = screenId)
            }
        }

        // Hide system UI safely after the view is attached
        window.decorView.post {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        }
    }
}
