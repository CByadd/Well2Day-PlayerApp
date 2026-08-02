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
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.example.playerapp.utils.HeightCalibrationStorage
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UsbDeviceInfo(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val hasPermission: Boolean,
    val isConnected: Boolean
)

data class UsbStatus(
    val availablePorts: List<UsbDeviceInfo> = emptyList(),
    val connectedArduinosCount: Int = 0,
    val receivedDataBytes: String = ""
)
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
         * [onSensorStable] is fired.
         */
        private const val STABLE_THRESHOLD = 10

        /** Height readings are collected during frames [HEIGHT_COLLECT_START]+1 to [STABLE_THRESHOLD]. */
        private const val HEIGHT_COLLECT_START = 0

        /** Weights at or below this value are treated as "nobody on scale". */
        private const val ZERO_WEIGHT_THRESHOLD = 0.5f

        /** Valid height range in cm — outside this range height is reported as 0. */
        private const val MIN_HEIGHT_CM = 140f
        private const val MAX_HEIGHT_CM = 200f

        /**
         * USB device classes that are definitely NOT serial/BMI devices.
         *
         * Crash loop root-cause: a 2.4 GHz wireless USB dongle (HID class 3) was
         * accepted by probeFallbackDriver (it returned CdcAcmSerialDriver for ANY
         * unknown device).  connectToDriver then called port.open() on a HID device
         * which threw RuntimeException (NOT IOException), was uncaught, killed the
         * process, and CrashRestartHandler restarted in 2 s — dongle still attached
         * → infinite crash loop.
         *
         * These USB class codes are filtered out BEFORE any driver probe:
         *  3  = HID (keyboard, mouse, wireless HID receiver — e.g. 2.4 GHz dongles)
         *  9  = Hub
         *  7  = Printer
         *  6  = Imaging (camera, scanner)
         *  8  = Mass Storage
         *  11 = Smart Card (0x0B)
         *  14 = Video (0x0E)
         *  15 = Personal Healthcare (0x0F)
         *  0xE0 = Wireless Controller (Bluetooth, UWB, RNDIS)
         *  0xEF = Miscellaneous (IAD device with Ethernet etc.)
         */
        private val NON_SERIAL_DEVICE_CLASSES = setOf(3, 6, 7, 8, 9, 11, 14, 15, 0xE0, 0xEF)

        /**
         * USB interface classes that identify a non-serial interface.
         * Used in probeFallbackDriver to avoid assigning CDC drivers to HID interfaces.
         */
        private val NON_SERIAL_INTERFACE_CLASSES = setOf(3, 6, 7, 8, 9, 11, 14, 15, 0xE0)
    }

    // ── USB / serial infrastructure ──────────────────────────────────────────

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var serialIoManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usb-serial-io").apply { isDaemon = true }
    }

    /** Accumulates bytes until a full newline-terminated line is received. */
    private val dataBuffer = StringBuilder()

    private val _usbStatusFlow = MutableStateFlow(UsbStatus())
    val usbStatusFlow: StateFlow<UsbStatus> = _usbStatusFlow.asStateFlow()

    private val customProber: UsbSerialProber by lazy {
        val customTable = UsbSerialProber.getDefaultProbeTable()

        // WCH CH340 / CH341 / CH9102 / CH347 (used on 90%+ of Arduino clones)
        customTable.addProduct(0x1A86, 0x7523, Ch34xSerialDriver::class.java)
        customTable.addProduct(0x1A86, 0x5523, Ch34xSerialDriver::class.java)
        customTable.addProduct(0x1A86, 0x5512, Ch34xSerialDriver::class.java)
        customTable.addProduct(0x1A86, 0x7522, Ch34xSerialDriver::class.java)
        customTable.addProduct(0x1A86, 0x7524, Ch34xSerialDriver::class.java)
        customTable.addProduct(0x1A86, 0x5584, Ch34xSerialDriver::class.java) // CH9102

        // Arduino CDC ACM (Uno R3/R4, Mega, Leonardo, Micro, Nano Every, Nano BLE/IoT, Due, Zero, MKR)
        customTable.addProduct(0x2341, 0x0043, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x0010, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x0042, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x0036, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x8036, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x0037, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x8037, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x003d, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x003e, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x804d, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x804e, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x0058, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x005a, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x0057, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x0069, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x006a, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2A03, 0x0043, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2A03, 0x0042, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x239A, 0x800B, CdcAcmSerialDriver::class.java) // Adafruit
        customTable.addProduct(0x1B4F, 0x9256, CdcAcmSerialDriver::class.java) // Sparkfun
        customTable.addProduct(0x16C0, 0x0483, CdcAcmSerialDriver::class.java) // Teensy
        customTable.addProduct(0x0483, 0x5740, CdcAcmSerialDriver::class.java) // STM32
        customTable.addProduct(0x2E8A, 0x000A, CdcAcmSerialDriver::class.java) // Raspberry Pi Pico
        customTable.addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java) // ESP32-S2/S3

        // FTDI variations
        customTable.addProduct(0x0403, 0x6001, FtdiSerialDriver::class.java)
        customTable.addProduct(0x0403, 0x6010, FtdiSerialDriver::class.java)
        customTable.addProduct(0x0403, 0x6011, FtdiSerialDriver::class.java)
        customTable.addProduct(0x0403, 0x6014, FtdiSerialDriver::class.java)
        customTable.addProduct(0x0403, 0x6015, FtdiSerialDriver::class.java)

        // CP210x variations
        customTable.addProduct(0x10C4, 0xEA60, Cp21xxSerialDriver::class.java)
        customTable.addProduct(0x10C4, 0xEA70, Cp21xxSerialDriver::class.java)
        customTable.addProduct(0x10C4, 0xEA71, Cp21xxSerialDriver::class.java)
        customTable.addProduct(0x10C4, 0xEA80, Cp21xxSerialDriver::class.java)

        // PL2303 variations
        customTable.addProduct(0x067B, 0x2303, ProlificSerialDriver::class.java)
        customTable.addProduct(0x067B, 0x23A3, ProlificSerialDriver::class.java)
        customTable.addProduct(0x067B, 0x2323, ProlificSerialDriver::class.java)

        UsbSerialProber(customTable)
    }

    private fun updateDeviceList() {
        val availableDrivers = customProber.findAllDrivers(usbManager)
        val devices = availableDrivers.map { driver ->
            val dev = driver.device
            UsbDeviceInfo(
                name = dev.deviceName ?: "Unknown",
                vendorId = dev.vendorId,
                productId = dev.productId,
                hasPermission = usbManager.hasPermission(dev),
                isConnected = (dev == serialPort?.device)
            )
        }
        val arduinoCount = devices.count { it.isConnected }
        _usbStatusFlow.update { it.copy(availablePorts = devices, connectedArduinosCount = arduinoCount) }
    }


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
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    updateDeviceList()
                    findAndConnectDevice()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    updateDeviceList()
                    disconnect()
                }
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
        val availableDrivers = customProber.findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            Log.d(TAG, "No USB serial drivers found via probe table; checking raw USB devices...")
        }

        // Get driver list from prober, plus fallback probe for attached USB devices.
        // Non-serial class devices (HID, wireless, hub, etc.) are filtered BEFORE
        // fallback probing to avoid the crash loop caused by attempting to open them.
        val driverList = mutableListOf<UsbSerialDriver>()
        driverList.addAll(availableDrivers)

        for (device in usbManager.deviceList.values) {
            if (isNonSerialDevice(device)) {
                Log.d(TAG, "⏭ Skipping non-serial USB device " +
                    "VID=0x${device.vendorId.toString(16).uppercase()} " +
                    "class=0x${device.deviceClass.toString(16)} (HID/wireless/hub/etc.)")
                continue
            }
            if (driverList.none { it.device == device }) {
                val fallbackDriver = probeFallbackDriver(device)
                if (fallbackDriver != null) {
                    driverList.add(fallbackDriver)
                }
            }
        }

        if (driverList.isEmpty()) {
            Log.d(TAG, "No USB serial devices detected on system")
            return
        }

        for (driver in driverList) {
            val device = driver.device

            // Guard: skip if already connected to this exact device
            if (serialPort?.device == device && serialPort != null) {
                Log.d(TAG, "↩ Already connected to ${device.deviceName}, skipping reconnect")
                continue
            }

            Log.d(TAG, "Found USB device: ${device.deviceName} " +
                    "VID=0x${device.vendorId.toString(16).uppercase()} " +
                    "PID=0x${device.productId.toString(16).uppercase()}")

            if (!usbManager.hasPermission(device)) {
                // ── Strategy #1 (priv-app, Android 12+): grantPermission(device, packageName)
                // Requires MANAGE_USB + priv-app + privapp-permissions XML
                var granted = tryGrantPermission(device)

                if (!granted) {
                    // ── Strategy #2 (priv-app, older Android): grantPermission(device)
                    granted = tryGrantPermissionLegacy(device)
                }

                if (!granted) {
                    // ── Strategy #3 (fallback): requestPermission() shows a dialog.
                    Log.w(TAG, "⚠️ Both silent grant strategies failed — showing permission dialog")
                    val permissionIntent = PendingIntent.getBroadcast(
                        context, 0,
                        Intent(ACTION_USB_PERMISSION),
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
                    )
                    usbManager.requestPermission(device, permissionIntent)
                    continue
                }
            } else {
                Log.d(TAG, "✅ USB permission already held (persisted from previous session)")
            }

            val connected = connectToDriver(driver)
            if (connected) {
                // Successfully connected to the BMI serial device — stop here.
                // (Single-scale use case; remove this break for multi-scale setups.)
                Log.d(TAG, "✅ Successfully connected to serial device, stopping probe loop")
                break
            }
        }
        updateDeviceList()
    }

    /**
     * Returns true if the USB device is definitely NOT a serial/BMI device.
     *
     * Checks device-level class first (fast path). If class is 0 (defined at
     * interface level), scans all interfaces and rejects if ALL interfaces are
     * non-serial (e.g. all HID).  If at least one interface looks like CDC or
     * vendor-specific serial, we allow probing.
     */
    private fun isNonSerialDevice(device: UsbDevice): Boolean {
        val devClass = device.deviceClass
        // Fast-path: device-level class is a known non-serial class
        if (devClass in NON_SERIAL_DEVICE_CLASSES) return true
        // If class is 0, check interfaces
        if (devClass == 0 && device.interfaceCount > 0) {
            var hasSerialLikeInterface = false
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val cls = iface.interfaceClass
                // CDC (2), CDC Data (10), and vendor-specific (255) are serial candidates
                if (cls == 2 || cls == 10 || cls == 255) {
                    hasSerialLikeInterface = true
                    break
                }
            }
            // If no interface looks serial, skip this device
            if (!hasSerialLikeInterface) return true
        }
        return false
    }

    /**
     * Fallback driver probe for unlisted custom/clone USB-Serial devices.
     *
     * IMPORTANT: This must only be called AFTER [isNonSerialDevice] has returned false.
     * The fallback is conservative: it only returns a driver when there is clear
     * evidence of a serial-like interface (CDC or vendor-specific).  The previous
     * behaviour of returning CdcAcmSerialDriver for ANY unknown device caused the
     * 2.4 GHz wireless dongle crash loop.
     */
    private fun probeFallbackDriver(device: UsbDevice): UsbSerialDriver? {
        return try {
            var hasCdcInterface = false
            var hasVendorSpecificInterface = false

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val cls = iface.interfaceClass
                if (cls in NON_SERIAL_INTERFACE_CLASSES) {
                    // At least one non-serial interface — log and continue (might be composite)
                    Log.d(TAG, "Interface $i on VID=0x${device.vendorId.toString(16)} " +
                        "has non-serial class=$cls — skipping that interface")
                    continue
                }
                when (cls) {
                    2, 10 -> { // CDC Control, CDC Data
                        hasCdcInterface = true
                        Log.d(TAG, "Found CDC interface (class=$cls) on VID=0x${device.vendorId.toString(16)}")
                    }
                    255 -> { // Vendor-specific — possible CH340 variant or proprietary serial
                        hasVendorSpecificInterface = true
                        Log.d(TAG, "Found vendor-specific interface on VID=0x${device.vendorId.toString(16)}")
                    }
                }
            }

            when {
                hasCdcInterface -> {
                    Log.d(TAG, "✅ Fallback: CDC-ACM driver for VID=0x${device.vendorId.toString(16)}")
                    CdcAcmSerialDriver(device)
                }
                hasVendorSpecificInterface -> {
                    // Vendor-specific but not HID — try CDC as best-effort
                    Log.d(TAG, "⚠️ Fallback: best-effort CDC-ACM for vendor-specific VID=0x${device.vendorId.toString(16)}")
                    CdcAcmSerialDriver(device)
                }
                else -> {
                    // No serial-like interfaces found — do NOT attempt connection
                    Log.w(TAG, "🚫 Fallback: no serial interface found on VID=0x${device.vendorId.toString(16)} " +
                        "PID=0x${device.productId.toString(16)} — skipping (likely non-serial device)")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback probe error for VID=0x${device.vendorId.toString(16)}: ${e.message}")
            null
        }
    }

    /**
     * Strategy #1 — priv-app silent grant (Android 12+).
     * Calls UsbManager.grantPermission(UsbDevice, String) via reflection.
     * Requires: installed in /system/priv-app + MANAGE_USB in privapp-permissions XML.
     *
     * @return true if the call succeeded and permission is now held.
     */
    private fun tryGrantPermission(device: UsbDevice): Boolean {
        return try {
            val method = usbManager.javaClass.getMethod(
                "grantPermission", UsbDevice::class.java, String::class.java
            )
            method.invoke(usbManager, device, context.packageName)
            val held = usbManager.hasPermission(device)
            if (held) Log.d(TAG, "✅ Strategy #1 succeeded: grantPermission(device, packageName)")
            else Log.w(TAG, "Strategy #1 invoked but permission not held yet")
            held
        } catch (e: Exception) {
            Log.d(TAG, "Strategy #1 unavailable: ${e.message}")
            false
        }
    }

    /**
     * Strategy #2 — priv-app silent grant (pre-Android 12 / older OEM builds).
     * Calls UsbManager.grantPermission(UsbDevice) via reflection.
     *
     * @return true if the call succeeded and permission is now held.
     */
    private fun tryGrantPermissionLegacy(device: UsbDevice): Boolean {
        return try {
            val method = usbManager.javaClass.getMethod(
                "grantPermission", UsbDevice::class.java
            )
            method.invoke(usbManager, device)
            val held = usbManager.hasPermission(device)
            if (held) Log.d(TAG, "✅ Strategy #2 succeeded: grantPermission(device)")
            else Log.w(TAG, "Strategy #2 invoked but permission not held yet")
            held
        } catch (e: Exception) {
            Log.d(TAG, "Strategy #2 unavailable: ${e.message}")
            false
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        // Guard: skip non-serial devices even when called from the permission receiver
        if (isNonSerialDevice(device)) {
            Log.w(TAG, "🚫 connectToDevice: ignoring non-serial device " +
                "VID=0x${device.vendorId.toString(16).uppercase()}")
            return
        }
        val driver = customProber.probeDevice(device) ?: probeFallbackDriver(device) ?: return
        connectToDriver(driver)
    }

    /**
     * Opens a USB serial connection and starts the IO manager.
     *
     * @return true if the port was successfully opened, false on any failure.
     *
     * NOTE: Catches ALL exceptions (not just IOException) because usb-serial-for-android
     * can throw RuntimeException / UsbSerialException on non-serial devices (e.g. HID).
     * Narrowing the catch to IOException was the direct cause of the crash loop when
     * a 2.4 GHz wireless dongle was plugged in and granted permission.
     */
    private fun connectToDriver(driver: UsbSerialDriver): Boolean {
        return try {
            val connection = usbManager.openDevice(driver.device) ?: run {
                Log.w(TAG, "openDevice returned null for ${driver.device.deviceName}")
                return false
            }
            usbConnection = connection
            if (driver.ports.isEmpty()) {
                Log.w(TAG, "No ports available on ${driver.device.deviceName}")
                connection.close()
                usbConnection = null
                return false
            }
            serialPort = driver.ports[0]
            serialPort?.let { port ->
                port.open(connection)
                port.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
                startIoManager()
            }
            Log.d(TAG, "✅ Port opened: ${driver.device.deviceName}")
            true
        } catch (e: Exception) {
            // Broad catch is intentional — UsbSerialException, RuntimeException, etc.
            // all indicate the device is not a valid serial target.
            Log.e(TAG, "connectToDriver failed for VID=0x${driver.device.vendorId.toString(16).uppercase()}: ${e.message}")
            // Clean up partial state so a retry won't be blocked
            runCatching { serialPort?.close() }
            serialPort = null
            runCatching { usbConnection?.close() }
            usbConnection = null
            false
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
            val decoded = String(data, Charsets.UTF_8)
            dataBuffer.append(decoded)

            while (true) {
                var newlineIdx = dataBuffer.indexOf('\n')
                var skip = 1
                
                if (newlineIdx < 0) {
                    newlineIdx = dataBuffer.indexOf("\\n")
                    if (newlineIdx >= 0) skip = 2
                }
                
                if (newlineIdx < 0) break

                val line = dataBuffer.substring(0, newlineIdx)
                dataBuffer.delete(0, newlineIdx + skip)
                
                if (line.isNotBlank()) {
                    _usbStatusFlow.update { it.copy(receivedDataBytes = line.trim()) }
                    processLine(line.trim())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Data processing error", e)
        }
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
            // Notify callers that the scale is gone so they can exit "measuring" UI state
            onSensorStable?.invoke(0f, 0f)
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
