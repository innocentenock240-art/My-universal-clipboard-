package com.example.sync.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.core.identity.DeviceIdentityManager
import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.model.ClipboardItem
import com.example.data.model.ConnectionState
import com.example.data.model.Device
import com.example.sync.model.parseClipboardItemFromJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.ArrayDeque
import java.util.UUID

/**
 * Local Wi-Fi transport implementation for direct local network TCP socket communication
 * and NsdManager mDNS device discovery.
 * Responsible strictly for network transport and socket connections without clipboard capture logic.
 */
class LocalWifiTransport(
    private val context: Context? = null,
    private val port: Int = DEFAULT_PORT,
    private val customDeviceId: String? = null
) : Transport {

    companion object {
        const val DEFAULT_PORT = 53711
        // Android NsdManager serviceType must NOT have a trailing dot
        private const val SERVICE_TYPE = "_uclip._tcp"
        private const val TAG = "LocalWifiTransport"
        private const val PREFS_NAME = "uclip_device_prefs"
        private const val KEY_KNOWN_PEERS = "known_peer_device_ids"
    }

    override val transportName: String = "LocalWi-Fi"

    private var serverSocket: ServerSocket? = null
    private var listeningJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _incomingItems = MutableSharedFlow<ClipboardItem>(replay = 1, extraBufferCapacity = 64)
    private val _incomingMessages = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 64)

    val incomingMessages: Flow<String> = _incomingMessages.asSharedFlow()

    private val _discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val discoveredDevices: StateFlow<List<Device>> = _discoveredDevices.asStateFlow()

    private val knownPeerDeviceIds = java.util.Collections.synchronizedSet(HashSet<String>())
    private val reconnectingJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    init {
        loadKnownPeersFromPrefs()
    }

    private fun loadKnownPeersFromPrefs() {
        context?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val stored = prefs.getStringSet(KEY_KNOWN_PEERS, null)
                if (stored != null) {
                    knownPeerDeviceIds.addAll(stored)
                    Log.i(TAG, "Loaded ${stored.size} known peer(s) from persistent storage: $stored")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to load known peers from SharedPreferences", e)
            }
        }
    }

    private fun saveKnownPeersToPrefs() {
        context?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                synchronized(knownPeerDeviceIds) {
                    prefs.edit().putStringSet(KEY_KNOWN_PEERS, HashSet(knownPeerDeviceIds)).apply()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to save known peers to SharedPreferences", e)
            }
        }
    }

    fun addKnownPeer(deviceId: String) {
        if (deviceId.isBlank() || deviceId == localDeviceId) return
        val added = knownPeerDeviceIds.add(deviceId)
        if (added) {
            saveKnownPeersToPrefs()
            Log.i(TAG, "Added known peer: $deviceId. Total known peers: ${knownPeerDeviceIds.size}")
        }
    }

    fun removeKnownPeer(deviceId: String) {
        val removed = knownPeerDeviceIds.remove(deviceId)
        if (removed) {
            saveKnownPeersToPrefs()
            Log.i(TAG, "Removed known peer: $deviceId")
        }
    }

    fun isKnownPeer(deviceId: String): Boolean {
        return knownPeerDeviceIds.contains(deviceId)
    }

    fun getKnownPeers(): Set<String> {
        return synchronized(knownPeerDeviceIds) { knownPeerDeviceIds.toSet() }
    }

    fun clearKnownPeers() {
        knownPeerDeviceIds.clear()
        saveKnownPeersToPrefs()
        Log.i(TAG, "Cleared all known peers from storage")
    }

    private class PeerSession(
        val deviceId: String,
        val deviceName: String,
        val socket: Socket,
        val reader: BufferedReader,
        val writer: PrintWriter
    ) {
        fun closeSilently() {
            try { reader.close() } catch (_: Throwable) {}
            try { writer.close() } catch (_: Throwable) {}
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private val activeSessions = java.util.concurrent.ConcurrentHashMap<String, PeerSession>()

    private val recentlyProcessedHashes = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile
    private var _isListening = false

    @Volatile
    private var isNsdAdvertising = false

    @Volatile
    private var isNsdDiscovering = false

    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null

    private var multicastLock: WifiManager.MulticastLock? = null

    private val nsdManager: NsdManager? by lazy {
        context?.applicationContext?.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private val wifiManager: WifiManager? by lazy {
        context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    val localDeviceId: String by lazy {
        DeviceIdentityManager.getLocalDeviceId(context, customDeviceId)
    }

    val localDeviceName: String by lazy {
        DeviceIdentityManager.getLocalDeviceName()
    }

    // Resolution Queue to prevent NsdManager.FAILURE_ALREADY_ACTIVE (Error 3)
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false

    override val isAvailable: Boolean
        get() = _isListening && serverSocket != null && !serverSocket!!.isClosed

    override suspend fun startDiscovery() {
        startServer()
        startNsdDiscovery()
    }

    override suspend fun stopDiscovery() {
        stopNsdDiscovery()
        stopNsdAdvertisement()
    }

    fun startServer() {
        if (_isListening) return
        try {
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }
            _isListening = true
            Log.i(TAG, "LocalWifiTransport server started on port $port")

            listeningJob = scope.launch {
                while (isActive && _isListening) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch { handleIncomingSocket(clientSocket) }
                    } catch (e: SocketException) {
                        Log.d(TAG, "Server socket closed or accept interrupted: ${e.message}")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error accepting client connection", e)
                    }
                }
            }

            // Also advertise via mDNS if context is available
            startNsdAdvertisement()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LocalWifiTransport server on port $port", e)
            _isListening = false
        }
    }

    fun stopServer() {
        _isListening = false
        listeningJob?.cancel()
        listeningJob = null
        reconnectingJobs.values.forEach { it.cancel() }
        reconnectingJobs.clear()
        stopNsdAdvertisement()
        stopNsdDiscovery()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        } finally {
            serverSocket = null
        }
        Log.i(TAG, "LocalWifiTransport server stopped")
    }

    fun startNsdAdvertisement() {
        if (isNsdAdvertising || nsdRegistrationListener != null || nsdManager == null) return
        acquireMulticastLock()
        try {
            val listeningPort = serverSocket?.localPort ?: this@LocalWifiTransport.port
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "UClip_$localDeviceId"
                serviceType = SERVICE_TYPE
                setPort(listeningPort)
                try {
                    setAttribute("deviceId", localDeviceId)
                    setAttribute("deviceName", localDeviceName)
                } catch (e: Throwable) {
                    Log.w(TAG, "NsdServiceInfo setAttribute unavailable", e)
                }
            }

            Log.i(TAG, "NSD Registration started for service UClip_$localDeviceId with type $SERVICE_TYPE on port $listeningPort")

            nsdRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(registeredServiceInfo: NsdServiceInfo) {
                    isNsdAdvertising = true
                    Log.i(TAG, "NSD Service registered successfully: ${registeredServiceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    isNsdAdvertising = false
                    nsdRegistrationListener = null
                    Log.e(TAG, "NSD Service registration failed with errorCode: $errorCode")
                    releaseMulticastLock()
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    isNsdAdvertising = false
                    nsdRegistrationListener = null
                    Log.i(TAG, "NSD Service unregistered: ${serviceInfo.serviceName}")
                    releaseMulticastLock()
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    isNsdAdvertising = false
                    nsdRegistrationListener = null
                    Log.e(TAG, "NSD Service unregistration failed with errorCode: $errorCode")
                    releaseMulticastLock()
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD advertisement", e)
            nsdRegistrationListener = null
            releaseMulticastLock()
        }
    }

    fun stopNsdAdvertisement() {
        if (!isNsdAdvertising && nsdRegistrationListener == null) {
            releaseMulticastLock()
            return
        }
        try {
            nsdRegistrationListener?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering NSD service", e)
        } finally {
            nsdRegistrationListener = null
            isNsdAdvertising = false
            releaseMulticastLock()
        }
    }

    fun startNsdDiscovery() {
        if (isNsdDiscovering || nsdManager == null) return
        acquireMulticastLock()
        try {
            nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    isNsdDiscovering = true
                    Log.i(TAG, "NSD Discovery started for $regType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "NSD Service found: ${serviceInfo.serviceName}, type: ${serviceInfo.serviceType}")
                    if (serviceInfo.serviceType.contains("_uclip")) {
                        if (serviceInfo.serviceName.contains(localDeviceId) || serviceInfo.serviceName == "UClip_$localDeviceId") {
                            Log.d(TAG, "Ignoring self-discovered device service: ${serviceInfo.serviceName} (localDeviceId: $localDeviceId)")
                            return
                        }
                        queueResolveService(serviceInfo)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "NSD Service lost: ${serviceInfo.serviceName}")
                    removeDiscoveredDeviceByServiceName(serviceInfo.serviceName)
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    isNsdDiscovering = false
                    Log.i(TAG, "NSD Discovery stopped for $serviceType")
                    releaseMulticastLock()
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    isNsdDiscovering = false
                    Log.e(TAG, "NSD Discovery start failed with errorCode: $errorCode")
                    releaseMulticastLock()
                    try {
                        nsdManager?.stopServiceDiscovery(this)
                    } catch (e: Exception) { /* ignore */ }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    isNsdDiscovering = false
                    Log.e(TAG, "NSD Discovery stop failed with errorCode: $errorCode")
                    releaseMulticastLock()
                }
            }

            nsdManager?.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                nsdDiscoveryListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery", e)
            releaseMulticastLock()
        }
    }

    fun stopNsdDiscovery() {
        if (!isNsdDiscovering && nsdDiscoveryListener == null) {
            releaseMulticastLock()
            return
        }
        try {
            nsdDiscoveryListener?.let { listener ->
                nsdManager?.stopServiceDiscovery(listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping NSD discovery", e)
        } finally {
            nsdDiscoveryListener = null
            isNsdDiscovering = false
            releaseMulticastLock()
        }
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("UClipMulticastLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
                Log.i(TAG, "MulticastLock acquired for mDNS discovery")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire MulticastLock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (!isNsdDiscovering && !isNsdAdvertising && nsdDiscoveryListener == null && nsdRegistrationListener == null) {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                    Log.i(TAG, "MulticastLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release MulticastLock", e)
        }
    }

    private fun queueResolveService(serviceInfo: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.add(serviceInfo)
            processNextResolve()
        }
    }

    private fun processNextResolve() {
        synchronized(resolveQueue) {
            if (isResolving || resolveQueue.isEmpty()) return
            val nextService = resolveQueue.poll() ?: return
            isResolving = true
            Log.i(TAG, "NSD Service resolution started for ${nextService.serviceName}")

            val resolveTimeoutJob = scope.launch {
                kotlinx.coroutines.delay(5000)
                synchronized(resolveQueue) {
                    if (isResolving) {
                        Log.w(TAG, "NSD Resolve timed out after 5s for ${nextService.serviceName}")
                        isResolving = false
                        processNextResolve()
                    }
                }
            }

            try {
                nsdManager?.resolveService(nextService, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolveTimeoutJob.cancel()
                        Log.e(TAG, "NSD Resolve failed for ${serviceInfo.serviceName} with errorCode: $errorCode")
                        synchronized(resolveQueue) {
                            isResolving = false
                            processNextResolve()
                        }
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolveTimeoutJob.cancel()
                        Log.i(TAG, "NSD Service resolved: ${serviceInfo.serviceName}, host=${serviceInfo.host}, port=${serviceInfo.port}")
                        handleResolvedService(serviceInfo)
                        synchronized(resolveQueue) {
                            isResolving = false
                            processNextResolve()
                        }
                    }
                })
            } catch (e: Exception) {
                resolveTimeoutJob.cancel()
                Log.e(TAG, "Failed to invoke resolveService for ${nextService.serviceName}", e)
                isResolving = false
                processNextResolve()
            }
        }
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo) {
        val hostAddress = serviceInfo.host?.hostAddress ?: return

        var devId = try {
            val bytes = serviceInfo.attributes["deviceId"]
            bytes?.let { String(it, Charsets.UTF_8) }
        } catch (e: Throwable) { null }

        var devName = try {
            val bytes = serviceInfo.attributes["deviceName"]
            bytes?.let { String(it, Charsets.UTF_8) }
        } catch (e: Throwable) { null }

        if (devId.isNullOrBlank()) {
            devId = serviceInfo.serviceName.removePrefix("UClip_")
        }
        if (devName.isNullOrBlank()) {
            devName = serviceInfo.serviceName.removePrefix("UClip_").replace("_", " ")
        }

        // Filter out local device ID or self-discovery
        if (devId == localDeviceId || serviceInfo.serviceName.contains(localDeviceId)) {
            Log.d(TAG, "Ignoring self-discovered device in handleResolvedService: $devId (localDeviceId: $localDeviceId)")
            return
        }

        Log.i(TAG, "Discovered IP address: $hostAddress for deviceId: $devId ($devName)")

        val discoveredDevice = Device(
            deviceId = devId,
            deviceName = devName,
            deviceType = "PHONE",
            ipAddress = hostAddress,
            isLocalDevice = false,
            isOnline = true,
            isPaired = false
        )

        addDiscoveredDevice(discoveredDevice)
    }

    fun addDiscoveredDevice(device: Device) {
        if (device.deviceId == localDeviceId) return
        val isKnown = isKnownPeer(device.deviceId)
        val current = _discoveredDevices.value.toMutableList()
        val index = current.indexOfFirst { it.deviceId == device.deviceId || (it.ipAddress != null && it.ipAddress == device.ipAddress) }
        val updatedDevice: Device

        if (index >= 0) {
            val existing = current[index]
            val preservedState = if (existing.connectionState == ConnectionState.CONNECTED || existing.connectionState == ConnectionState.CONNECTING || existing.connectionState == ConnectionState.RECONNECTING) {
                existing.connectionState
            } else if (isKnown && (existing.connectionState == ConnectionState.DISCONNECTED || existing.connectionState == ConnectionState.DISCOVERED)) {
                ConnectionState.RECONNECTING
            } else {
                device.connectionState
            }
            updatedDevice = device.copy(
                deviceId = if (device.deviceId.startsWith("dev_local_")) device.deviceId else existing.deviceId,
                isPaired = isKnown || existing.isPaired,
                connectionState = preservedState
            )
            current[index] = updatedDevice
        } else {
            val initialState = if (isKnown && device.connectionState == ConnectionState.DISCOVERED) {
                ConnectionState.RECONNECTING
            } else {
                device.connectionState
            }
            updatedDevice = device.copy(
                isPaired = isKnown,
                connectionState = initialState
            )
            current.add(updatedDevice)
        }
        _discoveredDevices.value = current

        // Automatic Reconnection Trigger for Known Peers
        if (isKnown) {
            triggerAutoReconnectIfEligible(updatedDevice)
        }
    }

    fun triggerAutoReconnectIfEligible(targetDevice: Device) {
        val deviceId = targetDevice.deviceId
        if (!isKnownPeer(deviceId) || deviceId == localDeviceId) return
        if (activeSessions.containsKey(deviceId)) {
            Log.d(TAG, "Device $deviceId already has an active session. No auto-reconnect needed.")
            return
        }

        val currentStatus = _discoveredDevices.value.find { it.deviceId == deviceId }?.connectionState
        if (currentStatus == ConnectionState.CONNECTED) {
            return
        }

        val existingJob = reconnectingJobs[deviceId]
        if (existingJob != null && existingJob.isActive) {
            Log.d(TAG, "Auto-reconnect already active for $deviceId. Skipping duplicate trigger.")
            return
        }

        val job = scope.launch(Dispatchers.IO) {
            try {
                var attempt = 0
                val maxAttempts = 3
                val backoffs = listOf(100L, 1000L, 2500L)

                while (isActive && attempt < maxAttempts) {
                    if (activeSessions.containsKey(deviceId)) {
                        Log.i(TAG, "Device $deviceId is connected. Terminating auto-reconnect loop.")
                        break
                    }

                    // Retrieve newest device entry from state flow to ensure latest IP from NSD is used
                    val latestDevice = _discoveredDevices.value.find { it.deviceId == deviceId } ?: targetDevice
                    if (latestDevice.ipAddress.isNullOrBlank()) {
                        Log.w(TAG, "Cannot auto-reconnect to $deviceId: Missing IP address.")
                        break
                    }

                    attempt++
                    Log.i(TAG, "Auto-reconnecting to known peer $deviceId at ${latestDevice.ipAddress} (Attempt $attempt/$maxAttempts)...")
                    updateDeviceConnectionState(deviceId, ConnectionState.RECONNECTING)

                    val success = connectToDevice(latestDevice)
                    if (success) {
                        Log.i(TAG, "Auto-reconnection succeeded for known peer $deviceId.")
                        break
                    } else {
                        Log.w(TAG, "Auto-reconnection attempt $attempt failed for $deviceId.")
                        if (attempt < maxAttempts) {
                            val delayMs = backoffs.getOrElse(attempt) { 2000L }
                            delay(delayMs)
                        }
                    }
                }

                if (!activeSessions.containsKey(deviceId)) {
                    val finalState = _discoveredDevices.value.find { it.deviceId == deviceId }?.connectionState
                    if (finalState != ConnectionState.CONNECTED) {
                        updateDeviceConnectionState(deviceId, ConnectionState.DISCONNECTED)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during auto-reconnection for $deviceId", e)
                if (!activeSessions.containsKey(deviceId)) {
                    updateDeviceConnectionState(deviceId, ConnectionState.DISCONNECTED)
                }
            } finally {
                reconnectingJobs.remove(deviceId)
            }
        }

        reconnectingJobs[deviceId] = job
    }

    fun updateDeviceConnectionState(deviceId: String, state: ConnectionState) {
        val current = _discoveredDevices.value.toMutableList()
        val index = current.indexOfFirst { it.deviceId == deviceId }
        if (index >= 0) {
            val updated = current[index].copy(
                connectionState = state,
                isOnline = state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING || state == ConnectionState.DISCOVERED
            )
            current[index] = updated
            _discoveredDevices.value = current
        }
    }

    private fun parseKeyFromMessage(message: String?, key: String): String? {
        if (message.isNullOrBlank()) return null
        try {
            val regex = Regex("""(?i)\b$key\s*[:=]\s*([^;\,\}\s"]+)""")
            val match = regex.find(message)
            if (match != null) {
                return match.groupValues[1].trim()
            }
            if (key == "deviceName") {
                if (message.startsWith("ACK_FROM_")) {
                    return message.removePrefix("ACK_FROM_").trim()
                }
                if (message.startsWith("HELLO_FROM_")) {
                    return message.removePrefix("HELLO_FROM_").trim()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse $key from message: $message", e)
        }
        return null
    }

    suspend fun connectToDevice(targetDevice: Device): Boolean {
        val deviceId = targetDevice.deviceId
        val currentDeviceState = _discoveredDevices.value.find { it.deviceId == deviceId }?.connectionState
        if (currentDeviceState == ConnectionState.CONNECTED && activeSessions.containsKey(deviceId)) {
            Log.i(TAG, "Device $deviceId is already CONNECTED with active session.")
            return true
        }
        if (currentDeviceState == ConnectionState.CONNECTING) {
            Log.i(TAG, "Device $deviceId is currently CONNECTING. Ignoring duplicate connection request.")
            return false
        }

        updateDeviceConnectionState(deviceId, ConnectionState.CONNECTING)

        return withContext(Dispatchers.IO) {
            val rawIp = targetDevice.ipAddress
            if (rawIp.isNullOrBlank()) {
                Log.e(TAG, "Cannot connect to device $deviceId: IP address is missing")
                updateDeviceConnectionState(deviceId, ConnectionState.ERROR)
                return@withContext false
            }

            val targetIp = if (rawIp.contains(":")) rawIp.substringBefore(":") else rawIp
            val targetPort = if (rawIp.contains(":")) rawIp.substringAfter(":").toIntOrNull() ?: port else port

            var socket: Socket? = null
            try {
                socket = Socket(targetIp, targetPort)
                socket.soTimeout = 0 // Keep connection open for persistent session
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                val helloMsg = "HELLO deviceId=$localDeviceId;deviceName=${localDeviceName.replace(" ", "_")}"
                writer.println(helloMsg)
                Log.d(TAG, "Sent identity handshake to $targetIp:$targetPort: $helloMsg")

                val ackLine = withTimeoutOrNull(5000) {
                    reader.readLine()
                }

                if (ackLine.isNullOrBlank()) {
                    Log.e(TAG, "Handshake failed: No response ACK received from $targetIp:$targetPort")
                    socket.close()
                    updateDeviceConnectionState(deviceId, ConnectionState.ERROR)
                    return@withContext false
                }

                Log.d(TAG, "Received identity ACK from $targetIp:$targetPort: $ackLine")

                val remoteDeviceId = parseKeyFromMessage(ackLine, "deviceId")
                val remoteDeviceName = parseKeyFromMessage(ackLine, "deviceName")

                // Verification requirement: Remote deviceId must match targetDevice.deviceId
                if (remoteDeviceId != null && remoteDeviceId != deviceId) {
                    Log.e(TAG, "Identity mismatch! Expected deviceId '$deviceId', but peer returned '$remoteDeviceId'. Rejecting connection.")
                    socket.close()
                    updateDeviceConnectionState(deviceId, ConnectionState.ERROR)
                    return@withContext false
                }

                val finalDeviceId = remoteDeviceId ?: deviceId
                Log.i(TAG, "Peer identity verified for device $finalDeviceId ($remoteDeviceName). Session CONNECTED.")

                // Remember this peer for future automatic reconnection
                addKnownPeer(finalDeviceId)
                reconnectingJobs.remove(finalDeviceId)?.cancel()

                val session = PeerSession(
                    deviceId = finalDeviceId,
                    deviceName = remoteDeviceName ?: targetDevice.deviceName,
                    socket = socket,
                    reader = reader,
                    writer = writer
                )

                activeSessions[finalDeviceId]?.closeSilently()
                activeSessions[finalDeviceId] = session

                val connectedDevice = targetDevice.copy(
                    deviceId = finalDeviceId,
                    deviceName = remoteDeviceName ?: targetDevice.deviceName,
                    isPaired = true,
                    connectionState = ConnectionState.CONNECTED
                )
                addDiscoveredDevice(connectedDevice)
                updateDeviceConnectionState(finalDeviceId, ConnectionState.CONNECTED)
                startSessionMonitoring(session)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Connection attempt failed to $deviceId at $targetIp:$targetPort", e)
                try { socket?.close() } catch (_: Throwable) {}
                updateDeviceConnectionState(deviceId, ConnectionState.ERROR)
                false
            }
        }
    }

    suspend fun disconnectFromDevice(deviceId: String) {
        withContext(Dispatchers.IO) {
            reconnectingJobs.remove(deviceId)?.cancel()
            val session = activeSessions.remove(deviceId)
            if (session != null) {
                try {
                    session.writer.println("DISCONNECT")
                    session.writer.flush()
                } catch (_: Throwable) {}
                session.closeSilently()
                Log.i(TAG, "Explicitly disconnected from device $deviceId")
            }
            updateDeviceConnectionState(deviceId, ConnectionState.DISCONNECTED)
        }
    }

    private suspend fun processIncomingClipboardItem(item: ClipboardItem, writer: PrintWriter? = null): Boolean {
        Log.i("SyncDiagnostics", "[SYNC_PATH_10_DESERIALIZATION] Deserialized item ID=${item.id}, source=${item.sourceDeviceId} (${item.sourceDeviceName}), length=${item.content.length}, type=${item.type}")

        // 1. Validate SHA-256 Hash
        val computedHash = ClipboardCoreManager.computeSha256(item.content)
        val hashMatches = computedHash.equals(item.hash, ignoreCase = true)
        val isSelfEcho = item.sourceDeviceId == localDeviceId

        if (!hashMatches) {
            Log.e(TAG, "SHA-256 hash validation failed for item [${item.id}]. Expected: ${item.hash}, Computed: $computedHash")
            writer?.println("ERROR_HASH_MISMATCH")
            Log.i("SyncDiagnostics", "[SYNC_PATH_8_TCP_ACK] Receiver sent ERROR_HASH_MISMATCH")
            return false
        }

        // 2. Self Device & Echo Loop Prevention
        if (isSelfEcho) {
            Log.w(TAG, "Ignoring echo item from self device ID: ${item.sourceDeviceId}")
            writer?.println("ACK_ECHO_SKIPPED")
            Log.i("SyncDiagnostics", "[SYNC_PATH_8_TCP_ACK] Receiver sent ACK_ECHO_SKIPPED")
            return false
        }

        // 3. Deduplication Check
        val isDuplicate = synchronized(recentlyProcessedHashes) {
            if (recentlyProcessedHashes.contains(item.hash)) {
                true
            } else {
                recentlyProcessedHashes.add(item.hash)
                if (recentlyProcessedHashes.size > 500) {
                    val iterator = recentlyProcessedHashes.iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
                false
            }
        }

        Log.i("SyncDiagnostics", "[SYNC_PATH_11_HASH_VALIDATION] Hash validation: computed=${computedHash.take(8)}, expected=${item.hash.take(8)}, match=$hashMatches, isSelfEcho=$isSelfEcho, isDuplicate=$isDuplicate")

        if (isDuplicate) {
            Log.w(TAG, "Ignoring duplicate item with hash prefix: ${item.hash.take(8)}")
            writer?.println("ACK_DUPLICATE_SKIPPED")
            Log.i("SyncDiagnostics", "[SYNC_PATH_8_TCP_ACK] Receiver sent ACK_DUPLICATE_SKIPPED")
            return false
        }

        // 4. Automatically register sender peer device so bi-directional sync immediately works
        if (item.sourceDeviceId != localDeviceId) {
            val peerDevice = Device(
                deviceId = item.sourceDeviceId,
                deviceName = item.sourceDeviceName,
                deviceType = "PHONE",
                isLocalDevice = false,
                isOnline = true,
                isPaired = true
            )
            addDiscoveredDevice(peerDevice)
        }

        // 5. Accepted
        writer?.println("ACK_OK")
        Log.i("SyncDiagnostics", "[SYNC_PATH_8_TCP_ACK] Receiver confirmed item ${item.id} with ACK_OK")
        _incomingItems.emit(item)
        _incomingMessages.emit("Received ClipboardItem [ID: ${item.id}, Source: ${item.sourceDeviceName}, ContentLength: ${item.content.length}]")
        Log.i(TAG, "Successfully received and validated remote ClipboardItem [ID: ${item.id}, HashPrefix: ${item.hash.take(8)}]")
        return true
    }

    private fun startSessionMonitoring(session: PeerSession) {
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val line = session.reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed == "DISCONNECT" || trimmed == "BYE") {
                        Log.i(TAG, "Peer ${session.deviceId} sent explicit disconnect signal.")
                        break
                    }
                    if (trimmed.startsWith("{")) {
                        Log.i("SyncDiagnostics", "[SYNC_PATH_9_RECEPTION] Received raw JSON item over session ${session.deviceId}")
                        val item = parseClipboardItemFromJson(trimmed)
                        if (item != null) {
                            processIncomingClipboardItem(item, session.writer)
                            continue
                        }
                    }
                    if (trimmed.startsWith("ACK_")) {
                        Log.i("SyncDiagnostics", "[SYNC_PATH_8_TCP_ACK] Received session ACK from ${session.deviceId}: $trimmed")
                    }
                    _incomingMessages.emit("Session [${session.deviceId}]: $trimmed")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Session read loop ended for ${session.deviceId}: ${e.message}")
            } finally {
                Log.i(TAG, "Peer session ended for device ${session.deviceId}")
                session.closeSilently()
                activeSessions.remove(session.deviceId, session)
                updateDeviceConnectionState(session.deviceId, ConnectionState.DISCONNECTED)
            }
        }
    }

    private fun removeDiscoveredDeviceByServiceName(serviceName: String) {
        val current = _discoveredDevices.value.toMutableList()
        current.removeAll { 
            serviceName.contains(it.deviceId) && 
            it.connectionState != ConnectionState.CONNECTED && 
            !activeSessions.containsKey(it.deviceId) 
        }
        _discoveredDevices.value = current
    }

    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyList()
    }

    private suspend fun handleIncomingSocket(socket: Socket) {
        withContext(Dispatchers.IO) {
            var isPersistentSession = false
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                val message = reader.readLine()
                if (message != null) {
                    val trimmed = message.trim()
                    val remoteHost = socket.inetAddress?.hostAddress ?: "127.0.0.1"

                    if (trimmed.startsWith("{")) {
                        Log.i("SyncDiagnostics", "[SYNC_PATH_9_RECEPTION] Received raw JSON item from TCP $remoteHost:${socket.port}")
                        val item = parseClipboardItemFromJson(trimmed)
                        if (item != null) {
                            if (item.sourceDeviceId != localDeviceId) {
                                val peerDevice = Device(
                                    deviceId = item.sourceDeviceId,
                                    deviceName = item.sourceDeviceName,
                                    ipAddress = remoteHost,
                                    isLocalDevice = false,
                                    isOnline = true,
                                    isPaired = true,
                                    connectionState = ConnectionState.CONNECTED
                                )
                                addDiscoveredDevice(peerDevice)
                            }
                            processIncomingClipboardItem(item, writer)
                            return@withContext
                        }
                    }

                    // Handshake or diagnostic message fallback
                    Log.d(TAG, "Received message: $message from ${socket.remoteSocketAddress}")
                    _incomingMessages.emit(message)

                    val peerDeviceId = parseKeyFromMessage(trimmed, "deviceId")
                    val peerDeviceName = parseKeyFromMessage(trimmed, "deviceName") ?: "Remote Device"

                    val response = if (trimmed.contains("deviceId=")) {
                        "ACK deviceId=$localDeviceId;deviceName=${localDeviceName.replace(" ", "_")}"
                    } else if (trimmed.startsWith("HELLO_")) {
                        val rawModel = try { android.os.Build.MODEL } catch (e: Throwable) { null }
                        val devName = if (rawModel.isNullOrBlank()) "DEVICE" else rawModel.replace(" ", "_")
                        "ACK_FROM_$devName"
                    } else {
                        "ACK_OK"
                    }
                    writer.println(response)
                    Log.d(TAG, "Sent handshake ACK: $response")

                    if (trimmed.startsWith("HELLO") && !peerDeviceId.isNullOrBlank() && peerDeviceId != localDeviceId) {
                        isPersistentSession = true
                        socket.soTimeout = 0

                        // Remember this peer for future automatic reconnection
                        addKnownPeer(peerDeviceId)
                        reconnectingJobs.remove(peerDeviceId)?.cancel()

                        val peerDevice = Device(
                            deviceId = peerDeviceId,
                            deviceName = peerDeviceName,
                            ipAddress = remoteHost,
                            isLocalDevice = false,
                            isOnline = true,
                            isPaired = true,
                            connectionState = ConnectionState.CONNECTED
                        )
                        addDiscoveredDevice(peerDevice)
                        updateDeviceConnectionState(peerDeviceId, ConnectionState.CONNECTED)

                        val session = PeerSession(
                            deviceId = peerDeviceId,
                            deviceName = peerDeviceName,
                            socket = socket,
                            reader = reader,
                            writer = writer
                        )
                        activeSessions[peerDeviceId]?.closeSilently()
                        activeSessions[peerDeviceId] = session

                        startSessionMonitoring(session)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming socket connection", e)
            } finally {
                if (!isPersistentSession) {
                    try {
                        socket.close()
                    } catch (e: Exception) {
                        // Ignore close error
                    }
                }
            }
        }
    }

    /**
     * Direct local handshake test connection method for Milestone 5.1 verification.
     * Connects to target IP and port, sends test message, waits for ACK response.
     */
    suspend fun sendHandshake(targetIp: String, targetPort: Int = port, message: String): String? {
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket(targetIp, targetPort)
                socket.soTimeout = 5000
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                writer.println(message)
                Log.d(TAG, "Sent handshake message '$message' to $targetIp:$targetPort")

                val ack = reader.readLine()
                Log.d(TAG, "Received ACK '$ack' from $targetIp:$targetPort")

                val remoteDeviceId = parseKeyFromMessage(ack ?: "", "deviceId")
                val remoteDeviceName = parseKeyFromMessage(ack ?: "", "deviceName")
                val devId = remoteDeviceId ?: "dev_${targetIp.replace(".", "_")}"
                val devName = remoteDeviceName ?: "Device at $targetIp"

                val discoveredDevice = Device(
                    deviceId = devId,
                    deviceName = devName,
                    ipAddress = targetIp,
                    isLocalDevice = false,
                    isOnline = true,
                    isPaired = true,
                    connectionState = ConnectionState.DISCOVERED
                )
                addDiscoveredDevice(discoveredDevice)

                ack
            } catch (e: Exception) {
                Log.e(TAG, "Handshake failed to $targetIp:$targetPort", e)
                null
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                    // Ignore close error
                }
            }
        }
    }

    override suspend fun sendItem(item: ClipboardItem, targetDeviceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            val jsonPayload = item.toJsonString()
            Log.i("SyncDiagnostics", "[SYNC_PATH_6_MESSAGE_CREATION] Serialized item ${item.id} to JSON payload (${jsonPayload.toByteArray(Charsets.UTF_8).size} bytes, hash=${item.hash.take(8)})")

            // 1. Try sending over active persistent PeerSessions first
            var sentViaSession = false
            if (activeSessions.isNotEmpty()) {
                val sessionTargets = if (targetDeviceId.isNotBlank() && targetDeviceId != "ALL" && !targetDeviceId.contains(".")) {
                    activeSessions.filterKeys { it == targetDeviceId }.values
                } else {
                    activeSessions.values
                }

                Log.i("SyncDiagnostics", "[SYNC_PATH_5_SESSION_STATE] Active PeerSessions available: ${sessionTargets.size}")

                for (session in sessionTargets) {
                    try {
                        Log.i("SyncDiagnostics", "[SYNC_PATH_7_TCP_WRITE] Writing item ${item.id} over active PeerSession to ${session.deviceId}")
                        session.writer.println(jsonPayload)
                        session.writer.flush()
                        Log.i(TAG, "Sent ClipboardItem ${item.id} via active PeerSession to ${session.deviceId}")
                        sentViaSession = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send ClipboardItem ${item.id} via PeerSession to ${session.deviceId}", e)
                        session.closeSilently()
                        activeSessions.remove(session.deviceId, session)
                        updateDeviceConnectionState(session.deviceId, ConnectionState.DISCONNECTED)
                    }
                }
            }

            if (sentViaSession) {
                Log.i("SyncDiagnostics", "[SYNC_PATH_RESULT] Sent item ${item.id} via active PeerSession successfully")
                return@withContext true
            }

            // 2. Fallback to direct TCP socket connections (for target IP strings or discovered devices without an active session)
            val targets = if (targetDeviceId.isNotBlank() && targetDeviceId.contains(".")) {
                listOf(Device(deviceId = "target_ip", deviceName = "Target IP Device", ipAddress = targetDeviceId))
            } else if (targetDeviceId.isNotBlank() && targetDeviceId != "ALL") {
                _discoveredDevices.value.filter { it.deviceId == targetDeviceId && !it.ipAddress.isNullOrBlank() }
            } else {
                _discoveredDevices.value.filter { !it.ipAddress.isNullOrBlank() && it.deviceId != localDeviceId }
            }

            Log.i("SyncDiagnostics", "[SYNC_PATH_3_TARGET_SELECTION] Candidate target peers count: ${targets.size} (${targets.map { "${it.deviceId}@${it.ipAddress}" }})")

            if (targets.isEmpty()) {
                Log.w(TAG, "No active discovered target devices found to send ClipboardItem ${item.id}")
                return@withContext false
            }

            var sentSuccessfully = false
            for (device in targets) {
                val rawIp = device.ipAddress ?: continue
                val targetIp = if (rawIp.contains(":")) rawIp.substringBefore(":") else rawIp
                val targetPort = if (rawIp.contains(":")) rawIp.substringAfter(":").toIntOrNull() ?: port else port

                var socket: Socket? = null
                try {
                    Log.i("SyncDiagnostics", "[SYNC_PATH_7_TCP_WRITE] Connecting TCP to $targetIp:$targetPort for item ${item.id}...")
                    socket = Socket(targetIp, targetPort)
                    socket.soTimeout = 5000
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    writer.println(jsonPayload)
                    Log.i("SyncDiagnostics", "[SYNC_PATH_7_TCP_WRITE] Sent ClipboardItem ${item.id} to $targetIp:$targetPort, waiting for receiver ACK...")

                    val ack = reader.readLine()
                    Log.i("SyncDiagnostics", "[SYNC_PATH_8_TCP_ACK] Received receiver confirmation '$ack' for item ${item.id} from $targetIp:$targetPort")

                    if (ack == "ACK_OK" || ack == "ACK_DUPLICATE_SKIPPED" || ack == "ACK_ECHO_SKIPPED" || ack?.startsWith("ACK_") == true) {
                        sentSuccessfully = true
                        Log.i("SyncDiagnostics", "[SYNC_PATH_RESULT] Remote receiver confirmed processing item ${item.id} with '$ack'")
                    } else {
                        Log.w("SyncDiagnostics", "[SYNC_PATH_RESULT] Receiver responded with error/unexpected ACK: '$ack'")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send ClipboardItem ${item.id} to $targetIp:$targetPort", e)
                } finally {
                    try {
                        socket?.close()
                    } catch (e: Exception) {
                        // Ignore close error
                    }
                }
            }
            sentSuccessfully
        }
    }

    override fun observeIncomingItems(): Flow<ClipboardItem> {
        return _incomingItems.asSharedFlow()
    }
}
