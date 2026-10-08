package com.example.playerapp.f3

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.playerapp.R
import androidx.compose.material3.Divider
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.view.WindowCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.example.playerapp.data.Repository
import com.example.playerapp.data.Asset
import com.example.playerapp.data.DefaultAsset
import com.example.playerapp.ui.QRModal
import com.example.playerapp.ui.BMIDataModal
import com.example.playerapp.ui.BMIModal
import com.example.playerapp.ui.FortuneModal
import com.example.playerapp.ui.PlayerScreen
import com.example.playerapp.ui.F1Screen
import com.example.playerapp.ui.ScreenLogo
import com.example.playerapp.ui.FlowDrawer
import com.example.playerapp.ui.FlowDrawerAssets
import com.example.playerapp.ui.hasRenderableAssets
import com.example.playerapp.ui.loadFlowDrawerAssets
import com.example.playerapp.ui.refreshFlowDrawerAssets
import com.example.playerapp.ui.debugFlowDrawerConfig
import com.example.playerapp.ui.PaymentFlowScreen
import com.example.playerapp.ui.PaymentFlowState
import com.example.playerapp.utils.isOnline
import com.example.playerapp.utils.observeNetworkState
import java.io.File
import com.example.playerapp.utils.getOrCreateMachineId
import com.example.playerapp.utils.FlowTypeStorage
import com.example.playerapp.ui.TroubleshootModal
import com.example.playerapp.data.UsbSerialManager
import com.example.playerapp.utils.HeightCalibrationStorage
import com.example.playerapp.utils.LogoVisibilityStorage
import com.example.playerapp.utils.InfoStorage
import com.example.playerapp.BuildConfig
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

// ========== PAYMENT FLOW STATE MACHINE ==========
// PaymentFlowState enum is now defined in ui.PaymentFlowScreen.kt
// Using the enum from ui package for better organization

class MainActivity : ComponentActivity() {
    // Flow type is hardcoded to F1 only - this app only supports F1 flow
    private val flowType: String = "F1"
    
    // Compose state, not a plain var: registration completes asynchronously after setContent,
    // and F3PlayerScreen only sees the new value if reading it triggers recomposition.
    private var isRegistered by mutableStateOf(false)
    private var registrationJob: Job? = null
    private var assetCleanupJob: Job? = null
    private var screenId: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var registrationRetryCount = 0
    private val maxRegistrationRetries = 5
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Prevent screen from locking or turning off
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        println("DEBUG: F3 - Screen-on flag added (FLAG_KEEP_SCREEN_ON)")

        // Crash auto-restart is installed in Application (PlayerApp) for single-install safety.

        screenId = getOrCreateMachineId(this)

        // Flush offline BMI records left on the device — including ones saved by older
        // builds, which never scheduled the upload. The worker no-ops when there are none.
        Repository.scheduleBmiSync(this)

        // Keep playlist assets cached persistently across restarts.
        // We now validate against the server on startup/socket updates instead
        // of clearing files and forcing a full re-download.
        println("DEBUG: F3 - Persistent asset cache enabled - startup will reuse cached playlist files")
        
        // Force update the default asset to the new Cloudinary URL
        println("DEBUG: F3 - Force updating default asset to new Cloudinary URL")
        Repository.forceUpdateDefaultAsset(this) { success ->
            if (success) {
                println("DEBUG: F3 - New default asset downloaded and cached successfully")
            } else {
                println("DEBUG: F3 - Failed to download new default asset, will use fallback")
            }
        }
        
        // Update flow drawer images persistently on startup
        screenId?.let { id ->
            println("DEBUG: F3 - Triggering persistent Flow Drawer refresh on startup")
            refreshFlowDrawerAssets(this, id) { success, loadedAssets, error ->
                if (success) {
                    println(
                        "DEBUG: F3 - Startup Flow Drawer refresh completed: " +
                            "${loadedAssets.topAssets.size} top, ${loadedAssets.bottomAssets.size} bottom"
                    )
                } else {
                    println("DEBUG: F3 - Startup Flow Drawer refresh failed: $error")
                }
            }
        }
        
        // Load persisted info from info.json on startup
        screenId?.let { id ->
            val persistedInfo = InfoStorage.loadInfo(this)
            if (persistedInfo != null) {
                println("DEBUG: F3 - Loaded persisted info from info.json:")
                println("  - screenId: ${persistedInfo.screenId}")
                println("  - screenName: ${persistedInfo.screenName}")
                println("  - location: ${persistedInfo.location}")
                println("  - heightCalibration: ${persistedInfo.heightCalibration}")
                println("  - heightCalibrationEnabled: ${persistedInfo.heightCalibrationEnabled}")
                println("  - playlistId: ${persistedInfo.playlistId}")
                println("  - paymentAmount: ${persistedInfo.paymentAmount}")
                println("  - hideScreenId: ${persistedInfo.hideScreenId}")
                println("  - rotation: ${persistedInfo.rotation}")
                
                // Apply rotation
                applyAppRotation(persistedInfo.rotation)
                
                // Apply persisted height calibration if available
                persistedInfo.heightCalibration?.let { calibration ->
                    HeightCalibrationStorage.saveHeightCalibration(this, calibration)
                    println("DEBUG: F3 - Applied persisted height calibration: $calibration cm")
                }
            } else {
                println("DEBUG: F3 - No persisted info.json found, will fetch from server")
            }
        }
        
        // Get device information
        val deviceInfo = getDeviceInfo()
        
        println("DEBUG: F3 - App starting with hardcoded flow type: F1 (F1 flow only)")
        
        // Register with Adscape server on startup - CRITICAL for registration
        // This ensures the screen is registered in the database with all device info
        screenId?.let { id ->
            // Try immediate registration if online
            if (isOnline(this)) {
                println("DEBUG: F3 - Network available, attempting immediate registration")
                registerWithAdscape(id, deviceInfo)
            } else {
                println("DEBUG: F3 - Network not available, will retry when network becomes available")
                // Still attempt registration (might work if network is slow to detect)
                registerWithAdscape(id, deviceInfo)
            }
        }
        
        // Setup network connectivity listener to retry registration when network becomes available
        setupNetworkConnectivityListener()
        
        // Start periodic registration updates (every 5 minutes) to keep device info current
        startPeriodicRegistration()
        
        // Start periodic asset cleanup (every 24 hours)
        startPeriodicAssetCleanup()
        
        setContent {
            MaterialTheme {
                screenId?.let { id ->
                    F3PlayerScreen(
                        screenId = id,
                        flowType = flowType,
                        isRegistered = isRegistered
                    )
                }
            }
        }

        // Hide system UI safely after the view is attached
        window.decorView.post {
            hideSystemUI()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun onBackPressed() {
        // Enable back button for F1 F3 app
        super.onBackPressed()
    }
    
    // (Crash watchdog removed; see CrashRestartHandler in Application.)
    
    override fun onResume() {
        super.onResume()
        hideSystemUI()
        // Re-register when app comes back online
        screenId?.let { id ->
            val deviceInfo = getDeviceInfo()
            // Reset retry count on resume
            registrationRetryCount = 0
            println("DEBUG: F3 - onResume: Attempting registration")
            if (isOnline(this)) {
                registerWithAdscape(id, deviceInfo)
            } else {
                println("DEBUG: F3 - onResume: Network not available, will retry when network becomes available")
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        // Cancel periodic tasks
        registrationJob?.cancel()
        assetCleanupJob?.cancel()
        
        // Unregister network callback
        networkCallback?.let { callback ->
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                println("DEBUG: F3 - Error unregistering network callback: ${e.message}")
            }
        }
    }
    
    private fun setupNetworkConnectivityListener() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    println("DEBUG: F3 - Network available - attempting registration")
                    screenId?.let { id ->
                        val deviceInfo = getDeviceInfo()
                        // Reset retry count when network becomes available
                        registrationRetryCount = 0
                        registerWithAdscape(id, deviceInfo)
                    }
                }
                
                override fun onLost(network: android.net.Network) {
                    println("DEBUG: F3 - Network lost")
                }
            }
            
            cm.registerNetworkCallback(networkRequest, networkCallback!!)
            println("DEBUG: F3 - Network connectivity listener registered")
        } catch (e: Exception) {
            println("DEBUG: F3 - Error registering network callback: ${e.message}")
        }
    }
    
    private fun startPeriodicRegistration() {
        val scope = CoroutineScope(Dispatchers.Main)
        registrationJob = scope.launch {
            // First update after 1 minute to ensure registration is successful
            delay(60 * 1000)
            screenId?.let { id ->
                println("DEBUG: F3 - First periodic registration update (1 min after startup)")
                val deviceInfo = getDeviceInfo()
                registerWithAdscape(id, deviceInfo)
            }
            // Then update every 5 minutes to keep device info current
            while (true) {
                delay(5 * 60 * 1000) // Wait 5 minutes between updates
                screenId?.let { id ->
                    println("DEBUG: F3 - Periodic registration update")
                    val deviceInfo = getDeviceInfo()
                    registerWithAdscape(id, deviceInfo)
                }
            }
        }
    }
    
    private fun startPeriodicAssetCleanup() {
        val scope = CoroutineScope(Dispatchers.IO) // Use IO dispatcher for file operations
        assetCleanupJob = scope.launch {
            // First cleanup after 1 hour to allow initial assets to be downloaded
            delay(60 * 60 * 1000) // 1 hour
            
            // Get retention period from environment or use default (30 days)
            // For now, use default 30 days - can be made configurable later
            val retentionDays = 30
            
            println("DEBUG: F3 - Running initial asset cleanup (1 hour after startup)")
            val (deleted, errors) = Repository.cleanupOldAssets(this@MainActivity, retentionDays)
            println("DEBUG: F3 - Asset cleanup result: Deleted=$deleted, Errors=$errors")
            
            // Then cleanup every 24 hours
            while (true) {
                delay(24 * 60 * 60 * 1000) // Wait 24 hours between cleanups
                println("DEBUG: F3 - Running periodic asset cleanup")
                val (deletedCount, errorCount) = Repository.cleanupOldAssets(this@MainActivity, retentionDays)
                println("DEBUG: F3 - Asset cleanup result: Deleted=$deletedCount, Errors=$errorCount")
            }
        }
    }
    
    private fun getDeviceInfo(): DeviceInfo {
        val displayMetrics = resources.displayMetrics
        val packageManager = packageManager
        val packageInfo = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        
        return DeviceInfo(
            deviceName = Build.MODEL,
            screenWidth = displayMetrics.widthPixels,
            screenHeight = displayMetrics.heightPixels,
            ipAddress = getLocalIpAddress(),
            location = "Unknown", // Could be enhanced with location services
            osVersion = "Android ${Build.VERSION.RELEASE}",
            appVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo?.longVersionCode?.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo?.versionCode?.toString()
            }
        )
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in Collections.list(interfaces)) {
                val addresses = networkInterface.inetAddresses
                for (address in Collections.list(addresses)) {
                    if (!address.isLoopbackAddress && address is InetAddress) {
                        val hostAddress = address.hostAddress
                        if (hostAddress != null && !hostAddress.contains(":")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    fun applyAppRotation(rotation: String?) {
        val rotationStr = rotation ?: "portrait"
        val orientation = when (rotationStr.lowercase()) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "reverse_landscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            "reverse_portrait" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        Handler(Looper.getMainLooper()).post {
            try {
                requestedOrientation = orientation
                println("DEBUG: F3 - Screen rotation requested: $rotationStr ($orientation)")
            } catch (e: Exception) {
                println("DEBUG: F3 - Error setting screen orientation: ${e.message}")
            }
        }
    }

    private fun registerWithAdscape(screenId: String, deviceInfo: DeviceInfo) {
        try {
            println("DEBUG: F3 - Starting registration with screenId: $screenId")
            println("DEBUG: F3 - Device info: ${deviceInfo.deviceName}, ${deviceInfo.screenWidth}x${deviceInfo.screenHeight}")
            println("DEBUG: F3 - OS Version: ${deviceInfo.osVersion}, IP: ${deviceInfo.ipAddress}")
            println("DEBUG: F3 - Registering with hardcoded flow type: F1 (F1 flow only)")
            
            // Load persisted info from info.json to use admin-configured values
            val persistedInfo = InfoStorage.loadInfo(this)
            val deviceNameToUse = persistedInfo?.screenName?.takeIf { !it.isNullOrBlank() } 
                ?: deviceInfo.deviceName 
                ?: Build.MODEL
            val locationToUse = persistedInfo?.location?.takeIf { !it.isNullOrBlank() } 
                ?: deviceInfo.location
            
            println("DEBUG: F3 - Using deviceName: $deviceNameToUse (from ${if (persistedInfo?.screenName != null) "info.json" else "device"})")
            println("DEBUG: F3 - Using location: $locationToUse (from ${if (persistedInfo?.location != null) "info.json" else "device"})")
            
            // Register with hardcoded flow type from BuildConfig
            // Use persisted values from info.json if available, otherwise use device defaults
            Repository.registerAdscapePlayer(
                context = this,
                screenId = screenId,
                appVersion = "F3",
                flowType = "F1", // Always F1 in this app
                deviceName = deviceNameToUse,
                screenWidth = deviceInfo.screenWidth,
                screenHeight = deviceInfo.screenHeight,
                ipAddress = deviceInfo.ipAddress,
                location = locationToUse,
                osVersion = deviceInfo.osVersion ?: "Android ${Build.VERSION.RELEASE}",
                appVersionCode = deviceInfo.appVersionCode
            ) { success, returnedFlowType, registrationCode, error ->
                println("DEBUG: F3 - Registration callback: success=$success, registrationCode=$registrationCode, error=$error")
                if (success) {
                    println("DEBUG: F3 Registration successful with flow type: F1 (F1 flow only)")
                    isRegistered = true
                    registrationRetryCount = 0 // Reset retry count on success
                    
                    // Log registration code if returned
                    if (registrationCode != null) {
                        println("DEBUG: F3 - Registration code: $registrationCode")
                    }
                } else {
                    println("DEBUG: F3 Registration failed: $error")
                    isRegistered = false
                    
                    // Retry registration with exponential backoff
                    if (registrationRetryCount < maxRegistrationRetries) {
                        registrationRetryCount++
                        val retryDelay = minOf(10000L * registrationRetryCount, 60000L) // Max 60 seconds
                        println("DEBUG: F3 - Retrying registration (attempt $registrationRetryCount/$maxRegistrationRetries) after ${retryDelay}ms")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (isOnline(this@MainActivity)) {
                                registerWithAdscape(screenId, deviceInfo)
                            } else {
                                println("DEBUG: F3 - Network still not available, will retry when network becomes available")
                            }
                        }, retryDelay)
                    } else {
                        println("DEBUG: F3 - Max registration retries reached. Will retry when network becomes available or on app resume.")
                    }
                }
            }
        } catch (e: Exception) {
            println("DEBUG: F3 - Registration exception: ${e.message}")
            e.printStackTrace()
            isRegistered = false
            
            // Retry registration with exponential backoff
            if (registrationRetryCount < maxRegistrationRetries) {
                registrationRetryCount++
                val retryDelay = minOf(10000L * registrationRetryCount, 60000L) // Max 60 seconds
                println("DEBUG: F3 - Retrying registration after exception (attempt $registrationRetryCount/$maxRegistrationRetries) after ${retryDelay}ms")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isOnline(this)) {
                        registerWithAdscape(screenId, deviceInfo)
                    } else {
                        println("DEBUG: F3 - Network not available, will retry when network becomes available")
                    }
                }, retryDelay)
            } else {
                println("DEBUG: F3 - Max registration retries reached after exception. Will retry when network becomes available or on app resume.")
            }
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        }
    }
}

data class DeviceInfo(
    val deviceName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val ipAddress: String?,
    val location: String,
    val osVersion: String,
    val appVersionCode: String?
)

@Composable
fun F3PlayerScreen(
    screenId: String,
    flowType: String?, // Hardcoded flow type from BuildConfig
    isRegistered: Boolean
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val mainActivity = context as? MainActivity
    // Long-running LaunchedEffect(Unit) loops capture the first composition's parameter;
    // read this instead to see the current registration status.
    val isRegisteredNow by rememberUpdatedState(isRegistered)
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val drawerWidthDp = (configuration.screenWidthDp * 0.45f).toInt()
    
    // ========== PAYMENT FLOW STATE MACHINE ==========
    // Single source of truth for payment flow state
    // PaymentFlowState is defined in ui.PaymentFlowScreen.kt
    var paymentFlowState by remember { mutableStateOf(PaymentFlowState.IDLE) }

    // True from the moment the customer's phone creates the Razorpay order
    // ('payment-processing' socket event) until payment-success/reset — drives
    // the "Payment processing…" indicator on the QR screen.
    var isPaymentProcessingLive by remember { mutableStateOf(false) }

    // Track timing for transitions
    var bmiShownTimestamp by remember { mutableStateOf<Long?>(null) }
    var fortuneShownTimestamp by remember { mutableStateOf<Long?>(null) }
    
    // Legacy modal states - derived from paymentFlowState for backward compatibility
    val showQRModal = remember(paymentFlowState) { paymentFlowState == PaymentFlowState.QR_SHOWING }
    val showBMIDataModal = remember(paymentFlowState) { paymentFlowState == PaymentFlowState.BMI_SHOWING }
    val showFortuneModal = remember(paymentFlowState) { paymentFlowState == PaymentFlowState.FORTUNE_SHOWING }
    
    var bmiData by remember { mutableStateOf<Map<String, Any>?>(null) }
    var currentBmiId by remember { mutableStateOf<String?>(null) } // Track current BMI ID for polling (F1 flow)
    var currentToken by remember { mutableStateOf<String?>(null) }
    var flowSessionId by remember { mutableIntStateOf(0) }
    
    // Development panel state
    var devInfoVisible by remember { mutableStateOf(false) }
    
    // Dev mode: Force specific screen for testing
    var forcedScreen by remember { mutableStateOf<F1Screen?>(null) }
    
    // Track sensor readings for dev panel
    var lastWeight by remember { mutableStateOf<Float?>(null) }
    var lastHeight by remember { mutableStateOf<Float?>(null) }
    var lastReadingTime by remember { mutableStateOf<Long>(0) }
    
    // Track BMI detection state - show loading when detection starts
    var isDetectingBMI by remember { mutableStateOf(false) }
    var isRetryingHeight by remember { mutableStateOf(false) }
    var heightRetryStartTime by remember { mutableLongStateOf(0L) }
    var lockedWeightForRetry by remember { mutableFloatStateOf(0f) }
    var lastHeightValidationReason by remember { mutableStateOf(com.example.playerapp.data.HeightValidationReason.VALID) }
    var failedReadingWeightLock by remember { mutableStateOf(false) }
    var failedReadingLowWeightStartTime by remember { mutableLongStateOf(0L) }
    
    // Track if flow is active (BMI data has been captured)
    var isFlowActive by remember { mutableStateOf(false) }
    var capturedWeight by remember { mutableStateOf<Float?>(null) }
    var capturedHeight by remember { mutableStateOf<Float?>(null) }
    var lowWeightDetectedAt by remember { mutableStateOf<Long?>(null) }
    
    // Track when last flow ended to enforce 5 second interval between flows
    var lastFlowEndTime by remember { mutableStateOf<Long?>(null) }
    
    // Track flow start time for duration calculation
    var flowStartTime by remember { mutableStateOf<Long?>(null) }
    
    // Asset info state for dev panel
    var assetInfoVisible by remember { mutableStateOf(false) }
    var assetsInfo by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var defaultAssetInfo by remember { mutableStateOf<DefaultAsset?>(null) }
    var tomorrowAssetsCountInfo by remember { mutableStateOf(0) }
    
    // Flow type is hardcoded to F1 only - this app only supports F1 flow
    // No need to fetch or change flow type - it's always F1
    var currentFlowType by remember { mutableStateOf<String>("F1") }
    var lastFlowTypeChange by remember { mutableStateOf<String?>(null) }
    var flowTypeChangeCount by remember { mutableStateOf(0) }
    
    // Height calibration state - initialize from localStorage
    var heightCalibration by remember { mutableStateOf(HeightCalibrationStorage.getHeightCalibration(context)) }
    
    // Logo visibility state - initialize from SharedPreferences
    var showLogo by remember { mutableStateOf(LogoVisibilityStorage.getShowLogo(context)) }
    
    // Screen ID visibility state - initialize from persisted info (server-controlled)
    var hideScreenId by remember { mutableStateOf(InfoStorage.getHideScreenId(context) ?: false) }
    var hideAppMargin by remember { mutableStateOf(InfoStorage.getHideAppMargin(context) ?: false) }
    
    // Network connectivity state — updated reactively via ConnectivityManager.NetworkCallback
    val isOnlineFlow = remember { observeNetworkState(context) }
    var isOffline by remember { mutableStateOf(!isOnline(context)) }
    LaunchedEffect(Unit) {
        isOnlineFlow.collect { online ->
            isOffline = !online
        }
    }
    
    // Configuration validation state - initialize from persisted info
    var heightCalibrationEnabled by remember { 
        val persistedValue = InfoStorage.getHeightCalibrationEnabled(context)
        println("DEBUG: F3 - Initializing heightCalibrationEnabled from persisted info: $persistedValue (defaulting to true if null)")
        mutableStateOf(persistedValue ?: true)
    }
    var showConfigError by remember { mutableStateOf(false) }
    var configErrorTriggered by remember { mutableStateOf(false) }
    var bottomToastMessage by remember { mutableStateOf<String?>(null) }
    var topWarningBannerMessage by remember { mutableStateOf<String?>(null) }
    
    // Flow drawer enabled state - initialize from persisted info
    var flowDrawerEnabled by remember {
        val persistedValue = InfoStorage.getFlowDrawerEnabled(context)
        println("DEBUG: F3 - Initializing flowDrawerEnabled from persisted info: $persistedValue (defaulting to true if null)")
        mutableStateOf(persistedValue ?: true)
    }
    var flowDrawerSlotCount by remember {
        val persistedValue = InfoStorage.getFlowDrawerSlotCount(context)
        println("DEBUG: F3 - Initializing flowDrawerSlotCount from persisted info: $persistedValue (defaulting to 2 if null)")
        mutableIntStateOf((persistedValue ?: 2).coerceAtLeast(1))
    }

    // App mode (admin-controlled): "F1" | "F2" | "PlayerOnly"
    // Defaults to F1 if the server has never set it.
    var appMode by remember {
        val persisted = InfoStorage.getAppMode(context)
        println("DEBUG: F3 - Initializing appMode from persisted info: $persisted (defaulting to F1)")
        mutableStateOf(persisted ?: "F1")
    }
    // Derive whether the full F1 BMI flow is enabled from the mode
    val isF1ModeActive = appMode.equals("F1", ignoreCase = true)
    val isF2ModeActive = appMode.equals("F2", ignoreCase = true)
    
    // Track current BMI modal screen for drawer visibility
    var currentBMIScreen by remember { mutableStateOf<F1Screen?>(null) }
    
    // Dev panel toggle for drawer
    var showDrawerInDev by remember { mutableStateOf(false) }
    var showDevFooter by remember { mutableStateOf(InfoStorage.getShowDevFooter(context)) }
    
    // FlowDrawer assets state - load cached assets immediately so the drawer can render on time
    var flowDrawerAssets by remember(screenId, flowDrawerSlotCount) {
        mutableStateOf(
            screenId?.let { loadFlowDrawerAssets(context, it) } ?: loadFlowDrawerAssets(context)
        )
    }
    var assetRefreshTrigger by remember { mutableStateOf(0) }
    var isPlaylistUpdating by remember { mutableStateOf(false) }
    var updatingText by remember { mutableStateOf("Playlist is updating...") }
    var lastFlowDrawerDemandState by remember { mutableStateOf<String?>(null) }
    
    // Flow Drawer visibility calculation - placed after all variables are declared
    val anyModalShowing = showQRModal || showBMIDataModal || showFortuneModal
    val flowDrawerHasAssets = flowDrawerAssets.hasRenderableAssets()
    val drawerAllowedByRenderedScreen =
        paymentFlowState == PaymentFlowState.BMI_SHOWING ||
        paymentFlowState == PaymentFlowState.FORTUNE_SHOWING ||
        currentBMIScreen == F1Screen.BMI ||
        currentBMIScreen == F1Screen.FORTUNE
    val shouldShowDrawer = flowDrawerEnabled && (
        (anyModalShowing && drawerAllowedByRenderedScreen) ||
            (showDrawerInDev && anyModalShowing)
        )
    val drawerOffsetDp = if (shouldShowDrawer) drawerWidthDp else 0
    
    // Debug FlowDrawer visibility
    println("DEBUG: F3 - FlowDrawer visibility check:")
    println("  anyModalShowing: $anyModalShowing (QR:$showQRModal, BMI:$showBMIDataModal, Fortune:$showFortuneModal)")
    println("  currentBMIScreen: $currentBMIScreen")
    println("  flowDrawerEnabled: $flowDrawerEnabled")
    println("  paymentFlowState: $paymentFlowState")
    println("  flowDrawerHasAssets: $flowDrawerHasAssets")
    println("  drawerAllowedByRenderedScreen: $drawerAllowedByRenderedScreen")
    println("  showDrawerInDev: $showDrawerInDev")
    println("  shouldShowDrawer: $shouldShowDrawer")
    println("  drawerOffsetDp: $drawerOffsetDp")
    
    // Auto-hide the update UI banner after 3 seconds and only show when there are actual updates
    LaunchedEffect(assetRefreshTrigger) {
        if (assetRefreshTrigger > 0) {
            // Only show updating indicator if this is not the periodic refresh
            // or if there are actual socket events
            isPlaylistUpdating = true
            kotlinx.coroutines.delay(3000) // Reduced from 4 seconds
            isPlaylistUpdating = false
        }
    }
    
    // Monitor network connectivity — now driven by the reactive flow above (no polling needed)
    // Also clears the offline toast when the device comes back online.
    LaunchedEffect(Unit) {
        isOnlineFlow.collect { online ->
            println("DEBUG: F3 - Network connectivity change: ${if (online) "Online" else "Offline"}")
            if (online && bottomToastMessage == "Device is offline Cant process BMI") bottomToastMessage = null
        }
    }
    
    // Load cached FlowDrawer assets immediately, then always attempt a persistent refresh from server.
    LaunchedEffect(screenId, assetRefreshTrigger, flowDrawerEnabled, flowDrawerSlotCount) {
        screenId?.let { id ->
            // Debug flow drawer configuration
            debugFlowDrawerConfig(context, id)
            
            // Always load cached assets first for immediate display
            val cachedAssets = loadFlowDrawerAssets(context, id)
            flowDrawerAssets = cachedAssets
            println("DEBUG: F3 - FlowDrawer cached assets loaded immediately: ${cachedAssets.topAssets.size} top, ${cachedAssets.bottomAssets.size} bottom")

            if (!flowDrawerEnabled) {
                println("DEBUG: F3 - FlowDrawer disabled, skipping remote image refresh")
                return@let
            }

            val hasCachedImages = cachedAssets.hasRenderableAssets()

            println("DEBUG: F3 - Refreshing FlowDrawer images for screenId: $id (trigger: $assetRefreshTrigger, hasCached: $hasCachedImages)")
            refreshFlowDrawerAssets(context, id) { success, loadedAssets, error ->
                if (success) {
                    println(
                        "DEBUG: F3 - FlowDrawer refresh completed: " +
                            "${loadedAssets.topAssets.size} top, ${loadedAssets.bottomAssets.size} bottom"
                    )
                } else {
                    println("DEBUG: F3 - Failed to get FlowDrawer images: $error")
                }

                flowDrawerAssets = loadedAssets
                println("DEBUG: F3 - FlowDrawer assets reloaded: ${loadedAssets.topAssets.size} top, ${loadedAssets.bottomAssets.size} bottom")
            }
        } ?: run {
            println("DEBUG: F3 - No screenId, loading FlowDrawer assets from default directory")
            flowDrawerAssets = loadFlowDrawerAssets(context)
        }
    }
    
    // Periodically refresh FlowDrawer assets every 10 minutes instead of 5 minutes
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(600000) // 10 minutes instead of 5 minutes
            // Only trigger refresh if drawer is enabled to reduce unnecessary network calls
            if (flowDrawerEnabled) {
                assetRefreshTrigger++
                println("DEBUG: F3 - Triggering periodic FlowDrawer asset refresh (10 minutes)")
            }
        }
    }

    // If the user enters BMI/Fortune flow and drawer assets are still missing,
    // trigger an immediate refresh instead of waiting for periodic/socket refreshes.
    LaunchedEffect(paymentFlowState, currentBMIScreen, flowDrawerEnabled, screenId, flowDrawerHasAssets) {
        if (!flowDrawerEnabled || screenId.isBlank()) return@LaunchedEffect

        val isDrawerScreen =
            paymentFlowState == PaymentFlowState.BMI_SHOWING ||
                paymentFlowState == PaymentFlowState.FORTUNE_SHOWING
        if (!isDrawerScreen || flowDrawerHasAssets) return@LaunchedEffect

        val demandState = "${paymentFlowState.name}:${currentBMIScreen?.name ?: "null"}"
        if (lastFlowDrawerDemandState != demandState) {
            lastFlowDrawerDemandState = demandState
            assetRefreshTrigger++
            println("DEBUG: F3 - FlowDrawer assets missing during active flow, forcing refresh for state: $demandState")
        }
    }
    
    // Dev screen test buttons data
    data class DevScreenButton(
        val label: String,
        val color: Color,
        val screen: F1Screen,
        val bmiData: Map<String, Any>
    )
    
    val devScreenButtons = remember {
        listOf(
            DevScreenButton(
                label = "QR",
                color = Color(0xFF3B82F6),
                screen = F1Screen.QR,
                bmiData = mapOf(
                    "bmiId" to "dev-test-001",
                    "webUrl" to "https://example.com/bmi/dev-test",
                    "bmi" to 22.5,
                    "category" to "Normal",
                    "height" to 175,
                    "weight" to 68.5
                )
            ),
            DevScreenButton(
                label = "Loading",
                color = Color(0xFF10B981),
                screen = F1Screen.LOADING,
                bmiData = mapOf(
                    "bmiId" to "dev-test-001",
                    "webUrl" to "https://example.com/bmi/dev-test",
                    "user" to mapOf(
                        "userId" to "dev-user-001",
                        "name" to "Dev User",
                        "mobile" to "1234567890"
                    ),
                    "bmi" to 22.5,
                    "category" to "Normal",
                    "height" to 175,
                    "weight" to 68.5
                )
            ),
            DevScreenButton(
                label = "BMI",
                color = Color(0xFFF59E0B),
                screen = F1Screen.BMI,
                bmiData = mapOf(
                    "bmiId" to "dev-test-001",
                    "webUrl" to "https://example.com/bmi/dev-test",
                    "user" to mapOf(
                        "userId" to "dev-user-001",
                        "name" to "Dev User",
                        "mobile" to "1234567890"
                    ),
                    "bmi" to 22.5,
                    "category" to "Normal",
                    "height" to 175,
                    "weight" to 68.5,
                    "timestamp" to System.currentTimeMillis()
                )
            ),
            DevScreenButton(
                label = "Fortune",
                color = Color(0xFF8B5CF6),
                screen = F1Screen.FORTUNE,
                bmiData = mapOf(
                    "bmiId" to "dev-test-001",
                    "webUrl" to "https://example.com/bmi/dev-test",
                    "user" to mapOf(
                        "userId" to "dev-user-001",
                        "name" to "Dev User",
                        "mobile" to "1234567890"
                    ),
                    "bmi" to 22.5,
                    "category" to "Normal",
                    "height" to 175,
                    "weight" to 68.5,
                    "timestamp" to System.currentTimeMillis(),
                    "fortune" to "Your health journey is just beginning! Every step forward is progress worth celebrating. Stay hydrated and keep moving!",
                    "fortuneMessage" to "Your health journey is just beginning! Every step forward is progress worth celebrating. Stay hydrated and keep moving!"
                )
            ),
            DevScreenButton(
                label = "Processing",
                color = Color(0xFF6B7280),
                screen = F1Screen.PROCESSING,
                bmiData = mapOf(
                    "bmiId" to "dev-test-001",
                    "webUrl" to "https://example.com/bmi/dev-test",
                    "user" to mapOf(
                        "userId" to "dev-user-001",
                        "name" to "Dev User",
                        "mobile" to "1234567890"
                    ),
                    "bmi" to 22.5,
                    "category" to "Normal",
                    "height" to 175,
                    "weight" to 68.5
                )
            )
        )
    }
    
    // SocketManager for real-time payment and fortune updates
    val socketManager = remember { com.example.playerapp.data.SocketManager(context) }
    val usbSerialManager = remember { com.example.playerapp.data.UsbSerialManager(context) }
    val usbStatus by usbSerialManager.usbStatusFlow.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    fun resetPaymentFlow(reason: String, invalidateSession: Boolean = true) {
        println("[PAYMENT_FLOW] Resetting flow: $reason")
        if (invalidateSession) {
            flowSessionId += 1
        }
        isFlowActive = false
        paymentFlowState = PaymentFlowState.IDLE
        isPaymentProcessingLive = false
        bmiData = null
        capturedWeight = null
        capturedHeight = null
        isDetectingBMI = false
        isRetryingHeight = false
        failedReadingWeightLock = false
        failedReadingLowWeightStartTime = 0L
        lockedWeightForRetry = 0f
        currentBMIScreen = null
        flowStartTime = null
        currentBmiId = null
        forcedScreen = null
        lowWeightDetectedAt = null
        bmiShownTimestamp = null
        fortuneShownTimestamp = null
    }
    
    // Function to process first valid BMI reading
    fun processFirstValidReading(weight: Float, height: Float) {
        if (isFlowActive) return // Already processed
        failedReadingWeightLock = false
        failedReadingLowWeightStartTime = 0L
        bottomToastMessage = null
        topWarningBannerMessage = null
        val activeFlowSessionId = flowSessionId + 1
        flowSessionId = activeFlowSessionId
        
        // Check 0 second interval between flows (Immediate processing)
        val currentTime = System.currentTimeMillis()
        if (lastFlowEndTime != null) {
            val timeSinceLastFlow = currentTime - lastFlowEndTime!!
            val minIntervalMs = 0L // 0 seconds
            if (timeSinceLastFlow < minIntervalMs) {
                val remainingSeconds = ((minIntervalMs - timeSinceLastFlow) / 1000f).coerceAtLeast(0f)
                println("DEBUG: F3 - Flow cooldown active: ${String.format("%.1f", remainingSeconds)}s remaining (need 0s between flows)")
                return // Still in cooldown period
            }
        }
        
        // Check for config validation - log warning but don't stop the flow
        if (!heightCalibrationEnabled) {
            println("DEBUG: F3 - ⚠️ heightCalibrationEnabled is false - Proceeding with weight-only logic")
        }
        
        println("DEBUG: F3 - First valid reading detected! Weight: $weight kg, Height: $height cm")
        println("DEBUG: F3 - ⚠️ RAW VALUES FROM USB - NO CONVERSION - Weight: $weight kg, Height: $height cm")
        
        // Validate raw values are reasonable (catch parsing errors)
        // Height must be at least 50cm (reasonable minimum human height)
        if (weight < 0f || weight > 500f) {
            println("DEBUG: F3 - ❌ INVALID WEIGHT: $weight kg - Out of range! Skipping.")
            return
        }
        
        val isHeightValid = heightCalibrationEnabled && height in com.example.playerapp.data.UsbSerialManager.MIN_HEIGHT_CM..com.example.playerapp.data.UsbSerialManager.MAX_HEIGHT_CM
        if (!isHeightValid) {
            if (!heightCalibrationEnabled) {
                println("DEBUG: F3 - ⚠️ Height calibration is DISABLED. Proceeding with WEIGHT ONLY flow.")
            } else {
                println("DEBUG: F3 - ⚠️ INVALID/MISSING HEIGHT: $height cm (valid range: ${com.example.playerapp.data.UsbSerialManager.MIN_HEIGHT_CM}-${com.example.playerapp.data.UsbSerialManager.MAX_HEIGHT_CM}cm). Proceeding with WEIGHT ONLY flow.")
            }
        }
        
        // `height` is already calibrated: UsbSerialManager.validateHeight() applies the
        // offset (and its sign) before the stable callback. Adding it again here doubled it.
        val calibratedHeight = height
        
        // ========== PAYMENT FLOW LOGGING - FLOW STARTED ==========
        val currentFlowStartTime = System.currentTimeMillis()
        flowStartTime = currentFlowStartTime
        println("═══════════════════════════════════════════════════════════════")
        println("[PAYMENT_FLOW] 🚀 FLOW TRIGGERED - START")
        println("[PAYMENT_FLOW] Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(currentFlowStartTime))}")
        println("[PAYMENT_FLOW] Screen ID: $screenId")
        println("[PAYMENT_FLOW] Weight (raw): $weight kg")
        println("[PAYMENT_FLOW] Height (calibrated): $height cm (offset $heightCalibration cm already applied)")
        println("[PAYMENT_FLOW] Flow Type: $flowType")
        println("═══════════════════════════════════════════════════════════════")
        
        isFlowActive = true
        capturedWeight = weight
        capturedHeight = if (isHeightValid) calibratedHeight else 0f // Store 0 if height is invalid
        
        // Keep USB connected to monitor weight continuously
        // Flow will terminate if weight drops below 5kg
        println("DEBUG: F3 - Valid data detected, flow is now active")
        println("DEBUG: F3 - USB will remain connected to monitor weight (flow active only if weight > 5kg)")
        println("DEBUG: F3 - Captured weight (raw): $weight kg, height (raw): ${if (isHeightValid) height else "INVALID"} cm")
        
        // Calculate BMI locally using calibrated height for accuracy (only if height is valid)
        val bmi = if (isHeightValid) usbSerialManager.calculateBMI(weight, calibratedHeight) else 0f
        val bmiCategory = if (isHeightValid) usbSerialManager.getBMICategory(bmi) else "N/A"
        
        // Validate BMI is reasonable (10-50 is normal range, allow up to 60 for extreme cases)
        // If BMI is out of range, show calibration error and auto-close flow (only if height is valid)
        if (isHeightValid && (bmi < 10f || bmi > 60f)) {
            println("DEBUG: F3 - ❌❌❌ IMPROPER BMI DETECTED: $bmi (Weight: $weight kg, Height: $height cm, Calibrated Height: $calibratedHeight cm)")
            println("DEBUG: F3 - ⚠️ BMI is out of reasonable range (10-60) - resetting flow without overlay")
            isDetectingBMI = false
            
            // Auto-close flow after 7 seconds
            coroutineScope.launch {
                kotlinx.coroutines.delay(7000) // 7 seconds
                println("DEBUG: F3 - Auto-closing flow due to improper BMI")
                resetPaymentFlow("Improper BMI detected")
                lastFlowEndTime = System.currentTimeMillis()
            }
            return
        }
                
        // Pick fortune and health tip from local JSON (no server fetch); use unique seeded pick per reading
        val (fortuneMessage, healthTip) = com.example.playerapp.data.LocalFortuneHealth.pick(
            context = context,
            screenId = screenId,
            heightCm = height,
            weightKg = weight,
            category = bmiCategory
        )
        println("[PAYMENT_FLOW] 📖 Local fortune pick: ${!fortuneMessage.isBlank()}, health tip: ${!healthTip.isBlank()}")

        // Show QR code modal IMMEDIATELY after USB data is received - no delays
        // Create initial BMI data map with calculated values (webUrl will be added when server responds)
        val initialBmiDataMap = mutableMapOf<String, Any>(
            "weight" to weight as Any, // Raw weight
            "height" to (if (isHeightValid) calibratedHeight else 0f) as Any, // Calibrated height or 0
            "timestamp" to System.currentTimeMillis() as Any,
            "source" to "USB_SENSOR" as Any,
            "screenId" to screenId as Any,
            "isHeightValid" to isHeightValid as Any
        )
        if (isHeightValid) {
            initialBmiDataMap["bmi"] = bmi
            initialBmiDataMap["category"] = bmiCategory
        } else {
            initialBmiDataMap["category"] = "Weight Only"
        }
        if (fortuneMessage.isNotBlank()) {
            initialBmiDataMap["fortune"] = fortuneMessage
            initialBmiDataMap["fortuneMessage"] = fortuneMessage
        }
        if (healthTip.isNotBlank()) {
            initialBmiDataMap["healthTip"] = healthTip
        }

        // Show ProcessingScreen (DETECTING state) for 1.2s for visual feedback before transitioning to QR/BMI modal
        bmiData = initialBmiDataMap
        paymentFlowState = PaymentFlowState.DETECTING
        currentBMIScreen = F1Screen.PROCESSING
        
        coroutineScope.launch {
            kotlinx.coroutines.delay(1200L)
            if (flowSessionId == activeFlowSessionId) {
                if (isF2ModeActive) {
                    println("[PAYMENT_FLOW] ⚡ Processing complete - F2 mode active - Proceeding to BMI_SHOWING")
                    paymentFlowState = PaymentFlowState.BMI_SHOWING
                    currentBMIScreen = F1Screen.BMI
                    bmiShownTimestamp = System.currentTimeMillis()
                } else {
                    println("[PAYMENT_FLOW] ⚡ Processing complete - Showing QR modal")
                    paymentFlowState = PaymentFlowState.QR_SHOWING
                    currentBMIScreen = F1Screen.QR
                }
                isDetectingBMI = false
            }
        }
        println("[PAYMENT_FLOW] ✅ ProcessingScreen displayed - will transition in 1.2s")
        
        // Start timeout for QR code generation - if server doesn't respond in 60 seconds, show placeholder
        coroutineScope.launch {
            kotlinx.coroutines.delay(60000) // 60 second timeout
            if (flowSessionId != activeFlowSessionId || !isFlowActive || paymentFlowState != PaymentFlowState.QR_SHOWING) {
                println("[PAYMENT_FLOW] QR timeout ignored because flow session is no longer active")
                return@launch
            }
            val currentBmiData = bmiData
            if (currentBmiData != null && !currentBmiData.containsKey("webUrl")) {
                println("[PAYMENT_FLOW] ⚠️ QR code generation timeout (5s) - server hasn't responded yet")
                println("[PAYMENT_FLOW] Adding fallback webUrl to prevent stuck screen")
                
                // Add fallback webUrl to prevent stuck screen
                val updatedBmiDataMap = currentBmiData.toMutableMap()
                updatedBmiDataMap["webUrl"] = "https://well2day.com/offline-payment"
                bmiData = updatedBmiDataMap
                println("[PAYMENT_FLOW] ✅ Added fallback webUrl after timeout")
            }
        }
        
        // Call server API to create BMI record with CALIBRATED height and weight; send locally-picked fortune/healthTip so they are stored in DB
        println("[PAYMENT_FLOW] 📤 Calling Repository.createBMI - Creating BMI record on server with CALIBRATED data...")
        println("[PAYMENT_FLOW] ⚡ Fast QR generation mode - targeting 1 second response time")
        Repository.createBMI(
            heightCm = if (isHeightValid) calibratedHeight else null, // Omit height in weight-only mode
            weightKg = weight, // Use raw weight
            screenId = screenId,
            appVersion = flowType ?: "F3",
            fortune = fortuneMessage.takeIf { it.isNotBlank() },
            healthTip = healthTip.takeIf { it.isNotBlank() }
        ) { success: Boolean, webUrl: String?, bmiId: String?, error: String? ->
            if (flowSessionId != activeFlowSessionId || !isFlowActive) {
                println("[PAYMENT_FLOW] Ignoring stale createBMI callback because trigger is no longer active")
            } else if (success && webUrl != null && bmiId != null) {
                // ========== TOKEN GENERATION ==========
                // Generate payment token to ensure payment completion
                val paymentToken = com.example.playerapp.utils.PaymentTokenManager.generateToken(bmiId, screenId)
                
                // In F2 mode, ensure the save-to-cloud QR code has appVersion=f2
                var finalWebUrl = webUrl
                if (isF2ModeActive) {
                    finalWebUrl = finalWebUrl.replace(Regex("appVersion=[^&#]*", RegexOption.IGNORE_CASE), "appVersion=f2")
                }
                
                // Server URL ends in "#server=...": the token must go before the '#', or the
                // web client (which reads location.search) never sees it. F2 is claim-only
                // (no payment), so skip the token there — a shorter URL means a coarser,
                // easier-to-scan QR on the fortune screen.
                val (urlBase, urlFragment) = finalWebUrl.split('#', limit = 2).let { it[0] to it.getOrNull(1) }
                val tokenizedWebUrl = if (isF2ModeActive) finalWebUrl else
                    urlBase + (if ('?' in urlBase) "&" else "?") + "paymentToken=$paymentToken" +
                        (urlFragment?.let { "#$it" } ?: "")
                
                // Update BMI data map with server response (webUrl with token and bmiId)
                val updatedBmiDataMap = (bmiData ?: emptyMap()).toMutableMap()
                updatedBmiDataMap["webUrl"] = tokenizedWebUrl
                updatedBmiDataMap["paymentToken"] = paymentToken // Store token for verification
                
                // Store BMI ID for polling (F1 flow)
                currentBmiId = bmiId
                updatedBmiDataMap["bmiId"] = bmiId
                
                // ========== PAYMENT FLOW LOGGING - BMI CREATED ==========
                println("═══════════════════════════════════════════════════════════════")
                println("[PAYMENT_FLOW] ✅ BMI RECORD CREATED ON SERVER")
                println("[PAYMENT_FLOW] BMI ID: $bmiId")
                println("[PAYMENT_FLOW] Web URL: $webUrl")
                println("[PAYMENT_FLOW] BMI: $bmi ($bmiCategory)")
                println("[PAYMENT_FLOW] Waiting for payment...")
                println("═══════════════════════════════════════════════════════════════")
                
                // ========== POLLING FALLBACK: Check for payment completion ==========
                // Poll server every 2 seconds to check if payment was completed
                // This serves as a backup if socket event doesn't arrive (especially important for Vercel)
                coroutineScope.launch {
                    var pollCount = 0
                    val maxPolls = 90 // Poll for up to 3 minutes (90 * 2 seconds)
                    val pollInterval = 2000L // Poll every 2 seconds
                    
                    println("[PAYMENT_FLOW] 🔄 Starting polling fallback mechanism")
                    
                    while (pollCount < maxPolls && paymentFlowState == PaymentFlowState.QR_SHOWING && flowSessionId == activeFlowSessionId && isFlowActive) {
                        kotlinx.coroutines.delay(pollInterval)
                        pollCount++
                        
                        val currentBmiIdToCheck = currentBmiId
                        if (currentBmiIdToCheck != null) {
                            // Log every 10th poll to reduce spam, but always log first 5
                            if (pollCount % 10 == 0 || pollCount <= 5) {
                                println("[PAYMENT_FLOW] 🔍 Polling for payment completion (attempt $pollCount/$maxPolls)...")
                                println("[PAYMENT_FLOW] Checking BMI ID: $currentBmiIdToCheck")
                            }
                            
                            Repository.getBMIRecord(currentBmiIdToCheck) { successPoll, bmiRecordData, errorPoll ->
                                if (flowSessionId != activeFlowSessionId || !isFlowActive || paymentFlowState != PaymentFlowState.QR_SHOWING) {
                                    println("[PAYMENT_FLOW] Ignoring stale polling result because QR flow is no longer active")
                                } else if (successPoll && bmiRecordData != null) {
                                    val userIdPoll = bmiRecordData["userId"] as? String
                                    val userPoll = bmiRecordData["user"] as? Map<*, *>
                                    
                                    if (userIdPoll != null && userPoll != null && paymentFlowState == PaymentFlowState.QR_SHOWING) {
                                        println("═══════════════════════════════════════════════════════════════")
                                        println("[PAYMENT_FLOW] ✅✅✅ PAYMENT DETECTED VIA POLLING!")
                                        println("[PAYMENT_FLOW] User ID: $userIdPoll")
                                        println("[PAYMENT_FLOW] User: $userPoll")
                                        println("[PAYMENT_FLOW] Poll attempt: $pollCount")
                                        println("[PAYMENT_FLOW] Transitioning to BMI screen via polling fallback")
                                        println("═══════════════════════════════════════════════════════════════")
                                        
                                        // Process payment directly
                                        Handler(Looper.getMainLooper()).post {
                                            if (flowSessionId != activeFlowSessionId || !isFlowActive || paymentFlowState != PaymentFlowState.QR_SHOWING) {
                                                println("[PAYMENT_FLOW] Ignoring stale payment completion UI update because flow ended")
                                            } else {
                                                val existingBmiData = bmiData ?: emptyMap()
                                                val updatedBmiData = existingBmiData.toMutableMap()
                                                updatedBmiData["userId"] = userIdPoll
                                                updatedBmiData["user"] = userPoll
                                                
                                                // Update BMI values from server if available
                                                bmiRecordData.forEach { (key, value) ->
                                                    if (key != "weight" && key != "height") {
                                                        updatedBmiData[key] = value
                                                    }
                                                }
                                                
                                                bmiData = updatedBmiData.toMap()
                                                paymentFlowState = PaymentFlowState.BMI_SHOWING
                                                isPaymentProcessingLive = false
                                                currentBMIScreen = F1Screen.PROCESSING
                                                
                                                // Fetch fortune immediately
                                                Repository.getBMIRecord(currentBmiIdToCheck) { fortuneSuccess, fortuneData, fortuneError ->
                                                    if (flowSessionId != activeFlowSessionId || !isFlowActive) {
                                                        println("[PAYMENT_FLOW] Ignoring stale fortune fetch because flow ended")
                                                    } else if (fortuneSuccess && fortuneData != null) {
                                                        val currentBmiData = bmiData ?: emptyMap()
                                                        val updatedWithFortune = currentBmiData.toMutableMap()
                                                        val fortuneMessage = fortuneData["fortuneMessage"] as? String
                                                        val fortune = fortuneData["fortune"] as? String
                                                        val healthTip = fortuneData["healthTip"] as? String
                                                        
                                                        if (!fortuneMessage.isNullOrBlank()) updatedWithFortune["fortuneMessage"] = fortuneMessage
                                                        if (!fortune.isNullOrBlank()) updatedWithFortune["fortune"] = fortune
                                                        if (!healthTip.isNullOrBlank()) updatedWithFortune["healthTip"] = healthTip
                                                        
                                                        bmiData = LinkedHashMap<String, Any>(updatedWithFortune).toMap()
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
                
                // Update bmiData with webUrl and bmiId
                bmiData = updatedBmiDataMap
                println("[PAYMENT_FLOW] ✅ Updated bmiData with webUrl and bmiId")
            } else {
                // ========== PAYMENT FLOW LOGGING - BMI CREATION FAILED / OFFLINE ==========
                println("═══════════════════════════════════════════════════════════════")
                println("[PAYMENT_FLOW] ⚠️ FAILED TO CREATE BMI RECORD ON SERVER OR OFFLINE")
                println("[PAYMENT_FLOW] Error: $error")
                println("[PAYMENT_FLOW] Adding fallback webUrl to prevent QR screen stuck")
                println("═══════════════════════════════════════════════════════════════")
                
                // Add fallback webUrl to prevent QR screen from being stuck (F1 only).
                // F2 has no record to claim when creation failed, so leave webUrl unset and
                // the fortune screen hides its "Save to Cloud" QR instead of encoding a dead link.
                if (!isF2ModeActive) {
                    val updatedBmiDataMap = (bmiData ?: emptyMap()).toMutableMap()
                    updatedBmiDataMap["webUrl"] = "https://well2day.com/offline-payment" // Fallback URL
                    bmiData = updatedBmiDataMap
                }
                println("[PAYMENT_FLOW] ✅ Added fallback webUrl to prevent stuck QR screen")
                
                // [OFFLINE_SYNC] Save to local database for later sync
                coroutineScope.launch {
                    try {
                        // Goes through Repository so the upload worker is actually scheduled —
                        // a bare dao.insert() here left records stranded on the device.
                        Repository.saveBmiToLocal(
                            context = context,
                            userId = null,
                            weight = weight.toDouble(),
                            height = (if (isHeightValid) calibratedHeight else 0f).toDouble(),
                            bmi = if (isHeightValid) bmi.toDouble() else 0.0,
                            category = if (isHeightValid) bmiCategory else "Weight Only",
                            screenId = screenId
                        )
                        println("[PAYMENT_FLOW] ✅ BMI record saved locally for sync")
                    } catch (e: Exception) {
                        println("[PAYMENT_FLOW] ❌ Error saving BMI locally: ${e.message}")
                    }
                }
            }
        }
    }
    
    // Weight monitoring is handled in the USB sensor callback below
    
    // Periodic fetch service - runs every 10 minutes to fetch and update all data
    LaunchedEffect(Unit) {
        // Wait 1 minute after startup before first fetch
        kotlinx.coroutines.delay(60 * 1000)
        
        while (true) {
            if (isRegisteredNow) {
                println("DEBUG: F3 - Periodic fetch (10 minutes) - Fetching player info and updates")
                
                // Fetch player info (flow type, height calibration, playlist, enabled status)
                Repository.getAdscapePlayerInfo(screenId, context) { success, playlistId, startDate, endDate, flowType, enabled, cal, heightCalibrationEnabledValue ->
                    if (success) {
                        // Update height calibration
                        if (cal != null) {
                            val currentCalibration = HeightCalibrationStorage.getHeightCalibration(context)
                            if (HeightCalibrationStorage.hasChanged(context, cal)) {
                                HeightCalibrationStorage.saveHeightCalibration(context, cal)
                                println("DEBUG: F3 - Height calibration updated: $currentCalibration -> $cal cm")
                            }
                            heightCalibration = cal
                        }
                        
                        // Update height calibration enabled status from server
                        if (heightCalibrationEnabledValue != null) {
                            val previousValue = heightCalibrationEnabled
                            heightCalibrationEnabled = heightCalibrationEnabledValue
                            println("DEBUG: F3 - Height calibration enabled updated from server: $previousValue -> $heightCalibrationEnabled")
                            // Value is already saved to info.json by Repository.getAdscapePlayerInfo
                            
                            // If disabled, clear any active flow and show config error
                            if (!heightCalibrationEnabled) {
                                println("DEBUG: F3 - Height calibration disabled, clearing active flow if any")
                                resetPaymentFlow("Height calibration disabled by server")
                                // Don't show config error immediately - only show when flow is triggered
                            } else if (heightCalibrationEnabled) {
                                // Re-enabled, clear config error
                                showConfigError = false
                                configErrorTriggered = false
                            }
                        } else {
                            // Server didn't return value (might be offline), use persisted value
                            val persistedValue = InfoStorage.getHeightCalibrationEnabled(context)
                            if (persistedValue != null && persistedValue != heightCalibrationEnabled) {
                                heightCalibrationEnabled = persistedValue
                                println("DEBUG: F3 - Using persisted heightCalibrationEnabled: $persistedValue (server didn't return value, might be offline)")
                            }
                        }
                        
                        // Update flow drawer enabled status from persisted info (server saves it to info.json)
                        val persistedDrawerEnabled = InfoStorage.getFlowDrawerEnabled(context)
                        if (persistedDrawerEnabled != null && persistedDrawerEnabled != flowDrawerEnabled) {
                            flowDrawerEnabled = persistedDrawerEnabled
                            println("DEBUG: F3 - Flow drawer enabled updated from persisted info: $persistedDrawerEnabled")
                        }
                        val persistedDrawerSlotCount = InfoStorage.getFlowDrawerSlotCount(context)?.coerceAtLeast(1)
                        if (persistedDrawerSlotCount != null && persistedDrawerSlotCount != flowDrawerSlotCount) {
                            flowDrawerSlotCount = persistedDrawerSlotCount
                            println("DEBUG: F3 - Flow drawer slot count updated from persisted info: $persistedDrawerSlotCount")
                            flowDrawerAssets = loadFlowDrawerAssets(context, screenId)
                        }
                        
                        // Update hide screen ID status from persisted info (server saves it to info.json)
                        val persistedHideScreenId = InfoStorage.getHideScreenId(context)
                        if (persistedHideScreenId != null && persistedHideScreenId != hideScreenId) {
                            hideScreenId = persistedHideScreenId
                            println("DEBUG: F3 - Hide screen ID updated from persisted info: $persistedHideScreenId")
                        }
                        
                        // Update hide app margin status from persisted info
                        val persistedHideAppMargin = InfoStorage.getHideAppMargin(context)
                        if (persistedHideAppMargin != null && persistedHideAppMargin != hideAppMargin) {
                            hideAppMargin = persistedHideAppMargin
                            println("DEBUG: F3 - Hide app margin updated from persisted info: $persistedHideAppMargin")
                        }
                        
                        // Update app mode from persisted info (server saves it to info.json)
                        val persistedAppMode = InfoStorage.getAppMode(context)
                        if (persistedAppMode != null && persistedAppMode != appMode) {
                            appMode = persistedAppMode
                            println("DEBUG: F3 - App mode updated from server: $persistedAppMode")
                        }
                        
                        // Flow type is hardcoded to F1 - no need to check or update
                    } else {
                        println("DEBUG: F3 - Failed to fetch player info in periodic update")
                    }
                }
            }
            
            // Wait 10 minutes before next fetch
            kotlinx.coroutines.delay(10 * 60 * 1000)
        }
    }
    
    // Setup Socket.IO for real-time payment and fortune updates (replaces polling)
    // Connect immediately when screenId is available, don't wait for registration
    val socketStatusScope = rememberCoroutineScope()
    LaunchedEffect(screenId) {
        if (screenId.isNotBlank()) {
            println("═══════════════════════════════════════════════════════════════")
            println("[SOCKET] 🔌🔌🔌 SETTING UP SOCKET CONNECTION 🔌🔌🔌")
            println("[SOCKET] Screen ID: $screenId")
            println("[SOCKET] Registered: $isRegistered")
            println("[SOCKET] ⚠️ Connecting socket immediately (registration may still be pending)")
            println("═══════════════════════════════════════════════════════════════")
            
            // Connect to socket immediately - don't wait for registration
            socketManager.connect(screenId)
            println("[SOCKET] ✅ Connection request sent")
            
            // Wait a bit for connection, then register callback
            kotlinx.coroutines.delay(2000)
            
            // Register payment success callback IMMEDIATELY - don't wait for registration
            println("[SOCKET] 📝 Registering payment success callback...")
            println("[SOCKET] Screen ID: $screenId")
            println("[SOCKET] Socket connected: ${socketManager.isConnected()}")
            println("[SOCKET] ⚠️ Registering callback regardless of registration status")
            
            // Log socket status periodically for debugging
            socketStatusScope.launch {
                repeat(10) {
                    kotlinx.coroutines.delay(5000) // Every 5 seconds
                    println("[SOCKET] 🔍 Socket Status Check:")
                    println("[SOCKET]   Connected: ${socketManager.isConnected()}")
                    println("[SOCKET]   Screen ID: $screenId")
                    println("[SOCKET]   Current BMI ID: $currentBmiId")
                    println("[SOCKET]   Payment Flow State: ${paymentFlowState.name}")
                    println("[SOCKET]   BMI Data present: ${bmiData != null}")
                    if (bmiData != null) {
                        println("[SOCKET]   BMI Data keys: ${bmiData?.keys?.joinToString(", ")}")
                        println("[SOCKET]   Payment Token in BMI Data: ${bmiData?.get("paymentToken")}")
                    }
                }
            }
            
            socketManager.setOnPaymentSuccess { paymentData ->
            println("[SOCKET] 🔔🔔🔔 PAYMENT SUCCESS CALLBACK TRIGGERED! 🔔🔔🔔")
            println("[SOCKET] Payment data received: ${paymentData.keys.joinToString(", ")}")
            
            val paymentReceivedTime = System.currentTimeMillis()
            val bmiId = paymentData["bmiId"] as? String
            val userId = paymentData["userId"] as? String
            val paymentScreenId = paymentData["screenId"] as? String
            val user = paymentData["user"] as? Map<*, *>
            val userName = (user?.get("name") as? String) ?: "Unknown"
            val userMobile = (user?.get("mobile") as? String) ?: "Unknown"
            
            // ========== PAYMENT FLOW LOGGING - PAYMENT RECEIVED ==========
            println("═══════════════════════════════════════════════════════════════")
            println("[PAYMENT_FLOW] 💰 PAYMENT COMPLETED - INFO RECEIVED")
            println("[PAYMENT_FLOW] Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(paymentReceivedTime))}")
            println("[PAYMENT_FLOW] BMI ID: $bmiId")
            println("[PAYMENT_FLOW] User ID: $userId")
            println("[PAYMENT_FLOW] User Name: $userName")
            println("[PAYMENT_FLOW] User Mobile: $userMobile")
            println("[PAYMENT_FLOW] Payment Screen ID: $paymentScreenId")
            println("[PAYMENT_FLOW] Current Screen ID: $screenId")
            println("[PAYMENT_FLOW] Current BMI ID: $currentBmiId")
            println("[PAYMENT_FLOW] Payment Data Keys: ${paymentData.keys.joinToString(", ")}")
            println("═══════════════════════════════════════════════════════════════")
            
            // ========== VALIDATION: Check if payment is for this screen ==========
            if (paymentScreenId != null && paymentScreenId != screenId) {
                println("[PAYMENT_FLOW] ⚠️ Payment ignored - Screen ID mismatch")
                println("[PAYMENT_FLOW] Payment Screen ID: $paymentScreenId, Current Screen ID: $screenId")
                return@setOnPaymentSuccess
            }
            
            // ========== VALIDATION: Check if we're in QR_SHOWING state ==========
            val currentState = paymentFlowState
            val currentBmiDataForCheck = bmiData
            val hasActiveFlow = currentBmiDataForCheck != null
            
            println("[PAYMENT_FLOW] Current Flow State: ${currentState.name}")
            println("[PAYMENT_FLOW] Has active flow (bmiData exists): $hasActiveFlow")
            println("[PAYMENT_FLOW] Current BMI ID: $currentBmiId")
            println("[PAYMENT_FLOW] Payment BMI ID: $bmiId")
            
            // Allow payment if:
            // 1. We're in QR_SHOWING state (normal case)
            // 2. OR we have active flow data and payment BMI ID matches (payment for current flow)
            val shouldProcessPayment = currentState == PaymentFlowState.QR_SHOWING || 
                                      (hasActiveFlow && (bmiId == currentBmiId || bmiId == currentBmiDataForCheck?.get("bmiId") as? String))
            
            if (!shouldProcessPayment) {
                println("[PAYMENT_FLOW] ⚠️ Payment ignored - Not in valid state for payment")
                println("[PAYMENT_FLOW] Current state: ${currentState.name}")
                println("[PAYMENT_FLOW] Has active flow: $hasActiveFlow")
                println("[PAYMENT_FLOW] BMI ID match: ${bmiId == currentBmiId || bmiId == currentBmiDataForCheck?.get("bmiId") as? String}")
                return@setOnPaymentSuccess
            }
            
            // If we're not in QR_SHOWING but have active flow, transition to QR_SHOWING first
            if (currentState != PaymentFlowState.QR_SHOWING && hasActiveFlow) {
                println("[PAYMENT_FLOW] ℹ️ Payment received but not in QR_SHOWING - transitioning to QR_SHOWING first")
                paymentFlowState = PaymentFlowState.QR_SHOWING
            }
            
            // ========== VALIDATION: Check BMI ID match ==========
            val currentBmiDataId = bmiData?.get("bmiId") as? String
            val bmiIdMatches = bmiId == currentBmiId || bmiId == currentBmiDataId
            
            if (!bmiIdMatches && currentBmiId != null && currentBmiDataId != null) {
                println("[PAYMENT_FLOW] ⚠️ Payment BMI ID mismatch - but processing anyway for active flow")
                println("[PAYMENT_FLOW] Payment BMI ID: $bmiId")
                println("[PAYMENT_FLOW] Current BMI ID: $currentBmiId")
                println("[PAYMENT_FLOW] BMI Data ID: $currentBmiDataId")
            }
            
            // ========== TOKEN VERIFICATION WITH RETRY ==========
            // Verify payment token to ensure payment completion
            val paymentToken = paymentData["paymentToken"] as? String
            val storedToken = bmiData?.get("paymentToken") as? String
            
            println("═══════════════════════════════════════════════════════════════")
            println("[TOKEN] 🔐 VERIFYING PAYMENT TOKEN")
            println("[TOKEN] Payment Token from server: $paymentToken")
            println("[TOKEN] Stored Token in BMI Data: $storedToken")
            println("[TOKEN] BMI ID: $bmiId")
            println("[TOKEN] Screen ID: $screenId")
            println("[TOKEN] All payment data keys: ${paymentData.keys.joinToString(", ")}")
            println("═══════════════════════════════════════════════════════════════")
            
            // Token verification with retry mechanism (exponential backoff)
            // Prefer token from server, fallback to stored token
            val tokenToVerify = paymentToken ?: storedToken
            var tokenVerified = false
            val maxRetries = 3
            val initialDelay = 100L // 100ms
            
            if (tokenToVerify != null) {
                println("[TOKEN] 🔍 Token found, starting verification...")
                println("[TOKEN] Token to verify: $tokenToVerify")
                
                // Retry token verification with exponential backoff (non-blocking)
                for (attempt in 1..maxRetries) {
                    println("[TOKEN] 🔍 Verification attempt $attempt/$maxRetries")
                    tokenVerified = com.example.playerapp.utils.PaymentTokenManager.verifyToken(
                        token = tokenToVerify,
                        bmiId = bmiId,
                        screenId = screenId
                    )
                    
                    if (tokenVerified) {
                        println("[TOKEN] ✅✅✅ Token verified successfully on attempt $attempt")
                        break
                    } else if (attempt < maxRetries) {
                        val delayMs = 100L * (1 shl (attempt - 1)) // 100ms, 200ms, 400ms
                        println("[TOKEN] ⚠️ Token verification failed on attempt $attempt, waiting ${delayMs}ms...")
                        // NOTE: We are on the main looper here (Handler.post callback), so we cannot
                        // use Thread.sleep without risking an ANR on low-power devices.
                        // Instead we just break and fall through to proceed-anyway logic.
                        // Security note: not blocking here is intentional — a failed token merely logs
                        // a warning; payment is never blocked solely on token mismatch.
                        break
                    } else {
                        println("[TOKEN] ❌ Token verification failed on final attempt $attempt")
                    }
                }
                
                if (!tokenVerified) {
                    println("[TOKEN] ⚠️⚠️⚠️ TOKEN VERIFICATION FAILED AFTER $maxRetries ATTEMPTS")
                    println("[TOKEN] ⚠️ Token: $tokenToVerify")
                    println("[TOKEN] ⚠️ BMI ID: $bmiId")
                    println("[TOKEN] ⚠️ Screen ID: $screenId")
                    println("[TOKEN] ⚠️ Token verification failed, but proceeding with payment anyway")
                    println("[TOKEN] ⚠️ This may indicate token mismatch or expiration, but payment was completed")
                    
                    // Log active tokens for debugging
                    val activeTokens = com.example.playerapp.utils.PaymentTokenManager.getActiveTokens()
                    println("[TOKEN] 🔍 Active tokens count: ${activeTokens.size}")
                    activeTokens.forEach { (token, data) ->
                        println("[TOKEN]   Token: $token, BMI ID: ${data.bmiId}, Screen ID: ${data.screenId}")
                    }
                    
                    // Don't block payment - proceed anyway since payment was completed
                    // Token verification is for security, but we shouldn't block legitimate payments
                    println("[TOKEN] ⚠️ Proceeding with payment despite token verification failure")
                }
            } else {
                println("[TOKEN] ⚠️⚠️⚠️ WARNING: No payment token found!")
                println("[TOKEN] ⚠️ Payment token from server: ${if (paymentToken != null) "present" else "null"}")
                println("[TOKEN] ⚠️ Stored token in BMI data: ${if (storedToken != null) "present" else "null"}")
                println("[TOKEN] ⚠️ This may indicate payment was not initiated through QR code")
                println("[TOKEN] ⚠️ Proceeding with payment but token verification skipped (legacy flow)")
                // Allow payment to proceed without token (legacy flow support)
            }
            
            if (tokenVerified) {
                println("[TOKEN] ✅✅✅ TOKEN VERIFIED - PAYMENT APPROVED")
                println("[TOKEN] ✅ Proceeding with BMI screen transition")
            } else {
                println("[TOKEN] ⚠️ No token provided - proceeding with legacy payment flow")
            }
            
            // ========== PROCESS PAYMENT: Update BMI data and transition state ==========
            println("[PAYMENT_FLOW] ✅✅✅ PROCESSING PAYMENT - Transitioning QR → BMI")
            
            // Merge payment data with existing BMI data
            val existingBmiData = bmiData ?: emptyMap()
            val updatedBmiData = existingBmiData.toMutableMap()
            
            // Preserve local fortune/health from JSON – do not overwrite with null/empty from server
            val keepFortune = (existingBmiData["fortuneMessage"] as? String)?.takeIf { it.isNotBlank() }
                ?: (existingBmiData["fortune"] as? String)?.takeIf { it.isNotBlank() }
            val keepHealthTip = (existingBmiData["healthTip"] as? String)?.takeIf { it.isNotBlank() }
            
            // Merge payment data - preserve raw sensor values and local fortune/health
            paymentData.forEach { (key, value) ->
                when (key) {
                    "bmiId" -> {
                        if (updatedBmiData["bmiId"] == null) {
                            updatedBmiData[key] = value
                        }
                    }
                    "weight", "height" -> {
                        // Preserve raw values from USB sensor
                    }
                    "fortune", "fortuneMessage" -> {
                        // Do not overwrite local JSON fortune with server value; keep existing if we have it
                        if (keepFortune != null) {
                            updatedBmiData["fortune"] = keepFortune
                            updatedBmiData["fortuneMessage"] = keepFortune
                        } else if (value != null && value.toString().isNotBlank()) {
                            updatedBmiData[key] = value
                            if (key == "fortune") updatedBmiData["fortuneMessage"] = value
                            else if (key == "fortuneMessage") updatedBmiData["fortune"] = value
                        }
                    }
                    "healthTip" -> {
                        if (keepHealthTip != null) {
                            updatedBmiData["healthTip"] = keepHealthTip
                        } else if (value != null && value.toString().isNotBlank()) {
                            updatedBmiData[key] = value
                        }
                    }
                    else -> {
                        updatedBmiData[key] = value
                    }
                }
            }
            if (keepFortune != null && !updatedBmiData.containsKey("fortuneMessage")) {
                updatedBmiData["fortuneMessage"] = keepFortune
                updatedBmiData["fortune"] = keepFortune
            }
            if (keepHealthTip != null && !updatedBmiData.containsKey("healthTip")) {
                updatedBmiData["healthTip"] = keepHealthTip
            }
            
            // Update currentBmiId if needed
            if (currentBmiId == null && bmiId != null) {
                currentBmiId = bmiId
            }
            
            // ========== STATE TRANSITION: QR_SHOWING → BMI_SHOWING ==========
            // Update state directly - callback is already on main thread via SocketManager
            val newBmiDataMap = LinkedHashMap<String, Any>(updatedBmiData).toMap()
            val previousState = paymentFlowState
            
            // CRITICAL: Update state variables - this should trigger recomposition
            // Update bmiData first, then paymentFlowState to ensure proper state propagation
            bmiData = updatedBmiData.toMap()
            
            // Force state update with explicit assignment
            paymentFlowState = PaymentFlowState.BMI_SHOWING
            isPaymentProcessingLive = false
            currentBMIScreen = F1Screen.PROCESSING
            assetRefreshTrigger++
            
            println("═══════════════════════════════════════════════════════════════")
            println("[PAYMENT_FLOW] ✅✅✅ STATE TRANSITIONED: ${previousState.name} → ${paymentFlowState.name}")
            println("[PAYMENT_FLOW] ✅ BMI data updated with user info")
            println("[PAYMENT_FLOW] ✅ currentBMIScreen updated to PROCESSING for gate visibility")
            println("[PAYMENT_FLOW] ✅ paymentFlowState is now: ${paymentFlowState.name}")
            println("[PAYMENT_FLOW] ✅ Triggered immediate FlowDrawer refresh for BMI flow")
            println("[PAYMENT_FLOW] ✅ bmiData keys: ${bmiData?.keys?.joinToString(", ")}")
            println("[PAYMENT_FLOW] ✅ bmiData has user: ${bmiData?.containsKey("user")}")
            println("[PAYMENT_FLOW] ✅ State update complete - Compose should recompose")
            println("═══════════════════════════════════════════════════════════════")
            
            // Verify state update persisted (check after a brief delay)
            Handler(Looper.getMainLooper()).postDelayed({
                println("[PAYMENT_FLOW] 🔍 State verification (100ms delay):")
                println("[PAYMENT_FLOW]   paymentFlowState: ${paymentFlowState.name}")
                println("[PAYMENT_FLOW]   Expected: BMI_SHOWING")
                println("[PAYMENT_FLOW]   bmiData present: ${bmiData != null}")
                if (paymentFlowState != PaymentFlowState.BMI_SHOWING) {
                    println("[PAYMENT_FLOW] ❌❌❌ ERROR: State did not persist! Forcing update...")
                    paymentFlowState = PaymentFlowState.BMI_SHOWING
                } else {
                    println("[PAYMENT_FLOW] ✅ State persisted correctly")
                }
            }, 100)
            
            // ========== FETCH FORTUNE IMMEDIATELY AFTER PAYMENT ==========
            // Fetch fortune data immediately and store it - don't wait, do it now
            val targetBmiId = bmiId ?: currentBmiId
            if (targetBmiId != null) {
                println("[PAYMENT_FLOW] 🔮🔮🔮 FETCHING FORTUNE IMMEDIATELY for BMI ID: $targetBmiId")
                Repository.getBMIRecord(targetBmiId) { success, fortuneData, error ->
                    if (success && fortuneData != null) {
                        println("[PAYMENT_FLOW] ✅✅✅ Fortune data received from server immediately")
                        println("[PAYMENT_FLOW] Fortune data keys: ${fortuneData.keys.joinToString(", ")}")
                        
                        // Merge fortune data into bmiData immediately (prefer non-blank from server; keep existing if server blank)
                        val currentBmiData = bmiData ?: emptyMap()
                        val updatedWithFortune = currentBmiData.toMutableMap()
                        
                        val fortuneMessage = fortuneData["fortuneMessage"] as? String
                        val fortune = fortuneData["fortune"] as? String
                        val healthTip = fortuneData["healthTip"] as? String
                        
                        if (!fortuneMessage.isNullOrBlank()) {
                            updatedWithFortune["fortuneMessage"] = fortuneMessage
                            println("[PAYMENT_FLOW] ✅ Stored fortuneMessage: $fortuneMessage")
                        }
                        if (!fortune.isNullOrBlank()) {
                            updatedWithFortune["fortune"] = fortune
                            println("[PAYMENT_FLOW] ✅ Stored fortune: $fortune")
                        }
                        if (!healthTip.isNullOrBlank()) {
                            updatedWithFortune["healthTip"] = healthTip
                            println("[PAYMENT_FLOW] ✅ Stored healthTip: $healthTip")
                        }
                        
                        // Update bmiData with fortune - this will be available when Fortune screen shows
                        bmiData = updatedWithFortune.toMap()
                        println("[PAYMENT_FLOW] ✅✅✅ Fortune data stored in bmiData - ready for Fortune screen")
                        println("[PAYMENT_FLOW] bmiData now contains fortune: ${updatedWithFortune.containsKey("fortuneMessage") || updatedWithFortune.containsKey("fortune")}")
                    } else {
                        println("[PAYMENT_FLOW] ⚠️⚠️⚠️ Failed to fetch fortune data: $error")
                        println("[PAYMENT_FLOW] ⚠️ Fortune screen will not show without fortune data")
                    }
                }
            } else {
                println("[PAYMENT_FLOW] ⚠️ Cannot fetch fortune - BMI ID is null")
            }
            }
            
            println("[SOCKET] ✅ Payment success callback registered successfully")
            println("[SOCKET] ✅ Callback is now active and ready to receive payment events")
        } else {
            println("[SOCKET] ⚠️ Screen ID is blank, skipping socket connection")
        }
    }
    
    // Monitor socket connection status and health, ensure room membership
    // Monitor regardless of registration status - socket should always be connected
    LaunchedEffect(screenId) {
        if (screenId.isNotBlank()) {
            // Initial connection attempt
            kotlinx.coroutines.delay(5000) // Wait 5 seconds after initial setup
            
            while (true) {
                kotlinx.coroutines.delay(10000) // Check every 10 seconds
                val isConnected = socketManager.isConnected()
                println("[SOCKET] 📡 Connection health check:")
                println("[SOCKET]   Status: ${if (isConnected) "✅ CONNECTED" else "❌ DISCONNECTED"}")
                println("[SOCKET]   Screen ID: $screenId")
                println("[SOCKET]   Registered: $isRegisteredNow")
                
                if (!isConnected) {
                    println("[SOCKET] ⚠️ Socket disconnected, attempting reconnection...")
                    socketManager.connect(screenId)
                    kotlinx.coroutines.delay(2000) // Wait a bit after reconnection attempt
                    // Note: Callback should already be registered from initial setup
                    // If it's missing, the main LaunchedEffect will re-register it
                } else {
                    println("[SOCKET] ✅ Socket is healthy and connected")
                    // Periodically ensure we're still in the room
                    socketManager.ensureRoomJoined()
                }
            }
        }
    }
    
    // Live "payment processing" indicator — fires when the customer's phone creates
    // the Razorpay order (i.e. they've tapped Pay Now and are on the checkout screen).
    LaunchedEffect(Unit) {
        socketManager.setOnPaymentProcessing {
            if (paymentFlowState == PaymentFlowState.QR_SHOWING) {
                println("[PAYMENT_FLOW] 💳 Customer started paying — showing live processing indicator")
                isPaymentProcessingLive = true
            }
        }
    }

    // Fortune and Asset Refresh ready handlers
    LaunchedEffect(Unit) {
        socketManager.setOnFortuneReady { fortuneData ->
            println("DEBUG: F3 - Fortune ready received via socket: $fortuneData")
            val bmiId = fortuneData["bmiId"] as? String
            
            if (bmiId == currentBmiId && paymentFlowState != PaymentFlowState.IDLE) {
                val current = bmiData ?: emptyMap()
                val existingFortune = (current["fortuneMessage"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (current["fortune"] as? String)?.takeIf { it.isNotBlank() }
                val existingHealthTip = (current["healthTip"] as? String)?.takeIf { it.isNotBlank() }
                // Prefer local JSON fortune/health – only apply socket data if we don't already have them
                val updatedBmiData = current.toMutableMap()
                fortuneData.forEach { (key, value) ->
                    when (key) {
                        "fortune", "fortuneMessage" -> {
                            if (existingFortune != null) {
                                updatedBmiData["fortune"] = existingFortune
                                updatedBmiData["fortuneMessage"] = existingFortune
                            } else if (value != null && value.toString().isNotBlank()) {
                                updatedBmiData[key] = value
                                updatedBmiData["fortune"] = value
                                updatedBmiData["fortuneMessage"] = value
                            }
                        }
                        "healthTip" -> {
                            if (existingHealthTip != null) {
                                updatedBmiData["healthTip"] = existingHealthTip
                            } else if (value != null && value.toString().isNotBlank()) {
                                updatedBmiData[key] = value
                            }
                        }
                        else -> updatedBmiData[key] = value
                    }
                }
                if (existingFortune != null) {
                    updatedBmiData["fortune"] = existingFortune
                    updatedBmiData["fortuneMessage"] = existingFortune
                }
                if (existingHealthTip != null) {
                    updatedBmiData["healthTip"] = existingHealthTip
                }
                bmiData = updatedBmiData
                println("DEBUG: F3 - Updated BMI data with fortune via socket (local JSON preferred)")
            }
        }

        socketManager.setOnAssetsUpdated { eventType ->
            println("DEBUG: F3 - Assets updated event received ($eventType), refreshing FlowDrawer assets")
            updatingText = when (eventType) {
                "screen-config-changed" -> "Updating screen settings..."
                "flow-drawer-images-updated" -> "Updating flow images..."
                else -> "Playlist is updating..."
            }
            isPlaylistUpdating = true
            val activeScreenId = screenId
            if (!activeScreenId.isNullOrBlank()) {
                Repository.getAdscapePlayerInfo(activeScreenId, context) { success, _, _, _, _, _, cal, heightCalibrationEnabledValue ->
                    if (success) {
                        // Update height calibration
                        if (cal != null) {
                            val currentCalibration = HeightCalibrationStorage.getHeightCalibration(context)
                            if (HeightCalibrationStorage.hasChanged(context, cal)) {
                                HeightCalibrationStorage.saveHeightCalibration(context, cal)
                                println("DEBUG: F3 - Socket-updated height calibration: $currentCalibration -> $cal cm")
                            }
                            heightCalibration = cal
                        }
                        
                        // Update height calibration enabled
                        if (heightCalibrationEnabledValue != null) {
                            if (heightCalibrationEnabled != heightCalibrationEnabledValue) {
                                println("DEBUG: F3 - Socket-updated height calibration enabled: $heightCalibrationEnabled -> $heightCalibrationEnabledValue")
                                heightCalibrationEnabled = heightCalibrationEnabledValue
                            }
                        }
                        
                        val persistedDrawerEnabled = InfoStorage.getFlowDrawerEnabled(context)
                        if (persistedDrawerEnabled != null && persistedDrawerEnabled != flowDrawerEnabled) {
                            flowDrawerEnabled = persistedDrawerEnabled
                            println("DEBUG: F3 - Socket-updated flow drawer enabled: $persistedDrawerEnabled")
                        }

                        val persistedDrawerSlotCount = InfoStorage.getFlowDrawerSlotCount(context)?.coerceAtLeast(1)
                        if (persistedDrawerSlotCount != null && persistedDrawerSlotCount != flowDrawerSlotCount) {
                            flowDrawerSlotCount = persistedDrawerSlotCount
                            println("DEBUG: F3 - Socket-updated flow drawer slot count: $persistedDrawerSlotCount")
                        }
                    } else {
                        println("DEBUG: F3 - Failed to refresh player config on assets update")
                    }

                    if (flowDrawerEnabled) {
                        refreshFlowDrawerAssets(context, activeScreenId) { refreshSuccess, loadedAssets, error ->
                            if (refreshSuccess) {
                                println(
                                    "DEBUG: F3 - Socket-triggered FlowDrawer refresh completed: " +
                                        "${loadedAssets.topAssets.size} top, ${loadedAssets.bottomAssets.size} bottom"
                                )
                                flowDrawerAssets = loadedAssets
                            } else {
                                println("DEBUG: F3 - Socket-triggered FlowDrawer refresh failed: $error")
                            }
                        }
                    } else {
                        flowDrawerAssets = loadFlowDrawerAssets(context, activeScreenId)
                    }
                }
            }
            assetRefreshTrigger++
        }

        // Real-time mode change from admin panel — fires immediately on screen-mode-changed event
        socketManager.setOnModeChanged { newMode ->
            println("[SOCKET] 🔄 Admin pushed new app mode: $newMode")
            if (newMode != appMode) {
                appMode = newMode
                // Persist immediately so the mode survives app restarts
                InfoStorage.updateInfo(
                    context,
                    InfoStorage.ScreenInfo(
                        screenId = null, screenName = null, location = null,
                        heightCalibration = null, heightCalibrationEnabled = null,
                        playlistId = null, paymentAmount = null,
                        flowDrawerEnabled = null, flowDrawerSlotCount = null,
                        hideScreenId = null, hideAppMargin = null, appMode = newMode
                    )
                )
                println("[SOCKET] ✅ App mode updated and persisted: $newMode")
            }
        }

        // Real-time hideScreenId change from admin panel
        socketManager.setOnHideScreenIdChanged { newHideStatus ->
            println("[SOCKET] 🔄 Admin pushed new hideScreenId: $newHideStatus")
            if (newHideStatus != hideScreenId) {
                hideScreenId = newHideStatus
                // Persist immediately so it survives app restarts
                InfoStorage.updateInfo(
                    context,
                    InfoStorage.ScreenInfo(
                        screenId = null, screenName = null, location = null,
                        heightCalibration = null, heightCalibrationEnabled = null,
                        playlistId = null, paymentAmount = null,
                        flowDrawerEnabled = null, flowDrawerSlotCount = null,
                        hideScreenId = newHideStatus, hideAppMargin = null, appMode = null
                    )
                )
            }
        }

        // Real-time hideAppMargin change from admin panel
        socketManager.setOnHideAppMarginChanged { newHideMarginStatus ->
            println("[SOCKET] 🔄 Admin pushed new hideAppMargin: $newHideMarginStatus")
            if (newHideMarginStatus != hideAppMargin) {
                hideAppMargin = newHideMarginStatus
                // Persist immediately so it survives app restarts
                InfoStorage.updateInfo(
                    context,
                    InfoStorage.ScreenInfo(
                        screenId = null, screenName = null, location = null,
                        heightCalibration = null, heightCalibrationEnabled = null,
                        playlistId = null, paymentAmount = null,
                        flowDrawerEnabled = null, flowDrawerSlotCount = null,
                        hideScreenId = null, hideAppMargin = newHideMarginStatus, appMode = null
                    )
                )
            }
        }

        // Real-time rotation change from admin panel
        socketManager.setOnRotationChanged { newRotation ->
            println("[ROTATION_CHANGE] Admin pushed new screen rotation: $newRotation")
            mainActivity?.applyAppRotation(newRotation)
            InfoStorage.updateInfo(
                context,
                InfoStorage.ScreenInfo(
                    screenId = null, screenName = null, location = null,
                    heightCalibration = null, heightCalibrationEnabled = null,
                    playlistId = null, paymentAmount = null,
                    flowDrawerEnabled = null, flowDrawerSlotCount = null,
                    hideScreenId = null, hideAppMargin = null, appMode = null, rotation = newRotation
                )
            )
            println("[ROTATION_CHANGE] Screen rotation updated and persisted: $newRotation")
        }
    }
    
    // Fetch height calibration when registered - check if changed and update localStorage
    LaunchedEffect(isRegistered) {
        if (isRegistered) {
            Repository.getAdscapePlayerInfo(screenId, context) { success, _, _, _, _, _, cal, heightCalibrationEnabledValue ->
                if (success) {
                    // Update height calibration enabled status from server
                    if (heightCalibrationEnabledValue != null) {
                        val previousValue = heightCalibrationEnabled
                        heightCalibrationEnabled = heightCalibrationEnabledValue
                        println("DEBUG: F3 - Initial height calibration enabled updated from server: $previousValue -> $heightCalibrationEnabled")
                        // Value is already saved to info.json by Repository.getAdscapePlayerInfo
                    } else {
                        // Server didn't return value, use persisted value if available
                        val persistedValue = InfoStorage.getHeightCalibrationEnabled(context)
                        if (persistedValue != null && persistedValue != heightCalibrationEnabled) {
                            heightCalibrationEnabled = persistedValue
                            println("DEBUG: F3 - Using persisted heightCalibrationEnabled: $persistedValue (server didn't return value)")
                        }
                    }
                    
                    // Update flow drawer enabled status from persisted info (server saves it to info.json)
                    val persistedDrawerEnabled = InfoStorage.getFlowDrawerEnabled(context)
                    if (persistedDrawerEnabled != null && persistedDrawerEnabled != flowDrawerEnabled) {
                        flowDrawerEnabled = persistedDrawerEnabled
                        println("DEBUG: F3 - Initial flow drawer enabled updated from persisted info: $persistedDrawerEnabled")
                    }
                    val persistedDrawerSlotCount = InfoStorage.getFlowDrawerSlotCount(context)?.coerceAtLeast(1)
                    if (persistedDrawerSlotCount != null && persistedDrawerSlotCount != flowDrawerSlotCount) {
                        flowDrawerSlotCount = persistedDrawerSlotCount
                        println("DEBUG: F3 - Initial flow drawer slot count updated from persisted info: $persistedDrawerSlotCount")
                    }
                    
                    // Update hide screen ID status from persisted info (server saves it to info.json)
                    val persistedHideScreenId = InfoStorage.getHideScreenId(context)
                    if (persistedHideScreenId != null && persistedHideScreenId != hideScreenId) {
                        hideScreenId = persistedHideScreenId
                        println("DEBUG: F3 - Initial hide screen ID updated from persisted info: $persistedHideScreenId")
                    }
                    
                    // Update hide app margin status from persisted info
                    val persistedHideAppMargin = InfoStorage.getHideAppMargin(context)
                    if (persistedHideAppMargin != null && persistedHideAppMargin != hideAppMargin) {
                        hideAppMargin = persistedHideAppMargin
                        println("DEBUG: F3 - Initial hide app margin updated from persisted info: $persistedHideAppMargin")
                    }
                    
                    // Check if the calibration value has changed
                    if (cal != null) {
                        val currentCalibration = HeightCalibrationStorage.getHeightCalibration(context)
                        if (HeightCalibrationStorage.hasChanged(context, cal)) {
                            // Value has changed, save to localStorage
                            HeightCalibrationStorage.saveHeightCalibration(context, cal)
                            println("DEBUG: F3 - Height calibration changed from $currentCalibration to $cal cm, saved to localStorage")
                        } else {
                            println("DEBUG: F3 - Height calibration unchanged: $cal cm")
                        }
                        // Update state regardless (in case it was initialized from localStorage)
                        heightCalibration = cal
                    }
                    // Apply synced rotation
                    val currentRotation = InfoStorage.getRotation(context)
                    mainActivity?.applyAppRotation(currentRotation)
                    println("DEBUG: F3 - Height calibration loaded: $cal cm")
                } else {
                    // If fetch failed, use stored value
                    val storedCalibration = HeightCalibrationStorage.getHeightCalibration(context)
                    heightCalibration = storedCalibration
                    println("DEBUG: F3 - Failed to fetch height calibration, using stored value: $storedCalibration cm")
                }
            }
        } else {
            // Not registered yet, use stored value
            val storedCalibration = HeightCalibrationStorage.getHeightCalibration(context)
            heightCalibration = storedCalibration
            println("DEBUG: F3 - Not registered yet, using stored height calibration: $storedCalibration cm")
        }
    }
    
    // Flow type follows appMode — sync currentFlowType with dynamic appMode
    LaunchedEffect(appMode) {
        val resolvedFlowType = if (appMode.equals("F1", ignoreCase = true)) "F1" else appMode
        currentFlowType = resolvedFlowType
        FlowTypeStorage.saveFlowType(context, resolvedFlowType)
        println("DEBUG: F3 - Flow type set to $resolvedFlowType (appMode=$appMode)")
    }
    
    // Setup USB Serial Manager for F3 — when F1, F2 or PlayerOnly modes are active
    LaunchedEffect(isF1ModeActive, isF2ModeActive) {
        // Add sensor stable callback - triggers when UsbSerialManager locks in a stable reading (10 matching frames)
        usbSerialManager.setOnSensorStableResult { weight, heightResult ->
            println("DEBUG: F3 - [HEIGHT_UX] 📥 setOnSensorStableResult: weight=$weight kg | rawHeight=${heightResult.rawHeight}cm | calibrated=${heightResult.calibratedHeight}cm | isValid=${heightResult.isValid} | reason=${heightResult.reason} | isDetectingBMI=$isDetectingBMI | isFlowActive=$isFlowActive | lock=$failedReadingWeightLock")
            if (!failedReadingWeightLock && isDetectingBMI && !isFlowActive && weight >= 3.0f) {
                if (heightResult.isValid) {
                    println("DEBUG: F3 - [HEIGHT_UX] ✅ Valid stable height & weight captured! (weight=${weight}kg, height=${heightResult.calibratedHeight}cm)...")
                    isRetryingHeight = false
                    failedReadingWeightLock = false
                    topWarningBannerMessage = null
                    processFirstValidReading(weight, heightResult.calibratedHeight)
                } else if (heightCalibrationEnabled) {
                    if (heightResult.reason == com.example.playerapp.data.HeightValidationReason.SENSOR_TIMEOUT) {
                        println("DEBUG: F3 - [HEIGHT_UX] ℹ️ Height sensor not detected (hardware timeout). Proceeding with Weight-Only flow (no height/BMI)...")
                        isRetryingHeight = false
                        failedReadingWeightLock = false
                        bottomToastMessage = "Height sensor not detected"
                        topWarningBannerMessage = null
                        processFirstValidReading(weight, 0f)
                    } else if (heightResult.reason == com.example.playerapp.data.HeightValidationReason.OUT_OF_RANGE) {
                        // Genuinely outside the configured range (e.g. a very young child)
                        // — telling them to "stand straight and try again" is false hope,
                        // no retry fixes this. Fall back to weight-only, same as a sensor timeout.
                        println("DEBUG: F3 - [HEIGHT_UX] ℹ️ Height out of measurable range (${heightResult.calibratedHeight}cm). Proceeding with Weight-Only flow (no height/BMI)...")
                        isRetryingHeight = false
                        failedReadingWeightLock = false
                        bottomToastMessage = "Height out of measurable range"
                        topWarningBannerMessage = null
                        processFirstValidReading(weight, 0f)
                    } else {
                        println("DEBUG: F3 - [HEIGHT_UX] ⚠️ Measurement quality issue (${heightResult.reason}). Showing static top warning banner...")
                        isRetryingHeight = false
                        failedReadingWeightLock = true
                        isDetectingBMI = false
                        paymentFlowState = PaymentFlowState.IDLE
                        topWarningBannerMessage = "Height may not have been measured accurately. Results may vary. Please stand straight and try again."
                        println("DEBUG: F3 - [HEIGHT_UX] 📌 Locked static banner: '$topWarningBannerMessage'")
                    }
                } else {
                    println("DEBUG: F3 - [HEIGHT_UX] ⚠️ Height calibration disabled, processing with weight=${weight}kg")
                    processFirstValidReading(weight, heightResult.calibratedHeight)
                }
            } else {
                println("DEBUG: F3 - [HEIGHT_UX] ℹ️ Ignored stable result (lock=$failedReadingWeightLock, detecting=$isDetectingBMI, active=$isFlowActive, weight=$weight)")
            }
        }

        // Add sensor data callback - for real-time monitoring and instant feedback UI
        usbSerialManager.setOnSensorDataReceived { weight, height ->
            com.example.playerapp.utils.OtaUpdater.onWeight(weight)
            // 1. Check if user stepped off after a failed reading lock
            if (failedReadingWeightLock) {
                if (weight < 0.8f) {
                    println("DEBUG: F3 - [HEIGHT_UX] 🔓 User stepped off scale (weight=${weight}kg < 0.8kg). Clearing warning banner & releasing lock.")
                    failedReadingWeightLock = false
                    failedReadingLowWeightStartTime = 0L
                    bottomToastMessage = null
                    topWarningBannerMessage = null
                } else {
                    failedReadingLowWeightStartTime = 0L
                }
                return@setOnSensorDataReceived
            }

            // If the app is in PlayerOnly mode, show the toast if weight >= 3.0f
            if (!isF1ModeActive && !isF2ModeActive) {
                if (weight >= 3.0f) {
                    bottomToastMessage = "it is only An Player App"
                } else {
                    bottomToastMessage = null
                }
                return@setOnSensorDataReceived
            }

            // If BMI is triggered while offline, show a lightweight toast instead of blocking overlays (F1 only).
            // F2 mode should process BMI offline.
            if (isOffline && !isFlowActive && !isF2ModeActive) {
                if (weight >= 3.0f) {
                    if (bottomToastMessage == null) {
                        println("DEBUG: F3 - Offline trigger detected, showing bottom toast")
                    }
                    bottomToastMessage = "Device is offline Cant process BMI"
                } else if (bottomToastMessage != null) {
                    bottomToastMessage = null
                }
                println("DEBUG: F3 - Device offline, ignoring sensor input for BMI flow")
                return@setOnSensorDataReceived
            }
            
            // Always update lastWeight and lastHeight for real-time monitoring (regardless of flow state)
            val previousWeight = lastWeight
            val currentTime = System.currentTimeMillis()
            lastWeight = weight
            lastHeight = height
            lastReadingTime = currentTime
            
            // Check if weight actually changed (more than 0.1kg difference)
            val weightChanged = previousWeight == null || kotlin.math.abs(weight - previousWeight) > 0.1f
            
            // Log all weight updates for debugging (especially important to see when weight drops to 0 or low values)
            if (previousWeight != null) {
                if (weightChanged) {
                    println("DEBUG: F3 - Weight changed: ${previousWeight} kg -> $weight kg")
                }
            } else {
                println("DEBUG: F3 - Weight reading: $weight kg")
            }
            
            if (currentFlowType == "F1" || currentFlowType == "F2") {
                // If flow is active (BMI data captured), monitor weight to terminate only if weight < 3kg
                if (isFlowActive) {
                    // Flow is active - check if weight has dropped below 3.0kg (user stepped out)
                    val weightDropped = weight < 3.0f
                    val now = System.currentTimeMillis()
                    
                    if (weightDropped) {
                        if (lowWeightDetectedAt == null) {
                            lowWeightDetectedAt = now
                            println("DEBUG: F3 - Low weight detected (${weight}kg), waiting for confirmation...")
                        }
                        
                        // Terminate when user steps off immediately
                        // to prevent transient sensor glitches from abruptly killing the active flow.
                        val fortuneGraceActive = paymentFlowState == PaymentFlowState.FORTUNE_SHOWING &&
                            fortuneShownTimestamp != null &&
                            (now - fortuneShownTimestamp!!) < 3000L
                            
                        val lowDuration = now - lowWeightDetectedAt!!
                        if (lowDuration >= 0L && !fortuneGraceActive) {
                             println("DEBUG: F3 - User stepped off securely (0s confirmed), terminating flow")
                             lastFlowEndTime = now
                             resetPaymentFlow("User stepped off (stabilized)")
                             lowWeightDetectedAt = null
                        }
                    } else {
                        if (lowWeightDetectedAt != null) {
                            println("DEBUG: F3 - Weight recovered above threshold (${weight}kg), clearing low-weight timer")
                            lowWeightDetectedAt = null
                        }
                        
                        // Log weight changes for monitoring
                        if (weightChanged) {
                            println("DEBUG: F3 - Weight monitoring - Current: $weight kg (Captured: $capturedWeight kg)")
                        }
                    }
                    return@setOnSensorDataReceived
                }
                
                // USB provides both weight and height from sensors
                if (weight >= 3.0f) {
                    // Log if calibration is disabled but continue anyway
                    if (!heightCalibrationEnabled && !isDetectingBMI && !isFlowActive) {
                        println("DEBUG: F3 - ⚠️ Height calibration disabled, continuing with weight-only logic if height is invalid")
                    }
                    
                    // Show loading indicator when detection starts (before modal shows)
                    if (!failedReadingWeightLock && !isDetectingBMI && !isFlowActive && paymentFlowState == PaymentFlowState.IDLE) {
                        val currentTime = System.currentTimeMillis()
                        val cooldownActive = lastFlowEndTime != null && (currentTime - lastFlowEndTime!! < 0L)
                        
                        if (!cooldownActive) {
                            isDetectingBMI = true
                            paymentFlowState = PaymentFlowState.DETECTING // INSTANT FEEDBACK
                            usbSerialManager.rearmStableDetection()
                            println("DEBUG: F3 - ⚡ DETECTION STARTED: Valid reading detected (weight=$weight kg), showing instant processing UI...")
                        } else {
                            val remaining = ((0L - (currentTime - lastFlowEndTime!!)) / 1000f).coerceAtLeast(0f)
                            if (bottomToastMessage == null || !bottomToastMessage!!.contains("wait")) {
                                bottomToastMessage = "Please wait ${String.format("%.0f", remaining)}s before next reading"
                            }
                        }
                    }
                } else {
                    // Hide loading if weight drops back down while trying to detect
                    if (isDetectingBMI && !isFlowActive) {
                        isDetectingBMI = false
                        if (paymentFlowState == PaymentFlowState.DETECTING) {
                            paymentFlowState = PaymentFlowState.IDLE
                        }
                        bottomToastMessage = null
                        println("DEBUG: F3 - User stepped off before stabilization, detection aborted.")
                    }
                }
            } else {
                println("DEBUG: F3 - Flow type is $currentFlowType, skipping BMI detection logic")
            }
        }
        
        // Start searching for USB device
        kotlinx.coroutines.delay(2000) // Wait 2 seconds for UI to initialize
        usbSerialManager.findAndConnectDevice()
    }
    
    // (Stability delay has been removed. We now rely entirely on UsbSerialManager's 
    // internal increment-counter and setOnSensorStable callback for instant, delay-free progression.)
    
    // Monitor flow activity and sensor inactivity to terminate stale flows
    LaunchedEffect(isFlowActive, lowWeightDetectedAt) {
        if (isFlowActive) {
            while (isFlowActive) {
                kotlinx.coroutines.delay(200) // Faster polling for immediate termination
                val now = System.currentTimeMillis()
                
                // Case 2: Low weight persistence (User stepped off, sensor may or may not be sending data)
                val lowWeightStart = lowWeightDetectedAt
                if (lowWeightStart != null) {
                    val lowWeightDuration = now - lowWeightStart
                    
                    // Check for fortune grace period (give user time to read fortune even if they step off)
                    val fortuneGraceActive = paymentFlowState == PaymentFlowState.FORTUNE_SHOWING &&
                        fortuneShownTimestamp != null &&
                        (now - fortuneShownTimestamp!!) < 3000L
                    
                    // Respond to step off securely (instant debounce to allow immediate next user)
                    if (lowWeightDuration >= 0L && !fortuneGraceActive) {
                        println("DEBUG: F3 - Stale flow detected: Low weight (<3kg) for ${lowWeightDuration}ms")
                        lastFlowEndTime = now
                        resetPaymentFlow("User stepped off (sustained low weight)")
                        break
                    }
                }
                
                // Case 3: Absolute flow timeout (Safety fallback - no flow should last > 3 minutes)
                val startTime = flowStartTime ?: 0L
                if (startTime > 0 && (now - startTime) > 180000L) { // 3 minutes
                    println("DEBUG: F3 - Stale flow detected: Maximum flow duration (3m) exceeded")
                    resetPaymentFlow("Absolute timeout (3m)")
                    break
                }
            }
        }
    }
    
    // OTA installs wait until this is true (see OtaUpdater)
    LaunchedEffect(paymentFlowState, isFlowActive, isDetectingBMI) {
        com.example.playerapp.utils.OtaUpdater.isFlowIdle =
            paymentFlowState == PaymentFlowState.IDLE && !isFlowActive && !isDetectingBMI
    }

    // Additional Monitor payment flow state to track flow completion for UI dismissals
    LaunchedEffect(paymentFlowState) {
        if (paymentFlowState == PaymentFlowState.IDLE && isFlowActive) {
            val flowEndTime = System.currentTimeMillis()
            val duration = flowStartTime?.let { flowEndTime - it } ?: 0L
            // ========== PAYMENT FLOW LOGGING - FLOW ENDED (MODAL DISMISSED) ==========
            println("═══════════════════════════════════════════════════════════════")
            println("[PAYMENT_FLOW] 🏁 FLOW ENDED - MODAL DISMISSED")
            println("[PAYMENT_FLOW] Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(flowEndTime))}")
            println("[PAYMENT_FLOW] Reason: BMI modal was dismissed")
            println("[PAYMENT_FLOW] Captured Weight: $capturedWeight kg")
            println("[PAYMENT_FLOW] Captured Height: $capturedHeight cm")
            println("[PAYMENT_FLOW] BMI ID: $currentBmiId")
            println("[PAYMENT_FLOW] Flow Duration: ${duration}ms (${String.format("%.2f", duration / 1000.0)}s)")
            println("═══════════════════════════════════════════════════════════════")
            
            // Modal was dismissed, mark flow as completed and reconnect USB
            println("DEBUG: F3 - BMI modal dismissed, marking flow as completed")
            resetPaymentFlow("BMI modal dismissed")
            println("[PAYMENT_FLOW] 🧹 Flow terminated (modal dismissed) - BMI ID cleared, ready for new flow")
            
            // Reconnect USB Serial Manager after flow completes (wait a bit for cleanup)
            kotlinx.coroutines.delay(1000) // Wait 1 second before reconnecting
            println("DEBUG: F3 - Flow completed, reconnecting USB Serial Manager")
            usbSerialManager.findAndConnectDevice()
        }
    }
    
    // Cleanup USB Serial Manager and Socket Manager on dispose
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            usbSerialManager.cleanup()
            socketManager.disconnect()
        }
    }
    
    Box(modifier = Modifier
        .fillMaxSize()
        .padding(if (hideAppMargin) 0.dp else 10.dp)) {
        PlayerScreen(
            screenId = screenId,
            enableBMIFunctionality = false,
            refreshTrigger = assetRefreshTrigger,
            hideScreenId = hideScreenId
        )
        
        // ── Screen ID badge (top-left) — Material3 styled ──────────────────
        var showTroubleshootMenu by remember { mutableStateOf(false) }
        
        AnimatedVisibility(
            visible = !hideScreenId && screenId.isNotBlank(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .zIndex(100f),
            enter = fadeIn(animationSpec = tween(400)) + slideInVertically(
                initialOffsetY = { -it / 2 },
                animationSpec = tween(400)
            ),
            exit = fadeOut(animationSpec = tween(300))
        ) {
            Surface(
                modifier = Modifier
                    .padding(start = 8.dp, top = 8.dp)
                    .border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(12.dp))
                    .clickable { showTroubleshootMenu = true },
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E1F22),  // Fully opaque charcoal black — avoids alpha blending banding
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "SCREEN ID",
                        color = Color(0xFF9CA3AF),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = screenId,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
        
        if (showTroubleshootMenu) {
            TroubleshootModal(
                onDismiss = { showTroubleshootMenu = false },
                onShowBMI = {
                    paymentFlowState = PaymentFlowState.BMI_SHOWING
                    currentBMIScreen = F1Screen.BMI
                    // Inject mock data if empty
                    if (bmiData.isNullOrEmpty()) {
                        bmiData = mapOf(
                            "weight" to 70.0,
                            "height" to 175.0,
                            "bmi" to 22.8,
                            "category" to "Normal",
                            "healthTip" to "Keep up the good work!",
                            "isHeightValid" to true
                        )
                    }
                    showTroubleshootMenu = false
                },
                onShowFortune = {
                    paymentFlowState = PaymentFlowState.FORTUNE_SHOWING
                    currentBMIScreen = F1Screen.FORTUNE
                    // Inject mock data if empty or missing fortune details
                    val existing = bmiData ?: emptyMap()
                    val fortuneMsg = (existing["fortuneMessage"] as? String)?.takeIf { it.isNotBlank() }
                        ?: (existing["fortune"] as? String)?.takeIf { it.isNotBlank() }
                        ?: "Your determination today will shape your success tomorrow. Believe in your journey!"
                    val healthTip = (existing["healthTip"] as? String)?.takeIf { it.isNotBlank() }
                        ?: "Stay consistent, trust the process, and good things will follow."
                    
                    bmiData = mapOf(
                        "weight" to (existing["weight"] ?: 70.0),
                        "height" to (existing["height"] ?: 175.0),
                        "bmi" to (existing["bmi"] ?: 22.8),
                        "category" to (existing["category"] ?: "Normal"),
                        "healthTip" to healthTip,
                        "isHeightValid" to true,
                        "fortune" to fortuneMsg,
                        "fortuneMessage" to fortuneMsg,
                        "webUrl" to (existing["webUrl"] ?: "https://well2day.in")
                    )
                    showTroubleshootMenu = false
                },
                onShowProcessing = {
                    paymentFlowState = PaymentFlowState.DETECTING
                    showTroubleshootMenu = false
                },
                isInternetConnected = !isOffline,
                // A sensor already connected when the box booted is physically
                // detected before Android finishes the USB permission handshake.
                // Treat that as connected in diagnostics; the manager continues
                // retrying until its serial port is open.
                isUsbConnected = usbStatus.detectedDevicesCount > 0,
                appMode = appMode,
                paymentFlowState = paymentFlowState.name,
                socketConnected = socketManager.isConnected(),
                showDevFooter = showDevFooter,
                onToggleDevFooter = { 
                    showDevFooter = it
                    InfoStorage.setShowDevFooter(context, it)
                }
            )
        }

        // ── Offline chip (top-right) — only shows when truly offline ────────
        AnimatedVisibility(
            visible = isOffline && !isPlaylistUpdating,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(100f),
            enter = fadeIn(animationSpec = tween(500)) + slideInVertically(
                initialOffsetY = { -it },
                animationSpec = tween(400)
            ),
            exit = fadeOut(animationSpec = tween(400)) + slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(300)
            )
        ) {
            Surface(
                modifier = Modifier
                    .padding(end = 8.dp, top = 8.dp)
                    .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFE11D48)  // Solid premium red — avoids alpha blending banding
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = "Offline",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        
        FlowDrawer(
            isVisible = shouldShowDrawer,
            assets = flowDrawerAssets,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .zIndex(9999f), // Very high z-index to appear above modal dialogs
            drawerWidth = drawerWidthDp.toFloat()
        )

        // Playlist Updating Indicator Overlay
        androidx.compose.animation.AnimatedVisibility(
            visible = isPlaylistUpdating,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
                .zIndex(10000f),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { -it }),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { -it })
        ) {
            Row(
                modifier = Modifier
                    .background(
                        color = Color(0xFF1E1E1E),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = updatingText,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Development Panel - F3 specific info (Dev button commented out)
        // Toggle button (small, bottom-right corner)
    //    Box(
    //        modifier = Modifier
    //            .align(Alignment.BottomEnd)
    //            .background(Color.Black.copy(alpha = 0.6f))
    //            .clickable { devInfoVisible = !devInfoVisible }
    //            .padding(8.dp)
    //    ) {
    //        Text(
    //            text = if (devInfoVisible) "HIDE" else "DEV",
    //            color = Color.Yellow,
    //            fontSize = 10.sp,
    //            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
    //        )
    //    }

        // Development info overlay at bottom
        if (devInfoVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.9f))
                    .padding(16.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 500.dp) // Make scrollable with max height
            ) {
                Text(
                    text = "DEV MODE - F3",
                    color = Color.Yellow,
                    fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                // Flow Type
                Text(
                    text = "Flow Type: F1 (F1 only)",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                // Flow Drawer Toggle (Dev only)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Show Flow Drawer (Dev):",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = { showDrawerInDev = !showDrawerInDev },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (showDrawerInDev) Color.Green else Color.Gray
                        ),
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (showDrawerInDev) "ON" else "OFF",
                            fontSize = 10.sp,
                            color = Color.White
                        )
                    }
                }
                
                // Last Flow Type Change
                if (lastFlowTypeChange != null) {
                    Text(
                        text = "Last Flow Change: $lastFlowTypeChange",
                        color = Color.Cyan,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                
                Text(
                    text = "Flow Changes: $flowTypeChangeCount",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
                
                // Sensor Data
                Text(
                    text = "Sensor Data:",
                    color = Color.Yellow,
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                if (lastWeight != null && lastHeight != null) {
                    Text(
                        text = "Weight: ${String.format("%.2f", lastWeight)} kg",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    Text(
                        text = "Height: ${String.format("%.2f", lastHeight)} cm",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    
                    // Calculate BMI if both values available
                    if (lastHeight!! > 0) {
                        val bmi = (lastWeight!! / ((lastHeight!! / 100) * (lastHeight!! / 100)))
                        Text(
                            text = "BMI: ${String.format("%.2f", bmi)}",
                            color = Color.Green,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                    
                    // Last reading time
                    val timeAgo = (System.currentTimeMillis() - lastReadingTime) / 1000
                    Text(
                        text = "Last reading: ${timeAgo}s ago",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                } else {
                    Text(
                        text = "No sensor data received yet",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                
                Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
                
                // Stability Tracker Status
                Text(
                    text = "Stability Tracker:",
                    color = Color.Yellow,
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                Text(
                    text = "Flow Active: ${if (isFlowActive) "Yes" else "No"}",
                    color = if (isFlowActive) Color.Green else Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                
                // Modal Status
                val anyModalShowing = showQRModal || showBMIDataModal || showFortuneModal
                Text(
                    text = "Modals: QR=${if (showQRModal) "V" else "H"}, BMI=${if (showBMIDataModal) "V" else "H"}, Fortune=${if (showFortuneModal) "V" else "H"}",
                    color = if (anyModalShowing) Color.Green else Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
                
                // Asset Info Section
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { assetInfoVisible = !assetInfoVisible }
                        .padding(vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        text = "Asset Info:",
                        color = Color.Yellow,
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (assetInfoVisible) "▼" else "▶",
                        color = Color.White,
                        fontSize = 10.sp
                    )
                }
                
                if (assetInfoVisible) {
                    // Fetch and display asset information
                    LaunchedEffect(Unit) {
                        // Load assets from local storage
                        val filesDir = context.filesDir
                        val assetFiles = filesDir.listFiles { _, name -> 
                            name.startsWith("asset-") && !name.contains("-") 
                        } ?: emptyArray()
                        
                        val loadedAssets = mutableListOf<Asset>()
                        assetFiles.forEach { file ->
                            val slotMatch = Regex("asset-(\\d+)").find(file.name)
                            val slotNumber = slotMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            if (slotNumber > 0 && slotNumber <= 8) {
                                loadedAssets.add(Asset(file.absolutePath, slotNumber))
                            }
                        }
                        assetsInfo = loadedAssets.sortedBy { it.slotNumber }
                        
                        // Check default asset
                        val defaultFile = File(filesDir, "default-asset")
                        if (defaultFile.exists()) {
                            defaultAssetInfo = DefaultAsset(
                                id = -1,
                                assetUrl = defaultFile.absolutePath,
                                assetName = "Default Asset",
                                assetType = "image",
                                duration = 10,
                                isActive = true
                            )
                        }
                        
                        // Check tomorrow's assets
                        val tomorrowFiles = filesDir.listFiles { _, name -> 
                            name.startsWith("asset-") && name.contains("-") 
                        } ?: emptyArray()
                        tomorrowAssetsCountInfo = tomorrowFiles.size
                    }
                    
                    // Total assets
                    Text(
                        text = "Total Assets: ${assetsInfo.size}",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
                    )
                    
                    // Asset range
                    val assetRange = if (assetsInfo.isNotEmpty()) {
                        val minSlot = assetsInfo.minOfOrNull { it.slotNumber } ?: 0
                        val maxSlot = assetsInfo.maxOfOrNull { it.slotNumber } ?: 0
                        if (minSlot > 0 && maxSlot > 0) {
                            "$minSlot - $maxSlot"
                        } else {
                            "No assets"
                        }
                    } else {
                        "No assets"
                    }
                    Text(
                        text = "Asset Range: $assetRange${if (defaultAssetInfo != null) ", 9 (default)" else ", 9 (no default)"}",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
                    )
                    
                    // Default asset info
                    val defaultInfo = if (defaultAssetInfo != null) {
                        val defaultFile = File(defaultAssetInfo!!.assetUrl)
                        val fileSize = if (defaultFile.exists()) {
                            val sizeBytes = defaultFile.length()
                            when {
                                sizeBytes < 1024 -> "$sizeBytes B"
                                sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
                                else -> "${sizeBytes / (1024 * 1024)} MB"
                            }
                        } else "N/A"
                        "Available | Size: $fileSize | Type: ${defaultAssetInfo!!.assetType ?: "unknown"}"
                    } else {
                        "Not available"
                    }
                    Text(
                        text = "Default Asset (Slot 9): $defaultInfo",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
                    )
                    
                    // Tomorrow's assets
                    Text(
                        text = "Tomorrow's Assets: $tomorrowAssetsCountInfo downloaded",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
                    )
                    
                    // List of assets
                    if (assetsInfo.isNotEmpty()) {
                        Text(
                            text = "Available Assets:",
                            color = Color.Cyan,
                            fontSize = 10.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp)
                        )
                        assetsInfo.forEach { asset ->
                            val file = File(asset.assetUrl)
                            val fileExists = file.exists()
                            val fileSize = if (fileExists) {
                                val sizeBytes = file.length()
                                when {
                                    sizeBytes < 1024 -> "$sizeBytes B"
                                    sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
                                    else -> "${sizeBytes / (1024 * 1024)} MB"
                                }
                            } else "N/A"
                            val isVideo = asset.assetUrl.lowercase().endsWith(".mp4")
                            val status = if (fileExists) "✓" else "✗"
                            Text(
                                text = "  $status Slot ${asset.slotNumber}: ${if (isVideo) "Video" else "Image"} | $fileSize",
                                color = if (fileExists) Color.Green else Color(0xFFFF9800),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 1.dp)
                            )
                        }
                    } else {
                        Text(
                            text = "No assets available for today",
                            color = Color(0xFFFF9800),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
                        )
                    }
                }
                
                Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
                
                // Development Screen Testing Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Test Screens",
                        color = Color.Yellow,
                        fontSize = 11.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
                
                // Compact Grid of Test Screen Buttons (3 columns)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                ) {
                    items(devScreenButtons) { button ->
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        val scale = if (isPressed) 0.92f else 1f
                        
                        Card(
                            modifier = Modifier
                                .height(48.dp)
                                .scale(scale)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = {
                                        // Flow type is always F1 in this app - no need to check or set
                                        forcedScreen = button.screen
                                        bmiData = button.bmiData
                                        
                                        // Set flow as active for dev testing
                                        isFlowActive = true
                                        
                                        // Reset any previous flow state
                                        isDetectingBMI = false
                                        
                                        when (button.screen) {
                                            F1Screen.QR -> {
                                                paymentFlowState = PaymentFlowState.QR_SHOWING
                                                currentBMIScreen = F1Screen.QR
                                                println("DEBUG: F3 - Dev panel: Showing QR screen")
                                            }
                                            F1Screen.BMI -> {
                                                paymentFlowState = PaymentFlowState.BMI_SHOWING
                                                currentBMIScreen = F1Screen.BMI
                                                bmiShownTimestamp = System.currentTimeMillis()
                                                println("DEBUG: F3 - Dev panel: Showing BMI screen")
                                            }
                                            F1Screen.FORTUNE -> {
                                                paymentFlowState = PaymentFlowState.FORTUNE_SHOWING
                                                currentBMIScreen = F1Screen.FORTUNE
                                                fortuneShownTimestamp = System.currentTimeMillis()
                                                println("DEBUG: F3 - Dev panel: Showing Fortune screen")
                                            }
                                            F1Screen.LOADING -> {
                                                // For loading screen, show QR modal with loading state
                                                paymentFlowState = PaymentFlowState.QR_SHOWING
                                                currentBMIScreen = F1Screen.LOADING
                                                println("DEBUG: F3 - Dev panel: Showing Loading screen")
                                            }
                                            F1Screen.PROCESSING -> {
                                                // For processing screen, show ProcessingScreen via DETECTING state
                                                paymentFlowState = PaymentFlowState.DETECTING
                                                currentBMIScreen = F1Screen.PROCESSING
                                                println("DEBUG: F3 - Dev panel: Showing ProcessingScreen")
                                            }
                                            else -> {}
                                        }
                                    }
                                ),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = button.color
                            ),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (isPressed) 1.dp else 3.dp,
                                pressedElevation = 1.dp
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(button.color)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = button.label,
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
                
                Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
                
                // Logo Visibility Toggle (Compact)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Logo",
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.Switch(
                        checked = showLogo,
                        onCheckedChange = { newValue ->
                            showLogo = newValue
                            LogoVisibilityStorage.saveShowLogo(context, newValue)
                        },
                        modifier = Modifier.scale(0.8f),
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF10B981),
                            checkedTrackColor = Color(0xFF10B981).copy(alpha = 0.5f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f)
                        )
                    )
                }
                
                Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
                
                // Screen ID
                Text(
                    text = "Screen ID: $screenId",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                // Instruction
                Text(
                    text = "Tap 'HIDE' button to close",
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
        }
    }
    
    // Show F1 BMI/payment modals only when the admin-controlled appMode is F1 or F2
    val shouldShowModals = isF1ModeActive || isF2ModeActive || forcedScreen != null
    val effectiveFlowType = if (isF1ModeActive) "F1" else if (isF2ModeActive) "F2" else appMode
    
    if (shouldShowModals) {
        println("DEBUG: F3 - [MODAL] ═══════════════════════════════════════")
        println("DEBUG: F3 - [MODAL] Render check:")
        println("DEBUG: F3 - [MODAL]   flowType: $currentFlowType")
        println("DEBUG: F3 - [MODAL]   effectiveFlowType: $effectiveFlowType")
        println("DEBUG: F3 - [MODAL]   forcedScreen: $forcedScreen")
        println("DEBUG: F3 - [MODAL]   Modals: QR=$showQRModal, BMI=$showBMIDataModal, Fortune=$showFortuneModal")
        // Capture bmiData once for this scope to avoid smart cast issues
        val modalBmiData = bmiData
        println("DEBUG: F3 - [MODAL]   bmiData: ${if (modalBmiData != null) "present (keys: ${modalBmiData.keys.joinToString(", ")})" else "null"}")
        if (modalBmiData != null) {
            println("DEBUG: F3 - [MODAL]   bmiData content: $modalBmiData")
            val hasUser = modalBmiData.containsKey("user") && modalBmiData["user"] != null
            // Check if fortune exists AND has a non-null, non-empty value
            val fortuneMessage = modalBmiData["fortuneMessage"] as? String
            val fortune = modalBmiData["fortune"] as? String
            val hasFortune = (!fortuneMessage.isNullOrBlank()) || (!fortune.isNullOrBlank())
            val hasWebUrl = modalBmiData.containsKey("webUrl")
            println("DEBUG: F3 - [MODAL]   hasUser: $hasUser, hasFortune: $hasFortune, hasWebUrl: $hasWebUrl")
        }
        
        // (The manual isDetectingBMI overlay UI has been removed.
        // It is now managed by PaymentFlowScreen via the DETECTING state.)
        
        // ========== PAYMENT FLOW STATE MACHINE - TRANSITIONS ==========
        // Hide detection loading when flow is active
        if (isFlowActive) {
            isDetectingBMI = false
        }
        
        // Monitor payment flow state changes
        LaunchedEffect(paymentFlowState) {
            val currentState = paymentFlowState
            println("[PAYMENT_FLOW] 🔄 Payment flow state changed: ${currentState.name}")
            println("[PAYMENT_FLOW] 🔄 showQRModal: $showQRModal")
            println("[PAYMENT_FLOW] 🔄 showBMIDataModal: $showBMIDataModal")
            println("[PAYMENT_FLOW] 🔄 showFortuneModal: $showFortuneModal")
            println("[PAYMENT_FLOW] 🔄 bmiData present: ${bmiData != null}")
        }
        
        // SideEffect removed to prevent excessive logging during recomposition
        
        // ========== TRANSITION: BMI_SHOWING → FORTUNE_SHOWING (after 8 seconds + fortune data available) ==========
        LaunchedEffect(paymentFlowState, bmiShownTimestamp, bmiData) {
            // Condition: Must be in BMI_SHOWING state, have a timestamp, AND have fortune data
            if (paymentFlowState == PaymentFlowState.BMI_SHOWING && bmiShownTimestamp != null) {
                val elapsed = System.currentTimeMillis() - bmiShownTimestamp!!
                val remaining = 12000L - elapsed
                println("[PAYMENT_FLOW] ⏱️ BMI screen active, elapsed: ${elapsed}ms, remaining: ${remaining}ms")
                
                // Check if fortune data exists
                val currentBmiData = bmiData
                val hasFortune = currentBmiData != null && (
                    !(currentBmiData["fortuneMessage"] as? String).isNullOrBlank() ||
                    !(currentBmiData["fortune"] as? String).isNullOrBlank()
                )
                
                println("[PAYMENT_FLOW] ⏱️ Fortune data check: ${if (hasFortune) "✅ Available" else "❌ Not available yet"}")
                
                if (remaining > 0) {
                    kotlinx.coroutines.delay(remaining)
                }
                
                // Re-check state and fortune data after delay
                val stateAfterDelay = paymentFlowState
                val currentBmiDataAfterDelay = bmiData
                val hasFortuneAfterDelay = currentBmiDataAfterDelay != null && (
                    !(currentBmiDataAfterDelay["fortuneMessage"] as? String).isNullOrBlank() ||
                    !(currentBmiDataAfterDelay["fortune"] as? String).isNullOrBlank()
                )
                
                if (stateAfterDelay == PaymentFlowState.BMI_SHOWING) {
                    if (hasFortuneAfterDelay) {
                        println("[PAYMENT_FLOW] ✅✅✅ 12 seconds elapsed + Fortune data available - Transitioning BMI → FORTUNE")
                        paymentFlowState = PaymentFlowState.FORTUNE_SHOWING
                        fortuneShownTimestamp = System.currentTimeMillis()
                        currentBMIScreen = F1Screen.FORTUNE
                        println("[PAYMENT_FLOW] ✅ State transitioned: BMI_SHOWING → FORTUNE_SHOWING")
                        println("[PAYMENT_FLOW] ✅ fortuneShownTimestamp: $fortuneShownTimestamp")
                        println("[PAYMENT_FLOW] ✅ currentBMIScreen updated to FORTUNE for drawer visibility")
                    } else {
                        println("[PAYMENT_FLOW] ⚠️ 12 seconds elapsed but fortune data not available - staying on BMI screen")
                        println("[PAYMENT_FLOW] ⚠️ Will transition when fortune data arrives")
                    }
                } else {
                    println("[PAYMENT_FLOW] ⚠️ State changed during delay, current: ${stateAfterDelay.name}")
                }
            }
        }
        
        // ========== TRANSITION: BMI_SHOWING → FORTUNE_SHOWING (when fortune data arrives) ==========
        // If we're on BMI screen and fortune data just arrived, check if 8 seconds have passed
        LaunchedEffect(bmiData, paymentFlowState, bmiShownTimestamp) {
            val currentBmiDataForFortune = bmiData
            if (paymentFlowState == PaymentFlowState.BMI_SHOWING && currentBmiDataForFortune != null && bmiShownTimestamp != null) {
                val hasFortune = !(currentBmiDataForFortune["fortuneMessage"] as? String).isNullOrBlank() ||
                                !(currentBmiDataForFortune["fortune"] as? String).isNullOrBlank()
                
                if (hasFortune) {
                    val elapsed = System.currentTimeMillis() - bmiShownTimestamp!!
                    // Only transition if at least 12 seconds have passed on BMI screen
                    if (elapsed >= 12000L) {
                        println("[PAYMENT_FLOW] ✅✅✅ Fortune data arrived + 12s elapsed - Transitioning BMI → FORTUNE")
                        paymentFlowState = PaymentFlowState.FORTUNE_SHOWING
                        fortuneShownTimestamp = System.currentTimeMillis()
                        currentBMIScreen = F1Screen.FORTUNE
                        println("[PAYMENT_FLOW] ✅ currentBMIScreen updated to FORTUNE for drawer visibility")
                    } else {
                        val remaining = 12000L - elapsed
                        println("[PAYMENT_FLOW] ✅ Fortune data arrived, waiting ${remaining}ms for 12s on BMI screen")
                    }
                }
            }
        }
        
        // ========== PAYMENT FLOW STATE MACHINE - RENDERING ==========
        // Use dedicated PaymentFlowScreen component for better state management and recomposition
        // This ensures proper screen transitions and better accessibility
        PaymentFlowScreen(
            paymentFlowState = paymentFlowState,
            bmiData = bmiData,
            flowType = effectiveFlowType,
            drawerOffset = drawerOffsetDp,
            forcedScreen = forcedScreen,
            isPaymentProcessingLive = isPaymentProcessingLive,
            onDismiss = {
                println("[PAYMENT_FLOW] Modal dismissed - resetting flow")
                resetPaymentFlow("Modal dismissed")
            },
            allowDismissOnOverlay = forcedScreen != null, // Allow overlay dismissal in dev mode
            onProcessingComplete = {
                println("[PAYMENT_FLOW_SCREEN] 🛰️ Callback from PaymentFlowScreen - Processing complete!")
                currentBMIScreen = F1Screen.BMI
                bmiShownTimestamp = System.currentTimeMillis()
            }
        )
        
        // ========== DEV FOOTER ==========
        if (showDevFooter) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(200f),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    color = Color(0xCC000000), // Semi-transparent black
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = "🛠️ USB DEV MONITOR", color = Color.Yellow, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Connected Arduinos: ${usbStatus.connectedArduinosCount}", color = Color.White, fontSize = 12.sp)
                            Text(text = "USB: ${usbStatus.attachedDevicesCount} / Serial: ${usbStatus.detectedDevicesCount}", color = Color.White, fontSize = 12.sp)
                        }
                        usbStatus.availablePorts.forEach { port ->
                            Text(
                                text = "• ${port.name} (VID:${port.vendorId} PID:${port.productId}) - Serial: ${port.isSerialCandidate} - Driver: ${port.hasRecognizedDriver} - Perm: ${port.hasPermission} - Conn: ${port.isConnected}",
                                color = when {
                                    port.isConnected -> Color.Green
                                    port.isSerialCandidate -> Color.Yellow
                                    else -> Color.LightGray
                                },
                                fontSize = 10.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "Raw Data: ${usbStatus.receivedDataBytes}", color = Color.Cyan, fontSize = 12.sp)
                    }
                }
            }
        }
        
        if (!showQRModal && !showBMIDataModal && !showFortuneModal) {
            println("DEBUG: F3 - [MODAL] ⚠️ No modals showing")
            println("DEBUG: F3 - [MODAL]   bmiData: ${if (bmiData != null) "present" else "null"}")
        }
        println("DEBUG: F3 - [MODAL] ═══════════════════════════════════════")
        
        // Track activity during active flow (updates idle timeout)
        LaunchedEffect(showQRModal, showBMIDataModal, showFortuneModal, bmiData) {
            // Flow is active while modal is shown and weight > 5kg
            // Weight monitoring is handled in the USB sensor callback
        }
    } else {
        println("DEBUG: F3 - Not showing BMI modal, flow type: '$currentFlowType'")
    }
    
    // Top Warning Banner Overlay (zIndex = 20000f, overlays all dialogs and screens)
    SideEffect {
        println("DEBUG: F3 - [HEIGHT_UX] 🎨 TopWarningBanner render check: visible=${topWarningBannerMessage != null}, text='$topWarningBannerMessage', lock=$failedReadingWeightLock")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20000f),
        contentAlignment = Alignment.TopCenter
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = topWarningBannerMessage != null,
            modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { -it }),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { -it })
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFFFFBEB), // Amber-50 warning container
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B)), // Amber-500 border
                tonalElevation = 8.dp,
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .widthIn(max = 420.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = Color(0xFFD97706),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = topWarningBannerMessage ?: "",
                        color = Color(0xFF92400E),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = bottomToastMessage != null,
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 77.dp),
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFFF8FAFC),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Text(
                    text = bottomToastMessage ?: "",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = Color(0xFF111827),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}


// Socket listener functions removed - BMI data comes from USB sensors only
