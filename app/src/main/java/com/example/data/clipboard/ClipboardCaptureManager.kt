package com.example.data.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.example.data.model.ClipboardItem
import com.example.data.repository.ClipboardRepository
import java.security.MessageDigest

/**
 * Manages interaction with Android's ClipboardManager.
 * Detects foreground clipboard changes and creates validated domain ClipboardItems
 * while strictly respecting Android privacy restrictions and avoiding sensitive text logging.
 */
class ClipboardCaptureManager(
    private val context: Context,
    private val repository: ClipboardRepository,
    private val deviceId: String = "dev_local_${android.os.Build.MODEL.replace(" ", "_")}",
    private val deviceName: String = if (android.os.Build.MODEL.isNullOrBlank()) "Local Device" else android.os.Build.MODEL
) {
    private val clipboardManager: ClipboardManager? by lazy {
        try {
            context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve ClipboardManager service", e)
            null
        }
    }

    @Volatile
    var lastCapturedHash: String? = null
        private set

    @Volatile
    var isCapturing: Boolean = false
        private set

    var onItemCaptured: ((ClipboardItem) -> Unit)? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        processCurrentClip()
    }

    /**
     * Start listening for primary clip changes. Call when the activity/app is in foreground.
     */
    fun startCapture() {
        if (isCapturing) return
        val manager = clipboardManager ?: return
        try {
            manager.addPrimaryClipChangedListener(clipListener)
            isCapturing = true
            Log.d(TAG, "Clipboard listener registered successfully")
            processCurrentClip()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register clipboard listener", e)
        }
    }

    /**
     * Stop listening for primary clip changes. Call when the activity/app leaves foreground.
     */
    fun stopCapture() {
        if (!isCapturing) return
        val manager = clipboardManager ?: return
        try {
            manager.removePrimaryClipChangedListener(clipListener)
            isCapturing = false
            Log.d(TAG, "Clipboard listener unregistered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister clipboard listener", e)
        }
    }

    /**
     * Reads current clip from system ClipboardManager safely.
     */
    fun processCurrentClip() {
        val manager = clipboardManager ?: return
        try {
            if (!manager.hasPrimaryClip()) return
            val clipData = manager.primaryClip ?: return
            if (clipData.itemCount == 0) return

            val item = clipData.getItemAt(0)
            val text = item.text?.toString() ?: item.coerceToText(context)?.toString()

            processText(text)
        } catch (e: Exception) {
            // Android 10+ background access restriction or SecurityException when unaccessible
            Log.w(TAG, "Clipboard access restricted or unavailable: ${e.message}")
        }
    }

    /**
     * Core validation, hash generation, duplicate suppression, and domain item creation.
     */
    fun processText(text: String?): ClipboardItem? {
        if (text.isNullOrBlank()) {
            return null
        }

        val hash = computeSha256(text)

        // Prevent duplicate insertion if the content hash matches the last captured item
        if (hash == lastCapturedHash) {
            return null
        }

        lastCapturedHash = hash

        val now = System.currentTimeMillis()
        val newItem = ClipboardItem(
            id = "clip_${now}_${(1000..9999).random()}",
            sourceDeviceId = deviceId,
            sourceDeviceName = deviceName,
            type = if (text.startsWith("http://") || text.startsWith("https://")) "URL" else "TEXT",
            content = text,
            createdAt = now,
            expiresAt = ClipboardRepository.calculateExpirationTime(now, ClipboardRepository.DEFAULT_RETENTION_DAYS),
            hash = hash
        )

        // SECURITY: Log ONLY metadata (length, type, hash prefix) — NEVER log actual text
        Log.i(
            TAG,
            "Captured clipboard item [ID: ${newItem.id}, Type: ${newItem.type}, Length: ${text.length}, HashPrefix: ${hash.take(8)}]"
        )

        onItemCaptured?.invoke(newItem)
        return newItem
    }

    companion object {
        private const val TAG = "ClipboardCaptureManager"

        fun computeSha256(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
