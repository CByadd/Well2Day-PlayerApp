package com.example.playerapp.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent storage for height calibration settings.
 *
 * The BMI sensor reports a raw height that may need an offset added or
 * subtracted to produce the actual height of the person being measured.
 *
 * Two values are persisted:
 *  - [KEY_HEIGHT_CALIBRATION]: Float offset in cm (default 0)
 *  - [KEY_CALIBRATION_SIGN]: "+" (default) or "-"
 *
 * Applied in UsbSerialManager:
 *   sign "+"  → fVal = offset + rawSensorHeight
 *   sign "-"  → fVal = rawSensorHeight - offset
 *
 * This matches the reference BMI apps (machineHeight + mchheightindc).
 */
object HeightCalibrationStorage {
    private const val PREFS_NAME               = "height_calibration_prefs"
    private const val KEY_HEIGHT_CALIBRATION   = "height_calibration"
    private const val KEY_CALIBRATION_SIGN     = "calibration_sign"
    private const val KEY_LAST_UPDATED         = "last_updated"

    private fun getSharedPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Calibration offset ────────────────────────────────────────────────────

    fun saveHeightCalibration(context: Context, calibration: Float) {
        getSharedPreferences(context).edit().apply {
            putFloat(KEY_HEIGHT_CALIBRATION, calibration)
            putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
            apply()
        }
        println("DEBUG: HeightCalibrationStorage - Saved height calibration: $calibration cm")
    }

    fun getHeightCalibration(context: Context): Float {
        val v = getSharedPreferences(context).getFloat(KEY_HEIGHT_CALIBRATION, 0f)
        println("DEBUG: HeightCalibrationStorage - Retrieved height calibration: $v cm")
        return v
    }

    // ── Calibration sign ──────────────────────────────────────────────────────

    /**
     * Save the calibration sign: "+" means offset is added to raw sensor height;
     * "-" means offset is subtracted from raw sensor height.
     */
    fun saveCalibrationSign(context: Context, sign: String) {
        val normalised = if (sign == "-") "-" else "+"
        getSharedPreferences(context).edit().putString(KEY_CALIBRATION_SIGN, normalised).apply()
        println("DEBUG: HeightCalibrationStorage - Saved calibration sign: $normalised")
    }

    /** Returns "+" (default) or "-". */
    fun getCalibrationSign(context: Context): String =
        getSharedPreferences(context).getString(KEY_CALIBRATION_SIGN, "+") ?: "+"

    // ── Misc ──────────────────────────────────────────────────────────────────

    fun getLastUpdated(context: Context): Long =
        getSharedPreferences(context).getLong(KEY_LAST_UPDATED, 0L)

    fun hasChanged(context: Context, newCalibration: Float): Boolean {
        val stored = getHeightCalibration(context)
        return stored != newCalibration
    }
}
