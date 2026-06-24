package com.example.playerapp.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.playerapp.data.Repository
import com.example.playerapp.utils.LogoVisibilityStorage
import java.io.File
import kotlinx.coroutines.delay

@Composable
fun ScreenLogo(
    screenId: String?,
    modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp = 60.dp,
    maxWidth: androidx.compose.ui.unit.Dp = 180.dp,
    forceShow: Boolean = false // If true, always show logo regardless of dev panel toggle
) {
    val context = LocalContext.current
    var logoPath by remember { mutableStateOf<String?>(null) }
    var logoLoading by remember { mutableStateOf(false) }
    var showLogo by remember { mutableStateOf(LogoVisibilityStorage.getShowLogo(context)) }
    
    // Fetch and download logo when screenId is available
    LaunchedEffect(screenId) {
        if (!screenId.isNullOrBlank()) {
            logoLoading = true
            
            // Check if cached logo exists first
            val localFile = File(context.filesDir, "screen-logo-$screenId")
            if (localFile.exists() && localFile.length() > 0) {
                logoPath = localFile.absolutePath
                logoLoading = false
            }
            
            // Fetch logo from server
            Repository.getScreenLogo(context, screenId) { success, localPath, error ->
                logoLoading = false
                if (success && !localPath.isNullOrBlank()) {
                    val file = File(localPath)
                    if (file.exists() && file.length() > 0) {
                        logoPath = localPath
                    }
                } else {
                    if (logoPath == null) {
                        logoPath = null
                    }
                }
            }
        } else {
            logoLoading = false
            logoPath = null
        }
    }
    
    // Watch for logo visibility preference changes (only if not forced)
    if (!forceShow) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(300)
                val newShowLogo = LogoVisibilityStorage.getShowLogo(context)
                if (newShowLogo != showLogo) {
                    showLogo = newShowLogo
                }
            }
        }
    }
    
    // Display logo if enabled and available (or forced)
    val shouldShow = if (forceShow) true else showLogo
    if (shouldShow && (logoPath != null || logoLoading)) {
        Box(
            modifier = modifier
                .heightIn(max = maxHeight)
                .widthIn(max = maxWidth),
            contentAlignment = Alignment.TopStart
        ) {
            when {
                logoLoading && logoPath == null -> {
                    // Show nothing while fetching
                }
                logoPath != null -> {
                    // Show logo image
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(File(logoPath!!))
                            .crossfade(true)
                            .build(),
                        contentDescription = "Screen Logo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        onError = {
                            logoPath = null
                        }
                    )
                }
            }
        }
    }
}

