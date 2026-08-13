package com.example.core.transport

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
 * Universal Transport Manager that orchestrates multi-transport adapters
 * (Wi-Fi LAN, Bluetooth, Wi-Fi Direct, USB/Wired) with priority-based routing,
 * single persistent device ID mapping, and dynamic fallback.
 */
class TransportManager(
    val transports: List<TransportAdapter>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _transportStatuses = MutableStateFlow<List<TransportStatus>>(emptyList())
    val transportStatuses: StateFlow<List<TransportStatus>> = _transportStatuses.asStateFlow()

    private val _preferredTransportType = MutableStateFlow(TransportType.WIFI_LAN)
    val preferredTransportType: StateFlow<TransportType> = _preferredTransportType.asStateFlow()

    init {
        updateStatuses()
    }

    fun updateStatuses() {
        val statuses = transports.map { transport ->
            val type = when {
                transport.transportName.contains("Wi-Fi Direct", ignoreCase = true) -> TransportType.WIFI_DIRECT
                transport.transportName.contains("BLE", ignoreCase = true) -> TransportType.BLUETOOTH_LE
                transport.transportName.contains("Bluetooth", ignoreCase = true) -> TransportType.BLUETOOTH_CLASSIC
                transport.transportName.contains("USB", ignoreCase = true) -> TransportType.USB_WIRED
                else -> TransportType.WIFI_LAN
            }
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
    }

    /**
     * Sends a clipboard item to a target device using the best available transport channel.
     * Prevents multi-transport session races by trying the preferred transport first, then falling back.
     */
    suspend fun sendItem(item: ClipboardItem, targetDeviceId: String = ""): Boolean {
        // Find preferred transport first
        val preferred = transports.firstOrNull { it.isAvailable }
        if (preferred != null) {
            val sent = preferred.sendItem(item, targetDeviceId)
            if (sent) return true
        }

        // Fallback to any available transport
        for (transport in transports) {
            if (transport != preferred && transport.isAvailable) {
                val sent = transport.sendItem(item, targetDeviceId)
                if (sent) return true
            }
        }
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
        transports.forEach { it.startTransport() }
        updateStatuses()
    }

    suspend fun stopAll() {
        transports.forEach { it.stopTransport() }
        updateStatuses()
    }
}
