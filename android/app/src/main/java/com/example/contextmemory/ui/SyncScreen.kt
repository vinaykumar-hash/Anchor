package com.example.contextmemory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.contextmemory.db.MemoryStorage
import com.example.contextmemory.sync.DeviceDiscovery
import com.example.contextmemory.sync.SyncClient
import com.example.contextmemory.sync.SyncServer
import kotlinx.coroutines.launch

@Composable
fun SyncScreen(
    memoryStorage: MemoryStorage,
    syncServer: SyncServer,
    syncClient: SyncClient,
    deviceDiscovery: DeviceDiscovery,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var manualIp by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf<String?>(null) }
    val discoveredDevices by deviceDiscovery.discoveredDevices.collectAsState()
    val localIp = remember { deviceDiscovery.getLocalIpAddress() ?: "Unknown" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Back", color = Color(0xFF6B4EE6))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Device Sync",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // This Device Info
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E1E1E))
                .padding(16.dp)
        ) {
            Column {
                Text("This Device", color = Color(0xFF888888), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    android.os.Build.MODEL,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                Text("IP: $localIp : 8473", color = Color(0xFF6B4EE6), fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Discovered Devices
        Text("Discovered Devices", color = Color(0xFF888888), fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))

        if (discoveredDevices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Searching for devices on your WiFi...\nMake sure the PC app is running.",
                    color = Color(0xFF666666),
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 200.dp)
            ) {
                items(discoveredDevices) { device ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E1E1E))
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(device.name, color = Color.White, fontWeight = FontWeight.Medium)
                                Text("${device.ip}:${device.port}", color = Color(0xFF6B4EE6), fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    isSyncing = true
                                    syncResult = null
                                    coroutineScope.launch {
                                        val result = syncClient.syncWith(device.ip, device.port)
                                        isSyncing = false
                                        syncResult = if (result.success) {
                                            memoryStorage.saveSyncedIp(device.ip)
                                            "✅ Synced! Imported: ${result.imported}, Exported: ${result.exported}"
                                        } else {
                                            "❌ ${result.error}"
                                        }
                                    }
                                },
                                enabled = !isSyncing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF6B4EE6)
                                )
                            ) {
                                Text("Sync")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Manual IP Entry
        Text("Manual Connect", color = Color(0xFF888888), fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = manualIp,
                onValueChange = { manualIp = it },
                placeholder = { Text("e.g. 192.168.1.100") },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6B4EE6),
                    unfocusedBorderColor = Color(0xFF444444),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (manualIp.isNotBlank()) {
                        isSyncing = true
                        syncResult = null
                        coroutineScope.launch {
                            val result = syncClient.syncWith(manualIp.trim())
                            isSyncing = false
                            syncResult = if (result.success) {
                                memoryStorage.saveSyncedIp(manualIp.trim())
                                "✅ Synced! Imported: ${result.imported}, Exported: ${result.exported}"
                            } else {
                                "❌ ${result.error}"
                            }
                        }
                    }
                },
                enabled = !isSyncing && manualIp.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6B4EE6)
                )
            ) {
                Text("Connect")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Sync Progress/Result
        if (isSyncing) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF6B4EE6))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Syncing...", color = Color(0xFF888888))
                }
            }
        }

        syncResult?.let { result ->
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (result.startsWith("✅")) Color(0xFF1A3A1A)
                        else Color(0xFF3A1A1A)
                    )
                    .padding(16.dp)
            ) {
                Text(result, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}
