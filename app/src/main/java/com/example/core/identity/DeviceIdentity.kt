package com.example.core.identity

import android.content.Context
import com.example.core.capability.DeviceCapabilities
import com.example.core.capability.PlatformType
import java.util.UUID

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

object DeviceIdentityManager {
    private const val PREFS_NAME = "uclip_device_prefs"
    private const val KEY_DEVICE_ID = "local_device_id"

    @Volatile
    private var cachedDeviceId: String? = null

    fun getLocalDeviceId(context: Context? = null, customId: String? = null): String {
        if (!customId.isNullOrBlank()) {
            return customId
        }
        cachedDeviceId?.let { return it }

        val rawModel = try { android.os.Build.MODEL } catch (e: Throwable) { "Android" }
        val cleanModel = if (rawModel.isNullOrBlank()) "Android" else rawModel.replace(" ", "_")

        val deviceId = context?.let { ctx ->
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

        cachedDeviceId = deviceId
        return deviceId
    }

    fun getLocalDeviceName(): String {
        val rawModel = try { android.os.Build.MODEL } catch (e: Throwable) { "Local Device" }
        return if (rawModel.isNullOrBlank()) "Local Device" else rawModel
    }

    fun getLocalIdentity(context: Context? = null, customId: String? = null): DeviceIdentity {
        return DeviceIdentity(
            deviceId = getLocalDeviceId(context, customId),
            deviceName = getLocalDeviceName(),
            platformType = PlatformType.ANDROID,
            platformVersion = try { android.os.Build.VERSION.RELEASE ?: "" } catch (e: Throwable) { "" }
        )
    }
}

