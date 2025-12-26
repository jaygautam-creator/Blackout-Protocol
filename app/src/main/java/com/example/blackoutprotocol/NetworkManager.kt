package com.example.blackoutprotocol

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

class NetworkManager(private val context: Context) {
    private val TAG = "BlackoutProtocol"

    companion object {
        const val SERVICE_ID = "com.example.blackoutprotocol.mesh"
        const val SEEN_MESSAGE_TTL_MS = 300000L // 5 minutes
        private const val ENCRYPTION_KEY = "MeshEmergencyKey123" // Simple key for demo
        private const val CONNECTION_READY_DELAY_MS = 1000L // Wait 1 second before sending
    }

    // Firebase Database
    private val database: FirebaseDatabase = Firebase.database
    private val databaseRef = database.getReference("emergency_messages")

    var isActive = false
        private set

    private lateinit var connectionsClient: ConnectionsClient
    private val connectedEndpoints = mutableSetOf<String>()
    private val readyEndpoints = mutableSetOf<String>() // Endpoints ready for payloads
    private val pendingMessages = mutableMapOf<String, MutableList<MeshMessage>>() // endpointId -> messages
    private val seenMessages = ConcurrentHashMap<String, Long>() // messageId -> timestamp
    private lateinit var messageListener: (MeshMessage) -> Unit
    private lateinit var statusListener: (String) -> Unit
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val deviceId = "device_${System.currentTimeMillis()}"
    private val deviceName = "Node=${deviceId.takeLast(6)}"

    private var cleanupJob: Job? = null
    private var retryJob: Job? = null
    private var healthCheckJob: Job? = null

    // Connection state tracking
    private enum class ConnectionState {
        DISCONNECTED,
        INITIATED,
        ACCEPTED,
        READY,
        FAILED
    }

    private val connectionStates = ConcurrentHashMap<String, ConnectionState>()

    init {
        try {
            connectionsClient = Nearby.getConnectionsClient(context)
            Log.d(TAG, "✅ NetworkManager initialized")
            Log.d(TAG, "✅ Firebase Database initialized; ${database.reference}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize: ${e.message}")
        }
    }

    fun setListeners(
        onMessageReceived: (MeshMessage) -> Unit,
        onStatusUpdate: (String) -> Unit
    ) {
        this.messageListener = onMessageReceived
        this.statusListener = onStatusUpdate
    }

    fun startMeshNetwork() {
        if (isActive) return
        isActive = true

        // Start cleanup coroutine only when active
        startCleanupJob()

        // Start retry job for pending messages
        startRetryJob()

        // Start health check
        startHealthCheck()

        statusListener("Starting mesh network...")
        Log.d(TAG, "Starting mesh network")

        try {
            startAdvertising()
            startDiscovery()
            statusListener("Mesh active: $deviceName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mesh: ${e.message}")
        }
    }

    private fun startCleanupJob() {
        cleanupJob = scope.launch {
            while (isActive) {
                delay(60000L) // Every minute
                cleanupOldSeenMessages()
            }
        }
    }

    private fun startRetryJob() {
        retryJob = scope.launch {
            while (isActive) {
                delay(30000L) // Every 30 seconds
                retryPendingMessages()
            }
        }
    }

    private fun startHealthCheck() {
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(10000L) // Every 10 seconds
                logConnectionStatus()
                // Try to resend failed messages
                retryFailedPayloads()
            }
        }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startAdvertising(
            deviceName,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "📡 Advertising started as: $deviceName")
            statusListener("📡 Advertising as $deviceName...")
        }.addOnFailureListener { e ->
            Log.e(TAG, "❌ Advertising failed: ${e.message}")
            statusListener("❌ Advertising failed")
        }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "🔍 Discovery started")
            statusListener("🔍 Searching for devices...")
        }.addOnFailureListener { e ->
            Log.e(TAG, "❌ Discovery failed: ${e.message}")
            statusListener("❌ Discovery failed")
        }
    }

    fun sendMessage(content: String, encryptedLocation: String? = null) {
        try {
            val message = MeshMessage.createSOSMessage(
                content,
                deviceId,
                deviceName,
                encryptedLocation
            )

            // Mark as seen by this device
            markMessageAsSeen(message.id)

            // Try to upload to Firebase if internet is available
            if (hasInternetConnection()) {
                Log.d(TAG, "🌐 Internet available - uploading to Firebase as gateway")
                uploadToFirebase(message, true) // We are the original gateway
                statusListener("🔵 Original Gateway: Uploading to Firebase...")
            } else {
                Log.d(TAG, "📴 No internet - storing locally")
                statusListener("📴 Offline - storing message")
                storeMessageForLater(message)
            }

            // Broadcast via mesh with proper relay logic
            broadcastMessageToAllConnected(message)

            messageListener(message)
            Log.d(TAG, "📤 Message created: ${message.id}")
            statusListener("📤 Message created!")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send message: ${e.message}")
            statusListener("❌ Failed to send")
        }
    }

    private fun broadcastMessageToAllConnected(message: MeshMessage) {
        // Send to all connected endpoints, even if not "ready"
        // This is a fallback for P2P_CLUSTER issues

        if (connectedEndpoints.isEmpty()) {
            Log.d(TAG, "⚠️ No devices connected")
            statusListener("⚠️ No devices to send to")
            return
        }

        Log.d(TAG, "📤 Broadcasting to ${connectedEndpoints.size} devices")

        // Try to send to ready endpoints first
        if (readyEndpoints.isNotEmpty()) {
            relayMessage(message, excludedEndpointId = null)
        }

        // Also try to send to all connected endpoints as backup
        for (endpointId in connectedEndpoints) {
            sendToEndpointWithRetry(endpointId, message)
        }
    }

    private fun sendToEndpointWithRetry(endpointId: String, message: MeshMessage, retryCount: Int = 0) {
        if (retryCount > 3) {
            Log.w(TAG, "⛔ Giving up on endpoint $endpointId after 3 retries")
            return
        }

        try {
            val payload = Payload.fromBytes(message.toJson().toByteArray())
            connectionsClient.sendPayload(endpointId, payload)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Payload delivered to $endpointId")
                    statusListener("✅ Sent to device")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "⚠️ Failed to send to $endpointId (attempt ${retryCount + 1}): ${e.message}")

                    // Retry after delay
                    if (retryCount < 3) {
                        scope.launch {
                            delay(1000L * (retryCount + 1)) // Exponential backoff
                            sendToEndpointWithRetry(endpointId, message, retryCount + 1)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception sending to $endpointId: ${e.message}")
        }
    }

    private fun uploadToFirebase(message: MeshMessage, isGateway: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "☁️ Starting Firebase upload for message: ${message.id}")

                // Mark as delivered with gateway information
                val deliveredMessage = if (isGateway) {
                    message.markAsDelivered(deviceName)
                } else {
                    message.copy(
                        isDeliveredToCloud = true,
                        status = "DELIVERED_TO_CLOUD",
                        deliveredBy = deviceName
                    )
                }

                // Upload to Firebase
                databaseRef.child(message.id).setValue(deliveredMessage)
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ Firebase upload successful for ID: ${message.id}")
                        statusListener("✅ Message saved to cloud by ${if (isGateway) "YOU" else deviceName}!")

                        // If we were the gateway, update local message
                        if (isGateway) {
                            messageListener(deliveredMessage)
                        }

                        // Remove from local cache if stored
                        removeFromLocalCache(message.id)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ Firebase upload failed: ${e.message}")
                        statusListener("❌ Cloud save failed")

                        // Store locally for retry
                        if (!isGateway) {
                            storeMessageForLater(message)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception during Firebase upload: ${e.message}")
            }
        }
    }

    private fun relayMessage(message: MeshMessage, excludedEndpointId: String?) {
        if (readyEndpoints.isEmpty()) {
            Log.d(TAG, "⚠️ No ready devices to relay to")
            statusListener("⏳ Waiting for devices to be ready...")
            return
        }

        try {
            // Check hop limit using MeshMessage.MAX_HOPS
            if (message.hops >= MeshMessage.MAX_HOPS) {
                Log.d(TAG, "⛔ Message ${message.id} reached max hops (${MeshMessage.MAX_HOPS}) - stopping relay")
                statusListener("⛔ Hop limit reached for message")
                return
            }

            // Check if message already delivered
            if (message.isDeliveredToCloud) {
                Log.d(TAG, "✅ Message ${message.id} already delivered to cloud - stopping relay")
                return
            }

            // Check if this device already visited this message
            if (deviceId in message.visitedDevices) {
                Log.d(TAG, "🔁 Device already visited message ${message.id} - avoiding loop")
                return
            }

            val payload = Payload.fromBytes(message.toJson().toByteArray())
            var sentCount = 0

            for (endpoint in readyEndpoints) {
                // Don't send back to the device that sent it to us
                if (endpoint == excludedEndpointId) continue

                connectionsClient.sendPayload(endpoint, payload)
                    .addOnSuccessListener {
                        sentCount++
                        Log.d(TAG, "📤 Sent to ready device: $endpoint (hops: ${message.hops})")
                        statusListener("📤 Sent to $sentCount/${readyEndpoints.size} devices")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ Failed to send to ready endpoint $endpoint: ${e.message}")
                        // Remove failed endpoint from ready set
                        readyEndpoints.remove(endpoint)
                    }
            }

            if (sentCount == 0) {
                Log.w(TAG, "⚠️ Message prepared but no ready endpoints to send to")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Relay failed: ${e.message}")
        }
    }

    private fun sendToEndpoint(endpointId: String, message: MeshMessage) {
        if (connectionStates[endpointId] != ConnectionState.READY) {
            Log.w(TAG, "⚠️ Endpoint $endpointId not ready (state: ${connectionStates[endpointId]}). Queueing message.")

            // Queue message for later delivery
            val queue = pendingMessages.getOrPut(endpointId) { mutableListOf() }
            queue.add(message)
            return
        }

        try {
            val payload = Payload.fromBytes(message.toJson().toByteArray())
            connectionsClient.sendPayload(endpointId, payload)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Payload sent to $endpointId")
                    // Remove from pending if it was there
                    pendingMessages[endpointId]?.remove(message)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Failed to send payload to $endpointId: ${e.message}")
                    // Queue for retry
                    val queue = pendingMessages.getOrPut(endpointId) { mutableListOf() }
                    queue.add(message)
                }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception sending to $endpointId: ${e.message}")
        }
    }

    private fun sendPendingMessages(endpointId: String) {
        val messages = pendingMessages[endpointId] ?: return

        Log.d(TAG, "📨 Sending ${messages.size} pending messages to $endpointId")

        for (message in messages.toList()) { // Copy to avoid concurrent modification
            sendToEndpoint(endpointId, message)
        }
    }

    private fun retryFailedPayloads() {
        for ((endpointId, messages) in pendingMessages) {
            if (messages.isNotEmpty() && readyEndpoints.contains(endpointId)) {
                Log.d(TAG, "🔄 Retrying ${messages.size} failed payloads to $endpointId")
                sendPendingMessages(endpointId)
            }
        }
    }

    private fun storeMessageForLater(message: MeshMessage) {
        val prefs = context.getSharedPreferences("mesh_cache", Context.MODE_PRIVATE)
        val storedMessages = prefs.getStringSet("pending_messages", mutableSetOf()) ?: mutableSetOf()
        val updatedSet = storedMessages.toMutableSet().apply {
            add(message.toJson())
        }
        prefs.edit().putStringSet("pending_messages", updatedSet).apply()
        Log.d(TAG, "💾 Message stored locally: ${message.id}")
    }

    private fun logConnectionStatus() {
        Log.d(TAG, "📊 Connection Status: Connected=${connectedEndpoints.size}, Ready=${readyEndpoints.size}")
        for (endpoint in connectedEndpoints) {
            val state = connectionStates[endpoint] ?: ConnectionState.DISCONNECTED
            Log.d(TAG, "  - $endpoint: $state")
        }
    }

    private fun retryPendingMessages() {
        if (!hasInternetConnection()) {
            return
        }

        val prefs = context.getSharedPreferences("mesh_cache", Context.MODE_PRIVATE)
        val storedMessages = prefs.getStringSet("pending_messages", mutableSetOf()) ?: mutableSetOf()

        if (storedMessages.isEmpty()) {
            return
        }

        Log.d(TAG, "🔄 Retrying ${storedMessages.size} pending messages...")

        storedMessages.forEach { json ->
            try {
                val message = MeshMessage.fromJson(json)
                if (!message.isDeliveredToCloud) {
                    uploadToFirebase(message)
                } else {
                    // Remove delivered messages from cache
                    removeFromLocalCache(message.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to parse cached message: ${e.message}")
            }
        }
    }

    private fun removeFromLocalCache(messageId: String) {
        val prefs = context.getSharedPreferences("mesh_cache", Context.MODE_PRIVATE)
        val storedMessages = prefs.getStringSet("pending_messages", mutableSetOf()) ?: return

        val updatedSet = storedMessages.filterNot { json ->
            try {
                val msg = MeshMessage.fromJson(json)
                msg.id == messageId
            } catch (e: Exception) {
                false
            }
        }.toSet()

        prefs.edit().putStringSet("pending_messages", updatedSet).apply()
    }

    private fun markMessageAsSeen(messageId: String) {
        seenMessages[messageId] = System.currentTimeMillis()
    }

    private fun hasSeenMessage(messageId: String): Boolean {
        val timestamp = seenMessages[messageId]
        if (timestamp == null) return false

        // Check if the entry is still valid
        if (System.currentTimeMillis() - timestamp > SEEN_MESSAGE_TTL_MS) {
            seenMessages.remove(messageId)
            return false
        }
        return true
    }

    private fun cleanupOldSeenMessages() {
        val now = System.currentTimeMillis()
        val iterator = seenMessages.iterator()
        var removed = 0

        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > SEEN_MESSAGE_TTL_MS) {
                iterator.remove()
                removed++
            }
        }

        if (removed > 0) {
            Log.d(TAG, "🧹 Cleaned up $removed old seen messages")
        }
    }

    private fun hasInternetConnection(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            val hasInternet = capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )

            Log.d(TAG, "🌐 Internet check: $hasInternet")
            hasInternet
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking internet: ${e.message}")
            false
        }
    }

    // Simple encryption for GPS location (for demo purposes)
    fun encryptLocation(latitude: Double, longitude: Double): String {
        return try {
            val locationString = "$latitude,$longitude"
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            val secretKey = SecretKeySpec(ENCRYPTION_KEY.toByteArray().copyOf(16), "AES")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val encryptedBytes = cipher.doFinal(locationString.toByteArray())
            Base64.getEncoder().encodeToString(encryptedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Location encryption failed: ${e.message}")
            "ENCRYPTION_ERROR"
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "🔍 Found device: ${info.endpointName} (ID: ${endpointId.take(8)})")
            statusListener("🔍 Found: ${info.endpointName}")

            // Request connection immediately
            connectionsClient.requestConnection(
                deviceName,
                endpointId,
                connectionLifecycleCallback
            ).addOnSuccessListener {
                Log.d(TAG, "✅ Connection request sent to ${endpointId.take(8)}")
            }.addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to request connection: ${e.message}")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "⚠️ Lost device: ${endpointId.take(8)}")
            connectedEndpoints.remove(endpointId)
            readyEndpoints.remove(endpointId)
            connectionStates.remove(endpointId)
            pendingMessages.remove(endpointId)
            statusListener("Connected to ${connectedEndpoints.size} devices")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d(TAG, "🤝 Connection initiated with: ${connectionInfo.endpointName} (ID: ${endpointId.take(8)})")
            statusListener("🤝 Connecting to ${connectionInfo.endpointName}...")

            connectionStates[endpointId] = ConnectionState.INITIATED

            // Accept the connection IMMEDIATELY (CRITICAL for P2P_CLUSTER)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Connection accepted from ${endpointId.take(8)}")
                    connectionStates[endpointId] = ConnectionState.ACCEPTED

                    // Add to connected endpoints immediately
                    connectedEndpoints.add(endpointId)
                    statusListener("Connected to ${connectedEndpoints.size} devices")

                    // Mark as ready after delay
                    scope.launch {
                        delay(CONNECTION_READY_DELAY_MS)
                        readyEndpoints.add(endpointId)
                        connectionStates[endpointId] = ConnectionState.READY
                        Log.d(TAG, "🚀 Endpoint ${endpointId.take(8)} ready for payloads")
                        statusListener("🚀 ${readyEndpoints.size} devices ready")

                        // Send any pending messages for this endpoint
                        sendPendingMessages(endpointId)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Failed to accept connection from ${endpointId.take(8)}: ${e.message}")
                    connectionStates[endpointId] = ConnectionState.FAILED
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.d(TAG, "✅ Connection successful with ${endpointId.take(8)}")

                // Ensure endpoint is in connected set
                connectedEndpoints.add(endpointId)
                statusListener("Connected to ${connectedEndpoints.size} devices")

                // Wait before marking as ready for payloads
                scope.launch {
                    delay(CONNECTION_READY_DELAY_MS)
                    readyEndpoints.add(endpointId)
                    connectionStates[endpointId] = ConnectionState.READY
                    Log.d(TAG, "🚀 Endpoint ${endpointId.take(8)} ready for payloads")
                    statusListener("🚀 ${readyEndpoints.size} devices ready")

                    // Send any pending messages for this endpoint
                    sendPendingMessages(endpointId)
                }
            } else {
                Log.e(TAG, "❌ Connection failed with ${endpointId.take(8)}: ${result.status}")
                connectionStates[endpointId] = ConnectionState.FAILED
                connectedEndpoints.remove(endpointId)
                readyEndpoints.remove(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "🔌 Disconnected: ${endpointId.take(8)}")
            connectedEndpoints.remove(endpointId)
            readyEndpoints.remove(endpointId)
            connectionStates.remove(endpointId)
            pendingMessages.remove(endpointId)
            statusListener("Connected to ${connectedEndpoints.size} devices")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            Log.d(TAG, "📥 PAYLOAD RECEIVED from ${endpointId.take(8)}!")
            statusListener("📥 Message received!")

            payload.asBytes()?.let { bytes ->
                try {
                    val json = String(bytes)
                    val message = MeshMessage.fromJson(json)

                    // Loop prevention: check if we've seen this message
                    if (hasSeenMessage(message.id)) {
                        Log.d(TAG, "🔄 Already processed message ${message.id} - ignoring")
                        return@let
                    }

                    // Mark as seen
                    markMessageAsSeen(message.id)

                    Log.d(TAG, "📨 Received message from ${message.senderDeviceName}, hops: ${message.hops}")

                    // Check if this device already visited this message
                    if (deviceId in message.visitedDevices) {
                        Log.d(TAG, "🔁 Device already visited this message - dropping")
                        return@let
                    }

                    // Increment hop count and add this device to visited list
                    val updatedMessage = message.incrementHop(deviceId)

                    // Check hop limit
                    if (updatedMessage.hops > MeshMessage.MAX_HOPS) {
                        Log.d(TAG, "⛔ Message ${message.id} exceeded max hops (${MeshMessage.MAX_HOPS}) - dropping")
                        statusListener("⛔ Dropped message (hop limit)")
                        return@let
                    }

                    messageListener(updatedMessage)

                    // Check if we have internet AND message not already delivered
                    val hasInternet = hasInternetConnection()

                    if (hasInternet && !updatedMessage.isDeliveredToCloud) {
                        Log.d(TAG, "🔵 Acting as GATEWAY for message ${updatedMessage.id}")
                        statusListener("🔵 YOU are Gateway for message!")

                        // Upload to Firebase
                        uploadToFirebase(updatedMessage)

                        // Do NOT relay further - gateway responsibility fulfilled
                        Log.d(TAG, "✅ Gateway: Stopping relay for ${updatedMessage.id}")
                    } else {
                        if (updatedMessage.isDeliveredToCloud) {
                            Log.d(TAG, "✅ Message already delivered to cloud - stopping relay")
                            statusListener("✅ Message already in cloud")
                        } else {
                            // Relay to other devices (except the one we received from)
                            relayMessage(updatedMessage, endpointId)
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to process message: ${e.message}")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    Log.d(TAG, "✅ Payload transfer SUCCESS to ${endpointId.take(8)}")
                    statusListener("✅ Message delivered")
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    Log.e(TAG, "❌ Payload transfer FAILED to ${endpointId.take(8)}")
                    statusListener("❌ Message failed to send")
                }
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    // Log progress for large payloads
                    Log.d(TAG, "📤 Transfer in progress to ${endpointId.take(8)}")
                }
                else -> {}
            }
        }
    }

    fun stop() {
        isActive = false
        cleanupJob?.cancel()
        retryJob?.cancel()
        healthCheckJob?.cancel()
        scope.cancel()
        connectionsClient.stopAllEndpoints()
        Log.d(TAG, "🛑 Network stopped")
    }
}