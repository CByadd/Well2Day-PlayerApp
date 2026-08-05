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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UsbDeviceInfo(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val hasPermission: Boolean,
    val isConnected: Boolean,
    val isSerialCandidate: Boolean,
    val hasRecognizedDriver: Boolean
)

data class UsbStatus(
    val availablePorts: List<UsbDeviceInfo> = emptyList(),
    /** Every physical device currently enumerated by Android's USB host. */
    val attachedDevicesCount: Int = 0,
    /** USB serial-capable devices currently visible on the Android USB bus. */
    val detectedDevicesCount: Int = 0,
    /** Devices with a serial port that has been opened successfully. */
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
        private const val USB_ACCESS_PREFS = "usb_access_registry"
        private const val TRUSTED_DEVICE_PREFIX = "trusted_device:"
        private const val USB_REFRESH_INTERVAL_SECONDS = 3L
        private const val USB_RECOVERY_DELAY_SECONDS = 1L

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

        private val NUMBER_PATTERN = Regex("[-+]?\\d+(?:[.,]\\d+)?")
        private val WEIGHT_FIELD_PATTERN = Regex(
            """(?i)[\"']?(?:weight|wt|mass|w)[\"']?\s*[:=]\s*([-+]?\d+(?:[.,]\d+)?)\s*(kg|kgs|g|gram|grams|lb|lbs)?"""
        )
        private val HEIGHT_FIELD_PATTERN = Regex(
            """(?i)[\"']?(?:height|ht|h)[\"']?\s*[:=]\s*([-+]?\d+(?:[.,]\d+)?)\s*(cm|cms|m|meter|meters|mm|in|inch|inches)?"""
        )

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

        // Explicit blacklist for non-serial wireless receivers, mice, and dongles
        private val BLACKLISTED_VENDOR_PRODUCT_IDS = setOf(
            Pair(12994, 102),   // 0x32C2:0x0066 2.4 GHz Wireless Remote Dongle
            Pair(0x32C2, 0x0066)
        )

        // Boards from these manufacturers are allowed through the conservative
        // class filter even when they enumerate as a composite/bootloader device.
        // A replacement Arduino often has a different PID from the original board.
        private val KNOWN_SERIAL_VENDOR_IDS = setOf(
            0x2341, 0x2A03, 0x1A86, 0x0403, 0x10C4, 0x067B,
            0x239A, 0x1B4F, 0x16C0, 0x0483, 0x2E8A, 0x303A
        )
    }

    // ── USB / serial infrastructure ──────────────────────────────────────────

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val usbAccessPreferences = context.getSharedPreferences(USB_ACCESS_PREFS, Context.MODE_PRIVATE)
    private val permissionRequestsInFlight = mutableSetOf<String>()
    private val deniedPermissionKeys = mutableSetOf<String>()
    private val connectionLock = Any()
    @Volatile private var isCleanedUp = false
    @Volatile private var serialPort: UsbSerialPort? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var serialIoManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usb-serial-io").apply { isDaemon = true }
    }
    private val refreshExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "usb-device-watchdog").apply { isDaemon = true }
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

        // Arduino CDC ACM (Uno R1/R2/R3/R4, Mega, Leonardo, Micro, Nano Every, Nano BLE/IoT, Due, Zero, MKR)
        customTable.addProduct(0x2341, 0x0001, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x0043, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x0010, CdcAcmSerialDriver::class.java)
        customTable.addProduct(0x2341, 0x003F, CdcAcmSerialDriver::class.java)
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

    /**
     * Publishes the hardware state separately from the open-port state.
     *
     * A USB device can be physically present while Android is still waiting for
     * the permission result (common after a box reboot with the sensor already
     * connected). Previously that state was omitted because this method only
     * listed devices for which the serial prober had already produced a driver;
     * consequently the UI showed "Disconnected" even though the USB bus saw the
     * sensor. Unknown CDC/vendor serial devices are also kept visible so their
     * VID/PID can be diagnosed without unplugging the box.
     */
    private fun updateDeviceList() {
        val devices = usbManager.deviceList.values.map { dev ->
            val serialCandidate = !isNonSerialDevice(dev)
            UsbDeviceInfo(
                name = dev.deviceName ?: "Unknown",
                vendorId = dev.vendorId,
                productId = dev.productId,
                hasPermission = usbManager.hasPermission(dev),
                isConnected = (dev == serialPort?.device),
                isSerialCandidate = serialCandidate,
                hasRecognizedDriver = customProber.probeDevice(dev) != null
            )
        }
        val openPortCount = devices.count { it.isConnected }
        _usbStatusFlow.update {
            it.copy(
                availablePorts = devices,
                attachedDevicesCount = devices.size,
                detectedDevicesCount = devices.count { it.isSerialCandidate },
                connectedArduinosCount = openPortCount
            )
        }
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
                            device?.let {
                                permissionRequestsInFlight.remove(deviceKey(it))
                                deniedPermissionKeys.remove(deviceKey(it))
                                rememberApprovedDevice(it)
                                requestUsbRefresh("permission granted")
                            }
                        } else {
                            device?.let {
                                permissionRequestsInFlight.remove(deviceKey(it))
                                deniedPermissionKeys.add(deviceKey(it))
                            }
                            Log.w(TAG, "USB permission was denied for ${device?.deviceName}")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    usbDeviceFromIntent(intent)?.let { deniedPermissionKeys.remove(deviceKey(it)) }
                    requestUsbRefresh("device attached")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detachedDevice = usbDeviceFromIntent(intent)
                    detachedDevice?.let {
                        permissionRequestsInFlight.remove(deviceKey(it))
                        deniedPermissionKeys.remove(deviceKey(it))
                    }
                    refreshExecutor.execute {
                        if (detachedDevice != null && serialPort?.device == detachedDevice) {
                            closeSerialConnection(notifyDisconnected = true)
                        }
                        refreshUsbConnection("device detached")
                    }
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
        refreshExecutor.scheduleWithFixedDelay(
            { refreshUsbConnection("watchdog") },
            0,
            USB_REFRESH_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
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

    fun findAndConnectDevice() = requestUsbRefresh("requested")

    private fun requestUsbRefresh(reason: String) {
        if (!isCleanedUp) {
            refreshExecutor.execute { refreshUsbConnection(reason) }
        }
    }

    /**
     * Serialized USB bus reconciliation. Attach broadcasts are not guaranteed on
     * every Android/OEM combination, so the watchdog also runs this method.
     */
    private fun refreshUsbConnection(reason: String) {
        if (isCleanedUp) return
        synchronized(connectionLock) {
            try {
                val attachedDevices = usbManager.deviceList.values
                // Publish the physical bus state before permission/driver work.
                // This is important when the sensor was already connected at boot.
                updateDeviceList()
                val connectedDevice = serialPort?.device
                if (connectedDevice != null && attachedDevices.none { it == connectedDevice }) {
                    Log.w(TAG, "Connected USB device disappeared; reconnecting ($reason)")
                    closeSerialConnection(notifyDisconnected = true)
                }
                if (serialPort != null) {
                    updateDeviceList()
                    return
                }

        val drivers = discoverSerialDrivers()
        if (drivers.isNotEmpty()) {
            // USB access is granted per attached device and is revoked by Android after
            // a reboot/detach. A priv-app can silently re-grant it here; normal apps
            // fall back to Android's consent dialog. The local registry remembers which
            // devices were previously approved so every known sensor is retried at boot.
            val accessibleDrivers = drivers.filter { driver ->
                val device = driver.device
                usbManager.hasPermission(device) || ensureUsbPermission(device)
            }

            // Access is requested for every supported device above. PlayerApp has
            // one BMI input stream, so it opens the first available serial port.
            for (driver in accessibleDrivers) {
                if (serialPort?.device == driver.device) continue
                if (connectToDriver(driver)) {
                    updateDeviceList()
                    return
                }
            }
            updateDeviceList()
        } else {
            updateDeviceList()
        }
            } catch (t: Throwable) {
                // A transient USB framework failure must never stop future scans.
                Log.e(TAG, "USB refresh failed ($reason): ${t.message}", t)
            }
        }
    }

    private fun discoverSerialDrivers(): List<UsbSerialDriver> {
        val drivers = customProber.findAllDrivers(usbManager).toMutableList()
        for (device in usbManager.deviceList.values) {
            if (isNonSerialDevice(device) || drivers.any { it.device == device }) continue
            probeFallbackDriver(device)?.let(drivers::add)
        }
        return drivers
    }

    private fun usbDeviceFromIntent(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    /**
     * Ensures access for an attached serial device. Returns true only when it may
     * be opened immediately. A non-privileged app must wait for the permission
     * receiver after requesting consent.
     */
    private fun ensureUsbPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        if (tryGrantPermission(device) || tryGrantPermissionLegacy(device)) {
            rememberApprovedDevice(device)
            return true
        }

        val key = deviceKey(device)
        if (key in deniedPermissionKeys) return false
        if (permissionRequestsInFlight.add(key)) {
            val requestCode = key.hashCode()
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags
            )
            Log.i(
                TAG,
                "Requesting USB access for ${device.deviceName}; previously approved=" +
                    usbAccessPreferences.contains(TRUSTED_DEVICE_PREFIX + key)
            )
            usbManager.requestPermission(device, permissionIntent)
        }
        return false
    }

    /** Stores device identity only; Android's actual USB grant remains OS-owned. */
    private fun rememberApprovedDevice(device: UsbDevice) {
        usbAccessPreferences.edit()
            .putLong(TRUSTED_DEVICE_PREFIX + deviceKey(device), System.currentTimeMillis())
            .apply()
    }

    private fun deviceKey(device: UsbDevice): String = listOf(
        device.vendorId,
        device.productId,
        device.deviceClass,
        device.deviceSubclass,
        device.deviceProtocol,
        device.deviceName
    ).joinToString(":")

    /**
     * Returns true if the USB device is definitely NOT a serial/BMI device.
     *
     * Checks device-level class first (fast path). If class is 0 (defined at
     * interface level), scans all interfaces and rejects if ALL interfaces are
     * non-serial (e.g. all HID).  If at least one interface looks like CDC or
     * vendor-specific serial, we allow probing.
     */
    private fun isNonSerialDevice(device: UsbDevice): Boolean {
        // Fast-path: explicitly blacklisted USB dongles / non-serial receivers
        if (Pair(device.vendorId, device.productId) in BLACKLISTED_VENDOR_PRODUCT_IDS) return true

        val devClass = device.deviceClass
        // Fast-path: device-level class is a known non-serial class
        if (devClass in NON_SERIAL_DEVICE_CLASSES && !isKnownSerialVendor(device)) return true
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
            if (!hasSerialLikeInterface && !isKnownSerialVendor(device)) return true
        }
        return false
    }

    private fun isKnownSerialVendor(device: UsbDevice): Boolean =
        device.vendorId in KNOWN_SERIAL_VENDOR_IDS

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
                    Log.e(TAG, "USB serial I/O failed; scheduling recovery", e)
                    // This callback runs on the serial executor. Move close/reconnect
                    // work to the watchdog to avoid stopping the I/O manager from its
                    // own worker thread.
                    refreshExecutor.execute {
                        closeSerialConnection(notifyDisconnected = true)
                        if (!isCleanedUp) {
                            refreshExecutor.schedule(
                                { refreshUsbConnection("serial I/O failure") },
                                USB_RECOVERY_DELAY_SECONDS,
                                TimeUnit.SECONDS
                            )
                        }
                    }
                }
            })
            executor.submit(serialIoManager)
        }
    }

    // ── Serial data processing ────────────────────────────────────────────────

    /**
     * Accumulate raw bytes and process complete newline- or carriage-return-terminated
     * records. Devices may send CR, LF, CRLF, or the literal escape sequences.
     */
    private fun processReceivedData(data: ByteArray) {
        try {
            val decoded = String(data, Charsets.UTF_8)
            dataBuffer.append(decoded)

            while (true) {
                var newlineIdx = dataBuffer.indexOf('\n')
                var skip = 1
                val carriageReturnIdx = dataBuffer.indexOf('\r')
                if (carriageReturnIdx >= 0 && (newlineIdx < 0 || carriageReturnIdx < newlineIdx)) {
                    newlineIdx = carriageReturnIdx
                    if (dataBuffer.getOrNull(carriageReturnIdx + 1) == '\n') skip = 2
                }
                
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
     * Accepted examples include `65.5,175`, `65.5|175`, `weight=65.5kg height=175cm`,
     * JSON-like `{"weight":65.5,"height":1.75}`, or a single numeric weight. Two things
     * happen per valid line:
     *  A. [onSensorDataReceived] fires with the raw parsed value
     *     (so existing callers can do real-time monitoring).
     *  B. The reference increment-counter runs internally; when it exceeds
     *     [STABLE_THRESHOLD] [onSensorStable] fires once with the locked-in
     *     (and calibration-applied) values.
     */
    private fun processLine(rawLine: String) {
        val reading = parseSensorReading(rawLine) ?: return
        val weightStr = reading.weight.toString()
        val heightStr = reading.height?.toString().orEmpty()
        val weightRaw = reading.weight

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

    private data class ParsedSensorReading(val weight: Float, val height: Float?)

    /**
     * Parses permissive text records without making a transport-specific format part
     * of the protocol. Named fields take precedence; otherwise the first numeric
     * value is weight and the second is height. Unit suffixes are normalized.
     */
    private fun parseSensorReading(rawLine: String): ParsedSensorReading? {
        val line = rawLine.replace("\\r", "").replace("\\n", "").trim()
        if (line.isBlank() || line.equals("power", ignoreCase = true)) return null

        val weightMatch = WEIGHT_FIELD_PATTERN.find(line)
        val heightMatch = HEIGHT_FIELD_PATTERN.find(line)
        val numericValues = NUMBER_PATTERN.findAll(line).map { it.value }.toList()
        if (weightMatch == null && heightMatch != null) return null

        val weightValue = parseFlexibleNumber(weightMatch?.groupValues?.getOrNull(1)
            ?: numericValues.getOrNull(0)) ?: return null
        val weightUnit = weightMatch?.groupValues?.getOrNull(2).orEmpty()
        val normalizedWeight = when (weightUnit.lowercase()) {
            "g", "gram", "grams" -> weightValue / 1000f
            "lb", "lbs" -> weightValue * 0.45359237f
            else -> weightValue
        }

        val heightValue = parseFlexibleNumber(heightMatch?.groupValues?.getOrNull(1)
            ?: numericValues.getOrNull(1))
        val heightUnit = heightMatch?.groupValues?.getOrNull(2).orEmpty()
        val normalizedHeight = heightValue?.let { value ->
            when (heightUnit.lowercase()) {
                "m", "meter", "meters" -> value * 100f
                "mm" -> value / 10f
                "in", "inch", "inches" -> value * 2.54f
                // Height supplied as 1.75 without a unit is conventionally metres.
                "" -> if (value in 0.5f..3f) value * 100f else value
                else -> value
            }
        }
        return ParsedSensorReading(normalizedWeight, normalizedHeight)
    }

    private fun parseFlexibleNumber(value: String?): Float? =
        value?.trim()?.replace(',', '.')?.toFloatOrNull()

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

    /** Closes only the direct USB resources and leaves the manager able to recover. */
    private fun closeSerialConnection(notifyDisconnected: Boolean) {
        synchronized(connectionLock) {
            runCatching { serialIoManager?.stop() }
            serialIoManager = null
            runCatching { serialPort?.close() }
            serialPort = null
            runCatching { usbConnection?.close() }
            usbConnection = null
            dataBuffer.setLength(0)
            resetAlgorithmState()
            preViewsVal = ""
            _usbStatusFlow.update { it.copy(connectedArduinosCount = 0) }
            if (notifyDisconnected) {
                onSensorDataReceived?.invoke(0f, 0f)
                onSensorStable?.invoke(0f, 0f)
            }
        }
    }

    fun disconnect() {
        closeSerialConnection(notifyDisconnected = true)
    }

    fun cleanup() {
        try {
            isCleanedUp = true
            disconnect()
            context.unregisterReceiver(usbReceiver)
            executor.shutdown()
            refreshExecutor.shutdownNow()
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
