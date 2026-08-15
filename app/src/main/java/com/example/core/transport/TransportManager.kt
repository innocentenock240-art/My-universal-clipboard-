package com.example.core.transport

import android.util.Log
import com.example.core.adapter.TransportAdapter
import com.example.data.model.ClipboardItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Universal Transport Orchestrator that manages multi-transport adapters
 * (Wi-Fi LAN, Wi-Fi Direct, Bluetooth Classic, Bluetooth LE, USB/Wired)
 * with intelligent priority-based selection, seamless failover, and status observability.
 *
 * Priority Strategy (Default):
 * 1. Wi-Fi LAN / TCP (High throughput, low latency)
 * 2. Wi-Fi Direct (P2P direct high bandwidth)
 * 3. Bluetooth Classic (Medium throughput RFCOMM)
 * 4. Bluetooth LE (Discovery/Control & small payloads)
 * 5. USB / Wired IP (Direct loopback / wired Ethernet)
 */
class TransportManager(
    val transports: List<TransportAdapter>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "TransportOrchestrator"

        // Universal transport priority ranking (lower index = higher priority)
        val DEFAULT_PRIORITY_ORDER = listOf(
            TransportType.WIFI_LAN,
            TransportType.WIFI_DIRECT,
            TransportType.BLUETOOTH_CLASSIC,
            TransportType.BLUETOOTH_LE,
            TransportType.USB_WIRED,
            TransportType.LOCAL_LOOPBACK
        )
    }

    private val _transportStatuses = MutableStateFlow<List<TransportStatus>>(emptyList())
    val transportStatuses: StateFlow<List<TransportStatus>> = _transportStatuses.asStateFlow()

    private val _preferredTransportType = MutableStateFlow(TransportType.WIFI_LAN)
    val preferredTransportType: StateFlow<TransportType> = _preferredTransportType.asStateFlow()

    // Transport Diagnostics & Metrics
    private val _activeTransportType = MutableStateFlow(TransportType.WIFI_LAN)
    val activeTransportType: StateFlow<TransportType> = _activeTransportType.asStateFlow()

    private val _estimatedLatencyMs = MutableStateFlow(8L)
    val estimatedLatencyMs: StateFlow<Long> = _estimatedLatencyMs.asStateFlow()

    private val _estimatedThroughputMbps = MutableStateFlow(120.0)
    val estimatedThroughputMbps: StateFlow<Double> = _estimatedThroughputMbps.asStateFlow()

    private val _successfulTransfersCount = MutableStateFlow(0)
    val successfulTransfersCount: StateFlow<Int> = _successfulTransfersCount.asStateFlow()

    private val _failedTransfersCount = MutableStateFlow(0)
    val failedTransfersCount: StateFlow<Int> = _failedTransfersCount.asStateFlow()

    private val _lastFailureReason = MutableStateFlow<String?>(null)
    val lastFailureReason: StateFlow<String?> = _lastFailureReason.asStateFlow()

    private val _transportSwitchCount = MutableStateFlow(0)
    val transportSwitchCount: StateFlow<Int> = _transportSwitchCount.asStateFlow()

    private val _lastSuccessfulTransferTimestamp = MutableStateFlow(0L)
    val lastSuccessfulTransferTimestamp: StateFlow<Long> = _lastSuccessfulTransferTimestamp.asStateFlow()

    private var lastSwitchTimestamp: Long = 0L
    private val SWITCH_HYSTERESIS_MS = 2500L // 2.5s hysteresis to prevent rapid flapping

    init {
        updateStatuses()
    }

    /**
     * Determines the TransportType for a given adapter based on its transportName or class.
     */
    fun classifyTransport(transport: TransportAdapter): TransportType {
        val name = transport.transportName.lowercase()
        return when {
            name.contains("direct") -> TransportType.WIFI_DIRECT
            name.contains("ble") || name.contains("low energy") -> TransportType.BLUETOOTH_LE
            name.contains("bluetooth") -> TransportType.BLUETOOTH_CLASSIC
            name.contains("usb") || name.contains("wired") -> TransportType.USB_WIRED
            name.contains("loopback") -> TransportType.LOCAL_LOOPBACK
            else -> TransportType.WIFI_LAN
        }
    }

    /**
     * Updates the status list for all registered transports.
     */
    fun updateStatuses() {
        val statuses = transports.map { transport ->
            val type = classifyTransport(transport)
            TransportStatus(
                transportType = type,
                isAvailable = transport.isAvailable,
                isConnected = transport.isAvailable,
                activeSessionCount = if (transport.isAvailable) 1 else 0
            )
        }
        _transportStatuses.value = statuses
    }

    fun setPreferredTransport(type: TransportType) {
        _preferredTransportType.value = type
        Log.i(TAG, "Preferred transport set to: ${type.displayName}")
    }

    private var priorityOrder: List<TransportType> = DEFAULT_PRIORITY_ORDER

    fun setPriorityOrder(order: List<TransportType>) {
        priorityOrder = order
        order.firstOrNull()?.let { _preferredTransportType.value = it }
    }

    /**
     * Returns transports sorted by priority, placing the preferred transport at the top.
     */
    fun getSortedTransports(): List<TransportAdapter> {
        val preferred = _preferredTransportType.value
        return transports.sortedWith(
            compareBy(
                { classifyTransport(it) != preferred }, // Preferred transport first
                { val type = classifyTransport(it)
                  val idx = priorityOrder.indexOf(type)
                  if (idx != -1) idx else 999
                }
            )
        )
    }

    /**
     * Sends a clipboard item using intelligent priority-based transport routing with seamless failover.
     * Tries highest priority available transport first, failing over to alternative channels on error.
     */
    suspend fun sendItem(item: ClipboardItem, targetDeviceId: String = ""): Boolean {
        val candidateTransports = getSortedTransports().filter { it.isAvailable }
        if (candidateTransports.isEmpty()) {
            Log.w(TAG, "No transports currently available to send item [${item.id}]")
            _failedTransfersCount.value += 1
            _lastFailureReason.value = "No active transport interfaces available"
            return false
        }

        val startTime = System.currentTimeMillis()
        for (transport in candidateTransports) {
            val type = classifyTransport(transport)
            try {
                Log.d(TAG, "Attempting to send item [${item.id}] via ${type.displayName} (target: '$targetDeviceId')...")
                val success = transport.sendItem(item, targetDeviceId)
                if (success) {
                    val durationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    _estimatedLatencyMs.value = durationMs
                    _lastSuccessfulTransferTimestamp.value = System.currentTimeMillis()
                    _successfulTransfersCount.value += 1
                    _lastFailureReason.value = null

                    // Estimate throughput based on payload size and duration
                    val payloadBytes = item.sizeBytes.coerceAtLeast(item.content.length.toLong())
                    val mbps = (payloadBytes * 8.0 / (durationMs / 1000.0)) / (1024 * 1024)
                    _estimatedThroughputMbps.value = when (type) {
                        TransportType.WIFI_LAN -> mbps.coerceIn(20.0, 450.0)
                        TransportType.WIFI_DIRECT -> mbps.coerceIn(15.0, 250.0)
                        TransportType.BLUETOOTH_CLASSIC -> mbps.coerceIn(0.5, 3.0)
                        TransportType.BLUETOOTH_LE -> mbps.coerceIn(0.1, 1.0)
                        TransportType.USB_WIRED -> mbps.coerceIn(50.0, 600.0)
                        else -> 100.0
                    }

                    // Hysteresis & transport switch tracking
                    if (_activeTransportType.value != type) {
                        val now = System.currentTimeMillis()
                        if (now - lastSwitchTimestamp > SWITCH_HYSTERESIS_MS) {
                            _activeTransportType.value = type
                            _transportSwitchCount.value += 1
                            lastSwitchTimestamp = now
                        }
                    }

                    Log.i(TAG, "Successfully sent item [${item.id}] via ${type.displayName} in ${durationMs}ms")
                    return true
                } else {
                    Log.w(TAG, "Transport ${type.displayName} returned false for item [${item.id}]. Attempting failover...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transport ${type.displayName} failed with exception for item [${item.id}]. Attempting failover...", e)
                _lastFailureReason.value = "${type.displayName}: ${e.localizedMessage ?: "Unknown error"}"
            }
        }

        _failedTransfersCount.value += 1
        _lastFailureReason.value = "All candidate transports failed to complete transmission"
        Log.e(TAG, "All candidate transports failed to send item [${item.id}]")
        return false
    }

    /**
     * Merges incoming items from all active transport adapters into a single unified Flow.
     */
    fun observeAllIncomingItems(): Flow<ClipboardItem> {
        val flows = transports.map { it.observeIncomingItems() }
        return flows.merge()
    }

    suspend fun startAll() {
        transports.forEach { 
            try {
                it.startTransport() 
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start transport ${it.transportName}", e)
            }
        }
        updateStatuses()
    }

    suspend fun stopAll() {
        transports.forEach { 
            try {
                it.stopTransport() 
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop transport ${it.transportName}", e)
            }
        }
        updateStatuses()
    }
}

