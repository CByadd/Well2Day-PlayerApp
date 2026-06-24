package com.example.playerapp.utils

import android.content.Context
import android.content.SharedPreferences

object FlowTypeStorage {
    private const val PREFS_NAME = "flow_type_prefs"
    private const val KEY_FLOW_TYPE = "flow_type"
    private const val KEY_LAST_UPDATED = "last_updated"
    
    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun saveFlowType(context: Context, flowType: String?) {
        val prefs = getSharedPreferences(context)
        prefs.edit().apply {
            putString(KEY_FLOW_TYPE, flowType)
            putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
            apply()
        }
        println("DEBUG: FlowTypeStorage - Saved flow type: '$flowType'")
    }
    
    fun getFlowType(context: Context): String? {
        val prefs = getSharedPreferences(context)
        val flowType = prefs.getString(KEY_FLOW_TYPE, null)
        println("DEBUG: FlowTypeStorage - Retrieved flow type: '$flowType'")
        return flowType
    }
    
    fun getLastUpdated(context: Context): Long {
        val prefs = getSharedPreferences(context)
        return prefs.getLong(KEY_LAST_UPDATED, 0L)
    }
    
    fun clearFlowType(context: Context) {
        val prefs = getSharedPreferences(context)
        prefs.edit().clear().apply()
        println("DEBUG: FlowTypeStorage - Cleared flow type")
    }
    
    fun isFlowTypeExpired(context: Context, maxAgeMillis: Long = 300000): Boolean { // 5 minutes default
        val lastUpdated = getLastUpdated(context)
        val currentTime = System.currentTimeMillis()
        val isExpired = (currentTime - lastUpdated) > maxAgeMillis
        println("DEBUG: FlowTypeStorage - Flow type expired: $isExpired (age: ${currentTime - lastUpdated}ms)")
        return isExpired
    }
}


