package com.example.sync

import android.util.Log
import com.example.data.model.ClipboardItem
import com.example.sync.transport.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * Orchestrates multi-transport synchronization.
 * Decoupled from specific networking layers to support Wi-Fi, Bluetooth, and Cloud.
 */
class SyncEngine(
    private val transports: List<Transport> = emptyList()
) {
    companion object {
        private const val TAG = "SyncEngine"
    }

    /**
     * Dispatches a local [ClipboardItem] across all active transports to synchronized peers.
     */
    suspend fun syncClipboardItem(item: ClipboardItem, targetDeviceId: String = ""): Boolean {
        var success = false
        transports.forEach { transport ->
            if (transport.isAvailable) {
                try {
                    val result = transport.sendItem(item, targetDeviceId)
                    if (result) success = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send item via ${transport.transportName}", e)
                }
            }
        }
        return success
    }

    /**
     * Merges incoming item streams from all active transports into a unified flow.
     */
    fun observeIncomingItems(): Flow<ClipboardItem> {
        val flows = transports.map { it.observeIncomingItems() }
        return flows.merge()
    }
}
