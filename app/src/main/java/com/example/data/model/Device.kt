package com.example.data.model

enum class ConnectionState {
    DISCOVERED,
    CONNECTING,
    RECONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

/**
 * Data class representing an authorized or discovered device in the network.
 */
data class Device(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String = "PHONE", // PHONE, TABLET, LAPTOP, DESKTOP
    val ipAddress: String? = null,
    val publicKey: String? = null,
    val lastSeen: Long = System.currentTimeMillis(),
    val pairedAt: Long? = null,
    val isLocalDevice: Boolean = false,
    val isOnline: Boolean = true,
    val isPaired: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCOVERED
)
