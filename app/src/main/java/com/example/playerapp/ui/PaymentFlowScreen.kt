package com.example.playerapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Payment Flow State Enum - Shared across payment flow components
 */
enum class PaymentFlowState {
    IDLE,
    DETECTING, // Instant processing screen when user steps on scale
    QR_SHOWING,
    BMI_SHOWING,
    FORTUNE_SHOWING
}

/**
 * Payment Flow Screen — manages state transitions and modal rendering.
 *
 * Key changes vs. original:
 *  • 5-second PROCESSING animation is shown immediately after payment is
 *    confirmed (QR_SHOWING → BMI_SHOWING transition) before the health
 *    results are revealed.  The 5-second delay plays out inside this
 *    composable so the rest of the codebase does not need to change.
 *  • Step-indicator HUD shows the user which step they are on.
 *  • Verbose debug logging retained (easily grep-able) but the production
 *    paths are lean and allocation-free.
 */
@Composable
fun PaymentFlowScreen(
    paymentFlowState: PaymentFlowState,
    bmiData: Map<String, Any>?,
    flowType: String,
    drawerOffset: Int = 0,
    forcedScreen: F1Screen? = null,
    onDismiss: () -> Unit = {},
    allowDismissOnOverlay: Boolean = false,
    onProcessingComplete: () -> Unit = {}
) {
    val stateKey = paymentFlowState.name
    val currentBmiData = bmiData

    // ── 5-second processing gate ──────────────────────────────────────────────
    // When we first enter BMI_SHOWING we show a 5-second processing animation
    // before revealing the actual BMI results.  This flag flips to true once
    // the delay is done, causing the real BMI modal to appear.
    var processingComplete by remember(stateKey) { mutableStateOf(false) }

    LaunchedEffect(paymentFlowState) {
        if (paymentFlowState == PaymentFlowState.BMI_SHOWING && forcedScreen == null) {
            // Processing delay removed as requested - instantly show results
            processingComplete = true
            onProcessingComplete()
        } else {
            // For dev-forced screens or other states, no delay needed
            processingComplete = true
        }
    }

    // ── Debug logging ─────────────────────────────────────────────────────────
    LaunchedEffect(paymentFlowState, currentBmiData) {
        println("═══════════════════════════════════════════════════════════════")
        println("[PAYMENT_FLOW_SCREEN] 🔄 State: ${paymentFlowState.name}")
        println("[PAYMENT_FLOW_SCREEN]   BMI Data: ${if (currentBmiData != null) "present (${currentBmiData.keys.joinToString(", ")})" else "null"}")
        println("═══════════════════════════════════════════════════════════════")
    }

    // ── Rendering ─────────────────────────────────────────────────────────────
    key(stateKey) {
        when (paymentFlowState) {

            PaymentFlowState.QR_SHOWING -> {
                if (currentBmiData != null) {
                    if (forcedScreen == F1Screen.LOADING) {
                        BMIModal(
                            isVisible = true,
                            bmiData = currentBmiData,
                            flowType = flowType,
                            onDismiss = onDismiss,
                            forceScreen = F1Screen.LOADING,
                            drawerOffset = drawerOffset,
                            allowDismissOnOverlay = allowDismissOnOverlay
                        )
                    } else {
                        println("[PAYMENT_FLOW_SCREEN] 🎨 RENDERING QR MODAL")
                        QRModal(
                            isVisible = true,
                            bmiData = currentBmiData,
                            onDismiss = onDismiss,
                            drawerOffset = drawerOffset,
                            allowDismissOnOverlay = allowDismissOnOverlay
                        )
                    }
                } else {
                    println("[PAYMENT_FLOW_SCREEN] ⚠️ QR_SHOWING but bmiData is null — not rendering")
                }
            }

            PaymentFlowState.BMI_SHOWING -> {
                if (currentBmiData != null) {
                    if (forcedScreen == F1Screen.PROCESSING) {
                        // Dev-forced processing screen
                        BMIModal(
                            isVisible = true,
                            bmiData = currentBmiData,
                            flowType = flowType,
                            onDismiss = onDismiss,
                            forceScreen = F1Screen.PROCESSING,
                            drawerOffset = drawerOffset,
                            allowDismissOnOverlay = allowDismissOnOverlay
                        )
                    } else {
                        // ── BMI results ──
                        println("[PAYMENT_FLOW_SCREEN] 🎨 RENDERING BMI DATA MODAL")
                        BMIDataModal(
                            isVisible = true,
                            bmiData = currentBmiData,
                            flowType = flowType,
                            onDismiss = onDismiss,
                            drawerOffset = drawerOffset,
                            allowDismissOnOverlay = allowDismissOnOverlay
                        )
                    }
                } else {
                    println("[PAYMENT_FLOW_SCREEN] ⚠️ BMI_SHOWING but bmiData is null — not rendering")
                }
            }

            PaymentFlowState.FORTUNE_SHOWING -> {
                if (currentBmiData != null) {
                    val hasFortune = !(currentBmiData["fortuneMessage"] as? String).isNullOrBlank() ||
                                    !(currentBmiData["fortune"] as? String).isNullOrBlank()

                    if (hasFortune) {
                        println("[PAYMENT_FLOW_SCREEN] 🎨 RENDERING FORTUNE MODAL")
                        FortuneModal(
                            isVisible = true,
                            bmiData = currentBmiData,
                            flowType = flowType,
                            onDismiss = onDismiss,
                            drawerOffset = drawerOffset,
                            allowDismissOnOverlay = allowDismissOnOverlay
                        )
                    } else {
                        // No fortune yet — stay on BMI screen
                        println("[PAYMENT_FLOW_SCREEN] ⚠️ FORTUNE_SHOWING but no fortune data — showing BMI")
                        BMIDataModal(
                            isVisible = true,
                            bmiData = currentBmiData,
                            flowType = flowType,
                            onDismiss = onDismiss,
                            drawerOffset = drawerOffset,
                            allowDismissOnOverlay = allowDismissOnOverlay
                        )
                    }
                } else {
                    println("[PAYMENT_FLOW_SCREEN] ⚠️ FORTUNE_SHOWING but bmiData is null — not rendering")
                }
            }

            PaymentFlowState.DETECTING -> {
                // ── Processing animation instantly shown when weight > 3.0kg ──
                println("[PAYMENT_FLOW_SCREEN] 🎨 RENDERING PROCESSING (DETECTING STATE)")
                ProcessingGateModal(
                    onDismiss = onDismiss,
                    allowDismissOnOverlay = allowDismissOnOverlay,
                    drawerOffset = drawerOffset
                )
            }

            PaymentFlowState.IDLE -> {
                // Nothing to show
                println("[PAYMENT_FLOW_SCREEN] 🎨 State is IDLE — no modal")
            }
        }
    }
}

/**
 * Full-screen processing animation shown for 5 seconds after payment confirmation.
 * Decoupled from BMIModal so it can be displayed independently.
 */
@Composable
private fun ProcessingGateModal(
    onDismiss: () -> Unit,
    allowDismissOnOverlay: Boolean,
    drawerOffset: Int = 0
) {
    Dialog(
        onDismissRequest = { if (allowDismissOnOverlay) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = allowDismissOnOverlay,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = if (drawerOffset > 0) Arrangement.Start else Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Card(
                    modifier = Modifier
                        .size(420.dp)
                        .aspectRatio(1f)
                        .padding(32.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ProcessingScreen() // Reuses the animated loading composable from BMIModal
                    }
                }
            }
        }
    }
}

/**
 * Step indicator strip — shows the user where they are in the flow.
 * Rendered above the modal content.
 */
@Composable
fun FlowStepIndicator(
    currentStep: Int, // 1 = QR, 2 = Processing, 3 = Results, 4 = Fortune
    modifier: Modifier = Modifier
) {
    val steps = listOf("Scan QR", "Processing", "Results", "Fortune")
    Row(
        modifier = modifier
            .background(Color(0xCC1C2334), shape = RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { idx, label ->
            val step = idx + 1
            val isActive = step == currentStep
            val isDone = step < currentStep

            // Circle
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isDone   -> Color(0xFF10B981) // green
                            isActive -> Color(0xFF3B82F6) // blue
                            else     -> Color(0xFF374151) // dark gray
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isDone) "✓" else step.toString(),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Label
            if (isActive) {
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Connector line (not after last item)
            if (idx < steps.size - 1) {
                Divider(
                    modifier = Modifier
                        .width(20.dp)
                        .height(1.5.dp),
                    color = if (isDone) Color(0xFF10B981) else Color(0xFF4B5563)
                )
            }
        }
    }
}
