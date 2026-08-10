package com.example.sync

import com.example.data.model.ClipboardItem
import com.example.sync.transport.Transport

/**
 * Orchestrates multi-transport synchronization.
 * Decoupled from specific networking layers to support Wi-Fi, Bluetooth, and Cloud.
 */
class SyncEngine(
    private val transports: List<Transport> = emptyList()
) {
    suspend fun syncClipboardItem(item: ClipboardItem) {
        // Implementation for dispatching items across active transports will be added in Milestone 7
    }
}
