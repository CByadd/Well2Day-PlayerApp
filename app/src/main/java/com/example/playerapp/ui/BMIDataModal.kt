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
            screenWidthDp.coerceAtMost(480.dp)
        }

        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val resultsCardWidth = if (isLandscape && flowType == "F2") {
            if (drawerOffset > 0) {
                val space = screenWidthDp - drawerOffset.dp
                (space - 336.dp).coerceAtLeast(360.dp)
            } else {
                480.dp
            }
        } else {
            if (drawerOffset > 0) {
                val space = screenWidthDp - drawerOffset.dp
                space.coerceAtLeast(400.dp)
            } else {
                600.dp
            }
        }
        val isCompact = !isLandscape

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
                                    .width(resultsCardWidth)
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
                                        .padding(16.dp)
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
                    }
                }
            }
        }
    }
}
