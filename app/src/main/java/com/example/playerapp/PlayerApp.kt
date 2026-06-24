package com.example.playerapp

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.example.playerapp.cache.CacheManager
import com.example.playerapp.stability.CrashRestartHandler

@UnstableApi
class PlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CacheManager.initialize(this)
        CrashRestartHandler.install(this)
    }
}

