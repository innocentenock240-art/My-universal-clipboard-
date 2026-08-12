package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Device

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    devices: List<Device>,
    isServerRunning: Boolean = false,
    listeningPort: Int = com.example.sync.transport.LocalWifiTransport.DEFAULT_PORT,
    incomingMessages: List<String> = emptyList(),
    lastAckResult: String? = null,
    isSendingHandshake: Boolean = false,
    onStartServer: () -> Unit = {},
    onStopServer: () -> Unit = {},
    onSendHandshake: (targetIp: String, message: String) -> Unit = { _, _ -> },
    onClearLogs: () -> Unit = {}
) {
    val localDevice = devices.firstOrNull { it.isLocalDevice }
    val pairedDevices = devices.filter { !it.isLocalDevice && it.isPaired }
    val discoveredDevices = devices.filter { !it.isLocalDevice && !it.isPaired }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices & Diagnostic", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Milestone 5.1 Temporary Diagnostic Card
            item {
                Milestone51DiagnosticCard(
                    isServerRunning = isServerRunning,
                    listeningPort = listeningPort,
                    incomingMessages = incomingMessages,
                    lastAckResult = lastAckResult,
                    isSendingHandshake = isSendingHandshake,
                    onStartServer = onStartServer,
                    onStopServer = onStopServer,
                    onSendHandshake = onSendHandshake,
                    onClearLogs = onClearLogs
                )
            }

            // Local Device Section
            item {
                Text(
                    text = "This Device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                if (localDevice != null) {
                    DeviceItemCard(device = localDevice, isLocal = true)
                }
            }

            // Local Network Discovery Status Card
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Local network mDNS/NSD device discovery will be enabled in Milestone 5. Cryptographic pairing follows in Milestone 6.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Paired Devices Section
            item {
                Text(
                    text = "Trusted Paired Devices (${pairedDevices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (pairedDevices.isEmpty()) {
                item {
                    Text(
                        text = "No paired devices yet. Nearby devices on your Wi-Fi will appear below.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                items(pairedDevices, key = { it.deviceId }) { device ->
                    DeviceItemCard(device = device, isLocal = false)
                }
            }

            // Discovered Nearby Devices Section
            item {
                Text(
                    text = "Discovered Nearby on Wi-Fi (${discoveredDevices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (discoveredDevices.isEmpty()) {
                item {
                    Text(
                        text = "No unpaired devices detected on local network.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                items(discoveredDevices, key = { it.deviceId }) { device ->
                    DeviceItemCard(device = device, isLocal = false)
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun DeviceItemCard(
    device: Device,
    isLocal: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_card_${device.deviceId}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = when (device.deviceType) {
                    "DESKTOP" -> Icons.Default.DesktopWindows
                    "LAPTOP" -> Icons.Default.Laptop
                    "TABLET" -> Icons.Default.Tablet
                    else -> Icons.Default.Smartphone
                },
                contentDescription = null,
                tint = if (device.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.deviceName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (device.isOnline) "● Online (Wi-Fi)" else "○ Offline",
                        fontSize = 12.sp,
                        color = if (device.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• ID: ${device.deviceId}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isLocal) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "ACTIVE",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            } else if (device.isPaired) {
                OutlinedButton(
                    onClick = { /* Unpair dialog in Milestone 6 */ },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text("Trusted", fontSize = 12.sp)
                }
            } else {
                Button(
                    onClick = { /* Pairing flow in Milestone 6 */ },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text("Pair Device", fontSize = 12.sp)
                }
            }
        }
    }
}
