package com.example.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.example.ui.screens.ClipboardScreen
import com.example.ui.screens.DevicesScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.viewmodel.MainViewModel

enum class NavigationTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home, "tab_home"),
    CLIPBOARD("Clipboard", Icons.Filled.ContentPaste, Icons.Outlined.ContentPaste, "tab_clipboard"),
    DEVICES("Devices", Icons.Filled.Devices, Icons.Outlined.Devices, "tab_devices"),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "tab_settings")
}

@Composable
fun MainNavGraph(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(NavigationTab.HOME) }

    val clipboardItems by viewModel.clipboardItems.collectAsState()
    val isCaptureActive by viewModel.isCaptureActive.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val retentionDays by viewModel.retentionDays.collectAsState()
    val isWifiSyncEnabled by viewModel.isWifiSyncEnabled.collectAsState()
    val isBluetoothSyncEnabled by viewModel.isBluetoothSyncEnabled.collectAsState()
    val isCloudSyncEnabled by viewModel.isCloudSyncEnabled.collectAsState()

    val onCopyText: (String) -> Unit = { text ->
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Universal Clipboard", text)
        clipboard.setPrimaryClip(clip)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationTab.entries.forEach { tab ->
                    val selected = currentTab == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentTab = tab },
                        modifier = Modifier.testTag(tab.testTag),
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.title
                            )
                        },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (currentTab) {
                NavigationTab.HOME -> {
                    HomeScreen(
                        clipboardItems = clipboardItems,
                        devices = devices,
                        onCopyItem = onCopyText,
                        onNavigateToClipboard = { currentTab = NavigationTab.CLIPBOARD },
                        onNavigateToDevices = { currentTab = NavigationTab.DEVICES }
                    )
                }
                NavigationTab.CLIPBOARD -> {
                    ClipboardScreen(
                        items = clipboardItems,
                        isCaptureActive = isCaptureActive,
                        onAddItem = { text ->
                            viewModel.addClipboardItem(text)
                            onCopyText(text)
                        },
                        onCopyItem = onCopyText,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onTogglePin = viewModel::togglePin,
                        onDeleteItem = viewModel::deleteItem,
                        onClearAll = viewModel::clearAllItems,
                        onCheckClipboard = viewModel::checkClipboardNow,
                        onToggleCapture = {
                            if (isCaptureActive) viewModel.stopClipboardCapture() else viewModel.startClipboardCapture()
                        }
                    )
                }
                NavigationTab.DEVICES -> {
                    DevicesScreen(
                        devices = devices
                    )
                }
                NavigationTab.SETTINGS -> {
                    SettingsScreen(
                        retentionDays = retentionDays,
                        isWifiSyncEnabled = isWifiSyncEnabled,
                        isBluetoothSyncEnabled = isBluetoothSyncEnabled,
                        isCloudSyncEnabled = isCloudSyncEnabled,
                        onRetentionDaysChanged = viewModel::setRetentionDays,
                        onWifiSyncToggled = viewModel::setWifiSyncEnabled
                    )
                }
            }
        }
    }
}
