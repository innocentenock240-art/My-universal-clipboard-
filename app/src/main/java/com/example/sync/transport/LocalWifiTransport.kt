package com.example.sync.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.data.model.ClipboardItem
import com.example.data.model.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val port: Int = DEFAULT_PORT
) : Transport {

    companion object {
        const val DEFAULT_PORT = 53711
        // Android NsdManager serviceType must NOT have a trailing dot
        private const val SERVICE_TYPE = "_uclip._tcp"
        private const val TAG = "LocalWifiTransport"
        private const val PREFS_NAME = "uclip_device_prefs"
        private const val KEY_DEVICE_ID = "local_device_id"
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
        val rawModel = try { android.os.Build.MODEL } catch (e: Throwable) { "Android" }
        val cleanModel = if (rawModel.isNullOrBlank()) "Android" else rawModel.replace(" ", "_")

        context?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                var storedId = prefs.getString(KEY_DEVICE_ID, null)
                if (storedId.isNullOrBlank()) {
                    val randomSuffix = UUID.randomUUID().toString().replace("-", "").take(6)
                    storedId = "dev_local_${cleanModel}_$randomSuffix"
                    prefs.edit().putString(KEY_DEVICE_ID, storedId).apply()
                }
                storedId
            } catch (e: Throwable) {
                "dev_local_$cleanModel"
            }
        } ?: "dev_local_$cleanModel"
    }

    val localDeviceName: String by lazy {
        val rawModel = try { android.os.Build.MODEL } catch (e: Throwable) { "Android Device" }
        if (rawModel.isNullOrBlank()) "Android Device" else rawModel
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
            devId = "dev_" + serviceInfo.serviceName.replace(" ", "_")
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
        val current = _discoveredDevices.value.toMutableList()
        val index = current.indexOfFirst { it.deviceId == device.deviceId || (it.ipAddress != null && it.ipAddress == device.ipAddress) }
        if (index >= 0) {
            current[index] = device
        } else {
            current.add(device)
        }
        _discoveredDevices.value = current
    }

    private fun removeDiscoveredDeviceByServiceName(serviceName: String) {
        val current = _discoveredDevices.value.toMutableList()
        current.removeAll { serviceName.contains(it.deviceId) }
        _discoveredDevices.value = current
    }

    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyList()
    }

    private suspend fun handleIncomingSocket(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                val message = reader.readLine()
                if (message != null) {
                    Log.d(TAG, "Received message: $message from ${socket.remoteSocketAddress}")
                    _incomingMessages.emit(message)

                    // Generate ACK response for handshake
                    val rawModel = try { android.os.Build.MODEL } catch (e: Throwable) { null }
                    val deviceName = if (rawModel.isNullOrBlank()) "DEVICE" else rawModel.replace(" ", "_")
                    val response = if (message.startsWith("HELLO_")) {
                        "ACK_FROM_$deviceName"
                    } else {
                        "ACK_OK"
                    }
                    writer.println(response)
                    Log.d(TAG, "Sent handshake ACK: $response")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming socket connection", e)
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                    // Ignore close error
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
        // ClipboardItem transmission will be connected in subsequent sub-milestones
        return false
    }

    override fun observeIncomingItems(): Flow<ClipboardItem> {
        return _incomingItems.asSharedFlow()
    }
}
