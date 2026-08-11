package com.example.keyboard

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
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
import com.example.data.database.ClipboardDatabase
import com.example.data.model.ClipboardItem
import com.example.data.repository.ClipboardRepository
import com.example.ui.theme.UniversalClipboardTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class UniversalClipboardInputMethodService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    var repository: ClipboardRepository? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        try {
            val db = ClipboardDatabase.getInstance(applicationContext)
            repository = ClipboardRepository(db.clipboardItemDao())
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
                        onEnter = { handleEnter() }
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
        } else {
            val selectedText = ic.getSelectedText(0)
            if (selectedText.isNullOrEmpty()) {
                ic.deleteSurroundingText(1, 0)
            } else {
                ic.commitText("", 1)
            }
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
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
