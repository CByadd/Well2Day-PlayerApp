package com.example.playerapp.ui

import android.net.Uri
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/**
 * Global player manager to track active players and ensure resources are freed properly.
 * Fixed: Improved player lifecycle management and prevented overlapping players
 */
@UnstableApi
object PlayerManager {
    private val activePlayers = ConcurrentHashMap<String, ExoPlayer>()
    private val lock = Any()
    private var currentPlayerId: String? = null
    
    fun registerPlayer(id: String, player: ExoPlayer) {
        synchronized(lock) {
            // Stop and release previous player if it exists
            currentPlayerId?.let { prevId ->
                activePlayers[prevId]?.let { prevPlayer ->
                    try {
                        prevPlayer.stop()
                        prevPlayer.release()
                    } catch (e: Exception) {
                        println("DEBUG: PlayerManager - Error releasing previous player: ${e.message}")
                    }
                }
                activePlayers.remove(prevId)
            }
            
            activePlayers[id] = player
            currentPlayerId = id
            println("DEBUG: PlayerManager - Registered: $id (previous player stopped)")
        }
    }
    
    fun unregisterPlayer(id: String) {
        synchronized(lock) {
            val player = activePlayers.remove(id)
            if (player != null) {
                try {
                    player.stop()
                    player.release()
                } catch (e: Exception) {
                    println("DEBUG: PlayerManager - Error releasing player: ${e.message}")
                }
            }
            if (currentPlayerId == id) {
                currentPlayerId = null
            }
            println("DEBUG: PlayerManager - Unregistered and released: $id")
        }
    }

    /**
     * Stop and release all players.
     */
    fun releaseAll() {
        synchronized(lock) {
            activePlayers.values.forEach { 
                try {
                    it.stop()
                    it.release()
                } catch (e: Exception) {
                    // Ignore release errors
                }
            }
            activePlayers.clear()
        }
    }
}

/**
 * Enhanced VideoPlayer for seamless transitions and robust playback.
 * 
 * Features:
 * 1. Freeze Watchdog: Detects if playback stops moving while it should be active and skips to the next asset.
 * 2. Seamless Start: Hides the player view until the first frame is rendered to prevent black flashes.
 * 3. Transparent Shutter: Optimizes for Crossfade transitions between videos.
 * 4. Automatic Cleanup: Releases player resources as soon as it leaves the composition.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    url: String,
    playbackId: Int = 0,
    modifier: Modifier = Modifier,
    onVideoEnd: (() -> Unit)? = null,
    onReady: (() -> Unit)? = null,
    resizeMode: Int = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
) {
    val context = LocalContext.current
    // Unique ID for this specific playback instance to avoid collisions during rapid loops
    val playerId = remember(url, playbackId) { "player_${url.hashCode()}_${playbackId}_${System.currentTimeMillis()}" }
    
    // State to track if the video content is actually ready to show
    var isFirstFrameRendered by remember { mutableStateOf(false) }

    // Create and prepare ExoPlayer instance with improved lifecycle management
    // Explicitly specify type to avoid inference issues
    val exoPlayer: ExoPlayer = remember(url, playbackId) {
        println("DEBUG: VideoPlayer - Creating new player for: $url")
        val player = ExoPlayer.Builder(context).build()
        
        // Prevent Android OS or Dialog focus changes from muting/pausing our video
        player.setAudioAttributes(AudioAttributes.DEFAULT, false)
            
        val uri = if (url.startsWith("/") || url.startsWith("file://")) {
            Uri.parse(if (url.startsWith("file://")) url else "file://$url")
        } else {
            Uri.parse(url)
        }
        
        player.setMediaItem(MediaItem.fromUri(uri))
        player.volume = 1f
        player.repeatMode = Player.REPEAT_MODE_OFF // Play once and signal end via STATE_ENDED
        
        player.addListener(object : Player.Listener {
            private var endTriggered = false
            private var isFirstFrame = true

            override fun onRenderedFirstFrame() {
                if (isFirstFrame) {
                    println("DEBUG: VideoPlayer - First frame rendered for $url")
                    isFirstFrameRendered = true
                    isFirstFrame = false
                    onReady?.invoke()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                println("DEBUG: VideoPlayer - Playback state changed for $url: $state")
                if (state == Player.STATE_ENDED && !endTriggered) {
                    endTriggered = true
                    println("DEBUG: VideoPlayer - STATE_ENDED reached for $url")
                    onVideoEnd?.invoke()
                } else if (state == Player.STATE_READY) {
                    println("DEBUG: VideoPlayer - Player ready for $url")
                } else if (state == Player.STATE_BUFFERING) {
                    println("DEBUG: VideoPlayer - Player buffering for $url")
                }
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("VideoPlayer", "Playback error for $url: ${error.message}")
                if (!endTriggered) {
                    endTriggered = true
                    onVideoEnd?.invoke() // Skip to next video on error
                }
            }
        })
        
        player.prepare()
        player.playWhenReady = true
        
        PlayerManager.registerPlayer(playerId, player)
        player
    }
    
    // Watchdog: Detects if the video position stays same while it should be playing
    LaunchedEffect(exoPlayer) {
        var lastPos = -1L
        var lastUpdate = System.currentTimeMillis()
        var stuckCheckCount = 0
        
        while (true) {
            delay(2000) // Check every 2 seconds (reduced from 3s for faster detection)
            
            if (!exoPlayer.playWhenReady || exoPlayer.playbackState == Player.STATE_ENDED) {
                // Player is not supposed to be playing, reset watchdog
                lastPos = -1L
                lastUpdate = System.currentTimeMillis()
                stuckCheckCount = 0
                continue
            }
            
            // Force resume if paused by OS unexpectedly (e.g., when Dialog overlay shows)
            if (exoPlayer.playWhenReady && !exoPlayer.isPlaying && exoPlayer.playbackState == Player.STATE_READY) {
                println("DEBUG: VideoPlayer - Watchdog detected unexpected pause for $url. Resuming.")
                exoPlayer.play()
            }

            if (exoPlayer.playbackState == Player.STATE_READY) {
                val currentPos = exoPlayer.currentPosition
                if (currentPos == lastPos && currentPos != 0L) {
                    stuckCheckCount++
                    // Position hasn't moved. If stuck for too long, force end.
                    if (stuckCheckCount >= 3) { // 6 seconds total (3 checks * 2 seconds)
                        android.util.Log.w("VideoPlayer", "Playback frozen for $url. Skipping.")
                        onVideoEnd?.invoke()
                        break
                    }
                } else {
                    lastPos = currentPos
                    lastUpdate = System.currentTimeMillis()
                    stuckCheckCount = 0
                }
            } else if (exoPlayer.playbackState == Player.STATE_BUFFERING) {
                 if (System.currentTimeMillis() - lastUpdate > 10000) { // Reduced from 15s to 10s
                     android.util.Log.w("VideoPlayer", "Buffering timeout for $url. Skipping.")
                     onVideoEnd?.invoke()
                     break
                 }
            } else {
                lastUpdate = System.currentTimeMillis()
                stuckCheckCount = 0
            }
        }
    }
    
    // Cleanup: Release resources when this video is no longer part of the UI
    DisposableEffect(url, playbackId) {
        onDispose {
            println("DEBUG: VideoPlayer - Releasing player for $url")
            PlayerManager.unregisterPlayer(playerId)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                this.resizeMode = resizeMode
                // Transparent backgrounds allow Crossfade to overlap without black flashes
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                player = exoPlayer
                // Hide the view initially to avoid showing black shutter before video starts
                alpha = 0f
            }
        },
        modifier = modifier,
        update = { view ->
            if (view.player != exoPlayer) {
                view.player = exoPlayer
            }
            // Show only when first frame is rendered to ensure seamless transition
            if (isFirstFrameRendered) {
                view.alpha = 1f
            }
        }
    )
}

/**
 * Local video player (legacy wrapper for VideoPlayer)
 */
@Composable
fun LocalVideoPlayer(
    filePath: String,
    modifier: Modifier = Modifier,
    onVideoEnd: (() -> Unit)? = null,
    onVideoStart: (() -> Unit)? = null,
    onPlaybackPosition: ((Long) -> Unit)? = null,
    autoPlay: Boolean = true,
    resizeMode: Int = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
) {
    VideoPlayer(
        url = filePath,
        playbackId = 0,
        modifier = modifier,
        onVideoEnd = onVideoEnd,
        onReady = onVideoStart,
        resizeMode = resizeMode
    )
}
