package com.example.data.clipboard

import android.util.Log
import com.example.data.model.ClipboardItem
import com.example.data.repository.ClipboardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * CLIPBOARD CORE
 * Independent business logic component responsible for:
 * - Receiving raw clipboard content
 * - Validating content
 * - Determining content type (TEXT, URL)
 * - Computing SHA-256 hashes
 * - Duplicate detection
 * - Creating ClipboardItem domain objects
 * - Calculating expiration timestamps
 * - Storing through ClipboardRepository
 * - Exposing clipboard history and capture status to UI
 *
 * This component knows nothing about Wi-Fi, Bluetooth, Cloud, Keyboard IME, or remote devices.
 */
class ClipboardCoreManager(
    private val captureSource: ClipboardCaptureSource,
    private val repository: ClipboardRepository,
    private val deviceId: String = "dev_local_${android.os.Build.MODEL.replace(" ", "_")}",
    private val deviceName: String = if (android.os.Build.MODEL.isNullOrBlank()) "Local Device" else android.os.Build.MODEL,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    @Volatile
    var onItemProcessedListener: ((ClipboardItem) -> Unit)? = null

    @Volatile
    var lastCapturedHash: String? = null
        private set

    /**
     * Update the last captured hash directly when an item is received from a remote sync peer.
     */
    fun updateLastCapturedHash(hash: String) {
        lastCapturedHash = hash
    }

    /**
     * Apply a remote clipboard item to the local system clipboard while updating lastCapturedHash to prevent echo loops.
     */
    fun applyRemoteClipboardItem(item: ClipboardItem) {
        if (item.content.isBlank()) return
        val itemHash = item.hash.ifBlank { computeSha256(item.content) }
        lastCapturedHash = itemHash
        captureSource.setClipText(item.content)
    }

    private val _isCaptureActive = MutableStateFlow(false)
    val isCaptureActive: StateFlow<Boolean> = _isCaptureActive.asStateFlow()

    init {
        captureSource.setOnClipCapturedListener { rawText ->
            processClipboardText(rawText)
        }
    }

    /**
     * Start clipboard capture via the attached source.
     */
    fun startCapture() {
        captureSource.start()
        _isCaptureActive.value = captureSource.isCapturing()
    }

    /**
     * Stop clipboard capture via the attached source.
     */
    fun stopCapture() {
        captureSource.stop()
        _isCaptureActive.value = captureSource.isCapturing()
    }

    /**
     * Trigger a check of current clipboard contents via capture source.
     */
    fun checkClipboard() {
        captureSource.checkCurrentClip()
    }

    /**
     * Process raw clipboard text string through validation, hashing, duplicate detection, and persistence.
     * Returns the created [ClipboardItem] if accepted, or null if invalid/duplicate.
     */
    fun processClipboardText(rawText: String?): ClipboardItem? {
        if (rawText.isNullOrBlank()) {
            return null
        }

        val hash = computeSha256(rawText)

        // Duplicate Detection: Skip if the content SHA-256 hash matches the last processed hash
        if (hash == lastCapturedHash) {
            return null
        }

        lastCapturedHash = hash

        val now = System.currentTimeMillis()
        val newItem = ClipboardItem(
            id = "clip_${now}_${(1000..9999).random()}",
            sourceDeviceId = deviceId,
            sourceDeviceName = deviceName,
            type = if (rawText.startsWith("http://") || rawText.startsWith("https://")) "URL" else "TEXT",
            content = rawText,
            createdAt = now,
            expiresAt = ClipboardRepository.calculateExpirationTime(now, ClipboardRepository.DEFAULT_RETENTION_DAYS),
            hash = hash
        )

        // SECURITY REQUIREMENT: Never log the actual clipboard text to Logcat.
        // Log ONLY safe metadata: ID, type, content length, and hash prefix.
        Log.i(
            TAG,
            "Clipboard Core created item [ID: ${newItem.id}, Type: ${newItem.type}, Length: ${rawText.length}, HashPrefix: ${hash.take(8)}]"
        )

        coroutineScope.launch {
            try {
                repository.insertClipboardItem(newItem)
                onItemProcessedListener?.invoke(newItem)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist captured clipboard item into repository", e)
            }
        }

        return newItem
    }

    companion object {
        private const val TAG = "ClipboardCoreManager"

        @Volatile
        private var INSTANCE: ClipboardCoreManager? = null

        fun getInstance(
            context: android.content.Context,
            repository: ClipboardRepository
        ): ClipboardCoreManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val captureSource = AndroidClipboardCaptureSource(appContext)
                    val modelName = android.os.Build.MODEL ?: ""
                    val deviceId = "dev_local_${modelName.replace(" ", "_")}"
                    val deviceName = if (modelName.isBlank()) "Local Device" else modelName

                    val instance = ClipboardCoreManager(
                        captureSource = captureSource,
                        repository = repository,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        coroutineScope = CoroutineScope(Dispatchers.Default)
                    )
                    instance.startCapture()
                    INSTANCE = instance
                    instance
                }
            }
        }

        /**
         * Deterministic SHA-256 hash generation for duplicate check and future item identity checks.
         */
        fun computeSha256(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
