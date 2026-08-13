package com.example.data.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.util.Log

/**
 * Android implementation of [ClipboardCaptureSource].
 * Responsible ONLY for interacting with Android's [ClipboardManager].
 * Does NOT directly access Room, database, or repository layer.
 */
class AndroidClipboardCaptureSource(
    private val context: Context
) : ClipboardCaptureSource {

    private val clipboardManager: ClipboardManager? by lazy {
        try {
            context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve ClipboardManager service", e)
            null
        }
    }

    @Volatile
    private var capturing: Boolean = false

    private var onClipCapturedListener: ((String) -> Unit)? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkCurrentClip()
    }

    override fun start() {
        if (capturing) return
        val manager = clipboardManager ?: return
        try {
            manager.addPrimaryClipChangedListener(clipListener)
            capturing = true
            Log.d(TAG, "AndroidClipboardCaptureSource listener registered")
            checkCurrentClip()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register clip listener", e)
        }
    }

    override fun stop() {
        if (!capturing) return
        val manager = clipboardManager ?: return
        try {
            manager.removePrimaryClipChangedListener(clipListener)
            capturing = false
            Log.d(TAG, "AndroidClipboardCaptureSource listener unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister clip listener", e)
        }
    }

    override fun setOnClipCapturedListener(listener: (String) -> Unit) {
        this.onClipCapturedListener = listener
    }

    override fun isCapturing(): Boolean = capturing

    override fun checkCurrentClip() {
        val manager = clipboardManager ?: return
        try {
            if (!manager.hasPrimaryClip()) return
            val clipData = manager.primaryClip ?: return
            if (clipData.itemCount == 0) return

            val item = clipData.getItemAt(0)
            val text = item.text?.toString() ?: item.coerceToText(context)?.toString()

            if (!text.isNullOrBlank()) {
                onClipCapturedListener?.invoke(text)
            }
        } catch (e: Exception) {
            // Android 10+ background restriction or SecurityException when unaccessible
            Log.w(TAG, "Clipboard access unavailable or restricted: ${e.message}")
        }
    }

    override fun setClipText(text: String) {
        val manager = clipboardManager ?: return
        try {
            val clipData = android.content.ClipData.newPlainText("Universal Clipboard", text)
            manager.setPrimaryClip(clipData)
            Log.d(TAG, "Updated Android system primary clip successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update Android system clipboard: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AndroidClipCaptureSrc"
    }
}
