package com.example.core.capability

/**
 * Universal Platform Type enumeration representing client operating systems.
 */
enum class PlatformType(val displayName: String) {
    ANDROID("Android"),
    WINDOWS("Windows"),
    MACOS("macOS"),
    LINUX("Linux"),
    IOS("iOS"),
    IPADOS("iPadOS"),
    CHROMEOS("ChromeOS"),
    BSD("BSD"),
    OTHER("Other")
}

/**
 * Platform & Device capability descriptor.
 * Communicated during discovery / connection handshakes to establish mutual capabilities
 * without assuming all platforms support identical clipboard operations.
 */
data class DeviceCapabilities(
    val supportsText: Boolean = true,
    val supportsHtml: Boolean = true,
    val supportsImages: Boolean = true,
    val supportsFiles: Boolean = true,
    val supportsUrls: Boolean = true,
    val supportsCode: Boolean = true,
    val maxPayloadBytes: Long = 5 * 1024 * 1024L, // 5MB default safety limit
    val supportsBackgroundCapture: Boolean = false,
    val supportsBackgroundSync: Boolean = true,
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION
) {
    companion object {
        const val CURRENT_PROTOCOL_VERSION = 1

        val ANDROID_DEFAULT = DeviceCapabilities(
            supportsText = true,
            supportsHtml = true,
            supportsImages = true,
            supportsFiles = true,
            supportsUrls = true,
            supportsCode = true,
            maxPayloadBytes = 5 * 1024 * 1024L,
            supportsBackgroundCapture = false, // Foreground / Companion only per Android 10+
            supportsBackgroundSync = true,
            protocolVersion = CURRENT_PROTOCOL_VERSION
        )

        val DESKTOP_DEFAULT = DeviceCapabilities(
            supportsText = true,
            supportsHtml = true,
            supportsImages = true,
            supportsFiles = true,
            supportsUrls = true,
            supportsCode = true,
            maxPayloadBytes = 25 * 1024 * 1024L,
            supportsBackgroundCapture = true,
            supportsBackgroundSync = true,
            protocolVersion = CURRENT_PROTOCOL_VERSION
        )

        val IOS_DEFAULT = DeviceCapabilities(
            supportsText = true,
            supportsHtml = true,
            supportsImages = true,
            supportsFiles = true,
            supportsUrls = true,
            supportsCode = true,
            maxPayloadBytes = 5 * 1024 * 1024L,
            supportsBackgroundCapture = false, // Restricted by iOS Pasteboard privacy
            supportsBackgroundSync = false,
            protocolVersion = CURRENT_PROTOCOL_VERSION
        )
    }
}
