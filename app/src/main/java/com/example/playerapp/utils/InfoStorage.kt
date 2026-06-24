package com.example.playerapp.utils

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException

    /**
     * Utility class for persistent storage of screen information in info.json file.
     * Stores: screenId, screenName (deviceName), location, heightCalibration, heightCalibrationEnabled, playlistId, paymentAmount
     */
    object InfoStorage {
        private const val FILE_NAME = "info.json"
        
        data class ScreenInfo(
            val screenId: String?,
            val screenName: String?,
            val location: String?,
            val heightCalibration: Float?,
            val heightCalibrationEnabled: Boolean?,
            val playlistId: String?,
            val paymentAmount: Double?,
            val flowDrawerEnabled: Boolean?,
            val flowDrawerSlotCount: Int?,
            val hideScreenId: Boolean?,
            /** App operating mode pushed by admin: "F1", "F2", or "PlayerOnly" */
            val appMode: String? = null
        )
    
    /**
     * Save screen information to info.json file
     */
    fun saveInfo(context: Context, info: ScreenInfo) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            val json = JSONObject().apply {
                if (info.screenId != null) put("screenId", info.screenId)
                if (info.screenName != null) put("screenName", info.screenName)
                if (info.location != null) put("location", info.location)
                if (info.heightCalibration != null) put("heightCalibration", info.heightCalibration)
                if (info.heightCalibrationEnabled != null) put("heightCalibrationEnabled", info.heightCalibrationEnabled)
                // Only save playlistId if it's not null and not empty - don't save null values
                if (info.playlistId != null && info.playlistId.isNotBlank() && info.playlistId != "null") {
                    put("playlistId", info.playlistId)
                }
                if (info.paymentAmount != null) put("paymentAmount", info.paymentAmount)
                if (info.flowDrawerEnabled != null) put("flowDrawerEnabled", info.flowDrawerEnabled)
                if (info.flowDrawerSlotCount != null) put("flowDrawerSlotCount", info.flowDrawerSlotCount)
                if (info.hideScreenId != null) put("hideScreenId", info.hideScreenId)
                if (info.appMode != null) put("appMode", info.appMode)
            }
            
            file.writeText(json.toString(2)) // Pretty print with 2-space indent
            println("DEBUG: InfoStorage - Saved info.json: ${json.toString()}")
        } catch (e: Exception) {
            println("DEBUG: InfoStorage - Error saving info.json: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Load screen information from info.json file
     * Returns null if file doesn't exist or cannot be parsed
     */
    fun loadInfo(context: Context): ScreenInfo? {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) {
                println("DEBUG: InfoStorage - info.json does not exist")
                return null
            }
            
            val text = file.readText()
            if (text.isBlank()) {
                println("DEBUG: InfoStorage - info.json is empty")
                return null
            }
            
            val json = JSONObject(text)
            val info = ScreenInfo(
                screenId = json.optString("screenId", null).takeIf { !it.isNullOrBlank() },
                screenName = json.optString("screenName", null).takeIf { !it.isNullOrBlank() },
                location = json.optString("location", null).takeIf { !it.isNullOrBlank() },
                heightCalibration = if (json.has("heightCalibration") && !json.isNull("heightCalibration")) {
                    json.optDouble("heightCalibration", 0.0).toFloat()
                } else null,
                heightCalibrationEnabled = if (json.has("heightCalibrationEnabled") && !json.isNull("heightCalibrationEnabled")) {
                    json.optBoolean("heightCalibrationEnabled", true)
                } else null,
                playlistId = json.optString("playlistId", null).takeIf { !it.isNullOrBlank() },
                paymentAmount = if (json.has("paymentAmount") && !json.isNull("paymentAmount")) {
                    try {
                        val value = json.getDouble("paymentAmount")
                        if (!value.isNaN()) value else null
                    } catch (e: Exception) {
                        null
                    }
                } else null,
                flowDrawerEnabled = if (json.has("flowDrawerEnabled") && !json.isNull("flowDrawerEnabled")) {
                    json.optBoolean("flowDrawerEnabled", true)
                } else null,
                flowDrawerSlotCount = if (json.has("flowDrawerSlotCount") && !json.isNull("flowDrawerSlotCount")) {
                    json.optInt("flowDrawerSlotCount", 2)
                } else null,
                hideScreenId = if (json.has("hideScreenId") && !json.isNull("hideScreenId")) {
                    json.optBoolean("hideScreenId", false)
                } else null,
                appMode = json.optString("appMode", null).takeIf { !it.isNullOrBlank() }
            )
            
            println("DEBUG: InfoStorage - Loaded info.json: screenId=${info.screenId}, screenName=${info.screenName}, location=${info.location}, heightCalibration=${info.heightCalibration}, heightCalibrationEnabled=${info.heightCalibrationEnabled}, playlistId=${info.playlistId}, paymentAmount=${info.paymentAmount}")
            return info
        } catch (e: Exception) {
            println("DEBUG: InfoStorage - Error loading info.json: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * Update specific fields in info.json without overwriting existing values
     * Only updates fields that are provided (non-null)
     */
    fun updateInfo(context: Context, info: ScreenInfo) {
        try {
            // Load existing info first
            val existingInfo = loadInfo(context) ?: ScreenInfo(null, null, null, null, null, null, null, null, null, null)
            
            // Merge with new values (new values take precedence)
            val mergedInfo = ScreenInfo(
                screenId = info.screenId ?: existingInfo.screenId,
                screenName = info.screenName ?: existingInfo.screenName,
                location = info.location ?: existingInfo.location,
                heightCalibration = info.heightCalibration ?: existingInfo.heightCalibration,
                heightCalibrationEnabled = info.heightCalibrationEnabled ?: existingInfo.heightCalibrationEnabled,
                playlistId = info.playlistId ?: existingInfo.playlistId,
                paymentAmount = info.paymentAmount ?: existingInfo.paymentAmount,
                flowDrawerEnabled = info.flowDrawerEnabled ?: existingInfo.flowDrawerEnabled,
                flowDrawerSlotCount = info.flowDrawerSlotCount ?: existingInfo.flowDrawerSlotCount,
                hideScreenId = info.hideScreenId ?: existingInfo.hideScreenId,
                appMode = info.appMode ?: existingInfo.appMode
            )
            
            // Save merged info
            saveInfo(context, mergedInfo)
        } catch (e: Exception) {
            println("DEBUG: InfoStorage - Error updating info.json: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Check if info.json exists
     */
    fun exists(context: Context): Boolean {
        val file = File(context.filesDir, FILE_NAME)
        return file.exists()
    }
    
    /**
     * Clear info.json (delete the file)
     */
    fun clear(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                file.delete()
                println("DEBUG: InfoStorage - Cleared info.json")
            }
        } catch (e: Exception) {
            println("DEBUG: InfoStorage - Error clearing info.json: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Get screen name (deviceName) from persisted info
     */
    fun getScreenName(context: Context): String? {
        return loadInfo(context)?.screenName
    }
    
    /**
     * Get location from persisted info
     */
    fun getLocation(context: Context): String? {
        return loadInfo(context)?.location
    }
    
    /**
     * Get height calibration from persisted info
     * Returns null if not set, otherwise returns the calibration value
     */
    fun getHeightCalibration(context: Context): Float? {
        return loadInfo(context)?.heightCalibration
    }
    
    /**
     * Get playlist ID from persisted info
     */
    fun getPlaylistId(context: Context): String? {
        return loadInfo(context)?.playlistId
    }
    
    /**
     * Get payment amount from persisted info
     */
    fun getPaymentAmount(context: Context): Double? {
        return loadInfo(context)?.paymentAmount
    }
    
    /**
     * Get screen ID from persisted info
     */
    fun getScreenId(context: Context): String? {
        return loadInfo(context)?.screenId
    }
    
    /**
     * Get height calibration enabled status from persisted info
     */
    fun getHeightCalibrationEnabled(context: Context): Boolean? {
        return loadInfo(context)?.heightCalibrationEnabled
    }
    
    /**
     * Get flow drawer enabled status from persisted info
     */
    fun getFlowDrawerEnabled(context: Context): Boolean? {
        return loadInfo(context)?.flowDrawerEnabled
    }
    
    /**
     * Get flow drawer slot count from persisted info
     */
    fun getFlowDrawerSlotCount(context: Context): Int? {
        return loadInfo(context)?.flowDrawerSlotCount
    }
    
    /**
     * Get hide screen ID setting from persisted info
     */
    fun getHideScreenId(context: Context): Boolean? {
        return loadInfo(context)?.hideScreenId
    }

    /**
     * Get app mode pushed by admin: "F1", "F2", or "PlayerOnly".
     * Returns null if the server has never set a mode (defaults to F1 behavior).
     */
    fun getAppMode(context: Context): String? {
        return loadInfo(context)?.appMode
    }
}
