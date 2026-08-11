package com.example.data.clipboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ClipboardDatabase
import com.example.data.database.dao.ClipboardItemDao
import com.example.data.model.ClipboardItem
import com.example.data.repository.ClipboardRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fake implementation of [ClipboardCaptureSource] for unit testing [ClipboardCoreManager]
 * without relying on Android framework ClipboardManager.
 */
class FakeClipboardCaptureSource : ClipboardCaptureSource {
    var capturing = false
    private var listener: ((String) -> Unit)? = null

    override fun start() {
        capturing = true
    }

    override fun stop() {
        capturing = false
    }

    override fun setOnClipCapturedListener(listener: (String) -> Unit) {
        this.listener = listener
    }

    override fun isCapturing(): Boolean = capturing

    override fun checkCurrentClip() {
        // No-op for fake
    }

    fun emitClipText(text: String) {
        listener?.invoke(text)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ClipboardCoreManagerTest {

    private lateinit var database: ClipboardDatabase
    private lateinit var dao: ClipboardItemDao
    private lateinit var repository: ClipboardRepository
    private lateinit var fakeCaptureSource: FakeClipboardCaptureSource
    private lateinit var coreManager: ClipboardCoreManager

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClipboardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.clipboardItemDao()
        repository = ClipboardRepository(dao)

        fakeCaptureSource = FakeClipboardCaptureSource()
        coreManager = ClipboardCoreManager(
            captureSource = fakeCaptureSource,
            repository = repository,
            deviceId = "test_device_id",
            deviceName = "Test Device",
            coroutineScope = testScope
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testValidTextIsAccepted() {
        val result = coreManager.processClipboardText("Valid copied text")

        assertNotNull(result)
        assertEquals("Valid copied text", result?.content)
        assertEquals("TEXT", result?.type)
        assertEquals("test_device_id", result?.sourceDeviceId)
        assertEquals("Test Device", result?.sourceDeviceName)
    }

    @Test
    fun testUrlTextTypeDetected() {
        val result = coreManager.processClipboardText("https://example.com/test")

        assertNotNull(result)
        assertEquals("URL", result?.type)
    }

    @Test
    fun testEmptyTextIsIgnored() {
        val resultNull = coreManager.processClipboardText(null)
        val resultEmpty = coreManager.processClipboardText("")
        val resultBlank = coreManager.processClipboardText("    ")

        assertNull(resultNull)
        assertNull(resultEmpty)
        assertNull(resultBlank)
    }

    @Test
    fun testDuplicateTextIsDetected() {
        val firstResult = coreManager.processClipboardText("Duplicate test string")
        val duplicateResult = coreManager.processClipboardText("Duplicate test string")

        assertNotNull(firstResult)
        assertNull(duplicateResult)
    }

    @Test
    fun testDifferentTextCreatesNewItem() {
        val firstResult = coreManager.processClipboardText("First string")
        val secondResult = coreManager.processClipboardText("Second string")

        assertNotNull(firstResult)
        assertNotNull(secondResult)
        assertNotEquals(firstResult?.hash, secondResult?.hash)
    }

    @Test
    fun testSha256HashIsDeterministic() {
        val text = "Deterministic test text 123"
        val hash1 = ClipboardCoreManager.computeSha256(text)
        val hash2 = ClipboardCoreManager.computeSha256(text)

        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
    }

    @Test
    fun testCreatedAtAndExpiresAtAssignedCorrectly() {
        val before = System.currentTimeMillis()
        val result = coreManager.processClipboardText("Timestamp check text")
        val after = System.currentTimeMillis()

        assertNotNull(result)
        assertTrue(result!!.createdAt in before..after)

        val expectedExpiration = ClipboardRepository.calculateExpirationTime(
            createdAt = result.createdAt,
            retentionDays = ClipboardRepository.DEFAULT_RETENTION_DAYS
        )
        assertEquals(expectedExpiration, result.expiresAt)
        // Verify 7 days retention delta (7 * 24 * 3600 * 1000 = 604800000ms)
        assertEquals(604800000L, result.expiresAt - result.createdAt)
    }

    @Test
    fun testRepositoryReceivesProcessedItem() = runTest(testDispatcher) {
        fakeCaptureSource.emitClipText("Event emitted text")

        val items = repository.clipboardHistory.first()
        assertEquals(1, items.size)
        assertEquals("Event emitted text", items[0].content)
    }

    @Test
    fun testCaptureSourceStartAndStopLifecycle() {
        assertFalse(fakeCaptureSource.isCapturing())

        coreManager.startCapture()
        assertTrue(fakeCaptureSource.isCapturing())
        assertTrue(coreManager.isCaptureActive.value)

        coreManager.stopCapture()
        assertFalse(fakeCaptureSource.isCapturing())
        assertFalse(coreManager.isCaptureActive.value)
    }

    @Test
    fun testManualAdditionRoutedThroughClipboardCore() = runTest(testDispatcher) {
        val manualText = "Manual entry text test"
        val item = coreManager.processClipboardText(manualText)

        assertNotNull(item)
        assertEquals(manualText, item?.content)
        assertNotNull(item?.hash)
        assertEquals(64, item?.hash?.length)

        val storedItems = repository.clipboardHistory.first()
        assertEquals(1, storedItems.size)
        assertEquals(manualText, storedItems[0].content)
        assertEquals(item?.hash, storedItems[0].hash)
    }

    @Test
    fun testManualAdditionDeduplication() = runTest(testDispatcher) {
        val manualText = "Duplicate manual text entry"
        val firstItem = coreManager.processClipboardText(manualText)
        val secondItem = coreManager.processClipboardText(manualText)

        assertNotNull(firstItem)
        assertNull(secondItem)

        val storedItems = repository.clipboardHistory.first()
        assertEquals(1, storedItems.size)
    }
}
