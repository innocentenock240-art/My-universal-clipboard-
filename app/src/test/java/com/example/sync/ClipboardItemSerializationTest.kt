package com.example.sync

import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.model.ClipboardItem
import com.example.sync.model.parseClipboardItemFromJson
import com.example.sync.model.toJsonString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardItemSerializationTest {

    @Test
    fun testSerializationAndDeserializationSuccess() {
        val originalContent = "Hello, end-to-end sync!"
        val hash = ClipboardCoreManager.computeSha256(originalContent)
        val item = ClipboardItem(
            id = "clip_123",
            sourceDeviceId = "phone_a_001",
            sourceDeviceName = "Pixel 8",
            type = "TEXT",
            content = originalContent,
            createdAt = 1700000000000L,
            expiresAt = 1700604000000L,
            hash = hash,
            isFavorite = true,
            isPinned = false
        )

        val jsonStr = item.toJsonString()
        assertNotNull(jsonStr)

        val deserialized = parseClipboardItemFromJson(jsonStr)
        assertNotNull(deserialized)
        assertEquals(item.id, deserialized?.id)
        assertEquals(item.sourceDeviceId, deserialized?.sourceDeviceId)
        assertEquals(item.sourceDeviceName, deserialized?.sourceDeviceName)
        assertEquals(item.type, deserialized?.type)
        assertEquals(item.content, deserialized?.content)
        assertEquals(item.createdAt, deserialized?.createdAt)
        assertEquals(item.expiresAt, deserialized?.expiresAt)
        assertEquals(item.hash, deserialized?.hash)
        assertEquals(item.isFavorite, deserialized?.isFavorite)
        assertEquals(item.isPinned, deserialized?.isPinned)
    }

    @Test
    fun testDeserializationInvalidJsonReturnsNull() {
        val result = parseClipboardItemFromJson("NOT_A_JSON")
        assertNull(result)

        val nonClipboardJson = "{\"payloadType\":\"UNKNOWN_TYPE\",\"id\":\"123\"}"
        val result2 = parseClipboardItemFromJson(nonClipboardJson)
        assertNull(result2)
    }

    @Test
    fun testDeserializationComputesHashIfMissing() {
        val rawJson = """
            {
                "payloadType": "CLIPBOARD_ITEM",
                "id": "clip_456",
                "sourceDeviceId": "phone_b",
                "content": "Sample test text"
            }
        """.trimIndent()

        val item = parseClipboardItemFromJson(rawJson)
        assertNotNull(item)
        assertEquals("clip_456", item?.id)
        assertEquals("Sample test text", item?.content)
        val expectedHash = ClipboardCoreManager.computeSha256("Sample test text")
        assertEquals(expectedHash, item?.hash)
    }
}
