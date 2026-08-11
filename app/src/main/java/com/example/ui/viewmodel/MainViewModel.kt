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
    )
) : AndroidViewModel(application) {

    private val localDevice = Device(
        deviceId = "dev_local_${android.os.Build.MODEL.replace(" ", "_")}",
        deviceName = if (android.os.Build.MODEL.isNullOrBlank()) "Local Android Device" else android.os.Build.MODEL,
        deviceType = "PHONE",
        isLocalDevice = true,
        isOnline = true,
        isPaired = true
    )

    private val captureSource = AndroidClipboardCaptureSource(application)

    private val clipboardCore = ClipboardCoreManager(
        captureSource = captureSource,
        repository = repository,
        deviceId = localDevice.deviceId,
        deviceName = localDevice.deviceName,
        coroutineScope = viewModelScope
    )

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
}
