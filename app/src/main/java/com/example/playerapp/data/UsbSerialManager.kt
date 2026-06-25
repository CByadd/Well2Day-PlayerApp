package com.example.playerapp.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.example.playerapp.utils.HeightCalibrationStorage
import java.io.IOException
import java.util.concurrent.Executors

/**
 * USB Serial Manager for BMI Machine
 *
 * Implements the exact stability-detection algorithm from the reference BMI apps
 * (bmiv2withqr / tvbmiv2):
 *
 *  Data format: "weight,height\n"  (comma-separated, newline-terminated)
 *
 *  Two-tier callback system:
 *  ┌──────────────────────────────────────────────────────────────────────┐
 *  │ setOnSensorDataReceived  → fires on every valid parsed reading.      │
 *  │   Used by F3 for real-time weight monitoring and its own stability   │
 *  │   gate (1.5 s debounce + processFirstValidReading).                  │
 *  │                                                                      │
 *  │ setOnSensorStable        → fires ONCE per weighing session after     │
 *  │   [STABLE_THRESHOLD] consecutive identical weight readings.          │
 *  │   Applies findDuplicateValue() for the most consistent height value. │
 *  │   Height calibration is applied here (HeightCalibrationStorage).     │
 *  └──────────────────────────────────────────────────────────────────────┘
 *
 *  Stability algorithm (mirrors SerialConsoleActivity.updateReceivedData):
 *   1. Parse weight string and height string from each line.
 *   2. If weight equals [preViewsVal] → increment counter.
 *      a. Collect raw heights during readings 6–10 into [repetedHeight].
 *      b. When counter > [STABLE_THRESHOLD] fire [onSensorStable] (once).
 *   3. If weight changed → reset counter and [repetedHeight].
 *   4. When weight ≤ [ZERO_WEIGHT_THRESHOLD] → full reset, fire (0, 0).
 */
class UsbSerialManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbSerialManager"
        private const val ACTION_USB_PERMISSION = "com.example.playerapp.USB_PERMISSION"
        private const val BAUD_RATE = 9600
        private const val DATA_BITS = 8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY   = UsbSerialPort.PARITY_NONE

        /**
         * Number of consecutive identical-weight frames required before
         * [onSensorStable] is fired.  Mirrors `increment > 10` in reference.
         */
        private const val STABLE_THRESHOLD = 10

        /** Height readings are collected during frames [HEIGHT_COLLECT_START]+1 to [STABLE_THRESHOLD]. */
        private const val HEIGHT_COLLECT_START = 5

        /** Weights at or below this value are treated as "nobody on scale". */
        private const val ZERO_WEIGHT_THRESHOLD = 0.5f

        /** Valid height range in cm — outside this range height is reported as 0. */
        private const val MIN_HEIGHT_CM = 140f
        private const val MAX_HEIGHT_CM = 200f
    }

    // ── USB / serial infrastructure ──────────────────────────────────────────

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var serialIoManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    /** Accumulates bytes until a full newline-terminated line is received. */
    private val dataBuffer = StringBuilder()

    // ── Reference-algorithm state ─────────────────────────────────────────────

    /** Last weight string seen ("-1" sentinel when weight was 0). */
    @Volatile private var preViewsVal: String = ""

    /**
     * Count of consecutive frames with the same weight value.
     * Mirrors `increment` in SerialConsoleActivity.
     */
    @Volatile private var increment: Int = 0

    /**
     * Heights collected during the stable window (readings [HEIGHT_COLLECT_START]+1 to
     * [STABLE_THRESHOLD]).  Mirrors `repetedHeight` in SerialConsoleActivity.
     */
    private val repetedHeight = mutableListOf<String>()

    /** Prevents [onSensorStable] from firing more than once per weighing session. */
    @Volatile private var stableFired = false

    // ── Callbacks ─────────────────────────────────────────────────────────────

    /**
     * Called on every valid parsed reading (raw, unfiltered).
     * Allows callers (e.g., F3 MainActivity) to do their own real-time
     * weight monitoring and separate stability gating.
     */
    private var onSensorDataReceived: ((weight: Float, height: Float) -> Unit)? = null

    /**
     * Called ONCE per weighing session when the reference stability algorithm
     * determines the reading is locked-in (increment > [STABLE_THRESHOLD]).
     * Weight is raw; height has calibration applied and is 0 if out-of-range.
     */
    private var onSensorStable: ((weight: Float, height: Float) -> Unit)? = null

    private var onLogMessage: ((message: String) -> Unit)? = null

    // ── USB permission receiver ───────────────────────────────────────────────

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let { connectToDevice(it) }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> findAndConnectDevice()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> disconnect()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Register a callback for every valid raw reading from the sensor.
     * This is used by existing callers that have their own stability gate.
     */
    fun setOnSensorDataReceived(callback: (weight: Float, height: Float) -> Unit) {
        this.onSensorDataReceived = callback
    }

    /**
     * Register a callback fired once per weighing session when the reference
     * stability algorithm (increment > 10) locks in the reading.
     * Height is calibration-applied; 0 if invalid range.
     */
    fun setOnSensorStable(callback: (weight: Float, height: Float) -> Unit) {
        this.onSensorStable = callback
    }

    fun setOnLogMessage(callback: (message: String) -> Unit) {
        this.onLogMessage = callback
    }

    fun findAndConnectDevice() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) return
        val driver = availableDrivers.firstOrNull() ?: return
        val device = driver.device
        if (usbManager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: return
            val connection = usbManager.openDevice(driver.device) ?: return
            usbConnection = connection
            if (driver.ports.isEmpty()) return
            serialPort = driver.ports[0]
            serialPort?.let { port ->
                port.open(connection)
                port.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
                startIoManager()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Connection error", e)
        }
    }

    private fun startIoManager() {
        serialPort?.let { port ->
            serialIoManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    processReceivedData(data)
                }
                override fun onRunError(e: Exception) {
                    Log.e(TAG, "IO Manager error", e)
                }
            })
            executor.submit(serialIoManager)
        }
    }

    // ── Serial data processing ────────────────────────────────────────────────

    /**
     * Accumulate raw bytes and process complete newline-terminated lines.
     * Data format: "weight,height\n" e.g. "65.5,175\n"
     */
    private fun processReceivedData(data: ByteArray) {
        try {
            val decoded = hexToString(data.joinToString("") { "%02X".format(it) })
            dataBuffer.append(decoded)

            while (dataBuffer.contains('\n') || dataBuffer.contains("\\n")) {
                val newlineIdx = dataBuffer.indexOf('\n').takeIf { it >= 0 }
                    ?: dataBuffer.indexOf("\\n").takeIf { it >= 0 }
                    ?: break
                val line = dataBuffer.substring(0, newlineIdx)
                dataBuffer.delete(0, newlineIdx + 1)
                if (line.isNotBlank()) processLine(line.trim())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Data processing error", e)
        }
    }

    private fun hexToString(hex: String): String {
        val sb = StringBuilder(hex.length / 2)
        var i = 0
        while (i + 1 < hex.length) {
            sb.append(hex.substring(i, i + 2).toInt(16).toChar())
            i += 2
        }
        return sb.toString()
    }

    // ── Reference algorithm core ──────────────────────────────────────────────

    /**
     * Process one complete sensor line.
     *
     * Two things happen per valid line:
     *  A. [onSensorDataReceived] fires with the raw parsed value
     *     (so existing callers can do real-time monitoring).
     *  B. The reference increment-counter runs internally; when it exceeds
     *     [STABLE_THRESHOLD] [onSensorStable] fires once with the locked-in
     *     (and calibration-applied) values.
     */
    private fun processLine(rawLine: String) {
        val parts = rawLine.replace("\\n", "").replace("\r", "").split(",")
        if (parts.isEmpty()) return

        val weightStr = parts[0].trim()
        val heightStr = if (parts.size >= 2) parts[1].trim() else ""

        if (weightStr.isEmpty() || weightStr == "Power" || !isValidFloat(weightStr)) return
        val weightRaw = weightStr.toFloatOrNull() ?: return

        // ── Zero-weight: reset state and broadcast (0, 0) ─────────────────────
        if (weightRaw <= ZERO_WEIGHT_THRESHOLD) {
            if (preViewsVal != "-1") {
                Log.d(TAG, "↩ Zero-weight — resetting reference state")
                resetAlgorithmState()
                onSensorDataReceived?.invoke(0f, 0f)
                onSensorStable?.invoke(0f, 0f)  // Let callers know scale is empty
            }
            preViewsVal = "-1"
            return
        }

        // ── Valid weight: always fire raw callback ─────────────────────────────
        val rawHeight = heightStr.toFloatOrNull() ?: 0f
        onSensorDataReceived?.invoke(weightRaw, rawHeight)

        // ── Reference stability tracking ───────────────────────────────────────
        if (preViewsVal == weightStr) {
            increment++
            Log.d(TAG, "📊 Increment: $increment  weight=$weightStr  height=$heightStr")

            // Collect height values in the stable window
            if (increment > HEIGHT_COLLECT_START && increment <= STABLE_THRESHOLD) {
                if (heightStr.isNotEmpty() && isValidFloat(heightStr)) {
                    repetedHeight.add(heightStr)
                }
            }

            // Fire stable callback once when threshold exceeded
            if (increment > STABLE_THRESHOLD && !stableFired) {
                stableFired = true
                val bestHeight = findDuplicateValue(repetedHeight)
                    .takeIf { it.isNotEmpty() } ?: heightStr
                emitStableReading(weightRaw, bestHeight)
            }
        } else {
            // Weight changed — restart collection
            if (increment != 0 || repetedHeight.isNotEmpty()) {
                Log.d(TAG, "🔄 Weight changed: $preViewsVal → $weightStr")
            }
            increment = 0
            repetedHeight.clear()
            // Note: do NOT reset stableFired here; that resets only on zero-weight
        }

        preViewsVal = weightStr
    }

    /**
     * Mirrors SerialConsoleActivity.findDuplicateValue():
     * Returns the height string that appeared 2–5 times in the list.
     * Prefers the value with the highest frequency.  Returns "" if none.
     */
    private fun findDuplicateValue(list: List<String>): String {
        if (list.isEmpty()) return ""
        val freq = mutableMapOf<String, Int>()
        for (s in list) freq[s] = (freq[s] ?: 0) + 1
        return freq.entries
            .filter { it.value in 2..5 }
            .maxByOrNull { it.value }
            ?.key ?: ""
    }

    /**
     * Apply height calibration (HeightCalibrationStorage) and range-check,
     * then invoke [onSensorStable].
     *
     * Calibration mirrors reference app:
     *   sign "+"  → fVal = machineOffset + rawSensorHeight
     *   sign "-"  → fVal = rawSensorHeight - machineOffset
     */
    private fun emitStableReading(weightKg: Float, rawHeightStr: String) {
        val rawHeight = rawHeightStr.toFloatOrNull() ?: 0f
        val offset = HeightCalibrationStorage.getHeightCalibration(context)
        val sign   = HeightCalibrationStorage.getCalibrationSign(context)
        val calibrated = if (sign == "-") rawHeight - offset else offset + rawHeight

        val validHeight = if (calibrated < MIN_HEIGHT_CM || calibrated > MAX_HEIGHT_CM) {
            Log.w(TAG, "⚠️ Height out of valid range: $calibrated cm — reporting 0")
            0f
        } else {
            calibrated
        }

        Log.d(TAG,
            "✅ STABLE LOCKED — Weight: $weightKg kg | " +
            "rawHeight: $rawHeight → calibrated: $calibrated → sent: $validHeight cm"
        )
        onSensorStable?.invoke(weightKg, validHeight)
    }

    /** Reset only the algorithm state. Called on zero-weight. */
    private fun resetAlgorithmState() {
        increment = 0
        repetedHeight.clear()
        stableFired = false
        // preViewsVal is set to "-1" by the caller (sentinel for zero-weight)
    }

    // ── BMI helpers (unchanged API surface) ──────────────────────────────────

    fun calculateBMI(weight: Float, heightCm: Float): Float {
        if (heightCm <= 0) return 0f
        val heightM = heightCm / 100f
        return weight / (heightM * heightM)
    }

    fun getBMICategory(bmi: Float): String = when {
        bmi <= 0   -> "Unknown"
        bmi < 18.5 -> "Underweight"
        bmi < 25.0 -> "Normal"
        bmi < 30.0 -> "Overweight"
        else       -> "Obese"
    }

    // ── Connection lifecycle ──────────────────────────────────────────────────
    
    fun isConnected(): Boolean {
        return serialPort != null
    }

    fun disconnect() {
        try {
            serialIoManager?.stop()
            serialIoManager = null
            serialPort?.close()
            serialPort = null
            usbConnection?.close()
            usbConnection = null
            resetAlgorithmState()
            preViewsVal = ""
            onSensorDataReceived?.invoke(0f, 0f)
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        }
    }

    fun cleanup() {
        try {
            disconnect()
            context.unregisterReceiver(usbReceiver)
            executor.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun isValidFloat(value: String): Boolean {
        if (value.isBlank() || value == "null" || value == "Power") return false
        return try { value.toFloat(); true } catch (_: NumberFormatException) { false }
    }
}
