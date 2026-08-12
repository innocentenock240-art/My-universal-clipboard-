package com.example.sync.transport

import android.util.Log
import com.example.data.model.ClipboardItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Local Wi-Fi transport implementation for direct local network TCP socket communication.
 * Responsible strictly for network transport and socket connections without clipboard capture logic.
 */
class LocalWifiTransport(
    private val port: Int = DEFAULT_PORT
) : Transport {

    companion object {
        const val DEFAULT_PORT = 53711
        private const val TAG = "LocalWifiTransport"
    }

    override val transportName: String = "LocalWi-Fi"

    private var serverSocket: ServerSocket? = null
    private var listeningJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _incomingItems = MutableSharedFlow<ClipboardItem>(replay = 1, extraBufferCapacity = 64)
    private val _incomingMessages = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 64)

    val incomingMessages: Flow<String> = _incomingMessages.asSharedFlow()

    @Volatile
    private var _isListening = false

    override val isAvailable: Boolean
        get() = _isListening && serverSocket != null && !serverSocket!!.isClosed

    override suspend fun startDiscovery() {
        startServer()
    }

    override suspend fun stopDiscovery() {
        stopServer()
    }

    fun startServer() {
        if (_isListening) return
        try {
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }
            _isListening = true
            Log.i(TAG, "LocalWifiTransport server started on port $port")

            listeningJob = scope.launch {
                while (isActive && _isListening) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch { handleIncomingSocket(clientSocket) }
                    } catch (e: SocketException) {
                        Log.d(TAG, "Server socket closed or accept interrupted: ${e.message}")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error accepting client connection", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LocalWifiTransport server on port $port", e)
            _isListening = false
        }
    }

    fun stopServer() {
        _isListening = false
        listeningJob?.cancel()
        listeningJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        } finally {
            serverSocket = null
        }
        Log.i(TAG, "LocalWifiTransport server stopped")
    }

    private suspend fun handleIncomingSocket(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                val message = reader.readLine()
                if (message != null) {
                    Log.d(TAG, "Received message: $message from ${socket.remoteSocketAddress}")
                    _incomingMessages.emit(message)

                    // Generate ACK response for handshake
                    val rawModel = try { android.os.Build.MODEL } catch (e: Throwable) { null }
                    val deviceName = if (rawModel.isNullOrBlank()) "DEVICE" else rawModel.replace(" ", "_")
                    val response = if (message.startsWith("HELLO_")) {
                        "ACK_FROM_$deviceName"
                    } else {
                        "ACK_OK"
                    }
                    writer.println(response)
                    Log.d(TAG, "Sent handshake ACK: $response")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming socket connection", e)
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                    // Ignore close error
                }
            }
        }
    }

    /**
     * Direct local handshake test connection method for Milestone 5.1 verification.
     * Connects to target IP and port, sends test message, waits for ACK response.
     */
    suspend fun sendHandshake(targetIp: String, targetPort: Int = port, message: String): String? {
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket(targetIp, targetPort)
                socket.soTimeout = 5000
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                writer.println(message)
                Log.d(TAG, "Sent handshake message '$message' to $targetIp:$targetPort")

                val ack = reader.readLine()
                Log.d(TAG, "Received ACK '$ack' from $targetIp:$targetPort")
                ack
            } catch (e: Exception) {
                Log.e(TAG, "Handshake failed to $targetIp:$targetPort", e)
                null
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                    // Ignore close error
                }
            }
        }
    }

    override suspend fun sendItem(item: ClipboardItem, targetDeviceId: String): Boolean {
        // ClipboardItem transmission will be connected in subsequent sub-milestones
        return false
    }

    override fun observeIncomingItems(): Flow<ClipboardItem> {
        return _incomingItems.asSharedFlow()
    }
}
