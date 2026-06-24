package com.example.playerapp.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

/**
 * Socket.IO Manager for real-time payment and fortune updates
 */
class SocketManager(private val context: Context) {
    companion object {
        private const val TAG = "SocketManager"
        // Extract the socket host by removing only a trailing /api segment.
        // A plain replace("/api", "") would also corrupt https://api... hostnames.
        // Socket.IO client will automatically add /socket.io/ path
        private val SERVER_BASE_URL = com.example.playerapp.data.BASE_URL
            .trim()
            .removeSuffix("/api")
            .trimEnd('/')

    }


    
    private var socket: Socket? = null
    private var isConnected = false
    private var currentScreenId: String? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10
    private var roomJoinConfirmed = false
    private var roomJoinRetryHandler: Handler? = null
    
    // Callbacks
    private var onPaymentSuccess: ((Map<String, Any>) -> Unit)? = null
    private var onFortuneReady: ((Map<String, Any>) -> Unit)? = null
    private var onAssetsUpdated: (() -> Unit)? = null
    /** Fired when the admin panel pushes a new appMode ("F1", "F2", "PlayerOnly", …) */
    private var onModeChanged: ((String) -> Unit)? = null
    /** Fired when the admin panel pushes a new screen rotation ("landscape", "portrait", etc.) */
    private var onRotationChanged: ((String) -> Unit)? = null
    
    /**
     * Connect to Socket.IO server and join screen room
     */
    fun connect(screenId: String) {
        try {
            println("═══════════════════════════════════════════════════════════════")
            println("[SOCKET] 🔌🔌🔌 CONNECT CALLED 🔌🔌🔌")
            println("[SOCKET] Screen ID: $screenId")
            println("[SOCKET] Current Screen ID: $currentScreenId")
            println("[SOCKET] Socket exists: ${socket != null}")
            println("[SOCKET] Socket connected: ${socket?.connected()}")
            println("[SOCKET] Room join confirmed: $roomJoinConfirmed")
            println("═══════════════════════════════════════════════════════════════")
            
            currentScreenId = screenId

            roomJoinConfirmed = false
            
            // If already connected to the same screen, just ensure room join
            if (socket?.connected() == true && currentScreenId == screenId) {
                android.util.Log.d(TAG, "Socket already connected to screen: $screenId")
                println("[SOCKET] ✅ Socket already connected to screen: $screenId")
                // Always re-join room to ensure we're in the right room
                println("[SOCKET] 🔄 Ensuring room membership...")
                joinRoom(screenId)
                return
            }
            
            // Disconnect existing socket if connected to different screen
            if (socket?.connected() == true) {
                println("[SOCKET] 🔄 Disconnecting existing socket to reconnect to new screen")
                socket?.disconnect()
                socket?.off()
                socket = null
                isConnected = false
            }
            
            val opts = IO.Options().apply {
                reconnection = true
                reconnectionAttempts = 10
                reconnectionDelay = 1000
                reconnectionDelayMax = 5000
                timeout = 20000
                transports = arrayOf("polling") // Force polling only for debugging
                forceNew = false // Reuse connection if possible
                // Add additional options for better debugging
                rememberUpgrade = true
            }
            
            socket = IO.socket(SERVER_BASE_URL, opts)
            
            println("[SOCKET] 🔌 Socket instance created, setting up event listeners...")
            println("[SOCKET] Server URL: $SERVER_BASE_URL")
            println("[SOCKET] Full Socket.IO URL should be: ${SERVER_BASE_URL}/socket.io/")
            println("[SOCKET] Screen ID: $screenId")
            println("[SOCKET] Socket options: reconnection=true, attempts=10, transports=[polling ONLY]")
            println("[SOCKET] BASE_URL from config: ${com.example.playerapp.data.BASE_URL}")
            println("[SOCKET] 🔧 FORCING POLLING TRANSPORT FOR DEBUGGING")
            
            // Set up event listeners BEFORE connecting
            socket?.on(Socket.EVENT_CONNECT) {
                isConnected = true
                roomJoinConfirmed = false
                android.util.Log.d(TAG, "Socket connected")
                println("[SOCKET] ✅✅✅ SOCKET CONNECTED SUCCESSFULLY!")
                println("[SOCKET] Socket ID: ${socket?.id()}")
                println("[SOCKET] Joining room for screen: $screenId")
                println("[SOCKET] Room format should be: screen:$screenId")
                
                // Join room immediately after connection
                Handler(Looper.getMainLooper()).post {
                    joinRoom(screenId)
                    println("[SOCKET] ✅ Room join request sent")
                    
                    // Retry room join if not confirmed within 3 seconds
                    roomJoinRetryHandler?.removeCallbacksAndMessages(null)
                    roomJoinRetryHandler = Handler(Looper.getMainLooper())
                    roomJoinRetryHandler?.postDelayed({
                        if (!roomJoinConfirmed && socket?.connected() == true) {
                            println("[SOCKET] ⚠️ Room join not confirmed, retrying...")
                            joinRoom(screenId)
                        }
                    }, 3000)
                }
            }
            
            socket?.on(Socket.EVENT_DISCONNECT) {
                isConnected = false
                android.util.Log.d(TAG, "Socket disconnected")
                println("DEBUG: SocketManager - Socket disconnected")
            }
            
            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = args.getOrNull(0)?.toString() ?: "unknown"
                android.util.Log.e(TAG, "Socket connection error: $error")
                println("═══════════════════════════════════════════════════════════════")
                println("[SOCKET] ❌❌❌ SOCKET CONNECTION ERROR!")
                println("[SOCKET] Error: $error")
                println("[SOCKET] Error details: ${args.joinToString(", ")}")
                println("[SOCKET] Server URL: $SERVER_BASE_URL")
                println("[SOCKET] Full Socket.IO URL should be: ${SERVER_BASE_URL}/socket.io/")
                println("[SOCKET] Screen ID: $screenId")
                println("[SOCKET] Transport being used: polling")
                println("[SOCKET] ⚠️ This may indicate server issues or network problems")
                println("[SOCKET] ⚠️ Payment events will rely on polling fallback")
                println("═══════════════════════════════════════════════════════════════")
                isConnected = false
            }
            
            // Listen for payment success event
            println("[SOCKET] 📝 Registering 'payment-success' event listener...")
            println("[SOCKET] 📝 Socket instance: ${socket != null}")
            println("[SOCKET] 📝 Socket connected: ${socket?.connected()}")
            socket?.on("payment-success") { args ->
                try {
                    println("═══════════════════════════════════════════════════════════════")
                    println("[SOCKET] 🔔🔔🔔 'payment-success' EVENT RECEIVED! 🔔🔔🔔")
                    println("[SOCKET] Args count: ${args.size}")
                    println("[SOCKET] Args: ${args.joinToString(", ")}")
                    println("═══════════════════════════════════════════════════════════════")
                    
                    val data = args.getOrNull(0) as? JSONObject
                    if (data != null) {
                        val paymentData = jsonToMap(data)
                        val paymentReceivedTime = System.currentTimeMillis()
                        val bmiId = paymentData["bmiId"] as? String
                        val userId = paymentData["userId"] as? String
                        val user = paymentData["user"] as? Map<*, *>
                        val userName = (user?.get("name") as? String) ?: "Unknown"
                        val paymentToken = paymentData["paymentToken"] as? String
                        
                        // ========== PAYMENT FLOW LOGGING - SOCKET EVENT RECEIVED ==========
                        println("═══════════════════════════════════════════════════════════════")
                        println("[PAYMENT_FLOW] 📡 SocketManager - Payment success event received via Socket.IO")
                        println("[PAYMENT_FLOW] Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(paymentReceivedTime))}")
                        println("[PAYMENT_FLOW] BMI ID: $bmiId")
                        println("[PAYMENT_FLOW] User ID: $userId")
                        println("[PAYMENT_FLOW] User Name: $userName")
                        println("[PAYMENT_FLOW] Payment Token: $paymentToken")
                        println("[PAYMENT_FLOW] User Object: $user")
                        println("[PAYMENT_FLOW] Full Payment Data: $paymentData")
                        println("[PAYMENT_FLOW] Payment Data Keys: ${paymentData.keys.joinToString(", ")}")
                        println("[PAYMENT_FLOW] Callback registered: ${onPaymentSuccess != null}")
                        println("═══════════════════════════════════════════════════════════════")
                        
                        android.util.Log.d(TAG, "Payment success received: $paymentData")
                        println("DEBUG: SocketManager - Payment success received: $paymentData")
                        
                        // Execute callback on main thread to update UI state
                        Handler(Looper.getMainLooper()).post {
                            println("[PAYMENT_FLOW] 🔔 Executing payment success callback on main thread")
                            println("[PAYMENT_FLOW] Callback is null: ${onPaymentSuccess == null}")
                            if (onPaymentSuccess != null) {
                                println("[PAYMENT_FLOW] ✅ Invoking payment success callback...")
                                try {
                                    onPaymentSuccess?.invoke(paymentData)
                                    println("[PAYMENT_FLOW] ✅ Payment success callback executed successfully")
                                } catch (e: Exception) {
                                    println("[PAYMENT_FLOW] ❌❌❌ ERROR in payment success callback: ${e.message}")
                                    e.printStackTrace()
                                }
                            } else {
                                println("[PAYMENT_FLOW] ⚠️⚠️⚠️ WARNING: Payment success callback is null!")
                                println("[PAYMENT_FLOW] ⚠️ This means setOnPaymentSuccess was not called or callback was cleared")
                            }
                        }
                    } else {
                        println("[PAYMENT_FLOW] ⚠️ SocketManager - Payment success event received but data is null")
                        println("[PAYMENT_FLOW] Args: ${args.joinToString(", ")}")
                        println("[PAYMENT_FLOW] Args types: ${args.map { it?.javaClass?.simpleName }.joinToString(", ")}")
                    }
                } catch (e: Exception) {
                    println("[PAYMENT_FLOW] ❌ SocketManager - Error processing payment-success event: ${e.message}")
                    android.util.Log.e(TAG, "Error processing payment-success event", e)
                    println("DEBUG: SocketManager - Error processing payment-success event: ${e.message}")
                    e.printStackTrace()
                }
            }
            println("[SOCKET] ✅ 'payment-success' event listener registered")
            println("[SOCKET] ✅ Listener is active and waiting for events")
            
            // Listen for room join confirmation
            socket?.on("room-joined") { args ->
                try {
                    val data = args.getOrNull(0) as? JSONObject
                    if (data != null) {
                        val roomInfo = jsonToMap(data)
                        roomJoinConfirmed = true
                        roomJoinRetryHandler?.removeCallbacksAndMessages(null)
                        println("[SOCKET] ✅✅✅ ROOM JOIN CONFIRMED BY SERVER!")
                        println("[SOCKET] Room: ${roomInfo["roomName"]}")
                        println("[SOCKET] Screen ID: ${roomInfo["screenId"]}")
                        println("[SOCKET] Room size: ${roomInfo["roomSize"]}")
                        println("[SOCKET] Socket ID: ${roomInfo["socketId"]}")
                        android.util.Log.d(TAG, "Room joined confirmed: $roomInfo")
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error processing room-joined event", e)
                    println("[SOCKET] ❌ Error processing room-joined confirmation: ${e.message}")
                }
            }
            
            socket?.on("room-join-error") { args ->
                try {
                    val data = args.getOrNull(0) as? JSONObject
                    val error = data?.getString("error") ?: "unknown"
                    println("[SOCKET] ❌❌❌ ROOM JOIN ERROR FROM SERVER: $error")
                    android.util.Log.e(TAG, "Room join error: $error")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error processing room-join-error", e)
                }
            }
            
            // Listen for fortune ready event
            socket?.on("fortune-ready") { args ->
                try {
                    val data = args.getOrNull(0) as? JSONObject
                    if (data != null) {
                        val fortuneData = jsonToMap(data)
                        android.util.Log.d(TAG, "Fortune ready received: $fortuneData")
                        println("[SOCKET] 🔮 Fortune ready event received: $fortuneData")
                        // Execute callback on main thread to update UI state
                        Handler(Looper.getMainLooper()).post {
                            onFortuneReady?.invoke(fortuneData)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error processing fortune-ready event", e)
                    println("[SOCKET] ❌ Error processing fortune-ready event: ${e.message}")
                    e.printStackTrace()
                }
            }

            registerPlaylistRefreshListener("assets-updated")
            registerPlaylistRefreshListener("screen-config-changed")
            registerPlaylistRefreshListener("flow-drawer-images-updated")

            // Listen for admin-pushed mode changes
            socket?.on("screen-mode-changed") { args ->
                try {
                    val data = args.getOrNull(0) as? JSONObject
                    val newMode = data?.optString("appMode")?.takeIf { it.isNotBlank() }
                        ?: data?.optString("mode")?.takeIf { it.isNotBlank() }
                    if (newMode != null) {
                        println("[SOCKET] 🔄 screen-mode-changed received: mode=$newMode")
                        Handler(Looper.getMainLooper()).post {
                            onModeChanged?.invoke(newMode)
                        }
                    } else {
                        println("[SOCKET] ⚠️ screen-mode-changed received but mode field missing")
                    }
                } catch (e: Exception) {
                    println("[SOCKET] ❌ Error processing screen-mode-changed: ${e.message}")
                }
            }
            
            // Listen for admin-pushed rotation changes
            socket?.on("screen-rotation-changed") { args ->
                try {
                    val data = args.getOrNull(0) as? JSONObject
                    val newRotation = data?.optString("rotation")?.takeIf { it.isNotBlank() }
                    if (newRotation != null) {
                        println("[SOCKET] 🔄 screen-rotation-changed received: rotation=$newRotation")
                        Handler(Looper.getMainLooper()).post {
                            onRotationChanged?.invoke(newRotation)
                        }
                    } else {
                        println("[SOCKET] ⚠️ screen-rotation-changed received but rotation field missing")
                    }
                } catch (e: Exception) {
                    println("[SOCKET] ❌ Error processing screen-rotation-changed: ${e.message}")
                }
            }
            
            // Handle pong for connection health
            socket?.on("pong") {
                println("[SOCKET] 💓 Pong received - connection is alive")
            }
            
            println("[SOCKET] 🔌 Calling socket.connect()...")
            println("[SOCKET] Server URL: $SERVER_BASE_URL")
            println("[SOCKET] Screen ID: $screenId")
            socket?.connect()
            Log.d(TAG, "Attempting to connect to socket server: $SERVER_BASE_URL")
            println("[SOCKET] ✅ socket.connect() called, waiting for connection...")
            
            // Also try to join room immediately if socket is already connected (race condition)
            Handler(Looper.getMainLooper()).postDelayed({
                if (socket?.connected() == true && !roomJoinConfirmed) {
                    println("[SOCKET] 🔄 Socket connected but room not joined, attempting join...")
                    joinRoom(screenId)
                }
            }, 1000)
            
        } catch (e: URISyntaxException) {
            android.util.Log.e(TAG, "Invalid server URL", e)
            println("DEBUG: SocketManager - Invalid server URL: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error connecting socket", e)
            println("DEBUG: SocketManager - Error connecting socket: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Join socket room for this screen
     */
    private fun joinRoom(screenId: String) {
        try {
            if (socket == null) {
                println("[SOCKET] ❌ Cannot join room - socket is null")
                return
            }
            
            if (!socket!!.connected()) {
                println("[SOCKET] ⚠️ Cannot join room - socket not connected")
                println("[SOCKET] Socket state: ${socket?.connected()}")
                return
            }
            
            println("═══════════════════════════════════════════════════════════════")
            println("[SOCKET] 🚪🚪🚪 JOINING ROOM FOR SCREEN 🚪🚪🚪")
            println("[SOCKET] Screen ID: $screenId")
            println("[SOCKET] Room name format: screen:$screenId")
            println("[SOCKET] Socket ID: ${socket?.id()}")
            println("[SOCKET] Socket connected: ${socket?.connected()}")
            println("═══════════════════════════════════════════════════════════════")
            
            val joinData = JSONObject().apply {
                put("screenId", screenId)
            }
            
            println("[SOCKET] 📤 Emitting 'player-join' event with data: $joinData")
            socket?.emit("player-join", joinData)
            Log.d(TAG, "Joined room: screen:$screenId")
            println("[SOCKET] ✅ 'player-join' event emitted successfully")
            println("[SOCKET] ✅ Waiting for server confirmation...")
            
            // Verify room join after a short delay
            Handler(Looper.getMainLooper()).postDelayed({
                if (socket?.connected() == true) {
                    if (roomJoinConfirmed) {
                        println("[SOCKET] ✅✅✅ Room join confirmed - we are in the room!")
                    } else {
                        println("[SOCKET] ⚠️ Room join not yet confirmed - will retry if needed")
                    }
                } else {
                    println("[SOCKET] ⚠️ Room join verification - Socket disconnected")
                }
            }, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error joining room", e)
            println("[SOCKET] ❌❌❌ Error joining room: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun registerPlaylistRefreshListener(eventName: String) {
        socket?.on(eventName) { args ->
            try {
                val data = args.getOrNull(0) as? JSONObject
                println("═══════════════════════════════════════════════════════════════")
                println("[SOCKET] 🔔 '$eventName' EVENT RECEIVED")
                println("[SOCKET] Data: $data")
                println("═══════════════════════════════════════════════════════════════")
                Log.d(TAG, "Received $eventName event: $data")
                notifyAssetsUpdated()
            } catch (e: Exception) {
                println("[SOCKET] ❌ Error processing $eventName event: ${e.message}")
            }
        }
    }
    
    /**
     * Set callback for payment success
     */
    fun setOnPaymentSuccess(callback: (Map<String, Any>) -> Unit) {
        println("[SOCKET] 📝 setOnPaymentSuccess called - registering callback")
        this.onPaymentSuccess = callback
        println("[SOCKET] ✅ Payment success callback registered: ${this.onPaymentSuccess != null}")
    }
    
    /**
     * Set callback for fortune ready
     */
    fun setOnFortuneReady(callback: (Map<String, Any>) -> Unit) {
        this.onFortuneReady = callback
    }

    /**
     * Set callback for real-time asset updates
     */
    fun setOnAssetsUpdated(callback: () -> Unit) {
        this.onAssetsUpdated = callback
    }

    /**
     * Set callback for admin-pushed app mode changes.
     * The string value is the new mode: "F1", "F2", or "PlayerOnly".
     */
    fun setOnModeChanged(callback: (String) -> Unit) {
        this.onModeChanged = callback
    }

    /**
     * Set callback for admin-pushed screen rotation changes.
     */
    fun setOnRotationChanged(callback: (String) -> Unit) {
        this.onRotationChanged = callback
    }
    
    /**
     * Ensure we're in the room - call this periodically to maintain room membership
     */
    fun ensureRoomJoined() {
        val screenId = currentScreenId
        if (screenId != null && socket?.connected() == true) {
            if (!roomJoinConfirmed) {
                println("[SOCKET] 🔄 Room join not confirmed, rejoining room...")
                joinRoom(screenId)
            }
        }
    }
    
    /**
     * Disconnect from socket server
     */
    private fun notifyAssetsUpdated() {
        Handler(Looper.getMainLooper()).post {
            onAssetsUpdated?.invoke()
        }
    }

    fun disconnect() {
        try {
            roomJoinRetryHandler?.removeCallbacksAndMessages(null)
            socket?.disconnect()
            socket?.off()
            socket = null
            isConnected = false
            roomJoinConfirmed = false
            currentScreenId = null
            Log.d(TAG, "Socket disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting socket", e)
        }
    }
    
    /**
     * Check if socket is connected
     */
    fun isConnected(): Boolean {
        return isConnected && socket?.connected() == true
    }
    
    /**
     * Check if room is joined
     */
    fun isRoomJoined(): Boolean {
        return roomJoinConfirmed && isConnected()
    }
    
    /**
     * Convert JSONObject to Map<String, Any>
     */
    private fun jsonToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next() as String
            val value = json.get(key) as Any?
            val convertedValue: Any = when (value) {
                is JSONObject -> jsonToMap(value)
                is org.json.JSONArray -> value.toString()
                null -> ""
                else -> value as Any
            }
            map[key] = convertedValue
        }
        return map
    }
}
