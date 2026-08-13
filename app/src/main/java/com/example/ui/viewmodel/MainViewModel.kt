package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.clipboard.AndroidClipboardCaptureSource
import com.example.data.clipboard.ClipboardCoreManager
import com.example.data.database.ClipboardDatabase
import com.example.data.model.ClipboardItem
import com.example.data.model.Device
import com.example.data.repository.ClipboardRepository
import com.example.sync.SyncEngine
import com.example.sync.transport.LocalWifiTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: ClipboardRepository = ClipboardRepository(
        ClipboardDatabase.getInstance(application).clipboardItemDao()
    ),
    val localWifiTransport: LocalWifiTransport = LocalWifiTransport(context = application),
    val syncEngine: SyncEngine = SyncEngine(listOf(localWifiTransport))
) : AndroidViewModel(application) {

    private val localDevice = Device(
        deviceId = "dev_local_${android.os.Build.MODEL.replace(" ", "_")}",
        deviceName = if (android.os.Build.MODEL.isNullOrBlank()) "Local Android Device" else android.os.Build.MODEL,
        deviceType = "PHONE",
        isLocalDevice = true,
        isOnline = true,
        isPaired = true
    )

    private val clipboardCore = ClipboardCoreManager.getInstance(application, repository)

    private val _isWifiServerRunning = MutableStateFlow(false)
    val isWifiServerRunning: StateFlow<Boolean> = _isWifiServerRunning.asStateFlow()

    private val _isWifiDiscovering = MutableStateFlow(false)
    val isWifiDiscovering: StateFlow<Boolean> = _isWifiDiscovering.asStateFlow()

    val discoveredDevices: StateFlow<List<Device>> = localWifiTransport.discoveredDevices

    private val _incomingWifiMessages = MutableStateFlow<List<String>>(emptyList())
    val incomingWifiMessages: StateFlow<List<String>> = _incomingWifiMessages.asStateFlow()

    private val _wifiLastAckResult = MutableStateFlow<String?>(null)
    val wifiLastAckResult: StateFlow<String?> = _wifiLastAckResult.asStateFlow()

    private val _isSendingWifiHandshake = MutableStateFlow(false)
    val isSendingWifiHandshake: StateFlow<Boolean> = _isSendingWifiHandshake.asStateFlow()

    val isCaptureActive: StateFlow<Boolean> = clipboardCore.isCaptureActive

    val clipboardItems: StateFlow<List<ClipboardItem>> = repository.clipboardHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _devices = MutableStateFlow<List<Device>>(listOf(localDevice))
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _retentionDays = MutableStateFlow(ClipboardRepository.DEFAULT_RETENTION_DAYS.toInt())
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

    private val _isWifiSyncEnabled = MutableStateFlow(true)
    val isWifiSyncEnabled: StateFlow<Boolean> = _isWifiSyncEnabled.asStateFlow()

    private val _isBluetoothSyncEnabled = MutableStateFlow(false)
    val isBluetoothSyncEnabled: StateFlow<Boolean> = _isBluetoothSyncEnabled.asStateFlow()

    private val _isCloudSyncEnabled = MutableStateFlow(false)
    val isCloudSyncEnabled: StateFlow<Boolean> = _isCloudSyncEnabled.asStateFlow()

    init {
        // Automatically prune expired clipboard items when ViewModel/Repository initializes
        viewModelScope.launch {
            try {
                repository.deleteExpiredItems()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to delete expired items on startup", e)
            }
        }
        startClipboardCapture()

        // Wire local clipboard capture to SyncEngine for auto-broadcasting
        clipboardCore.onItemProcessedListener = { item ->
            if (_isWifiSyncEnabled.value) {
                viewModelScope.launch(Dispatchers.IO) {
                    syncEngine.syncClipboardItem(item)
                }
            }
        }

        // Observe incoming sync items from remote peers and persist in repository
        viewModelScope.launch(Dispatchers.IO) {
            syncEngine.observeIncomingItems().collect { incomingItem ->
                try {
                    clipboardCore.updateLastCapturedHash(incomingItem.hash)
                    repository.insertClipboardItem(incomingItem)
                    Log.i("MainViewModel", "Persisted remote ClipboardItem [ID: ${incomingItem.id}] from ${incomingItem.sourceDeviceName}")
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to persist remote ClipboardItem ${incomingItem.id}", e)
                }
            }
        }

        // Observe incoming wifi transport messages for diagnostic log display
        viewModelScope.launch(Dispatchers.IO) {
            localWifiTransport.incomingMessages.collect { msg ->
                _incomingWifiMessages.value = _incomingWifiMessages.value + msg
            }
        }
        startWifiDiscovery()
    }

    fun addClipboardItem(text: String) {
        clipboardCore.processClipboardText(text)
    }

    fun toggleFavorite(itemId: String) {
        val currentItem = clipboardItems.value.firstOrNull { it.id == itemId } ?: return
        viewModelScope.launch {
            try {
                repository.toggleFavorite(itemId, currentItem.isFavorite)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to toggle favorite for item $itemId", e)
            }
        }
    }

    fun togglePin(itemId: String) {
        val currentItem = clipboardItems.value.firstOrNull { it.id == itemId } ?: return
        viewModelScope.launch {
            try {
                repository.togglePin(itemId, currentItem.isPinned)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to toggle pin for item $itemId", e)
            }
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            try {
                repository.deleteClipboardItem(itemId)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to delete item $itemId", e)
            }
        }
    }

    fun deleteItems(itemIds: List<String>) {
        viewModelScope.launch {
            try {
                repository.deleteItemsByIds(itemIds)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to bulk delete items", e)
            }
        }
    }

    fun clearAllItems() {
        viewModelScope.launch {
            try {
                repository.clearAll()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to clear all items", e)
            }
        }
    }

    fun startClipboardCapture() {
        clipboardCore.startCapture()
    }

    fun stopClipboardCapture() {
        clipboardCore.stopCapture()
    }

    fun checkClipboardNow() {
        clipboardCore.checkClipboard()
    }

    fun setRetentionDays(days: Int) {
        // Retention requirement is fixed at 7 days for the current milestone
        _retentionDays.value = ClipboardRepository.DEFAULT_RETENTION_DAYS.toInt()
    }

    fun setWifiSyncEnabled(enabled: Boolean) {
        _isWifiSyncEnabled.value = enabled
    }

    // Milestone 5.2 Local Wi-Fi Discovery Methods
    fun startWifiDiscovery() {
        viewModelScope.launch(Dispatchers.IO) {
            localWifiTransport.startDiscovery()
            _isWifiDiscovering.value = true
            _isWifiServerRunning.value = localWifiTransport.isAvailable
        }
    }

    fun stopWifiDiscovery() {
        viewModelScope.launch(Dispatchers.IO) {
            localWifiTransport.stopDiscovery()
            _isWifiDiscovering.value = false
            _isWifiServerRunning.value = localWifiTransport.isAvailable
        }
    }

    // Milestone 5.1 Diagnostic Methods
    fun startWifiServer() {
        viewModelScope.launch(Dispatchers.IO) {
            localWifiTransport.startServer()
            _isWifiServerRunning.value = localWifiTransport.isAvailable
        }
    }

    fun stopWifiServer() {
        viewModelScope.launch(Dispatchers.IO) {
            localWifiTransport.stopServer()
            _isWifiServerRunning.value = localWifiTransport.isAvailable
        }
    }

    fun sendHandshake(targetIp: String, message: String = "HELLO_FROM_PHONE_A", targetPort: Int = LocalWifiTransport.DEFAULT_PORT) {
        if (targetIp.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isSendingWifiHandshake.value = true
            _wifiLastAckResult.value = "Sending..."
            val ack = localWifiTransport.sendHandshake(
                targetIp = targetIp.trim(),
                targetPort = targetPort,
                message = message
            )
            _wifiLastAckResult.value = ack ?: "ERROR: Connection failed or timed out"
            _isSendingWifiHandshake.value = false
        }
    }

    fun clearWifiDiagnosticLogs() {
        _incomingWifiMessages.value = emptyList()
        _wifiLastAckResult.value = null
    }

    override fun onCleared() {
        super.onCleared()
        localWifiTransport.stopServer()
    }
}
