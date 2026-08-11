package com.example.keyboard

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ClipboardItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UniversalClipboardInputMethodServiceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testImeServiceCanBeInstantiated() {
        val controller = Robolectric.buildService(UniversalClipboardInputMethodService::class.java)
        val service = controller.create().get()
        assertNotNull(service)
    }

    @Test
    fun testManifestRegistrationIsCorrect() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = context.packageManager
        val componentName = ComponentName(context, UniversalClipboardInputMethodService::class.java)

        val serviceInfo = packageManager.getServiceInfo(componentName, PackageManager.GET_META_DATA)
        assertNotNull(serviceInfo)
        assertEquals("android.permission.BIND_INPUT_METHOD", serviceInfo.permission)
    }

    @Test
    fun testKeyboardViewCanBeCreated() {
        val controller = Robolectric.buildService(UniversalClipboardInputMethodService::class.java)
        val service = controller.create().get()

        val inputView = service.onCreateInputView()
        assertNotNull(inputView)
    }

    @Test
    fun testDirectTextInsertionViaInputConnection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editText = EditText(context)
        val editorInfo = EditorInfo()
        val realInputConnection = editText.onCreateInputConnection(editorInfo)

        val service = object : UniversalClipboardInputMethodService() {
            override fun getCurrentInputConnection(): InputConnection? {
                return realInputConnection
            }
        }

        // Verify service insertText delegates to InputConnection
        service.insertText("Hello Universal Clipboard")
        assertEquals("Hello Universal Clipboard", editText.text.toString())

        // Verify service handleBackspace delegates to InputConnection
        service.handleBackspace()
        assertEquals("Hello Universal Clipboar", editText.text.toString())
    }

    @Test
    fun testKeyboardUiAndClipboardPanelInteraction() {
        var insertedText = ""
        val sampleItems = listOf(
            ClipboardItem(
                id = "item_1",
                sourceDeviceId = "dev_local",
                sourceDeviceName = "Local Phone",
                type = "TEXT",
                content = "Copied secret code 1234",
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 600000
            )
        )

        composeTestRule.setContent {
            KeyboardScreen(
                clipboardItems = sampleItems,
                onInsertText = { insertedText += it },
                onBackspace = {},
                onEnter = {}
            )
        }

        // Verify keyboard root is rendered
        composeTestRule.onNodeWithTag("keyboard_root").assertIsDisplayed()

        // Click letter 'a' key
        composeTestRule.onNodeWithTag("key_a").performClick()
        assertEquals("a", insertedText)

        // Toggle to Clipboard History mode
        composeTestRule.onNodeWithTag("toggle_clipboard_btn").performClick()
        composeTestRule.onNodeWithTag("clipboard_panel").assertIsDisplayed()

        // Select item from clipboard panel
        composeTestRule.onNodeWithTag("clipboard_item_item_1").performClick()
        assertEquals("acopied secret code 1234", insertedText.lowercase())
    }

    @Test
    fun testEmptyClipboardPanelDisplaysEmptyMessage() {
        composeTestRule.setContent {
            KeyboardScreen(
                clipboardItems = emptyList(),
                onInsertText = {},
                onBackspace = {},
                onEnter = {}
            )
        }

        // Toggle to Clipboard History mode
        composeTestRule.onNodeWithTag("toggle_clipboard_btn").performClick()
        composeTestRule.onNodeWithText("No clipboard history found").assertIsDisplayed()
    }
}
