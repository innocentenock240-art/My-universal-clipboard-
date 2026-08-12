package com.example.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import com.example.sync.transport.LocalWifiTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainViewModelDiagnosticTest {

    @Test
    fun testWifiServerStartAndStopInViewModel() = runBlocking {
        val transport = LocalWifiTransport(port = 54331)
        val viewModel = MainViewModel(
            application = ApplicationProvider.getApplicationContext(),
            localWifiTransport = transport
        )

        assertFalse(viewModel.isWifiServerRunning.value)

        viewModel.startWifiServer()
        withTimeout(2000) {
            viewModel.isWifiServerRunning.first { it }
        }
        assertTrue(viewModel.isWifiServerRunning.value)

        viewModel.stopWifiServer()
        withTimeout(2000) {
            viewModel.isWifiServerRunning.first { !it }
        }
        assertFalse(viewModel.isWifiServerRunning.value)
    }

    @Test
    fun testHandshakeSelfTestInViewModel() = runBlocking {
        val receiverTransport = LocalWifiTransport(port = 54332)
        val receiverViewModel = MainViewModel(
            application = ApplicationProvider.getApplicationContext(),
            localWifiTransport = receiverTransport
        )
        receiverViewModel.startWifiServer()
        withTimeout(2000) {
            receiverViewModel.isWifiServerRunning.first { it }
        }
        assertTrue(receiverViewModel.isWifiServerRunning.value)

        val senderTransport = LocalWifiTransport(port = 54333)
        val senderViewModel = MainViewModel(
            application = ApplicationProvider.getApplicationContext(),
            localWifiTransport = senderTransport
        )

        senderViewModel.sendHandshake(
            targetIp = "127.0.0.1",
            message = "HELLO_FROM_PHONE_A",
            targetPort = 54332
        )

        val ack = withTimeout(3000) {
            senderViewModel.wifiLastAckResult.first { it != null && it != "Sending..." }
        }

        assertNotNull(ack)
        assertTrue("Expected ACK but got $ack", ack!!.startsWith("ACK_"))

        val incomingMsgs = withTimeout(3000) {
            receiverViewModel.incomingWifiMessages.first { it.isNotEmpty() }
        }
        assertTrue("Expected incoming message but got $incomingMsgs", incomingMsgs.contains("HELLO_FROM_PHONE_A"))

        receiverViewModel.stopWifiServer()
        senderViewModel.stopWifiServer()
    }
}
