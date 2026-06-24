package com.example.playerapp.utils

import android.content.Context
import android.content.SharedPreferences

object LogoVisibilityStorage {
    private const val PREFS_NAME = "logo_visibility_prefs"
    private const val KEY_SHOW_LOGO = "show_logo"
    
    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun saveShowLogo(context: Context, showLogo: Boolean) {
        val prefs = getSharedPreferences(context)
        prefs.edit().apply {
            putBoolean(KEY_SHOW_LOGO, showLogo)
            apply()
        }
        println("DEBUG: LogoVisibilityStorage - Saved show logo: $showLogo")
    }
    
    fun getShowLogo(context: Context): Boolean {
        val prefs = getSharedPreferences(context)
        // Default to true (show logo by default)
        val showLogo = prefs.getBoolean(KEY_SHOW_LOGO, true)
        println("DEBUG: LogoVisibilityStorage - Retrieved show logo: $showLogo")
        return showLogo
    }
}

