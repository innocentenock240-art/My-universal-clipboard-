package com.example.sync.model

import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.model.ClipboardItem
import org.json.JSONObject

/**
 * Serialization and deserialization utilities for transmitting [ClipboardItem] over network transports.
 */
fun ClipboardItem.toJsonString(): String {
    val json = JSONObject()
    json.put("payloadType", "CLIPBOARD_ITEM")
    json.put("id", id)
    json.put("sourceDeviceId", sourceDeviceId)
    json.put("sourceDeviceName", sourceDeviceName)
    json.put("type", type)
    json.put("content", content)
    json.put("createdAt", createdAt)
    json.put("expiresAt", expiresAt)
    json.put("hash", hash)
    json.put("isFavorite", isFavorite)
    json.put("isPinned", isPinned)
    return json.toString()
}

fun parseClipboardItemFromJson(jsonString: String): ClipboardItem? {
    return try {
        if (!jsonString.trim().startsWith("{")) return null
        val json = JSONObject(jsonString)
        if (json.optString("payloadType") != "CLIPBOARD_ITEM") return null

        val content = json.getString("content")
        val rawHash = json.optString("hash")
        val hash = if (rawHash.isNullOrBlank()) ClipboardCoreManager.computeSha256(content) else rawHash

        ClipboardItem(
            id = json.getString("id"),
            sourceDeviceId = json.getString("sourceDeviceId"),
            sourceDeviceName = json.optString("sourceDeviceName", "Remote Device"),
            type = json.optString("type", "TEXT"),
            content = content,
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            expiresAt = json.optLong("expiresAt", System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000)),
            hash = hash,
            isFavorite = json.optBoolean("isFavorite", false),
            isPinned = json.optBoolean("isPinned", false)
        )
    } catch (e: Exception) {
        null
    }
}
