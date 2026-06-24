package com.example.playerapp.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.example.playerapp.cache.CacheManager
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Central playback brain for kiosk loop.
 *
 * Goals:
 * - Single ExoPlayer instance (avoid per-composable player churn / leaks).
 * - FIFO queue via [ArrayDeque].
 * - Full state handling (IDLE/BUFFERING/READY/ENDED) with auto-recovery.
 * - Watchdog detects "stuck" playback (position not advancing) and recovers.
 */
@UnstableApi
class PlaybackController(
    context: Context,
    private val cacheDataSourceFactory: DataSource.Factory = CacheManager.cacheDataSourceFactoryOrThrow(),
) : Player.Listener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val queue = ArrayDeque<MediaAsset>()
    private var playlistSnapshot: List<MediaAsset> = emptyList()
    private var current: MediaAsset? = null

    private val _currentAsset = MutableStateFlow<MediaAsset?>(null)
    val currentAsset: StateFlow<MediaAsset?> = _currentAsset.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _lastError = MutableStateFlow<PlaybackException?>(null)
    val lastError: StateFlow<PlaybackException?> = _lastError.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheDataSourceFactory)
        )
        .build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ false)
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            addListener(this@PlaybackController)
        }

    private val watchdogRunning = AtomicBoolean(false)
    private var watchdogJob: Job? = null
    private var retryJob: Job? = null
    @Volatile private var suppressNextIdleRecovery = false

    /**
     * Replace the current queue (FIFO).
     * If the playlist changed, switch immediately so stale assets do not remain on screen.
     */
    fun setQueue(newQueue: List<MediaAsset>) {
        val normalizedQueue = newQueue.toList()
        val playlistChanged = playlistSnapshot != normalizedQueue
        val currentAsset = current

        queue.clear()
        playlistSnapshot = normalizedQueue

        if (!playlistChanged && currentAsset != null) {
            // Keep the current item when nothing changed, but avoid enqueuing it twice.
            playlistSnapshot
                .filterNot { it.id == currentAsset.id }
                .forEach { queue.addLast(it) }
            return
        }

        playlistSnapshot.forEach { queue.addLast(it) }
        if (playlistChanged) {
            println("DEBUG: PlaybackController - Playlist changed, switching immediately to refreshed queue")
            current = null
            _currentAsset.value = null
            player.stop()
            player.clearMediaItems()
            advanceToNext("setQueue:playlistChanged")
        } else if (current == null) {
            advanceToNext("setQueue")
        }
    }

    fun enqueue(asset: MediaAsset) {
        queue.addLast(asset)
        if (current == null) advanceToNext("enqueue")
    }

    fun clearQueue() {
        queue.clear()
    }

    fun peekNext(): MediaAsset? = queue.firstOrNull()

    fun skipToNext(reason: String = "skipToNext") {
        advanceToNext(reason)
    }

    fun start() {
        startWatchdogIfNeeded()
        if (current == null) advanceToNext("start")
        else {
            if (!player.isPlaying && player.playbackState == Player.STATE_READY) player.play()
        }
    }

    fun stop() {
        retryJob?.cancel()
        player.stop()
    }

    fun release() {
        retryJob?.cancel()
        watchdogJob?.cancel()
        player.removeListener(this)
        player.release()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        println("DEBUG: PlaybackController - Playback state changed: ${stateName(playbackState)}")
        _playbackState.value = playbackState
        when (playbackState) {
            Player.STATE_IDLE -> {
                if (current?.type == AssetType.IMAGE) {
                    println("DEBUG: PlaybackController - Ignoring STATE_IDLE while image asset is active")
                    suppressNextIdleRecovery = false
                    return
                }
                if (suppressNextIdleRecovery) {
                    println("DEBUG: PlaybackController - Ignoring expected STATE_IDLE during controlled player transition")
                    suppressNextIdleRecovery = false
                } else {
                    scheduleRecover("STATE_IDLE")
                }
            }
            Player.STATE_ENDED -> {
                println("DEBUG: PlaybackController - Video ended, advancing to next")
                advanceToNext("STATE_ENDED")
            }
            Player.STATE_BUFFERING, Player.STATE_READY -> {
                suppressNextIdleRecovery = false
            }
            else -> Unit
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        println("DEBUG: PlaybackController - Player error: ${error.errorCodeName} - ${error.message}")
        _lastError.value = error
        if (isHttp404(error)) {
            println("DEBUG: PlaybackController - Detected 404 for current asset, skipping immediately")
            advanceToNext("onPlayerError:http404")
            return
        }
        scheduleRecover("onPlayerError:${error.errorCodeName}")
    }

    private fun advanceToNext(reason: String) {
        retryJob?.cancel()
        println("DEBUG: PlaybackController - advanceToNext called: $reason")

        if (queue.isEmpty() && playlistSnapshot.isNotEmpty()) {
            // Loop forever in kiosk mode by refilling FIFO.
            playlistSnapshot.forEach { queue.addLast(it) }
        }
        val next = if (queue.isEmpty()) null else queue.removeFirst()
        current = next
        _currentAsset.value = next

        if (next == null) {
            // Nothing to play; keep player in a safe idle state and rely on retries/queue updates.
            player.stop()
            scheduleRecover("queueEmpty:$reason")
            return
        }

        when (next.type) {
            AssetType.VIDEO -> {
                println("DEBUG: PlaybackController - Preparing video: ${next.url}")
                val item = MediaItem.fromUri(normalizeUri(next.url))
                // Stop player completely before setting new media item to prevent overlapping
                suppressNextIdleRecovery = true
                player.stop()
                player.clearMediaItems()
                // Hard reset position so transitions are deterministic.
                player.setMediaItem(item, /* resetPosition= */ true)
                player.prepare()
                player.playWhenReady = true
                println("DEBUG: PlaybackController - Video prepared and playing: ${next.url}, player state: ${player.playbackState}")
            }
            AssetType.IMAGE -> {
                println("DEBUG: PlaybackController - Switching to image: ${next.url}")
                // UI layer handles image timing; controller simply exposes current asset.
                // If an image reaches here while a previous video is playing, stop player.
                suppressNextIdleRecovery = true
                player.stop()
                player.clearMediaItems()
            }
        }
    }

    /**
     * Recovery strategy:
     * - Quick retry current item once.
     * - If still stuck/error, skip to next.
     */
    private fun scheduleRecover(reason: String) {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            val asset = current
            // Small backoff avoids tight loops in persistent failures.
            delay(1200)
            if (!isActive) return@launch

            if (asset == null) {
                // If queue has items now, start.
                if (queue.isNotEmpty()) advanceToNext("recover:$reason")
                return@launch
            }

            if (asset.type != AssetType.VIDEO) {
                // Images are timed by UI; do not auto-skip them from player idle state.
                println("DEBUG: PlaybackController - Ignoring recovery for image asset: $reason")
                return@launch
            }

            // Retry same asset once by re-preparing.
            println("DEBUG: PlaybackController - Retrying asset: ${asset.url}")
            val retryItem = MediaItem.fromUri(normalizeUri(asset.url))
            suppressNextIdleRecovery = true
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(retryItem, /* resetPosition= */ true)
            player.prepare()
            player.playWhenReady = true

            // If we don't reach READY/BUFFERING quickly, skip.
            delay(6000)
            if (!isActive) return@launch
            val state = player.playbackState
            if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                advanceToNext("recover:skipAfterRetry:$reason")
            }
        }
    }

    private fun startWatchdogIfNeeded() {
        if (!watchdogRunning.compareAndSet(false, true)) return
        watchdogJob = scope.launch {
            var lastPos = -1L
            var lastProgressUptime = SystemClock.elapsedRealtime()
            var lastState = Player.STATE_IDLE

            while (isActive) {
                delay(3000)

                val asset = current
                val state = player.playbackState
                val isVideo = asset?.type == AssetType.VIDEO

                if (!isVideo) {
                    // Don't watchdog images.
                    lastPos = -1L
                    lastProgressUptime = SystemClock.elapsedRealtime()
                    lastState = state
                    continue
                }

                // If the player should be playing but isn't progressing, recover.
                val pos = player.currentPosition
                val shouldBePlaying = player.playWhenReady && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)

                if (shouldBePlaying) {
                    if (pos != lastPos) {
                        lastPos = pos
                        lastProgressUptime = SystemClock.elapsedRealtime()
                    } else {
                        val stuckForMs = SystemClock.elapsedRealtime() - lastProgressUptime
                        // 15s is aggressive enough for kiosk; avoids infinite audio-only or frozen frames.
                        if (stuckForMs > 15_000) {
                            scheduleRecover("watchdog:stuck:${stateName(state)}")
                            lastProgressUptime = SystemClock.elapsedRealtime()
                        }
                    }
                } else {
                    // Unexpected pause at READY should be resumed.
                    if (state == Player.STATE_READY && !player.isPlaying && player.playWhenReady) {
                        player.play()
                    }
                    // If state regressed to IDLE from non-IDLE, recover.
                    if (state == Player.STATE_IDLE && lastState != Player.STATE_IDLE) {
                        scheduleRecover("watchdog:idleRegression")
                    }
                }
                lastState = state
            }
        }
    }

    private fun normalizeUri(url: String): Uri {
        return when {
            url.startsWith("file://") -> Uri.parse(url)
            url.startsWith("/") -> Uri.parse("file://$url")
            else -> Uri.parse(url)
        }
    }

    private fun isHttp404(throwable: Throwable?): Boolean {
        var current = throwable
        while (current != null) {
            if (current is HttpDataSource.InvalidResponseCodeException && current.responseCode == 404) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }
}

