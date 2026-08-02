package com.example.playerapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import com.example.playerapp.utils.PrivAppInstaller
import kotlinx.coroutines.launch

@Composable
fun TroubleshootModal(
    onDismiss: () -> Unit,
    onShowBMI: () -> Unit,
    onShowFortune: () -> Unit,
    onShowProcessing: () -> Unit,
    isInternetConnected: Boolean,
    isUsbConnected: Boolean,
    appMode: String,
    paymentFlowState: String,
    socketConnected: Boolean,
    showDevFooter: Boolean,
    onToggleDevFooter: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Root / priv-app state
    val isPrivApp = remember { PrivAppInstaller.isPrivilegedApp(context) }
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    var rootActionRunning by remember { mutableStateOf(false) }
    var rootActionMessage by remember { mutableStateOf("") }
    var rootActionError by remember { mutableStateOf("") }
    var showUninstallConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        rootAvailable = PrivAppInstaller.isRootAvailable()
    }

    Dialog(onDismissRequest = { if (!rootActionRunning) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Admin & Troubleshooting",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E293B)
                    )
                    IconButton(onClick = { if (!rootActionRunning) onDismiss() }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.Gray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Flow Overrides
                Text(
                    text = "Force Flow (Mock Data)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF64748B)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onShowBMI,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text("BMI", fontSize = 12.sp)
                    }
                    Button(
                        onClick = onShowProcessing,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text("Process", fontSize = 12.sp)
                    }
                    Button(
                        onClick = onShowFortune,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F4C5C)),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text("Fortune", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // System Diagnostics
                Text(
                    text = "System Diagnostics",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF64748B)
                )
                Spacer(modifier = Modifier.height(12.dp))

                DiagnosticRow(label = "Internet", isGood = isInternetConnected, trueText = "Online", falseText = "Offline")
                Spacer(modifier = Modifier.height(8.dp))
                DiagnosticRow(label = "USB Serial", isGood = isUsbConnected, trueText = "Connected", falseText = "Disconnected")
                Spacer(modifier = Modifier.height(8.dp))
                DiagnosticRow(label = "Socket Server", isGood = socketConnected, trueText = "Connected", falseText = "Disconnected")
                Spacer(modifier = Modifier.height(8.dp))
                DiagnosticRow(
                    label = "Root Access",
                    isGood = rootAvailable == true,
                    trueText = "Available",
                    falseText = if (rootAvailable == null) "Checking..." else "Not available"
                )
                Spacer(modifier = Modifier.height(8.dp))
                DiagnosticRow(
                    label = "Install Location",
                    isGood = isPrivApp,
                    trueText = "System (/system/priv-app)",
                    falseText = "User (/data/app)"
                )

                // Root management section
                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = Color(0xFFE2E8F0))
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Installation Management",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF64748B)
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Status banner
                val statusColor = if (isPrivApp) Color(0xFF059669) else Color(0xFF0284C7)
                val statusBg = if (isPrivApp) Color(0xFFF0FDF4) else Color(0xFFEFF6FF)
                val statusText = if (isPrivApp)
                    "Installed as system priv-app. Silent USB permission is active."
                else
                    "Installed as user app. USB permission dialog may appear on each new device."
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = statusBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = statusText,
                        fontSize = 13.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Start
                    )
                }

                // Action result messages
                if (rootActionMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF0FDF4),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = rootActionMessage,
                            fontSize = 13.sp,
                            color = Color(0xFF059669),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                if (rootActionError.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1E1E2E),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Installation failed — shell output:",
                                fontSize = 12.sp,
                                color = Color(0xFFFCA5A5),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = rootActionError,
                                fontSize = 11.sp,
                                color = Color(0xFFCDD6F4),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (rootActionRunning) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF0284C7)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Running root command...", fontSize = 13.sp, color = Color(0xFF475569))
                    }
                } else if (rootAvailable == true) {
                    // Move to Root — only shown when NOT already priv-app
                    if (!isPrivApp) {
                        Button(
                            onClick = {
                                rootActionRunning = true
                                rootActionMessage = ""
                                rootActionError = ""
                                scope.launch {
                                    val (success, msg) = PrivAppInstaller.installAsPrivApp(context)
                                    rootActionRunning = false
                                    if (success) rootActionMessage = msg else rootActionError = msg
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Move to System (Install as Root)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Remove from Root — only shown when IS priv-app
                    if (isPrivApp) {
                        Button(
                            onClick = {
                                rootActionRunning = true
                                rootActionMessage = ""
                                rootActionError = ""
                                scope.launch {
                                    val (success, msg) = PrivAppInstaller.removeFromPrivApp(context)
                                    rootActionRunning = false
                                    if (success) rootActionMessage = msg else rootActionError = msg
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Remove from System (Move to User App)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Uninstall — always shown when root is available
                    if (!showUninstallConfirm) {
                        OutlinedButton(
                            onClick = { showUninstallConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                width = 1.dp
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Uninstall App", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        // Confirmation row
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFFFF7ED),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "This will permanently remove the app and all its data. Are you sure?",
                                    fontSize = 13.sp,
                                    color = Color(0xFF92400E),
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { showUninstallConfirm = false },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Cancel", fontSize = 12.sp)
                                    }
                                    Button(
                                        onClick = {
                                            showUninstallConfirm = false
                                            rootActionRunning = true
                                            rootActionMessage = ""
                                            rootActionError = ""
                                            scope.launch {
                                                val (success, msg) = PrivAppInstaller.uninstallApp(context)
                                                rootActionRunning = false
                                                if (success) rootActionMessage = msg else rootActionError = msg
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Confirm Uninstall", fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                } else if (rootAvailable == false) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF8FAFC),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Root (su) is not available on this device. Installation management requires a rooted device.",
                            fontSize = 13.sp,
                            color = Color(0xFF64748B),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                // rootAvailable == null => still checking, no buttons yet

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFFE2E8F0))
                Spacer(modifier = Modifier.height(12.dp))

                // States
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "App Mode:", fontSize = 14.sp, color = Color(0xFF475569))
                    Text(text = appMode, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Flow State:", fontSize = 14.sp, color = Color(0xFF475569))
                    Text(text = paymentFlowState, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFFE2E8F0))
                Spacer(modifier = Modifier.height(16.dp))

                // Dev Footer Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "Show Dev Footer", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                        Text(text = "Displays real-time USB stats", fontSize = 12.sp, color = Color(0xFF64748B))
                    }
                    Switch(
                        checked = showDevFooter,
                        onCheckedChange = onToggleDevFooter,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF0F766E)
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun DiagnosticRow(label: String, isGood: Boolean, trueText: String, falseText: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 15.sp, color = Color(0xFF1E293B))
        Box(
            modifier = Modifier
                .background(
                    color = if (isGood) Color(0xFF10B981).copy(alpha = 0.1f) else Color(0xFFEF4444).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (isGood) trueText else falseText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isGood) Color(0xFF059669) else Color(0xFFDC2626)
            )
        }
    }
}
