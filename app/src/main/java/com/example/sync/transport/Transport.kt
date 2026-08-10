package com.example.sync.transport

import com.example.data.model.ClipboardItem
import kotlinx.coroutines.flow.Flow

/**
 * Transport interface abstraction for multi-transport sync engine.
 * Future implementations will include Wi-Fi (mDNS/Sockets), Bluetooth, and Cloud.
 */
interface Transport {
    val transportName: String
    val isAvailable: Boolean
    
    suspend fun startDiscovery()
    suspend fun stopDiscovery()
    suspend fun sendItem(item: ClipboardItem, targetDeviceId: String): Boolean
    fun observeIncomingItems(): Flow<ClipboardItem>
}
