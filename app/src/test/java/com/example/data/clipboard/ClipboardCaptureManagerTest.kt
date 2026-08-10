package com.example.data.clipboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ClipboardDatabase
import com.example.data.database.dao.ClipboardItemDao
import com.example.data.model.ClipboardItem
import com.example.data.repository.ClipboardRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardCaptureManagerTest {

    private lateinit var database: ClipboardDatabase
    private lateinit var dao: ClipboardItemDao
    private lateinit var repository: ClipboardRepository
    private lateinit var captureManager: ClipboardCaptureManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClipboardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.clipboardItemDao()
        repository = ClipboardRepository(dao)

        captureManager = ClipboardCaptureManager(
            context = context,
            repository = repository,
            deviceId = "test_device_id",
            deviceName = "Test Device"
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testTextClipboardIsAccepted() {
        val capturedItems = mutableListOf<ClipboardItem>()
        captureManager.onItemCaptured = { capturedItems.add(it) }

        val result = captureManager.processText("Sample text content")

        assertNotNull(result)
        assertEquals("Sample text content", result?.content)
        assertEquals("TEXT", result?.type)
        assertEquals(1, capturedItems.size)
        assertEquals("Sample text content", capturedItems[0].content)
    }

    @Test
    fun testUrlTextTypeDetected() {
        val result = captureManager.processText("https://example.com/test")

        assertNotNull(result)
        assertEquals("URL", result?.type)
    }

    @Test
    fun testEmptyClipboardIsIgnored() {
        val capturedItems = mutableListOf<ClipboardItem>()
        captureManager.onItemCaptured = { capturedItems.add(it) }

        val resultNull = captureManager.processText(null)
        val resultEmpty = captureManager.processText("")
        val resultBlank = captureManager.processText("    ")

        assertNull(resultNull)
        assertNull(resultEmpty)
        assertNull(resultBlank)
        assertTrue(capturedItems.isEmpty())
    }

    @Test
    fun testDuplicateContentIsIgnored() {
        val capturedItems = mutableListOf<ClipboardItem>()
        captureManager.onItemCaptured = { capturedItems.add(it) }

        val firstCall = captureManager.processText("Hello World")
        val duplicateCall = captureManager.processText("Hello World")

        assertNotNull(firstCall)
        assertNull(duplicateCall)
        assertEquals(1, capturedItems.size)
    }

    @Test
    fun testNewDistinctContentIsAcceptedAfterPrevious() {
        val capturedItems = mutableListOf<ClipboardItem>()
        captureManager.onItemCaptured = { capturedItems.add(it) }

        val firstCall = captureManager.processText("First text")
        val secondCall = captureManager.processText("Second text")

        assertNotNull(firstCall)
        assertNotNull(secondCall)
        assertEquals(2, capturedItems.size)
    }

    @Test
    fun testClipboardItemReceivesTimestampsAndHash() {
        val before = System.currentTimeMillis()
        val result = captureManager.processText("Timestamp test string")
        val after = System.currentTimeMillis()

        assertNotNull(result)
        assertTrue(result!!.createdAt >= before && result.createdAt <= after)

        val expectedExpiration = ClipboardRepository.calculateExpirationTime(
            createdAt = result.createdAt,
            retentionDays = ClipboardRepository.DEFAULT_RETENTION_DAYS
        )
        assertEquals(expectedExpiration, result.expiresAt)

        val expectedHash = ClipboardCaptureManager.computeSha256("Timestamp test string")
        assertEquals(expectedHash, result.hash)
    }

    @Test
    fun testSha256HashConsistency() {
        val text = "Consistent input text 123!"
        val hash1 = ClipboardCaptureManager.computeSha256(text)
        val hash2 = ClipboardCaptureManager.computeSha256(text)

        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length) // SHA-256 hex string length is 64 chars
    }

    @Test
    fun testRepositoryReceivesCapturedContent() = runBlocking {
        captureManager.onItemCaptured = { item ->
            runBlocking {
                repository.insertClipboardItem(item)
            }
        }

        captureManager.processText("Repository Persistence Test")

        val storedItems = repository.clipboardHistory.first()
        assertEquals(1, storedItems.size)
        assertEquals("Repository Persistence Test", storedItems[0].content)
    }
}
