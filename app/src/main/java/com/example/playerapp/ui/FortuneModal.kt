package com.example.playerapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Fortune Cookie Modal - Shows fortune message and QR code
 * Uses RedesignedFortuneScreen for the UI layout.
 */
@Composable
fun FortuneModal(
    isVisible: Boolean,
    bmiData: Map<String, Any>?,
    flowType: String = "F1",
    onDismiss: () -> Unit = {},
    drawerOffset: Int = 0,
    allowDismissOnOverlay: Boolean = false
) {
    if (!isVisible || bmiData == null) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    if (allowDismissOnOverlay) onDismiss()
                })
            }
            .zIndex(100f)
    ) {
        val configuration = LocalConfiguration.current
        val screenWidthDp = configuration.screenWidthDp.dp
        val availableWidth = if (drawerOffset > 0) {
            screenWidthDp - drawerOffset.dp
        } else {
            screenWidthDp.coerceAtMost(600.dp) // Allow a bit more width for the new design
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(availableWidth)
                    .align(if (drawerOffset > 0) Alignment.CenterStart else Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                RedesignedFortuneScreen(
                    bmiData = bmiData,
                    flowType = flowType,
                    onDismiss = onDismiss,
                    allowDismissOnOverlay = allowDismissOnOverlay,
                    availableWidth = availableWidth
                )
            }
        }
    }
}
