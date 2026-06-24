package com.example.playerapp.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.playerapp.R

/**
 * Improved ConfigModal — shows helpful posture/sensor guidance instead of a generic error.
 *
 * Changes:
 * - Replaced "Configuration Error" title with actionable instructions.
 * - Added animated pulse icon so the user knows to act.
 * - Step-by-step instructions for user posture.
 * - Auto-dismiss countdown visible to the user.
 */
@Composable
fun ConfigModal(
    isVisible: Boolean,
    onDismiss: () -> Unit = {}
) {
    if (!isVisible) return

    // Countdown before auto-dismiss (7 seconds, matches the 7s delay in MainActivity)
    var countdown by remember { mutableStateOf(7) }
    LaunchedEffect(isVisible) {
        if (isVisible) {
            countdown = 7
            while (countdown > 0) {
                kotlinx.coroutines.delay(1000)
                countdown--
            }
        }
    }

    // Pulsing animation for the icon
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 36.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Pulsing icon
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(Color(0xFFFFF3CD)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.config),
                            contentDescription = "Posture guidance",
                            modifier = Modifier
                                .size(60.dp)
                                .padding(4.dp)
                        )
                    }

                    // Title
                    Text(
                        text = "Please Adjust Your Position",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F2937),
                        textAlign = TextAlign.Center
                    )

                    // Subtitle
                    Text(
                        text = "The system detected an issue with your reading.\nPlease follow these steps:",
                        fontSize = 17.sp,
                        color = Color(0xFF6B7280),
                        textAlign = TextAlign.Center,
                        lineHeight = 26.sp
                    )

                    // Instruction steps
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFF), shape = RoundedCornerShape(14.dp))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        InstructionStep(number = "1", text = "Stand still in the center of the machine")
                        InstructionStep(number = "2", text = "Keep your back straight and arms at your sides")
                        InstructionStep(number = "3", text = "Look straight ahead — do not bend forward")
                        InstructionStep(number = "4", text = "Step off and back on if the problem persists")
                    }

                    // Countdown
                    Text(
                        text = "Returning automatically in $countdown second${if (countdown != 1) "s" else ""}…",
                        fontSize = 14.sp,
                        color = Color(0xFF9CA3AF),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun InstructionStep(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(0xFF3B82F6)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = text,
            fontSize = 15.sp,
            color = Color(0xFF374151),
            lineHeight = 22.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
