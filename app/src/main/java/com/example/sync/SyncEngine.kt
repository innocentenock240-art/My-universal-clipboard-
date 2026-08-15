package com.example.sync

import android.util.Log
import com.example.core.adapter.TransportAdapter
import com.example.core.transport.TransportManager
import com.example.data.model.ClipboardItem
import kotlinx.coroutines.flow.Flow

/**
 * Orchestrates multi-transport synchronization.
 * Decoupled from specific networking layers to support Wi-Fi, Bluetooth, and Cloud.
 * Uses [TransportManager] for intelligent priority-based transport selection and seamless failover.
 */
class SyncEngine(
    val transportManager: TransportManager
) {
    /**
     * Secondary constructor for direct transport list initialization.
     */
    constructor(transports: List<TransportAdapter>) : this(TransportManager(transports))

    companion object {
        private const val TAG = "SyncEngine"
    }

    /**
     * Dispatches a local [ClipboardItem] to synchronized peers using intelligent multi-transport orchestration.
     */
    suspend fun syncClipboardItem(item: ClipboardItem, targetDeviceId: String = ""): Boolean {
        return try {
            val success = transportManager.sendItem(item, targetDeviceId)
            if (success) {
                Log.d(TAG, "Item [${item.id}] successfully synchronized.")
            } else {
                Log.w(TAG, "Failed to synchronize item [${item.id}] across all available transports.")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception during clipboard item synchronization", e)
            false
        }
    }

    /**
     * Merges incoming item streams from all active transports into a unified flow.
     */
    fun observeIncomingItems(): Flow<ClipboardItem> {
        return transportManager.observeAllIncomingItems()
    }
}

