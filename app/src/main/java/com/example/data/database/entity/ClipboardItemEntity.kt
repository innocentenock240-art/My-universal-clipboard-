package com.example.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.ClipboardItem

@Entity(tableName = "clipboard_items")
data class ClipboardItemEntity(
    @PrimaryKey val id: String,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val type: String,
    val content: String,
    val createdAt: Long,
    val expiresAt: Long,
    val hash: String,
    val isFavorite: Boolean,
    val isPinned: Boolean
)

fun ClipboardItemEntity.toDomainModel(): ClipboardItem {
    return ClipboardItem(
        id = id,
        sourceDeviceId = sourceDeviceId,
        sourceDeviceName = sourceDeviceName,
        type = type,
        content = content,
        createdAt = createdAt,
        expiresAt = expiresAt,
        hash = hash,
        isFavorite = isFavorite,
        isPinned = isPinned
    )
}

fun ClipboardItem.toEntity(): ClipboardItemEntity {
    return ClipboardItemEntity(
        id = id,
        sourceDeviceId = sourceDeviceId,
        sourceDeviceName = sourceDeviceName,
        type = type,
        content = content,
        createdAt = createdAt,
        expiresAt = expiresAt,
        hash = hash,
        isFavorite = isFavorite,
        isPinned = isPinned
    )
}
