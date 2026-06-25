package com.example.playerapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun TroubleshootModal(
    onDismiss: () -> Unit,
    onShowBMI: () -> Unit,
    onShowFortune: () -> Unit,
    isInternetConnected: Boolean,
    isUsbConnected: Boolean,
    appMode: String,
    paymentFlowState: String,
    socketConnected: Boolean
) {
    Dialog(onDismissRequest = onDismiss) {
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
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E))
                    ) {
                        Text("Show BMI")
                    }
                    Button(
                        onClick = onShowFortune,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F4C5C))
                    ) {
                        Text("Show Fortune")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Diagnostics
                Text(
                    text = "System Diagnostics",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF64748B)
                )
                Spacer(modifier = Modifier.height(12.dp))

                DiagnosticRow(label = "Internet Status", isGood = isInternetConnected, trueText = "Online", falseText = "Offline")
                Spacer(modifier = Modifier.height(8.dp))
                
                DiagnosticRow(label = "USB Serial Port", isGood = isUsbConnected, trueText = "Connected", falseText = "Disconnected")
                Spacer(modifier = Modifier.height(8.dp))

                DiagnosticRow(label = "Socket Server", isGood = socketConnected, trueText = "Connected", falseText = "Disconnected")
                Spacer(modifier = Modifier.height(8.dp))

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFFE2E8F0))
                
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
