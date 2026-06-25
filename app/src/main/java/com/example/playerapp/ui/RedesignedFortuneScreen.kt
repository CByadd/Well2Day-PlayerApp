package com.example.playerapp.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.core.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.playerapp.R

@Composable
fun RedesignedFortuneScreen(
    bmiData: Map<String, Any>?,
    flowType: String,
    onDismiss: () -> Unit = {},
    allowDismissOnOverlay: Boolean = false,
    availableWidth: androidx.compose.ui.unit.Dp
) {
    val isCompact = availableWidth < 450.dp

    val fortuneMessage = remember(bmiData) {
        val msg = bmiData?.get("fortuneMessage") as? String
            ?: bmiData?.get("fortune") as? String
        if (msg.isNullOrBlank()) {
            "Loading your fortune..."
        } else {
            msg
        }
    }
    val healthTip = remember(bmiData) {
        (bmiData?.get("healthTip") as? String)?.takeIf { it.isNotBlank() }
            ?: "Stay consistent, trust the process, and good things will follow."
    }

    val webUrl: String? = remember(bmiData) {
        bmiData?.get("webUrl") as? String
    }

    val qrRes = if (isCompact) 120 else 160
    val qrBitmap: Bitmap? = remember(webUrl, qrRes) {
        webUrl?.let { url ->
            try {
                generateQrBitmap(url, qrRes, qrRes)
            } catch (e: Exception) {
                null
            }
        }
    }

    // Adaptive sizes
    val titleFontSize = if (isCompact) 32.sp else 42.sp
    val subtitleFontSize = if (isCompact) 14.sp else 18.sp
    val cardPadding = if (isCompact) 16.dp else 24.dp
    val insightTitleFontSize = if (isCompact) 22.sp else 28.sp
    val fortuneMsgFontSize = if (isCompact) 18.sp else 24.sp
    val cookieBoxSize = if (isCompact) 100.dp else 140.dp
    val cookieIconSize = if (isCompact) 70.dp else 100.dp
    val dividerHeight = if (isCompact) 120.dp else 160.dp
    val gapSize = if (isCompact) 16.dp else 32.dp
    val healthTipFontSize = if (isCompact) 13.sp else 15.sp
    val starBoxSize = if (isCompact) 32.dp else 40.dp
    val starIconSize = if (isCompact) 18.dp else 24.dp
    val cloudBoxSize = if (isCompact) 60.dp else 80.dp
    val cloudIconSize = if (isCompact) 30.dp else 40.dp
    val cloudTitleSize = if (isCompact) 16.sp else 20.sp
    val cloudSubSize = if (isCompact) 13.sp else 15.sp
    val qrBoxSize = if (isCompact) 90.dp else 120.dp

    // Colors from the design
    val bgColor = Color(0xFFF7F8FA) // Light grayish background
    val darkTextColor = Color(0xFF111827)
    val grayTextColor = Color(0xFF6B7280)
    val primaryTeal = Color(0xFF0D5F6B) // Dark teal for "Your Fortune"
    val orangeIconBg = Color(0xFFFEF3E8) // Light orange background for icons
    val orangeIconTint = Color(0xFFF98B2A) // Orange for cookie/star icons
    val blueIconBg = Color(0xFFEBF3FB) // Light blue for cloud icon bg
    val blueIconTint = Color(0xFF1D5A9C) // Blue for cloud icon

    Box(
        modifier = Modifier
            .width(availableWidth)
            .background(
                color = bgColor,
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(cardPadding)
        ) {
            // Close button if allowed
            if (allowDismissOnOverlay) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Screen Logo if available
            val screenId = bmiData?.get("screenId") as? String
            if (!screenId.isNullOrBlank()) {
                ScreenLogo(
                    screenId = screenId,
                    modifier = Modifier.align(Alignment.Start),
                    maxHeight = if (isCompact) 45.dp else 60.dp,
                    maxWidth = if (isCompact) 140.dp else 180.dp,
                    forceShow = true
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Top Header
            Text(
                text = "Your Fortune",
                fontSize = titleFontSize,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.ExtraBold,
                color = darkTextColor,
                textAlign = TextAlign.Center
            )
            
//            Spacer(modifier = Modifier.height(8.dp))
//
//            Text(
//                text = fortuneMessage,
//                fontSize = subtitleFontSize,
//                fontFamily = ManropeFontFamily,
//                color = grayTextColor,
//                textAlign = TextAlign.Center,
//                lineHeight = if (isCompact) 20.sp else 26.sp,
//                modifier = Modifier.padding(horizontal = gapSize)
//            )

            Spacer(modifier = Modifier.height(if (isCompact) 20.dp else 32.dp))

            // Main Card (Current Insight)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(cardPadding)
                ) {
                    // Top Row of the card
                    Text(
                        text = "CURRENT INSIGHT",
                        fontSize = if (isCompact) 10.sp else 12.sp,
                        fontFamily = ManropeFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = grayTextColor,
                        letterSpacing = 1.5.sp
                    )
                    
//                    Spacer(modifier = Modifier.height(4.dp))
//
//                    Text(
//                        text = "Your Fortune",
//                        fontSize = insightTitleFontSize,
//                        fontFamily = ManropeFontFamily,
//                        fontWeight = FontWeight.Bold,
//                        color = primaryTeal
//                    )

                    Spacer(modifier = Modifier.height(if (isCompact) 16.dp else 24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Animation definitions
                        val infiniteTransition = rememberInfiniteTransition(label = "infinite")
                        val scale by infiniteTransition.animateFloat(
                            initialValue = 0.95f,
                            targetValue = 1.05f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1500, easing = EaseInOutSine),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "scale"
                        )
                        val rotation by infiniteTransition.animateFloat(
                            initialValue = -5f,
                            targetValue = 5f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(2000, easing = EaseInOutSine),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "rotation"
                        )

                        // Left: Fortune Cookie
                        Box(
                            modifier = Modifier
                                .size(cookieBoxSize)
                                .background(orangeIconBg, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.fortune),
                                contentDescription = "Fortune Cookie",
                                modifier = Modifier
                                    .size(cookieIconSize)
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        rotationZ = rotation
                                    ),
                                contentScale = ContentScale.Fit
                            )
                        }

                        Spacer(modifier = Modifier.width(gapSize))

                        // Divider
                        Box(
                            modifier = Modifier
                                .height(dividerHeight)
                                .width(1.dp)
                                .background(Color(0xFFF3F4F6))
                        )

                        Spacer(modifier = Modifier.width(gapSize))

                        // Right: Text Content
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = fortuneMessage,
                                fontSize = fortuneMsgFontSize,
                                fontFamily = ManropeFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = darkTextColor,
                                lineHeight = if (isCompact) 26.sp else 34.sp
                            )
                            
                            Spacer(modifier = Modifier.height(if (isCompact) 16.dp else 24.dp))
                            
                            HorizontalDivider(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFFF3F4F6),
                                thickness = 1.dp
                            )
                            
                            Spacer(modifier = Modifier.height(if (isCompact) 12.dp else 20.dp))
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(starBoxSize)
                                        .background(orangeIconBg, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = android.R.drawable.btn_star_big_on),
                                        contentDescription = "Star",
                                        tint = orangeIconTint,
                                        modifier = Modifier.size(starIconSize)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(12.dp))
                                
                                Text(
                                    text = healthTip,
                                    fontSize = healthTipFontSize,
                                    fontFamily = ManropeFontFamily,
                                    color = grayTextColor,
                                    lineHeight = if (isCompact) 18.sp else 22.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isCompact) 16.dp else 24.dp))

            // Save to Cloud Card (Hidden for F1)
            if (flowType != "F1" && qrBitmap != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(cardPadding),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Cloud Icon
                        Box(
                            modifier = Modifier
                                .size(cloudBoxSize)
                                .background(blueIconBg, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CloudUpload,
                                contentDescription = "Save to Cloud",
                                tint = blueIconTint,
                                modifier = Modifier.size(cloudIconSize)
                            )
                        }

                        Spacer(modifier = Modifier.width(if (isCompact) 16.dp else 24.dp))

                        // Middle: Text
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Save to Cloud",
                                fontSize = cloudTitleSize,
                                fontFamily = ManropeFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = primaryTeal
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Scan the QR code to save\nyour fortune to the cloud.",
                                fontSize = cloudSubSize,
                                fontFamily = ManropeFontFamily,
                                color = grayTextColor,
                                lineHeight = if (isCompact) 18.sp else 22.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(if (isCompact) 16.dp else 24.dp))

                        // Right: QR Code
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E7EB)),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier
                                    .size(qrBoxSize)
                                    .padding(8.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "You Can Step Off from the Machine",
                fontSize = if (isCompact) 13.sp else 15.sp,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                color = grayTextColor,
                lineHeight = if (isCompact) 18.sp else 22.sp
            )
        }
    }
}
