package com.example.contextmemory.ui

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.contextmemory.ai.GemmaInferenceModel
import com.example.contextmemory.db.MemoryEntry
import com.example.contextmemory.db.MemoryStorage
import com.example.contextmemory.sync.DeviceDiscovery
import com.example.contextmemory.sync.SyncClient
import com.example.contextmemory.sync.SyncServer
import kotlinx.coroutines.launch
import java.io.File

/**
 * A chat message with optional screenshot paths from context.
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val contextScreenshots: List<String> = emptyList(),
    val debugInfo: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val memoryStorage = remember { MemoryStorage(context) }
    val gemmaModel = remember { GemmaInferenceModel(context) }
    
    // Sync infrastructure
    val syncServer = remember { SyncServer(context, memoryStorage) }
    val syncClient = remember { SyncClient(memoryStorage) }
    val deviceDiscovery = remember { DeviceDiscovery(context) }
    var showSyncScreen by remember { mutableStateOf(false) }
    
    var inputText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    // Store pending screenshots for the current generation
    var pendingScreenshots by remember { mutableStateOf(listOf<String>()) }

    val currentStream by gemmaModel.currentStream.collectAsState()
    val isModelLoaded by gemmaModel.isModelLoaded.collectAsState()
    val initStatus by gemmaModel.initStatus.collectAsState()
    val isGenerating by gemmaModel.isGenerating.collectAsState()
    val lastDebugInfo by gemmaModel.lastDebugInfo.collectAsState()

    // Initialize databases and models
    LaunchedEffect(Unit) {
        memoryStorage.init()
        gemmaModel.initialize()
    }

    // Auto-Sync Logic: When a device is discovered, sync immediately (limit to once per minute)
    val discoveredDevices by deviceDiscovery.discoveredDevices.collectAsState()
    val lastSyncedTimes = remember { mutableMapOf<String, Long>() }
    
    LaunchedEffect(discoveredDevices) {
        val now = System.currentTimeMillis()
        discoveredDevices.forEach { device ->
            val lastSync = lastSyncedTimes[device.ip] ?: 0L
            if (now - lastSync > 60_000) { // Only auto-sync once per minute
                Log.d("ChatScreen", "🔄 Auto-syncing with ${device.name} at ${device.ip}")
                lastSyncedTimes[device.ip] = now
                coroutineScope.launch {
                    try {
                        syncClient.syncWith(device.ip, device.port)
                        Log.d("ChatScreen", "✅ Auto-sync with ${device.ip} successful")
                    } catch (e: Exception) {
                        Log.e("ChatScreen", "❌ Auto-sync with ${device.ip} failed", e)
                    }
                }
            }
        }
    }

    // Periodic Sync (Every 5 minutes for the Paired IP)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(300_000) // 5 minutes
            val pairedIp = memoryStorage.getSyncedIp()
            if (pairedIp != null) {
                Log.d("ChatScreen", "🕒 Periodic background sync starting...")
                try {
                    syncClient.syncWith(pairedIp)
                    Log.d("ChatScreen", "✅ Periodic sync successful")
                } catch (e: Exception) {
                    Log.e("ChatScreen", "❌ Periodic sync failed", e)
                }
            }
        }
    }

    // Show sync screen if toggled
    if (showSyncScreen) {
        SyncScreen(
            memoryStorage = memoryStorage,
            syncServer = syncServer,
            syncClient = syncClient,
            deviceDiscovery = deviceDiscovery,
            onBack = { showSyncScreen = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ContextMemory") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    TextButton(onClick = { showSyncScreen = true }) {
                        Text("🔄 Sync", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    if (!isModelLoaded) {
                        Text(
                            text = initStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                }
            )
        },
        bottomBar = {
            if (!isModelLoaded) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF121212))
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages) { message ->
                    ChatBubble(message)
                }
                
                // Show streaming message while generating
                if (isGenerating || (currentStream.isNotEmpty() && !messages.any { it.text == currentStream })) {
                    item {
                        ChatBubble(ChatMessage(
                            text = if (currentStream.isEmpty()) "Thinking..." else currentStream,
                            isUser = false
                        ))
                    }
                }
            }

            // When generation finishes, commit the stream to messages with screenshots
            LaunchedEffect(isGenerating) {
                if (!isGenerating && currentStream.isNotEmpty()) {
                    if (!messages.any { it.text == currentStream }) {
                        messages = messages + ChatMessage(
                            text = currentStream,
                            isUser = false,
                            contextScreenshots = pendingScreenshots,
                            debugInfo = lastDebugInfo
                        )
                        pendingScreenshots = emptyList()
                    }
                }
            }

            // Input Area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(24.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask about your past screen activity...") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    maxLines = 3
                )
                
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank() && !isGenerating && isModelLoaded) {
                            val query = inputText
                            inputText = ""
                            messages = messages + ChatMessage(query, isUser = true)
                            
                            coroutineScope.launch {
                                // 1. Search memory — always, for every question
                                val retrievedEntries = memoryStorage.searchMemoryEntries(query, limit = 3)
                                val contextTexts = retrievedEntries.map { it.text }
                                
                                // 2. Collect screenshot paths from context entries
                                pendingScreenshots = retrievedEntries.mapNotNull { it.screenshotPath }
                                
                                // 3. Trigger async generation
                                gemmaModel.generateResponseAsync(query, contextTexts)
                            }
                        }
                    },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFF6B4EE6))
                ) {
                    Text("Send", color = Color(0xFF6B4EE6))
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val backgroundColor = if (message.isUser) Color(0xFF6B4EE6) else Color(0xFF2A2A2A)
    val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (message.isUser) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        // Main message bubble
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(backgroundColor)
                .padding(12.dp)
        ) {
            Text(
                text = message.text,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Show screenshots used as context (only for AI responses)
        if (!message.isUser && message.contextScreenshots.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "📸 Sources (${message.contextScreenshots.size})",
                color = Color(0xFF888888),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
            
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(message.contextScreenshots) { screenshotPath ->
                    ScreenshotThumbnail(screenshotPath)
                }
            }
        }

        // Debug panel (tap to expand/collapse)
        if (!message.isUser && message.debugInfo.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = if (expanded) "🔧 Hide Debug" else "🔧 Show Debug",
                color = Color(0xFF666666),
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(start = 4.dp, top = 4.dp)
            )
            
            if (expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A1A1A))
                        .padding(8.dp)
                ) {
                    Text(
                        text = message.debugInfo,
                        color = Color(0xFF00FF88),
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ScreenshotThumbnail(path: String) {
    val bitmap = remember(path) {
        try {
            val file = File(path)
            if (file.exists()) {
                // Load a downscaled version for the thumbnail
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 4  // 1/4 resolution for thumbnail
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Context screenshot",
            modifier = Modifier
                .size(width = 120.dp, height = 200.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF444444), RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        // Fallback if image can't be loaded
        Box(
            modifier = Modifier
                .size(width = 120.dp, height = 200.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF333333)),
            contentAlignment = Alignment.Center
        ) {
            Text("📷", fontSize = 24.sp)
        }
    }
}
