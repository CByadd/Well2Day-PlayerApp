package com.example.playerapp.cache

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.File
import okhttp3.OkHttpClient

/**
 * Centralized Media3 cache for offline-first playback.
 *
 * - Uses a single [SimpleCache] instance process-wide (required by Media3).
 * - Provides a [CacheDataSource.Factory] for ExoPlayer.
 *
 * Critical: call [initialize] once from Application.
 */
@UnstableApi
object CacheManager {
    private const val CACHE_DIR_NAME = "media_cache"
    private const val MAX_CACHE_BYTES: Long = 512L * 1024L * 1024L // 512MB (tune per device storage)

    @Volatile private var cache: SimpleCache? = null
    @Volatile private var cacheDataSourceFactory: DataSource.Factory? = null

    fun initialize(context: Context) {
        if (cache != null && cacheDataSourceFactory != null) return
        synchronized(this) {
            if (cache != null && cacheDataSourceFactory != null) return

            val appContext = context.applicationContext
            val cacheDir = File(appContext.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
            val databaseProvider = StandaloneDatabaseProvider(appContext)
            val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)

            val simpleCache = SimpleCache(cacheDir, evictor, databaseProvider)
            cache = simpleCache

            val okHttpClient = OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .build()
            // IMPORTANT: must support both http(s) and local file:// URIs.
            // OkHttpDataSource alone cannot read file://, which would cause black screens for local assets.
            val httpUpstream = OkHttpDataSource.Factory(okHttpClient)
            val upstream = DefaultDataSource.Factory(appContext, httpUpstream)

            cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(simpleCache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
    }

    fun cacheDataSourceFactoryOrThrow(): DataSource.Factory =
        cacheDataSourceFactory ?: error("CacheManager not initialized. Call CacheManager.initialize() in Application.onCreate().")

    fun simpleCacheOrThrow(): SimpleCache =
        cache ?: error("CacheManager not initialized. Call CacheManager.initialize() in Application.onCreate().")

    /**
     * Release cache on process shutdown if needed (normally not required).
     */
    fun release() {
        synchronized(this) {
            try {
                cache?.release()
            } finally {
                cache = null
                cacheDataSourceFactory = null
            }
        }
    }
}

