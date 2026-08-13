package com.example.core.policy

/**
 * Policy defining whether a clipboard item should be synchronized or kept local.
 */
enum class SyncScope {
    AUTO,        // Synchronize according to global settings
    SYNC_ALL,    // Explicitly broadcast to all connected peers
    SYNC_TARGET, // Explicitly send to a selected peer
    LOCAL_ONLY   // Do not synchronize, keep on current device only
}

/**
 * Universal Synchronization Policy Configuration.
 * Governs the policy decisions across all platforms.
 */
data class SyncPolicy(
    val isAutoSyncEnabled: Boolean = true,
    val isSyncPaused: Boolean = false,
    val defaultScope: SyncScope = SyncScope.AUTO,
    val allowedDeviceIds: Set<String> = emptySet(), // Empty = allow all paired devices
    val blockedDeviceIds: Set<String> = emptySet(),
    val maxSyncSizeBytes: Long = 5 * 1024 * 1024L
) {
    fun shouldSyncItem(targetDeviceId: String? = null, itemSizeBytes: Long = 0L): Boolean {
        if (isSyncPaused) return false
        if (itemSizeBytes > maxSyncSizeBytes) return false
        if (targetDeviceId != null) {
            if (blockedDeviceIds.contains(targetDeviceId)) return false
            if (allowedDeviceIds.isNotEmpty() && !allowedDeviceIds.contains(targetDeviceId)) return false
        }
        return isAutoSyncEnabled
    }
}
