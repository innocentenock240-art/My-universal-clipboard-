package com.example.keyboard

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.data.clipboard.AndroidClipboardCaptureSource
import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.database.ClipboardDatabase
import com.example.data.model.ClipboardItem
import com.example.data.repository.ClipboardRepository
import com.example.ui.theme.UniversalClipboardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

open class UniversalClipboardInputMethodService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    var repository: ClipboardRepository? = null
    var clipboardCore: ClipboardCoreManager? = null
    var captureSource: AndroidClipboardCaptureSource? = null

    open override fun getCurrentInputConnection(): InputConnection? {
        return super.getCurrentInputConnection()
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        try {
            val db = ClipboardDatabase.getInstance(applicationContext)
            val repo = ClipboardRepository(db.clipboardItemDao())
            repository = repo

            val source = AndroidClipboardCaptureSource(applicationContext)
            captureSource = source
            val core = ClipboardCoreManager(
                captureSource = source,
                repository = repo,
                deviceId = "dev_local_${android.os.Build.MODEL.replace(" ", "_")}",
                deviceName = if (android.os.Build.MODEL.isNullOrBlank()) "Local Device" else android.os.Build.MODEL
            )
            clipboardCore = core
            core.startCapture()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        clipboardCore?.checkClipboard()
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)

            val decorView = window?.window?.decorView ?: this
            decorView.setViewTreeLifecycleOwner(this@UniversalClipboardInputMethodService)
            decorView.setViewTreeSavedStateRegistryOwner(this@UniversalClipboardInputMethodService)

            setContent {
                UniversalClipboardTheme {
                    val historyFlow: Flow<List<ClipboardItem>> = repository?.clipboardHistory ?: emptyFlow()
                    val clipboardItems by historyFlow.collectAsState(initial = emptyList())

                    KeyboardScreen(
                        clipboardItems = clipboardItems,
                        onInsertText = { text -> insertText(text) },
                        onBackspace = { handleBackspace() },
                        onEnter = { handleEnter() },
                        onDeleteItem = { id ->
                            CoroutineScope(Dispatchers.IO).launch {
                                repository?.deleteClipboardItem(id)
                            }
                        },
                        onDeleteItems = { ids ->
                            CoroutineScope(Dispatchers.IO).launch {
                                repository?.deleteItemsByIds(ids)
                            }
                        }
                    )
                }
            }
        }
        return composeView
    }

    fun insertText(text: String) {
        val ic = currentInputConnection
        ic?.commitText(text, 1)
    }

    fun handleBackspace() {
        val ic = currentInputConnection
        if (ic == null) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        try {
            val selectedText = ic.getSelectedText(0)
            if (!selectedText.isNullOrEmpty()) {
                ic.commitText("", 1)
            } else {
                val deleted = ic.deleteSurroundingText(1, 0)
                if (!deleted) {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                }
            }
        } catch (e: Exception) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    fun handleEnter() {
        val ic = currentInputConnection
        if (ic == null) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    override fun onDestroy() {
        clipboardCore?.stopCapture()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
