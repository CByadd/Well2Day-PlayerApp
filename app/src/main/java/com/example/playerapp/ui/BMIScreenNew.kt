package com.example.playerapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.playerapp.R
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.example.playerapp.data.Repository
import androidx.compose.ui.text.font.FontStyle


val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_bold, FontWeight.Bold)
)
val LexendFontFamily = FontFamily(
    Font(R.font.lexend_regular, FontWeight.Normal),
    Font(R.font.lexend_bold, FontWeight.Bold)
)
val ManropeFontFamily = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_bold, FontWeight.Bold)
)

@Composable
fun BMIScreenNew(bmiData: Map<String, Any>?) {
    val bmi = remember(bmiData) {
        (bmiData?.get("bmi") as? Number)?.toDouble()
    }
    val category = remember(bmiData) {
        bmiData?.get("category") as? String ?: ""
    }
    val height = remember(bmiData) {
        bmiData?.get("height") as? Number
    }
    val weight = remember(bmiData) {
        bmiData?.get("weight") as? Number
    }
    val healthTip = remember(bmiData) {
        (bmiData?.get("healthTip") as? String)?.takeIf { it.isNotBlank() } ?: ""
    }
    val isHeightValid = remember(bmiData) {
        bmiData?.get("isHeightValid") as? Boolean ?: (height != null && height.toDouble() > 0.0)
    }

    var startAnimation by remember { mutableStateOf(false) }
    LaunchedEffect(bmiData) {
        startAnimation = true
    }

    val animatedBmi by animateFloatAsState(
        targetValue = if (startAnimation) (bmi?.toFloat() ?: 0f) else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow),
        label = "bmiAnim"
    )
    val animatedWeight by animateFloatAsState(
        targetValue = if (startAnimation) (weight?.toFloat() ?: 0f) else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow),
        label = "weightAnim"
    )
    val animatedHeight by animateFloatAsState(
        targetValue = if (startAnimation) (height?.toFloat() ?: 0f) else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow),
        label = "heightAnim"
    )

    // Colors
    val backgroundColor = Color.Transparent
    val textPrimary = Color(0xFF1E293B)
    val textSecondary = Color(0xFF64748B)

    // Calculate ideal weight range
    val idealWeightRange = remember(height) {
        height?.let { h ->
            val heightM = h.toDouble() / 100.0
            val minWeight = 18.5 * (heightM * heightM)
            val maxWeight = 24.9 * (heightM * heightM)
            Pair(
                String.format("%.1f", minWeight),
                String.format("%.0f", maxWeight)
            )
        }
    }

    // Calculate water intake
    val waterRecommendation = remember(weight) {
        weight?.let { w ->
            val weightKg = w.toDouble()
            val waterLiters = weightKg * 0.033
            val waterMl = (weightKg * 33).toInt()
            val cups = (waterMl / 250.0).toInt()
            Triple(
                String.format("%.1f", waterLiters),
                waterMl,
                cups
            )
        }
    }

    // We don't need MaterialTheme typography wrapper if we set fontFamily explicitly on texts
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 8.dp), // Compacting padding
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp) // Compacting spacing
    ) {
            // Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 4.dp, top = 4.dp) // Compacting top padding
            ) {
            Text(
                text = "Your Health Results",
                fontFamily = ManropeFontFamily,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = textPrimary,
                letterSpacing = (-0.5).sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Here is a summary of your recent scan.",
                fontFamily = InterFontFamily,
                fontSize = 16.sp,
                color = textSecondary,
                fontWeight = FontWeight.Medium
            )
        }

            // Top Row: Weight & Height
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
            Box(modifier = Modifier.weight(1f)) {
                MeasurementCard(
                    label = "Weight",
                    value = if (weight != null) String.format("%.1f", animatedWeight) else "--",
                    unit = "kg",
                    pillColor = Color(0xFF2563EB)
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                MeasurementCard(
                    label = "Height",
                    value = if (height != null) String.format("%.0f", animatedHeight) else "--",
                    unit = "cm",
                    pillColor = Color(0xFF2563EB)
                )
            }
        }

        // Main BMI Card
        if (isHeightValid && bmi != null) {
            BMIGradientCard(bmi = animatedBmi.toDouble(), category = category)
        }

            // Middle Row: Water & Ideal Range
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
            Box(modifier = Modifier.weight(1f)) {
                WaterIntakeCard(waterRecommendation)
            }
            Box(modifier = Modifier.weight(1f)) {
                IdealRangeCard(idealWeightRange, height?.let { String.format("%.0f", it.toFloat()) })
            }
        }

        // Bottom Wellness Tip Card
        var healthTips by remember { mutableStateOf<List<String>?>(null) }
        var randomTip by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(category, isHeightValid) {
            if (isHeightValid && category.isNotBlank()) {
                Repository.getHealthTips(category) { success, tips, error ->
                    if (success && tips != null && tips.isNotEmpty()) {
                        healthTips = tips
                        randomTip = tips.random()
                    }
                }
            }
        }

        val displayTip = randomTip ?: healthTip.takeIf { it.isNotBlank() } ?: "Practice safe lifting techniques to prevent back injuries. Keep objects close to your body."

            WellnessTipCard(tipText = displayTip)
            
            Spacer(modifier = Modifier.height(10.dp))
        }
}

@Composable
fun MeasurementCard(label: String, value: String, unit: String, pillColor: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                Box(
                    modifier = Modifier
                        .background(pillColor, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = label,
                        fontFamily = InterFontFamily,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    fontFamily = InterFontFamily,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF1E293B)
                )
                Text(
                    text = " $unit",
                    fontFamily = InterFontFamily,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
fun BMIGradientCard(bmi: Double, category: String) {
    val pillColor = when {
        bmi < 18.5 -> Color(0xFF00B4D8)
        bmi < 25.0 -> Color(0xFF10B981)
        bmi < 30.0 -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }
    
    val pillBgColor = pillColor.copy(alpha = 0.15f)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "CURRENT STATUS",
                        fontFamily = InterFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B),
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Body Mass Index",
                        fontFamily = InterFontFamily,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F766E) // Dark Teal
                    )
                }
                Box(
                    modifier = Modifier
                        .background(pillBgColor, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(pillColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (category.isNotBlank()) category else "Result",
                            fontFamily = InterFontFamily,
                            color = pillColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format("%.1f", bmi),
                    fontFamily = InterFontFamily,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF0F766E),
                    letterSpacing = (-2).sp
                )
                Text(
                    text = " kg/m²",
                    fontFamily = InterFontFamily,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Labels Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("UNDERWEIGHT", fontFamily = InterFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                Text("NORMAL", fontFamily = InterFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                Text("OVERWEIGHT", fontFamily = InterFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                Text("OBESE", fontFamily = InterFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Progress Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val gradient = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF67E8F9), // Light Teal
                            Color(0xFF0D9488), // Teal
                            Color(0xFF78350F), // Brownish
                            Color(0xFFB91C1C)  // Red
                        )
                    )
                    // Draw animated filling bar
                    val minBmi = 15.0
                    val maxBmi = 40.0
                    val clampedBmi = bmi.coerceIn(minBmi, maxBmi)
                    val progress = ((clampedBmi - minBmi) / (maxBmi - minBmi)).toFloat()
                    val indicatorX = size.width * progress
                    
                    // Draw background track (light gray)
                    drawRoundRect(
                        color = Color(0xFFF1F5F9),
                        size = size,
                        cornerRadius = CornerRadius(8.dp.toPx())
                    )
                    
                    // Draw filled gradient area
                    if (indicatorX > 0) {
                        drawRoundRect(
                            brush = gradient,
                            size = Size(indicatorX, size.height),
                            cornerRadius = CornerRadius(8.dp.toPx())
                        )
                    }
                    
                    // Draw indicator line
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(indicatorX - 2.dp.toPx(), 0f),
                        size = Size(4.dp.toPx(), size.height),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            val summaryText = when {
                bmi < 18.5 -> "Your BMI suggests you are underweight. Consider consulting a healthcare provider or a dietitian to help build a healthy plan for weight gain."
                bmi < 25.0 -> "Your BMI is in the normal range. Maintaining a healthy diet and regular physical activity will help you sustain this."
                bmi < 30.0 -> "Your BMI suggests a focus on cardiovascular health. We've updated your daily step goal to help bridge the gap to the overweight threshold."
                else -> "Your BMI suggests you are in the obese range. We recommend consulting a healthcare provider for personalized advice."
            }
            
            Text(
                text = summaryText,
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                color = Color(0xFF475569),
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
fun WaterIntakeCard(waterRecommendation: Triple<String, Int, Int>?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                .padding(12.dp) // Reduced padding for compactness
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF0284C7), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_drop),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Water Intake",
                        fontFamily = LexendFontFamily,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp) // Tighter spacing
            ) {
                // Glass tube image
                Image(
                    painter = painterResource(id = R.drawable.ic_glass_tube),
                    contentDescription = "Glass Tube",
                    modifier = Modifier.size(28.dp),
                    contentScale = ContentScale.FillHeight
                )
                
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${waterRecommendation?.first ?: "--"}",
                            fontFamily = LexendFontFamily,
                            fontSize = 14.sp, // Smaller font
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        )
                        Text(
                            text = " Liters",
                            fontFamily = LexendFontFamily,
                            fontSize = 12.sp, // Smaller unit font
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1E293B),
                           
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${waterRecommendation?.second ?: "--"} ml",
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp, // Smaller text
                        color = Color(0xFF64748B),
                        fontWeight = FontWeight.Medium
                    )
//                    Spacer(modifier = Modifier.height(2.dp))
//                    Text(
//                        text = "${waterRecommendation?.third ?: "--"} cups/day",
//                        fontFamily = LexendFontFamily,
//                        fontSize = 12.sp, // Smaller text
//                        fontWeight = FontWeight.Bold,
//                        color = Color(0xFF0284C7)
//                    )
                }
            }
        }
    }
}

@Composable
fun IdealRangeCard(idealWeightRange: Pair<String, String>?, heightStr: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                .padding(12.dp) // Reduced padding
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF0284C7), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_ideal_range),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Ideal Range",
                            fontFamily = LexendFontFamily,
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Icon(
                    painter = painterResource(id = R.drawable.ic_heart),
                    contentDescription = null,
                    tint = Color.Unspecified, // Uses actual color in SVG
                    modifier = Modifier.size(16.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp) // Tighter spacing
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_weight_hourglass),
                    contentDescription = "Weight Hourglass",
                    modifier = Modifier.size(24.dp),
                    contentScale = ContentScale.Fit
                )
                
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${idealWeightRange?.first ?: "--"}–${idealWeightRange?.second ?: "--"}",
                            fontFamily = LexendFontFamily,
                            fontSize = 14.sp, // Smaller font
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        )
                        Text(
                            text = " Kg",
                            fontFamily = LexendFontFamily,
                            fontSize = 12.sp, // Smaller unit font
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1E293B),

                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "for ${heightStr ?: "--"} cm height",
                        fontFamily = InterFontFamily,
                        fontSize = 10.sp, // Smaller text
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun WellnessTipCard(tipText: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F4C5C)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Draw stars on the right
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .size(120.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_star),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    alpha = 0.1f
                )
            }
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
//                Box(
//                    modifier = Modifier
//                        .size(40.dp)
//                        .background(Color.White.copy(alpha = 0.15f), CircleShape),
//                    contentAlignment = Alignment.Center
//                ) {
//                    Icon(
//                        painter = painterResource(id = R.drawable.ic_bulb),
//                        contentDescription = null,
//                        tint = Color.White,
//                        modifier = Modifier.size(22.dp)
//                    )
//                }
//
//                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Daily Wellness Tip",
                    fontFamily = LexendFontFamily,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = tipText,
                    fontFamily = InterFontFamily,
                    fontSize = 15.sp,
                    fontStyle = FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.9f),
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth(0.85f)
                )
            }
        }
    }
}
