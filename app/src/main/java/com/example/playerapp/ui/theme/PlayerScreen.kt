package com.example.playerapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.bumptech.glide.Glide
import com.example.playerapp.data.Asset
import com.example.playerapp.data.Repository
import com.example.playerapp.data.DefaultAsset
import com.example.playerapp.data.AppConfig
import com.example.playerapp.utils.isOnline
import com.example.playerapp.utils.observeNetworkState
import com.example.playerapp.utils.InfoStorage
import kotlinx.coroutines.delay
import java.util.*
import java.io.File
import kotlinx.coroutines.launch
import android.view.LayoutInflater
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.example.playerapp.R
import com.example.playerapp.playback.AssetType
import com.example.playerapp.playback.MediaAsset
import com.example.playerapp.playback.PlaybackController
import com.example.playerapp.playback.PreloadManager

@Composable
fun PlayerScreen(screenId: String, enableBMIFunctionality: Boolean = true, refreshTrigger: Int = 0) {
    val context = LocalContext.current
    val hideScreenId = InfoStorage.getHideScreenId(context) ?: false
    // Network state — reactive so the offline label updates as soon as connectivity changes
    val isOnlineFlow = remember { observeNetworkState(context) }
    var online by remember { mutableStateOf(isOnline(context)) }
    LaunchedEffect(Unit) {
        isOnlineFlow.collect { isNowOnline ->
            online = isNowOnline
        }
    }
    var assets by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var defaultAsset by remember { mutableStateOf<DefaultAsset?>(null) }
    var currentAsset by remember { mutableStateOf<Asset?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var syncToastMessage by remember { mutableStateOf<String?>(null) }
    
    // Screen status
    var isRegistered by remember { mutableStateOf(false) }
    var isEnabled by remember { mutableStateOf(true) }
    var checkingStatus by remember { mutableStateOf(true) }
    
    // BMI Modal state
    var showBMIModal by remember { mutableStateOf(false) }
    var bmiData by remember { mutableStateOf<Map<String, Any>?>(null) }
    
    // Development toggle state
    var devInfoVisible by remember { mutableStateOf(false) }
    
    // Track current playlist ID for change detection
    var currentPlaylistId by remember { mutableStateOf<String?>(null) }
    
    // Central playback controller + preloader (single-player, queue-based)
    @OptIn(UnstableApi::class)
    val playbackController = remember {
        PlaybackController(context)
    }
    @OptIn(UnstableApi::class)
    val preloadManager = remember { PreloadManager(context) }

    // Local duration map for image timing (MediaAsset is fixed shape by requirement).
    val imageDurationSecById = remember { mutableStateMapOf<String, Long>() }
    
    val coroutineScope = rememberCoroutineScope()

    fun applyCachedAssets(): Boolean {
        val cachedAssets = Repository.loadAssetsFromFile(context).sortedBy { it.slotNumber }
        if (cachedAssets.isNotEmpty()) {
            assets = cachedAssets
            error = null
            loading = false
            println("DEBUG: PlayerScreen - Loaded ${cachedAssets.size} cached playlist assets immediately")
            return true
        }
        return false
    }

    // Compare server assets with local cache and only download missing/changed items.
    // When resetCache is true, treat this as a playlist swap and fully clear old playlist files first.
    fun syncAssets(
        showToastWhilePlaying: Boolean = assets.isNotEmpty(),
        resetCache: Boolean = false
    ) {
        println("DEBUG: PlayerScreen - Syncing playlist assets for screenId: $screenId")
        if (resetCache) {
            println("DEBUG: PlayerScreen - Playlist changed, keeping current loop live until replacement assets are ready")
        }
        syncToastMessage = if (showToastWhilePlaying) "Updating playlist..." else null
        Repository.fetchAssets(context, screenId) { list, err ->
            syncToastMessage = null
            if (!list.isNullOrEmpty()) {
                assets = list.sortedBy { it.slotNumber }
                error = null
                println("DEBUG: PlayerScreen - Playlist sync complete: ${list.size} assets ready")
            } else {
                assets = emptyList()
                error = null
                println("DEBUG: PlayerScreen - No playlist assets available, default asset will be used")
            }
            loading = false
        }
    }

    // Setup default asset
    fun setupDefaultAsset() {
        val localFile = File(context.filesDir, "default-asset")
        if (localFile.exists()) {
            println("DEBUG: Found cached default asset, loading from local storage")
            val cachedAsset = DefaultAsset(
                id = -1,
                assetUrl = localFile.absolutePath,
                assetName = "Cached Default Asset",
                assetType = "image",
                duration = 10,
                isActive = true
            )
            defaultAsset = cachedAsset
            loading = false
            return
        }

        if (online) {
            Repository.getDefaultAsset(context, screenId) { default, err ->
                if (default != null) {
                    defaultAsset = default
                } else {
                    defaultAsset = AppConfig.getLocalDefaultAsset()
                }
                loading = false
            }
        } else {
            defaultAsset = AppConfig.getLocalDefaultAsset()
            loading = false
        }
    }

    fun checkDefaultAssetUpdate() {
        if (!online) return
        val localFile = File(context.filesDir, "default-asset")
        val lastUpdate = if (localFile.exists()) {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault())
                .format(java.util.Date(localFile.lastModified()))
        } else {
            null
        }
        
        Repository.checkDefaultAssetUpdate(context, screenId, lastUpdate) { asset, hasUpdate, err ->
            if (asset != null) {
                defaultAsset = asset
            }
        }
    }

    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            syncAssets(
                showToastWhilePlaying = assets.isNotEmpty() || defaultAsset != null,
                resetCache = true
            )
        }
    }

    // Initial setup and periodic checks
    LaunchedEffect(Unit) {
        checkingStatus = true
        Repository.checkScreenStatus(screenId) { registered, enabled, err ->
            isRegistered = registered
            isEnabled = enabled
            checkingStatus = false
            
            // Always try to load cached assets, even when not registered (for offline playback)
            setupDefaultAsset()
            val hasCachedAssets = applyCachedAssets()
            
            if (registered && enabled) {
                
                Repository.getAdscapePlayerInfo(screenId, context) { success, playlistId, startDate, endDate, flowType, enabled, heightCalibration, _ ->
                    if (success) {
                        currentPlaylistId = playlistId
                        syncAssets(
                            showToastWhilePlaying = hasCachedAssets,
                            resetCache = false
                        )
                    } else {
                        assets = emptyList()
                        loading = false
                    }
                }
            } else {
                // Not registered or not enabled - but still try to use cached assets for offline playback
                loading = !hasCachedAssets
                if (!hasCachedAssets) {
                    println("DEBUG: PlayerScreen - Not registered and no cached assets available")
                } else {
                    println("DEBUG: PlayerScreen - Not registered but using cached assets for offline playback")
                }
            }
                
            // Periodic fetch every 10 minutes
            coroutineScope.launch {
                delay(60 * 1000)
                while (true) {
                    if (isRegistered && isEnabled) {
                        Repository.getAdscapePlayerInfo(screenId, context) { success, playlistId, startDate, endDate, flowType, enabled, heightCalibration, _ ->
                            if (success) {
                                if (enabled != null) isEnabled = enabled
                                if (playlistId != currentPlaylistId) {
                                    currentPlaylistId = playlistId
                                    syncAssets(
                                        showToastWhilePlaying = assets.isNotEmpty() || defaultAsset != null,
                                        resetCache = true
                                    )
                                }
                            }
                        }
                    }
                    delay(10 * 60 * 1000)
                }
            }
        }
    }

    // Build FIFO queue as MediaAssets and hand it to PlaybackController.
    LaunchedEffect(assets, defaultAsset, isRegistered, isEnabled, checkingStatus) {
        val canPlayOffline = !isRegistered && (assets.isNotEmpty() || defaultAsset != null)
        if (checkingStatus || (!isRegistered && !canPlayOffline)) return@LaunchedEffect

        val playlist = mutableListOf<Asset>()
        val shouldUsePlaylistAssets = if (isRegistered) isEnabled else assets.isNotEmpty()
        if (shouldUsePlaylistAssets) {
            playlist.addAll(assets.filter { it.slotNumber in 1..8 }.sortedBy { it.slotNumber })
        }

        val currentDefault = defaultAsset ?: AppConfig.getLocalDefaultAsset()
        val localDefaultPath = if (File(context.filesDir, "default-asset").exists()) {
            File(context.filesDir, "default-asset").absolutePath
        } else {
            currentDefault.assetUrl
        }
        playlist.add(
            Asset(
                assetUrl = localDefaultPath,
                slotNumber = 9,
                originalUrl = currentDefault.assetUrl,
                duration = currentDefault.duration
            )
        )

        if (playlist.isEmpty()) return@LaunchedEffect

        val mediaQueue = playlist.map { a ->
            val urlToCheck = a.originalUrl ?: a.assetUrl
            val isVideo = urlToCheck.lowercase(Locale.ROOT).let {
                it.endsWith(".mp4") || it.endsWith(".mov") || it.endsWith(".avi") || it.endsWith(".webm") ||
                    it.contains("/video/") || it.contains("resource_type=video")
            }
            val id = "slot-${a.slotNumber}-${(a.originalUrl ?: a.assetUrl).hashCode()}"
            if (!isVideo) {
                imageDurationSecById[id] = (a.duration?.toLong() ?: 10L).coerceIn(3L, 60L)
            }
            MediaAsset(
                id = id,
                url = a.assetUrl,
                type = if (isVideo) AssetType.VIDEO else AssetType.IMAGE,
            )
        }

        playbackController.setQueue(mediaQueue)
        playbackController.start()

        // Preload all images eagerly (best-effort).
        mediaQueue.asSequence()
            .filter { it.type == AssetType.IMAGE }
            .forEach { img ->
                try {
                    Glide.with(context).load(img.url).preload()
                } catch (_: Throwable) {
                    // Best-effort only
                }
            }
    }

    // Observe PlaybackController's current asset.
    val controllerAsset by playbackController.currentAsset.collectAsState()

    // Preload next asset (best-effort, background IO).
    LaunchedEffect(controllerAsset) {
        preloadManager.preloadNext(playbackController.peekNext())
    }

    DisposableEffect(Unit) {
        onDispose {
            preloadManager.release()
            playbackController.release()
        }
    }
    
    // Refresh app if stuck on checking status or loading for more than 3 minutes
    LaunchedEffect(checkingStatus, loading) {
        if (checkingStatus || loading) {
            kotlinx.coroutines.delay(180_000L) // 3 minutes
            println("DEBUG: PlayerScreen - Stuck on checking status/loading for 3 minutes. Refreshing app...")
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(context.packageName)
            if (intent != null) {
                val componentName = intent.component
                val restartIntent = android.content.Intent.makeRestartActivityTask(componentName)
                context.startActivity(restartIntent)
                Runtime.getRuntime().exit(0)
            }
        }
    }
    
    // UI Root
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when {
            checkingStatus || loading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (checkingStatus) "Checking status..." else "Fetching assets...", color = Color.White)
                }
            }

            !isRegistered -> {
                // Check if we have cached assets to play offline
                if (assets.isNotEmpty() || defaultAsset != null) {
                    // Play cached content even when not registered
                    Box(Modifier.fillMaxSize()) {
                        val asset = controllerAsset
                        if (asset != null) {
                            when (asset.type) {
                                AssetType.VIDEO -> {
                                    StablePlayerSurface(
                                        assetId = asset.id,
                                        assetUrl = asset.url,
                                        playerViewPlayer = playbackController.player,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                AssetType.IMAGE -> {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        KioskImage(
                                            model = asset.url,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Fit,
                                        )
                                        LaunchedEffect(asset.id) {
                                            val durationSec = imageDurationSecById[asset.id] ?: 10L
                                            delay(durationSec * 1000L)
                                            playbackController.skipToNext("imageDurationElapsed")
                                        }
                                    }
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Preparing offline playback...", color = Color.White)
                            }
                        }
                        if (!hideScreenId) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .background(Color.Black.copy(alpha = 0.7f))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "Offline Mode",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                } else {
                    // No cached assets - show not registered message
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!hideScreenId) {
                            Text("Screen ID", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                            Text(screenId, color = Color.White, fontSize = 32.sp)
                        }
                        Text("Not registered", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            }

            controllerAsset != null -> {
                Box(Modifier.fillMaxSize()) {
                    val asset = controllerAsset!!
                    when (asset.type) {
                        AssetType.VIDEO -> {
                            StablePlayerSurface(
                                assetId = asset.id,
                                assetUrl = asset.url,
                                playerViewPlayer = playbackController.player,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        AssetType.IMAGE -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                KioskImage(
                                    model = asset.url,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                                LaunchedEffect(asset.id) {
                                    val durationSec = imageDurationSecById[asset.id] ?: 10L
                                    delay(durationSec * 1000L)
                                    playbackController.skipToNext("imageDurationElapsed")
                                }
                            }
                        }
                    }
                    syncToastMessage?.let { message ->
                        PlaylistSyncToast(
                            message = message,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 20.dp)
                        )
                    }
                }
            }
            
            else -> { /* Black screen */ }
        }
        
        if (enableBMIFunctionality) {
            BMIModal(
                isVisible = showBMIModal,
                bmiData = bmiData,
                flowType = "F1",
                onDismiss = {
                    showBMIModal = false
                    bmiData = null
                }
            )
        }
    }
}

@Composable
fun PlaylistSyncToast(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = Color.Black.copy(alpha = 0.55f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
            Text(message, color = Color.White, fontSize = 14.sp)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun StablePlayerSurface(
    assetId: String,
    assetUrl: String,
    playerViewPlayer: androidx.media3.exoplayer.ExoPlayer,
    modifier: Modifier = Modifier,
) {
    var hasRenderedFrame by remember(assetId) { mutableStateOf(false) }

    DisposableEffect(playerViewPlayer, assetId, assetUrl) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                println("DEBUG: PlayerScreen - First frame rendered for video: $assetUrl")
                hasRenderedFrame = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                println("DEBUG: PlayerScreen - Video playback state changed: $playbackState for $assetUrl")
                if (playbackState == Player.STATE_READY) {
                    hasRenderedFrame = true
                }
            }
        }

        playerViewPlayer.addListener(listener)
        onDispose {
            playerViewPlayer.removeListener(listener)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val playerView = LayoutInflater.from(ctx)
                .inflate(R.layout.view_stable_player, null, false) as PlayerView
            playerView.apply {
                useController = false
                player = playerViewPlayer
                alpha = if (hasRenderedFrame) 1f else 0f
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setKeepContentOnPlayerReset(true)
            }
        },
        update = { view ->
            if (view.player !== playerViewPlayer) {
                view.player = playerViewPlayer
            }
            view.alpha = if (hasRenderedFrame) 1f else 0f
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            view.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            view.setKeepContentOnPlayerReset(true)
        },
        onRelease = { view ->
            if (view.player === playerViewPlayer) {
                view.player = null
            }
        }
    )
}
