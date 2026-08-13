package com.example.data.clipboard

/**
 * Abstraction for clipboard capture sources.
 * Decouples system-level clipboard listeners from core business logic.
 */
interface ClipboardCaptureSource {
    /**
     * Start monitoring for clipboard changes.
     */
    fun start()

    /**
     * Stop monitoring for clipboard changes.
     */
    fun stop()

    /**
     * Register callback to be invoked when new clipboard text is detected.
     */
    fun setOnClipCapturedListener(listener: (String) -> Unit)

    /**
     * Check if capture is currently active.
     */
    fun isCapturing(): Boolean

    /**
     * Force-check the current clipboard content.
     */
    fun checkCurrentClip()

    /**
     * Set/update system clipboard content.
     */
    fun setClipText(text: String)
}
