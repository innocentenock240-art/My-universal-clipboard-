package com.example.data.model

/**
 * Data class representing a synchronized clipboard item.
 */
data class ClipboardItem(
    val id: String,
    val sourceDeviceId: String,
    val sourceDeviceName: String = "Local Device",
    val type: String = "TEXT", // TEXT, URL, CODE, IMAGE, FILE
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000), // Default 7-day retention
    val hash: String = "",
    val isFavorite: Boolean = false,
    val isPinned: Boolean = false
)
