package com.example.sync.transport

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalWifiTransportTest {

    @Test
    fun testStartAndStopServer() {
        val transport = LocalWifiTransport(port = 54321)
        assertFalse(transport.isAvailable)

        transport.startServer()
        assertTrue(transport.isAvailable)

        transport.stopServer()
        assertFalse(transport.isAvailable)
    }

    @Test
    fun testHandshakeCommunication() = runBlocking {
        val transportReceiver = LocalWifiTransport(port = 54322)
        transportReceiver.startServer()
        assertTrue(transportReceiver.isAvailable)

        val transportSender = LocalWifiTransport(port = 54323)

        val ackResponse = transportSender.sendHandshake(
            targetIp = "127.0.0.1",
            targetPort = 54322,
            message = "HELLO_FROM_PHONE_A"
        )

        assertNotNull(ackResponse)
        assertTrue(ackResponse!!.startsWith("ACK_"))

        val receivedMsg = withTimeoutOrNull(2000) {
            transportReceiver.incomingMessages.first()
        }

        assertEquals("HELLO_FROM_PHONE_A", receivedMsg)

        transportReceiver.stopServer()
        transportSender.stopServer()
    }
}
