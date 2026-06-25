package com.example.playerapp.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.playerapp.ui.KioskImage
import com.example.playerapp.R
import com.example.playerapp.data.Repository
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import java.io.File
import com.example.playerapp.utils.LogoVisibilityStorage

enum class F1Screen {
    QR,
    LOADING,
    BMI,
    PROCESSING,
    FORTUNE
}

@Composable
fun BMIModal(
    isVisible: Boolean,
    bmiData: Map<String, Any>?,
    flowType: String?,
    onDismiss: () -> Unit,
    forceScreen: F1Screen? = null, // Dev only: Force a specific screen
    onScreenChange: ((F1Screen) -> Unit)? = null, // Callback when screen changes
    drawerOffset: Int = 0, // Offset to move modal left when drawer is visible (in dp)
    allowDismissOnOverlay: Boolean = false // Allow dismissing by clicking overlay (for dev panel)
) {
    // Don't render Dialog if not visible or missing required data
    if (!isVisible || bmiData == null || flowType == null) {
        println("DEBUG: BMIModal - Not rendering: isVisible=$isVisible, bmiData=${if (bmiData != null) "present" else "null"}, flowType=$flowType")
        return
    }
    
    // Check bmiData with remember to ensure recomputation when data changes
    // Use bmiData hashcode as key to force recomputation when data changes
    val bmiDataHash = bmiData?.hashCode() ?: 0
    val hasUser = remember(bmiData, bmiDataHash) { 
        val user = bmiData?.get("user")
        val hasUserKey = bmiData?.containsKey("user") == true
        val userNotNull = user != null
        val userIsValid = user is Map<*, *> || (user != null && user.toString() != "null" && user.toString().isNotBlank())
        val result = hasUserKey && userNotNull && userIsValid
        println("DEBUG: BMIModal - hasUser check:")
        println("DEBUG: BMIModal -   bmiData hash: $bmiDataHash")
        println("DEBUG: BMIModal -   has 'user' key: $hasUserKey")
        println("DEBUG: BMIModal -   user not null: $userNotNull")
        println("DEBUG: BMIModal -   user is valid: $userIsValid")
        println("DEBUG: BMIModal -   user value: $user")
        println("DEBUG: BMIModal -   user type: ${user?.javaClass?.simpleName}")
        println("DEBUG: BMIModal -   result: $result")
        result
    }
    val hasWebUrl = remember(bmiData) { 
        bmiData?.containsKey("webUrl") == true 
    }
    val fortuneMsg = remember(bmiData) {
        bmiData?.get("fortuneMessage") as? String
    }
    val fortune = remember(bmiData) {
        bmiData?.get("fortune") as? String
    }
    val hasFortune = remember(fortuneMsg, fortune) {
        !fortuneMsg.isNullOrBlank() || !fortune.isNullOrBlank()
    }
    
    println("DEBUG: BMIModal - Data check: hasUser=$hasUser, hasWebUrl=$hasWebUrl, hasFortune=$hasFortune")
    println("DEBUG: BMIModal - bmiData keys: ${bmiData?.keys?.joinToString(", ")}")
    if (bmiData?.containsKey("user") == true) {
        println("DEBUG: BMIModal - user object: ${bmiData["user"]}")
        println("DEBUG: BMIModal - user type: ${bmiData["user"]?.javaClass?.simpleName}")
    }
    
    // State Machine - Clean, manageable state management
    // Payment Flow: QR → BMI (7s) → Fortune (7s)
    data class ScreenState(
        val currentScreen: F1Screen,
        val bmiShownTime: Long? = null,
        val fortuneShownTime: Long? = null
    )
    
    // Persistent state machine - only resets when modal closes
    var screenState by remember(isVisible) {
        val initialScreen = when {
            forceScreen != null -> forceScreen // Payment received - go directly to BMI
            bmiData?.containsKey("user") == true && bmiData["user"] != null -> F1Screen.BMI // User data present - show BMI
            else -> F1Screen.QR // Default - show QR code
        }
        println("DEBUG: BMIModal - [STATE_MACHINE] Initializing state: $initialScreen (isVisible: $isVisible, forceScreen: $forceScreen)")
        mutableStateOf(ScreenState(currentScreen = initialScreen))
    }
    
    // Extract current values for easier access
    val currentScreen = screenState.currentScreen
    val bmiShownTime = screenState.bmiShownTime
    val fortuneShownTime = screenState.fortuneShownTime
    
    // State Machine: Handle payment flow (forceScreen = BMI) - Priority 1
    // Payment Flow: Close QR → Show BMI immediately
    SideEffect {
        if (forceScreen == F1Screen.BMI && currentScreen != F1Screen.BMI) {
            println("DEBUG: BMIModal - [PAYMENT_FLOW] 🎯 Payment received - Closing QR, showing BMI screen")
            screenState = screenState.copy(
                currentScreen = F1Screen.BMI,
                bmiShownTime = System.currentTimeMillis()
            )
            println("DEBUG: BMIModal - [PAYMENT_FLOW] ✅ Transitioned to BMI screen (payment flow started)")
        }
    }
    
    // Backup LaunchedEffect for payment flow
    LaunchedEffect(forceScreen) {
        if (forceScreen == F1Screen.BMI && currentScreen != F1Screen.BMI) {
            println("DEBUG: BMIModal - [PAYMENT_FLOW] 🎯 Payment flow LaunchedEffect - transitioning to BMI")
            screenState = screenState.copy(
                currentScreen = F1Screen.BMI,
                bmiShownTime = System.currentTimeMillis()
            )
        }
    }
    
    // Notify parent of screen changes
    LaunchedEffect(currentScreen, isVisible) {
        if (isVisible) {
            onScreenChange?.invoke(currentScreen)
        }
    }
    
    // Track modal open time and last activity time for timeout
    var modalOpenTime by remember(isVisible) { 
        mutableStateOf(if (isVisible) System.currentTimeMillis() else null)
    }
    var lastActivityTime by remember(isVisible) { 
        mutableStateOf(System.currentTimeMillis()) 
    }
    
    // Track modal open time when it becomes visible
    LaunchedEffect(isVisible) {
        if (isVisible) {
            modalOpenTime = System.currentTimeMillis()
            lastActivityTime = System.currentTimeMillis()
        } else {
            modalOpenTime = null
        }
    }
    
    // State Machine: Payment Flow - BMI → Fortune (after 12 seconds on BMI)
    // Extended from 8s to 12s as per requirements
    LaunchedEffect(currentScreen, bmiShownTime, flowType, isVisible, forceScreen) {
        if (!isVisible || (flowType != "F1" && flowType != "F2")) return@LaunchedEffect
        
        // Payment flow: BMI screen shown → wait 12 seconds → Fortune screen
        if (currentScreen == F1Screen.BMI && bmiShownTime != null) {
            val elapsed = System.currentTimeMillis() - bmiShownTime!!
            val remaining = 12000L - elapsed  // Changed from 8s to 12s
            
            if (remaining > 0) {
                println("DEBUG: BMIModal - [PAYMENT_FLOW] ⏱️ On BMI screen, waiting ${remaining}ms before Fortune")
                kotlinx.coroutines.delay(remaining)
            }
            
            // After 12 seconds, transition to Fortune
            val currentState = screenState
            if (currentState.currentScreen == F1Screen.BMI && currentState.fortuneShownTime == null) {
                println("DEBUG: BMIModal - [PAYMENT_FLOW] ✅✅✅ 12 seconds elapsed - Transitioning BMI → FORTUNE")
                screenState = screenState.copy(
                    currentScreen = F1Screen.FORTUNE,
                    fortuneShownTime = System.currentTimeMillis()
                )
                lastActivityTime = System.currentTimeMillis()
                println("DEBUG: BMIModal - [PAYMENT_FLOW] ✅ Successfully navigated to FORTUNE screen!")
            }
        }
    }
    
    // Periodic check removed - transition logic is now handled by the dedicated LaunchedEffects above
    // The BMI -> Fortune transition happens after exactly 12 seconds on BMI screen
    
    // Fortune screen stays open until weight < 5kg (flow termination handled by weight monitoring in MainActivity)
    
    // Timeout effect removed - modal stays open until weight < 5kg
    // Flow termination is handled by weight monitoring in MainActivity
    
    // Animation state for modal appearance
    // Initialize to 0 so animation starts from invisible when dialog first appears
    var initialAnimation by remember(isVisible) { mutableStateOf(!isVisible) }
    
    LaunchedEffect(isVisible) {
        if (isVisible) {
            initialAnimation = false // Start animation when visible
        }
    }
    
    val modalAlpha by animateFloatAsState(
        targetValue = if (isVisible && !initialAnimation) 1f else 0f,
        animationSpec = tween(
            durationMillis = 400,
            easing = FastOutSlowInEasing
        ),
        label = "modalAlpha"
    )
    
    val modalScale by animateFloatAsState(
        targetValue = if (isVisible && !initialAnimation) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "modalScale"
    )
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = allowDismissOnOverlay, // Allow overlay dismissal in dev mode
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // Completely transparent background - video shows through
        val configuration = LocalConfiguration.current
        val screenWidthDp = configuration.screenWidthDp.dp
        
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .alpha(modalAlpha),
            horizontalArrangement = if (drawerOffset > 0) Arrangement.Start else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Only the card is visible, background is transparent.
            // Give the QR screen more vertical room so the QR and instructions fit cleanly.
            val isBMIScreen = currentScreen == F1Screen.BMI
            val isQRScreen = currentScreen == F1Screen.QR
            // Calculate available width: screen width minus drawer width (300dp)
            val availableWidth = if (drawerOffset > 0) {
                screenWidthDp - drawerOffset.dp
            } else {
                if (isQRScreen) 540.dp else 480.dp
            }
            
            Card(
                modifier = Modifier
                    .width(availableWidth)
                    .then(
                        if (isBMIScreen) {
                            Modifier.heightIn(max = 900.dp) // Max height for BMI screen
                        } else if (isQRScreen) {
                            Modifier.heightIn(min = 560.dp, max = 640.dp)
                        } else {
                            Modifier.heightIn(max = 520.dp)
                        }
                    )
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .graphicsLayer {
                        scaleX = modalScale
                        scaleY = modalScale
                    },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (currentScreen == F1Screen.FORTUNE) Color.White else Color.White.copy(alpha = 0.95f) // Solid on fortune, semi-transparent on others
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .then(
                            if (isBMIScreen) {
                                Modifier.fillMaxWidth() // Only fill width for BMI screen
                            } else {
                                Modifier.fillMaxSize() // Fill both for other screens
                            }
                        )
                        .padding(24.dp)
                ) {
                    // Close button (only in dev mode)
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
                    
                    // Content with smooth transitions
                    Box(
                        modifier = Modifier
                            .then(
                                if (isBMIScreen) {
                                    Modifier.fillMaxWidth().padding(top = if (allowDismissOnOverlay) 8.dp else 0.dp)
                                } else {
                                    Modifier.fillMaxSize()
                                }
                            ),
                        contentAlignment = if (isBMIScreen) Alignment.TopCenter else Alignment.Center
                    ) {
                        // Log current screen state before rendering
                        println("DEBUG: BMIModal - [RENDER] About to render screen: $currentScreen")
                        println("DEBUG: BMIModal - [RENDER] bmiShownTime: $bmiShownTime, fortuneShownTime: $fortuneShownTime")
                        
                        // Use AnimatedContent for smooth screen transitions
                        AnimatedContent(
                            targetState = currentScreen,
                            transitionSpec = {
                                // Slide and fade transition
                                if (targetState.ordinal > initialState.ordinal) {
                                    // Forward transition (e.g., QR -> BMI -> Fortune)
                                    slideInHorizontally(
                                        initialOffsetX = { fullWidth -> fullWidth },
                                        animationSpec = tween(
                                            durationMillis = 400,
                                            easing = FastOutSlowInEasing
                                        )
                                    ) + fadeIn(
                                        animationSpec = tween(
                                            durationMillis = 400,
                                            easing = FastOutSlowInEasing
                                        )
                                    ) togetherWith slideOutHorizontally(
                                        targetOffsetX = { fullWidth -> -fullWidth },
                                        animationSpec = tween(
                                            durationMillis = 400,
                                            easing = FastOutSlowInEasing
                                        )
                                    ) + fadeOut(
                                        animationSpec = tween(
                                            durationMillis = 400,
                                            easing = FastOutSlowInEasing
                                        )
                                    )
                                } else {
                                    // Backward transition (shouldn't happen in normal flow, but handle gracefully)
                                    slideInHorizontally(
                                        initialOffsetX = { fullWidth -> -fullWidth },
                                        animationSpec = tween(
                                            durationMillis = 400,
                                            easing = FastOutSlowInEasing
                                        )
                                    ) + fadeIn(
                                        animationSpec = tween(
                                            durationMillis = 400,
                                            easing = FastOutSlowInEasing
                                        )
                                    ) togetherWith slideOutHorizontally(
                                        targetOffsetX = { fullWidth -> fullWidth },
                                        animationSpec = tween(
                                            durationMillis = 400,
                                            easing = FastOutSlowInEasing
                                        )
                                    ) + fadeOut(
                                        animationSpec = tween(
                                            durationMillis = 400,
                                            easing = FastOutSlowInEasing
                                        )
                                    )
                                }
                            },
                            label = "screenTransition"
                        ) { screen ->
                            when (screen) {
                                F1Screen.QR -> {
                                    println("DEBUG: BMIModal - [RENDER] ✅ Rendering QR screen")
                                    QRCodeScreen(bmiData)
                                }
                                F1Screen.LOADING -> {
                                    println("DEBUG: BMIModal - [RENDER] ✅ Rendering LOADING screen")
                                    LoadingScreen(label = "Processing payment...")
                                }
                                F1Screen.BMI -> {
                                    println("DEBUG: BMIModal - [RENDER] ✅✅✅ RENDERING BMI SCREEN")
                                    println("DEBUG: BMIModal - [RENDER] BMI data available: ${bmiData != null}")
                                    if (bmiData != null) {
                                        println("DEBUG: BMIModal - [RENDER] BMI data keys: ${bmiData.keys.joinToString(", ")}")
                                    }
                                    BMIScreen(bmiData)
                                }
                                F1Screen.PROCESSING -> {
                                    println("DEBUG: BMIModal - [RENDER] ✅ Rendering PROCESSING screen")
                                    ProcessingScreen()
                                }
                                F1Screen.FORTUNE -> {
                                    println("DEBUG: BMIModal - [RENDER] ✅ Rendering FORTUNE screen")
                                    FortuneScreen(bmiData)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun QRCodeScreen(bmiData: Map<String, Any>?, isSaveCloudOnly: Boolean = false) {
    val steps = remember(isSaveCloudOnly) {
        if (isSaveCloudOnly) {
            listOf(
                "Scan the QR code using your phone",
                "Log in or sign up to your account",
                "Save your measurements to the cloud",
                "Track your history and progress easily"
            )
        } else {
            listOf(
                "Scan the QR code using your phone",
                "Sign in to your Well2Day account",
                "Make a quick and secure payment",
                "Access your BMI insights and track progress"
            )
        }
    }
    val webUrl: String? = remember(bmiData) {
        bmiData?.get("webUrl") as? String
    }

    if (webUrl.isNullOrBlank()) {
        LoadingModal(
            label = "Generating QR code...",
            showImage = true
        )
        return
    }

    val qrBitmap: Bitmap? = remember(webUrl) {
        webUrl?.let { url ->
            try {
                generateQrBitmap(url, 210, 210)
            } catch (e: Exception) {
                null
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        qrBitmap?.let { bitmap: Bitmap ->
            Box(
                modifier = Modifier
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White,
                                Color(0xFFF8FAFC)
                            )
                        ),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(10.dp)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.size(180.dp)
                )
            }
        } ?: run {
            Box(
                modifier = Modifier
                    .background(
                        color = Color(0xFFFEE2E2),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "QR Code Error",
                        color = Color(0xFFDC2626),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Unable to generate QR code",
                        color = Color(0xFF7F1D1D),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isSaveCloudOnly) "Save to Cloud" else "Scan To Continue",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1F2937),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = if (isSaveCloudOnly) "Scan the QR code to save your results to the cloud" else "Secure payment and instant access to your Well2Day results",
            fontSize = 14.sp,
            color = Color(0xFF6B7280),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF8FAFC),
                            Color(0xFFF1F5F9)
                        )
                    ),
                    shape = RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            steps.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF111827),
                                        Color(0xFF374151)
                                    )
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = step,
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF334155),
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

/**
 * Reusable Loading Modal Component
 * Displays animated GIF with dynamic label
 */
@Composable
fun LoadingModal(
    label: String = "Processing...",
    showImage: Boolean = false,
    imageResId: Int? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth()
    ) {
        val displayImageRes = imageResId ?: if (showImage) R.drawable.qrloadimg else null
        
        if (displayImageRes != null) {
            Image(
                painter = painterResource(id = displayImageRes),
                contentDescription = "Loading Display Image",
                modifier = Modifier
                    .size(220.dp)
                    .padding(bottom = 24.dp),
                contentScale = ContentScale.Fit
            )
        }
        
        // Dynamic label — enlarged for TV viewing
        Text(
            text = label,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF1F2937),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun LoadingScreen(label: String = "Processing payment...") {
    LoadingModal(label = label)
}


@Composable
fun RowScope.MetricCard(
    label: String,
    value: String,
    unit: String
) {
    Card(
        modifier = Modifier
            .weight(1f)
            .height(120.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF3F4F6)
        ),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = Color(0xFF6B7280),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = value,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111827),
                textAlign = TextAlign.Center
            )

            Text(
                text = unit,
                fontSize = 12.sp,
                color = Color(0xFF6B7280),
                textAlign = TextAlign.Center
            )
        }
    }
}



@Composable
fun BMIScreen(bmiData: Map<String, Any>?) {
    val context = LocalContext.current
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
    val screenId = remember(bmiData) {
        bmiData?.get("screenId") as? String
    }
    val healthTip = remember(bmiData) {
        (bmiData?.get("healthTip") as? String)?.takeIf { it.isNotBlank() } ?: ""
    }
    val isHeightValid = remember(bmiData) {
        bmiData?.get("isHeightValid") as? Boolean ?: (height != null && height.toDouble() > 0.0)
    }
    
    // Logo state (local file path, loading, error)
    var logoPath by remember { mutableStateOf<String?>(null) }
    var logoLoading by remember { mutableStateOf(false) }
    var logoError by remember { mutableStateOf<String?>(null) }
    var showLogo by remember { mutableStateOf(LogoVisibilityStorage.getShowLogo(context)) }
    
    // Fetch and download logo when screen is displayed
    LaunchedEffect(screenId) {
        if (!screenId.isNullOrBlank()) {
            logoLoading = true
            logoError = null
            
            // Check if cached logo exists first
            val localFile = java.io.File(context.filesDir, "screen-logo-$screenId")
            if (localFile.exists()) {
                logoPath = localFile.absolutePath
                logoLoading = false
            }
            
            // Fetch logo from server
            Repository.getScreenLogo(context, screenId) { success, localPath, error ->
                logoLoading = false
                if (success && !localPath.isNullOrBlank()) {
                    logoPath = localPath
                    logoError = null
                } else {
                    if (logoPath == null) { // Only clear if we don't have a cached version
                        logoPath = null
                    }
                    logoError = error
                }
            }
        } else {
            logoLoading = false
            logoPath = null
            logoError = null
        }
    }
    
    // Watch for logo visibility preference changes (optimized polling)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(300) // Reduced from 500ms to 300ms
            val newShowLogo = LogoVisibilityStorage.getShowLogo(context)
            if (newShowLogo != showLogo) {
                showLogo = newShowLogo
            }
        }
    }

    // Calculate ideal weight range for height (BMI 18.5-24.9)
    val idealWeightRange = remember(height) {
        height?.let { h ->
            val heightM = h.toDouble() / 100.0
            val minWeight = 18.5 * (heightM * heightM)
            val maxWeight = 24.9 * (heightM * heightM)
            Pair(
                String.format("%.1f", minWeight).toDouble(),
                String.format("%.1f", maxWeight).toDouble()
            )
        }
    }

    // Calculate recommended daily water consumption
    val waterRecommendation = remember(weight) {
        weight?.let { w ->
            val weightKg = w.toDouble()
            val waterLiters = weightKg * 0.033 // 33ml per kg
            val waterMl = (weightKg * 33).toInt()
            val cups = (waterMl / 250.0).toInt()
            Triple(
                String.format("%.1f", waterLiters).toDouble(),
                waterMl,
                cups
            )
        }
    }

    val bmiColor = remember(bmi) {
        when {
            bmi == null -> Color(0xFF6B7280)
            bmi < 18.5 -> Color(0xFF3B82F6)
            bmi < 25 -> Color(0xFF10B981)
            bmi < 30 -> Color(0xFFF59E0B)
            else -> Color(0xFFEF4444)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 900.dp)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Logo display (if available, from local storage, and if enabled in dev panel)
        if (showLogo) {
            Box(
                modifier = Modifier
                    .heightIn(max = 60.dp)
                    .widthIn(max = 180.dp)
                    .padding(bottom = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    logoLoading && logoPath == null -> {
                        // Show nothing while fetching
                    }
                    logoPath != null -> {
                        // Show logo image
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(File(logoPath!!))
                                .crossfade(true)
                                .build(),
                            contentDescription = "Screen Logo",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            onError = {
                                logoError = "Failed to load image"
                                logoPath = null
                            }
                        )
                    }
                    logoError != null -> {
                        // Show error state (silently fail - don't show error UI)
                        // Logo just won't be displayed
                    }
                    else -> {
                        // No logo available (don't show anything)
                    }
                }
            }
        }
        
        // Title — enlarged for TV viewing
        Text(
            text = "Your Health Results",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF111827),
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
        )
        
        // Body Mass Index
       
        
        // Body Measurements Section
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Body Measurements",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF374151),
                modifier = Modifier.padding(bottom = 10.dp)
            )
            
            // Weight and Height side by side
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Weight
                if (weight != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Weight",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF6B7280)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = String.format("%.1f", weight.toFloat()),
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF111827)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "kg",
                            fontSize = 15.sp,
                            color = Color(0xFF6B7280)
                        )
                    }
                }
                
                // Divider
                if (weight != null && height != null && isHeightValid) {
                    VerticalDivider(
                        modifier = Modifier.height(45.dp),
                        color = Color(0xFFE5E7EB),
                        thickness = 1.dp
                    )
                }
                
                // Height
                if (height != null && isHeightValid) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Height",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF6B7280)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = String.format("%.0f", height.toFloat()),
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF111827)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "cm",
                            fontSize = 15.sp,
                            color = Color(0xFF6B7280)
                        )
                    }
                }
            }
        }

        if (isHeightValid && bmi != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Body Mass Index (BMI)",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF6B7280)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = String.format("%.1f", bmi),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = bmiColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = category,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF374151)
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Weight Measurement Complete",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF374151)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Height unavailable, so BMI was not calculated.",
                    fontSize = 14.sp,
                    color = Color(0xFF6B7280)
                )
            }
        }

        // Health tip (from local JSON, by BMI category)
        if (healthTip.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFECFDF5) // Light green tint for health tip
                ),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Health tip for you",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF6B7280)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = healthTip,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF4B5563),
                        lineHeight = 20.sp
                    )
                }
            }
        }

        // Water Consumption Level Suggestion - Compact Professional Display
        waterRecommendation?.let { (waterLiters, waterMl, cups) ->
            if (weight != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFF0F9FF) // Light blue background
                    ),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                // Try to load the water image
                                Image(
                                    painter = painterResource(id = R.drawable.water),
                                    contentDescription = "Water Intake",
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Water Intake",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF374151)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${String.format("%.1f", waterLiters)}L / $waterMl ml",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF06B6D4)
                                )
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = "$cups cups",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF6B7280)
                            )
                            Text(
                                text = "per day",
                                fontSize = 11.sp,
                                color = Color(0xFF9CA3AF)
                            )
                        }
                    }
                }
            }
        }

        // Ideal Weight Range Suggestion - Compact Professional Display
        idealWeightRange?.let { (minWeight, maxWeight) ->
            if (height != null && isHeightValid) {
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFF0F4FF) // Light blue-purple background
                    ),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                // Try to load the weight image
                                Image(
                                    painter = painterResource(id = R.drawable.weigth),
                                    contentDescription = "Ideal Weight",
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Ideal Weight Range",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF374151)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${String.format("%.1f", minWeight)} – ${String.format("%.1f", maxWeight)} kg",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF3B82F6)
                                )
                            }
                        }
                        Text(
                            text = "H: ${String.format("%.0f", height.toFloat())}cm",
                            fontSize = 11.sp,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                }
            }
        }
        
        // Health Tips Section - Show only 1 random tip
        var healthTips by remember { mutableStateOf<List<String>?>(null) }
        var healthTipsLoading by remember { mutableStateOf(false) }
        var healthTipsError by remember { mutableStateOf<String?>(null) }
        var randomTip by remember { mutableStateOf<String?>(null) }
        
        LaunchedEffect(category, isHeightValid) {
            if (isHeightValid && category.isNotBlank()) {
                healthTipsLoading = true
                healthTipsError = null
                Repository.getHealthTips(category) { success, tips, error ->
                    healthTipsLoading = false
                    if (success && tips != null && tips.isNotEmpty()) {
                        // Store all tips
                        healthTips = tips
                        // Pick one random tip
                        randomTip = tips.random()
                    } else {
                        healthTipsError = error
                        // Don't show anything if health tips fail to load
                        println("DEBUG: BMIScreen - Health tips failed to load: $error")
                    }
                }
            } else {
                healthTips = null
                randomTip = null
            }
        }
        
        // Show single random tip only if successfully loaded
        if (!healthTipsLoading && randomTip != null && healthTipsError == null) {
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF7ED) // Light orange/amber background
                ),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Health Tip",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF374151),
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "• ",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF59E0B)
                        )
                        Text(
                            text = randomTip ?: "",
                            fontSize = 13.sp,
                            color = Color(0xFF374151),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun ProcessingScreen() {
    // Processing animation: always shows for 5 seconds (managed by caller)
    LoadingModal(
        label = "Processing Your Measurements",
        imageResId = R.drawable.measureloadimg
    )
}

@Composable
fun FortuneScreen(bmiData: Map<String, Any>?) {
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
        (bmiData?.get("healthTip") as? String)?.takeIf { it.isNotBlank() } ?: ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Fortune cookie icon/decoration
            Image(
                painter = painterResource(id = R.drawable.fortune),
                contentDescription = "Fortune Cookie",
                modifier = Modifier.size(80.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Title — enlarged for TV
            Text(
                text = "Your Fortune",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Divider
            Divider(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .padding(vertical = 6.dp),
                color = Color(0xFFE5E7EB),
                thickness = 2.dp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Fortune message — enlarged for TV
            Text(
                text = fortuneMessage,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF374151),
                textAlign = TextAlign.Center,
                lineHeight = 30.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

//            if (healthTip.isNotBlank()) {
//                Spacer(modifier = Modifier.height(20.dp))
//                Divider(
//                    modifier = Modifier
//                        .fillMaxWidth(0.5f)
//                        .padding(vertical = 4.dp),
//                    color = Color(0xFFE5E7EB),
//                    thickness = 1.dp
//                )
//                Spacer(modifier = Modifier.height(12.dp))
//                Text(
//                    text = "Health tip for you",
//                    fontSize = 12.sp,
//                    fontWeight = FontWeight.SemiBold,
//                    color = Color(0xFF6B7280),
//                    textAlign = TextAlign.Center
//                )
//                Spacer(modifier = Modifier.height(6.dp))
//                Text(
//                    text = healthTip,
//                    fontSize = 14.sp,
//                    fontWeight = FontWeight.Normal,
//                    color = Color(0xFF4B5563),
//                    textAlign = TextAlign.Center,
//                    lineHeight = 20.sp,
//                    modifier = Modifier.padding(horizontal = 24.dp)
//                )
//            }
//
            Spacer(modifier = Modifier.height(20.dp))

            // Decorative element
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = Color(0xFF6366F1).copy(alpha = 0.3f),
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                }
            }
        }
    }
}

// Optimized: QR code generation with error handling
fun generateQrBitmap(text: String, width: Int, height: Int): Bitmap? {
    return try {
        val hints = hashMapOf<EncodeHintType, Any>().apply {
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H)
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.MARGIN, 1)
        }
        
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height, hints)
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

// ========== SEPARATE MODAL COMPONENTS ==========
// These are independent modals that can be shown/hidden separately
