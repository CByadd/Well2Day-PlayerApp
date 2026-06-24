package com.example.playerapp.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.example.playerapp.cache.CacheManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Background preloader for next media assets.
 *
 * Videos: Uses Media3 CacheWriter to warm the cache while online.
 * Images: Preloading is handled via Glide in UI layer.
 */
@UnstableApi
class PreloadManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val running = AtomicBoolean(false)

    fun preloadNext(next: MediaAsset?) {
        if (next == null) return
        if (next.type != AssetType.VIDEO) return

        // Avoid queueing infinite overlapping preloads.
        if (!running.compareAndSet(false, true)) return

        job?.cancel()
        job = scope.launch {
            try {
                val cache = CacheManager.simpleCacheOrThrow()
                val factory = CacheManager.cacheDataSourceFactoryOrThrow()
                val dataSource = factory.createDataSource() as? CacheDataSource ?: return@launch

                val dataSpec = DataSpec(Uri.parse(next.url))

                // Best-effort: CacheWriter will write missing ranges only; if already cached it's effectively a no-op.
                CacheWriter(dataSource, dataSpec, /* temporaryBuffer= */ null, /* progressListener= */ null).cache()
            } catch (_: Throwable) {
                // Best-effort preload; ignore failures (offline / network issues).
            } finally {
                running.set(false)
            }
        }
    }

    fun release() {
        job?.cancel()
    }
}

