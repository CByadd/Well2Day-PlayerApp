package com.example.playerapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.playerapp.utils.PrivAppInstaller
import kotlinx.coroutines.launch

/**
 * PrivAppInstallerModal — Standalone root elevation dialog for Android kiosk/TV boxes.
 *
 * Allows users to elevate PlayerApp to /system/priv-app/ directly on the device
 * using root (`su`) access without needing a PC or ADB.
 */
@Composable
fun PrivAppInstallerModal(
    isVisible: Boolean,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isInstalling by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isInstalling,
            dismissOnClickOutside = !isInstalling,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.80f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "⚡ System Priv-App Setup",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Text(
                        text = "Root access (`su`) detected on this device.",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF38BDF8)
                    )

                    Text(
                        text = "Elevating PlayerApp into /system/priv-app/ enables silent, permanent USB connection to Arduino BMI sensors without showing permission popups.",
                        fontSize = 14.sp,
                        color = Color(0xFF94A3B8),
                        textAlign = TextAlign.Center
                    )

                    if (statusMessage.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF0F172A),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = statusMessage,
                                color = Color(0xFF4ADE80),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(14.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    if (errorMessage.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF450A0A),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorMessage,
                                color = Color(0xFFFCA5A5),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(14.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    if (isInstalling) {
                        CircularProgressIndicator(color = Color(0xFF38BDF8))
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Later", fontSize = 14.sp)
                            }

                            Button(
                                onClick = {
                                    isInstalling = true
                                    errorMessage = ""
                                    statusMessage = "Installing into /system/priv-app... Device will reboot automatically."
                                    scope.launch {
                                        val (success, result) = PrivAppInstaller.installAsPrivApp(context)
                                        if (!success) {
                                            isInstalling = false
                                            statusMessage = ""
                                            errorMessage = result
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Install & Reboot", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
