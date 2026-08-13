package com.example.core.identity

import com.example.core.capability.DeviceCapabilities
import com.example.core.capability.PlatformType

/**
 * Universal, language-agnostic representation of a device's identity within the Universal Clipboard ecosystem.
 * Stable across IP changes, network migrations, and application restarts.
 */
data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val platformType: PlatformType = PlatformType.ANDROID,
    val platformVersion: String = "",
    val capabilities: DeviceCapabilities = DeviceCapabilities.ANDROID_DEFAULT
)
