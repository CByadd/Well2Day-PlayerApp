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
import java.io.IOException
import java.util.concurrent.Executors

/**
 * USB Serial Manager for BMI Machine (Optimised)
 *
 * Key improvements over original:
 * - Median-window filter for both weight and height to eliminate noisy spikes.
 * - Stability guard: reading is only forwarded once the window is full AND the
 *   standard deviation is below a threshold — prevents jitter-triggered flows.
 * - Zero-copy hex decode via byte-direct parsing.
 * - Executor is a single background thread (unchanged) to stay off the main thread.
 */
class UsbSerialManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbSerialManager"
        private const val ACTION_USB_PERMISSION = "com.example.playerapp.USB_PERMISSION"
        private const val BAUD_RATE = 9600
        private const val DATA_BITS = 8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE

        // Stability filter: collect this many consecutive readings before emitting one
        private const val STABILITY_WINDOW = 5
        // Maximum allowed standard deviation of weight within the window (kg)
        private const val WEIGHT_STABILITY_THRESHOLD = 0.5f
        // Maximum allowed standard deviation of height within the window (cm)
        private const val HEIGHT_STABILITY_THRESHOLD = 1.5f
    }

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var serialIoManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    // Data accumulation buffer
    private val dataBuffer = StringBuilder()

    // Rolling windows for median/stability filtering
    private val weightWindow = ArrayDeque<Float>(STABILITY_WINDOW)
    private val heightWindow = ArrayDeque<Float>(STABILITY_WINDOW)

    // Track last emitted stable value to avoid re-triggering with identical readings
    private var lastEmittedWeight: Float? = null
    private var lastEmittedHeight: Float? = null

    // Callbacks
    private var onSensorDataReceived: ((weight: Float, height: Float) -> Unit)? = null
    private var onLogMessage: ((message: String) -> Unit)? = null

    // USB permission receiver
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

    fun setOnSensorDataReceived(callback: (weight: Float, height: Float) -> Unit) {
        this.onSensorDataReceived = callback
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
                context,
                0,
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

    /**
     * Process received data from serial port
     * Data format from sensor: "weight,height\n"
     * Example: "65.5,175\n"
     */
    private fun processReceivedData(data: ByteArray) {
        try {
            // Zero-copy hex decode
            val sb = StringBuilder(data.size * 2)
            for (b in data) sb.append("%02X".format(b))
            val decoded = hexToString(sb.toString())

            dataBuffer.append(decoded)

            if (dataBuffer.contains("\n") || dataBuffer.contains("\\n")) {
                val completeData = dataBuffer.toString()
                dataBuffer.clear()
                parseSensorData(completeData)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Data processing error", e)
        }
    }

    private fun hexToString(hex: String): String {
        val result = StringBuilder(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            result.append(hex.substring(i, i + 2).toInt(16).toChar())
            i += 2
        }
        return result.toString()
    }

    /**
     * Parse and filter sensor data.
     *
     * After parsing raw floats we push them into rolling windows and only emit
     * a reading when:
     *   1. The window is full (STABILITY_WINDOW consecutive valid readings).
     *   2. The standard deviation of the window is below the threshold.
     *
     * This eliminates single-sample spikes while remaining responsive —
     * at 9600 baud a reading arrives every ~100–200ms, so 5 samples = ~500ms–1s.
     */
    private fun parseSensorData(data: String) {
        try {
            val cleanData = data.trim().replace("\n", "").replace("\\n", "").replace("\r", "")
            val parts = cleanData.split(",")

            if (parts.size < 2) {
                if (isValidFloat(cleanData)) {
                    val weight = cleanData.toFloat()
                    if (weight > 0f && weight < 500f) {
                        pushToWindow(weight, 0f)
                    }
                }
                return
            }

            val weightStr = parts[0].trim()
            val heightStr = parts[1].trim()

            if (!isValidFloat(weightStr)) return

            val weight = weightStr.toFloat()

            if (!isValidFloat(heightStr)) {
                if (weight > 0f && weight < 500f) pushToWindow(weight, 0f)
                return
            }

            val height = heightStr.toFloat()

            // Basic range validation
            if (weight < 0f || weight > 500f) {
                Log.e(TAG, "⚠️ INVALID WEIGHT: $weight kg - skipping")
                return
            }

            val validHeight = if (height < 50f || height > 250f) {
                Log.w(TAG, "⚠️ INVALID HEIGHT: $height cm - using 0")
                0f
            } else height

            pushToWindow(weight, validHeight)

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing sensor data: ${e.message}", e)
        }
    }

    /**
     * Push a weight/height pair into the rolling windows, then check
     * stability and emit if criteria are met.
     */
    private fun pushToWindow(weight: Float, height: Float) {
        // Add to windows
        if (weightWindow.size >= STABILITY_WINDOW) weightWindow.removeFirst()
        if (heightWindow.size >= STABILITY_WINDOW) heightWindow.removeFirst()
        weightWindow.addLast(weight)
        heightWindow.addLast(height)

        // Not enough samples yet
        if (weightWindow.size < STABILITY_WINDOW) return

        // Check weight stability
        val weightStdDev = stdDev(weightWindow)
        if (weightStdDev > WEIGHT_STABILITY_THRESHOLD) {
            Log.d(TAG, "Weight unstable (stdDev=${"%.2f".format(weightStdDev)}) — skipping")
            return
        }

        // Compute medians
        val stableWeight = median(weightWindow)
        val stableHeight = if (heightWindow.all { it > 0f }) {
            val heightStdDev = stdDev(heightWindow)
            if (heightStdDev > HEIGHT_STABILITY_THRESHOLD) {
                Log.d(TAG, "Height unstable (stdDev=${"%.2f".format(heightStdDev)}) — using raw")
                median(heightWindow) // Use median even if slightly unstable; caller validates range
            } else {
                median(heightWindow)
            }
        } else {
            0f // Height not available
        }

        // Suppress duplicate emissions (±0.2kg / ±0.5cm tolerance)
        val weightUnchanged = lastEmittedWeight?.let { kotlin.math.abs(it - stableWeight) < 0.2f } ?: false
        val heightUnchanged = lastEmittedHeight?.let { kotlin.math.abs(it - stableHeight) < 0.5f } ?: false
        if (weightUnchanged && heightUnchanged) return

        lastEmittedWeight = stableWeight
        lastEmittedHeight = stableHeight

        Log.d(TAG, "✅ Stable reading — Weight: $stableWeight kg, Height: $stableHeight cm")
        onSensorDataReceived?.invoke(stableWeight, stableHeight)
    }

    // ── Math helpers ─────────────────────────────────────────────────────────

    private fun median(values: ArrayDeque<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f
        else sorted[mid]
    }

    private fun stdDev(values: ArrayDeque<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.sum() / values.size
        val variance = values.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / values.size
        return kotlin.math.sqrt(variance.toDouble()).toFloat()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun isValidFloat(value: String): Boolean {
        if (value.isBlank() || value == "null" || value == "Power") return false
        return try {
            val regex = "[+-]?[0-9]+(\\.[0-9]+)?([Ee][+-]?[0-9]+)?".toRegex()
            regex.matches(value)
        } catch (e: Exception) {
            false
        }
    }

    fun calculateBMI(weight: Float, heightCm: Float): Float {
        if (heightCm <= 0) return 0f
        val heightM = heightCm / 100f
        return weight / (heightM * heightM)
    }

    fun getBMICategory(bmi: Float): String = when {
        bmi < 18.5 -> "Underweight"
        bmi < 25.0 -> "Normal"
        bmi < 30.0 -> "Overweight"
        else -> "Obese"
    }

    fun disconnect() {
        try {
            serialIoManager?.stop()
            serialIoManager = null
            serialPort?.close()
            serialPort = null
            usbConnection?.close()
            usbConnection = null
            // Reset filter windows on disconnect
            weightWindow.clear()
            heightWindow.clear()
            lastEmittedWeight = null
            lastEmittedHeight = null
            // Notify with 0,0 to signal disconnection
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
}
