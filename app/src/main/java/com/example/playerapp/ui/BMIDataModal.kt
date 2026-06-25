package com.example.playerapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * BMI Data Modal - Shows BMI information after payment
 */
@Composable
fun BMIDataModal(
    isVisible: Boolean,
    bmiData: Map<String, Any>?,
    flowType: String,
    onDismiss: () -> Unit = {},
    drawerOffset: Int = 0,
    allowDismissOnOverlay: Boolean = false
) {
    if (!isVisible || bmiData == null) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = allowDismissOnOverlay,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val configuration = LocalConfiguration.current
        val screenWidthDp = configuration.screenWidthDp.dp
        val availableWidth = if (drawerOffset > 0) {
            screenWidthDp - drawerOffset.dp
        } else {
            480.dp
        }

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
                val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

                if (isLandscape) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        // Results Card
                        Box {
                            val screenId = bmiData["screenId"] as? String
                            ScreenLogo(
                                screenId = screenId,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = 24.dp, y = (-70).dp),
                                maxHeight = 70.dp,
                                maxWidth = 200.dp,
                                forceShow = true
                            )

                            Card(
                                modifier = Modifier
                                    .width(availableWidth)
                                    .heightIn(max = 900.dp)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.White.copy(alpha = 0.95f)
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
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
                                            .fillMaxWidth()
                                            .padding(top = if (allowDismissOnOverlay) 8.dp else 0.dp),
                                        contentAlignment = Alignment.TopCenter
                                    ) {
                                        BMIScreen(bmiData)
                                    }
                                }
                            }
                        }

                        // QR Card for F2 Mode
                        if (flowType == "F2") {
                            var showF2QR by remember { mutableStateOf(true) }
                            if (showF2QR) {
                                Spacer(modifier = Modifier.width(16.dp))
                                Card(
                                    modifier = Modifier
                                        .width(360.dp)
                                        .heightIn(max = 900.dp)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color.White.copy(alpha = 0.95f)
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp)
                                    ) {
                                        IconButton(
                                            onClick = { showF2QR = false },
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

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            QRCodeScreen(bmiData = bmiData, isSaveCloudOnly = true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Portrait orientation: Column layout
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        // Results Card
                        Box {
                            val screenId = bmiData["screenId"] as? String
                            ScreenLogo(
                                screenId = screenId,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = 24.dp, y = (-70).dp),
                                maxHeight = 70.dp,
                                maxWidth = 200.dp,
                                forceShow = true
                            )

                            Card(
                                modifier = Modifier
                                    .width(availableWidth)
                                    .padding(vertical = 8.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.White.copy(alpha = 0.95f)
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
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
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.TopCenter
                                    ) {
                                        BMIScreen(bmiData)
                                    }
                                }
                            }
                        }

                        // QR Card for F2 Mode
                        if (flowType == "F2") {
                            var showF2QR by remember { mutableStateOf(true) }
                            if (showF2QR) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Card(
                                    modifier = Modifier
                                        .width(availableWidth)
                                        .padding(vertical = 8.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color.White.copy(alpha = 0.95f)
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp)
                                    ) {
                                        IconButton(
                                            onClick = { showF2QR = false },
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

                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            QRCodeScreen(bmiData = bmiData, isSaveCloudOnly = true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
