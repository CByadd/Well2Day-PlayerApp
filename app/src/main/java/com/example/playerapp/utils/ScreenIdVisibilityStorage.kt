package com.example.playerapp.utils

import android.content.Context
import android.content.SharedPreferences

object ScreenIdVisibilityStorage {
    private const val PREFS_NAME = "screen_id_visibility_prefs"
    private const val KEY_HIDE_SCREEN_ID = "hide_screen_id"
    
    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun saveHideScreenId(context: Context, hideScreenId: Boolean) {
        val prefs = getSharedPreferences(context)
        prefs.edit().apply {
            putBoolean(KEY_HIDE_SCREEN_ID, hideScreenId)
            apply()
        }
        println("DEBUG: ScreenIdVisibilityStorage - Saved hide screen ID: $hideScreenId")
    }
    
    fun getHideScreenId(context: Context): Boolean {
        val prefs = getSharedPreferences(context)
        // Default to false (show screen ID by default, i.e., not hidden)
        val hideScreenId = prefs.getBoolean(KEY_HIDE_SCREEN_ID, false)
        println("DEBUG: ScreenIdVisibilityStorage - Retrieved hide screen ID: $hideScreenId")
        return hideScreenId
    }
}
