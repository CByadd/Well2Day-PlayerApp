package com.example.playerapp.utils

import android.content.Context
import android.content.SharedPreferences

object HeightCalibrationStorage {
    private const val PREFS_NAME = "height_calibration_prefs"
    private const val KEY_HEIGHT_CALIBRATION = "height_calibration"
    private const val KEY_LAST_UPDATED = "last_updated"
    
    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun saveHeightCalibration(context: Context, calibration: Float) {
        val prefs = getSharedPreferences(context)
        prefs.edit().apply {
            putFloat(KEY_HEIGHT_CALIBRATION, calibration)
            putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
            apply()
        }
        println("DEBUG: HeightCalibrationStorage - Saved height calibration: $calibration cm")
    }
    
    fun getHeightCalibration(context: Context): Float {
        val prefs = getSharedPreferences(context)
        val calibration = prefs.getFloat(KEY_HEIGHT_CALIBRATION, 0f)
        println("DEBUG: HeightCalibrationStorage - Retrieved height calibration: $calibration cm")
        return calibration
    }
    
    fun getLastUpdated(context: Context): Long {
        val prefs = getSharedPreferences(context)
        return prefs.getLong(KEY_LAST_UPDATED, 0L)
    }
    
    fun hasChanged(context: Context, newCalibration: Float): Boolean {
        val storedCalibration = getHeightCalibration(context)
        val changed = storedCalibration != newCalibration
        if (changed) {
            println("DEBUG: HeightCalibrationStorage - Height calibration changed: $storedCalibration -> $newCalibration")
        }
        return changed
    }
}








