package com.example.playerapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.BackHandler

/**
 * QR Code Modal - Shows QR code for payment
 * Uses QRCodeScreen from BMIModal.kt (same package)
 */
@Composable
fun QRModal(
    isVisible: Boolean,
    bmiData: Map<String, Any>?,
    onDismiss: () -> Unit = {},
    drawerOffset: Int = 0,
    allowDismissOnOverlay: Boolean = false
) {
    if (!isVisible || bmiData == null) return

    if (allowDismissOnOverlay) {
        BackHandler { onDismiss() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f)
    ) {
        val configuration = LocalConfiguration.current
        val screenWidthDp = configuration.screenWidthDp.dp
        val availableWidth = if (drawerOffset > 0) {
            screenWidthDp - drawerOffset.dp
        } else {
            480.dp
        }

        // Overlay that only covers the available space, leaving drawer unobscured
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(if (drawerOffset > 0) screenWidthDp - drawerOffset.dp else screenWidthDp)
                .align(Alignment.CenterStart)
                .background(Color.Black.copy(alpha = 0.65f))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        if (allowDismissOnOverlay) onDismiss()
                    })
                }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = if (drawerOffset > 0) Arrangement.Start else Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        val screenId = bmiData["screenId"] as? String
                        ScreenLogo(
                            screenId = screenId,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = (-70).dp),
                            maxHeight = 70.dp,
                            maxWidth = 200.dp,
                            forceShow = true
                        )

                        Card(
                            modifier = Modifier
                                .width(availableWidth)
                                .heightIn(min = 560.dp, max = 640.dp)
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.White.copy(alpha = 0.95f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp)
                            ) {
                                if (allowDismissOnOverlay) {
                                    IconButton(
                                        onClick = onDismiss,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close",
                                            tint = Color.Gray,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = if (allowDismissOnOverlay) 8.dp else 0.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    QRCodeScreen(bmiData)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// generateQrBitmap is defined in BMIModal.kt (same package)
