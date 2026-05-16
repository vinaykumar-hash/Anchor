package com.example.contextmemory.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.example.contextmemory.ai.GemmaInferenceModel
import com.example.contextmemory.db.MemoryStorage
import com.example.contextmemory.services.ContextExtractionService
import com.example.contextmemory.sync.DeviceDiscovery
import com.example.contextmemory.sync.SyncClient
import com.example.contextmemory.sync.SyncServer
import kotlinx.coroutines.launch
import java.io.File

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val contextScreenshots: List<String> = emptyList()
)

private enum class MemoryScreen {
    Search,
    Settings,
    Reference,
    Vault
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val memoryStorage = remember { MemoryStorage(context) }
    val gemmaModel = remember { GemmaInferenceModel(context) }
    val syncServer = remember { SyncServer(context, memoryStorage) }
    val syncClient = remember { SyncClient(memoryStorage) }
    val deviceDiscovery = remember { DeviceDiscovery(context) }

    var activeScreen by remember { mutableStateOf(MemoryScreen.Search) }
    var selectedScreenshot by remember { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var pendingScreenshots by remember { mutableStateOf(listOf<String>()) }
    val syncPrefs = remember {
        context.getSharedPreferences(
            ContextExtractionService.PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }
    var syncEnabled by remember {
        mutableStateOf(syncPrefs.getBoolean(ContextExtractionService.KEY_SYNC_ENABLED, true))
    }

    val currentStream by gemmaModel.currentStream.collectAsState()
    val isModelLoaded by gemmaModel.isModelLoaded.collectAsState()
    val initStatus by gemmaModel.initStatus.collectAsState()
    val isGenerating by gemmaModel.isGenerating.collectAsState()
    val discoveredDevices by deviceDiscovery.discoveredDevices.collectAsState()
    val lastSyncedTimes = remember { mutableStateMapOf<String, Long>() }

    LaunchedEffect(Unit) {
        memoryStorage.init()
        gemmaModel.initialize()
    }

    DisposableEffect(Unit) {
        onDispose {
            gemmaModel.close()
            syncClient.close()
            deviceDiscovery.cleanup()
        }
    }

    LaunchedEffect(syncEnabled) {
        if (syncEnabled) {
            deviceDiscovery.startDiscovery()
        } else {
            syncServer.stop()
            deviceDiscovery.cleanup()
        }
    }

    LaunchedEffect(discoveredDevices) {
        if (!syncEnabled) return@LaunchedEffect
        val now = System.currentTimeMillis()
        discoveredDevices.forEach { device ->
            val lastSync = lastSyncedTimes[device.ip] ?: 0L
            if (now - lastSync > 60_000) {
                lastSyncedTimes[device.ip] = now
                coroutineScope.launch {
                    try {
                        syncClient.syncWith(device.ip, device.port)
                    } catch (e: Exception) {
                        Log.e("ChatScreen", "Auto-sync with ${device.ip} failed", e)
                    }
                }
            }
        }
    }

    LaunchedEffect(syncEnabled) {
        if (!syncEnabled) return@LaunchedEffect

        while (true) {
            kotlinx.coroutines.delay(300_000)
            val pairedIp = memoryStorage.getSyncedIp()
            if (pairedIp != null) {
                try {
                    syncClient.syncWith(pairedIp)
                } catch (e: Exception) {
                    Log.e("ChatScreen", "Periodic sync failed", e)
                }
            }
        }
    }

    LaunchedEffect(isGenerating) {
        if (!isGenerating && currentStream.isNotBlank()) {
            if (messages.lastOrNull()?.text != currentStream) {
                messages = messages + ChatMessage(
                    text = currentStream,
                    isUser = false,
                    contextScreenshots = pendingScreenshots
                )
                pendingScreenshots = emptyList()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = activeScreen,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                if (forward) {
                    (slideInHorizontally { width -> width } + fadeIn()) togetherWith
                        (slideOutHorizontally { width -> -width / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { width -> -width } + fadeIn()) togetherWith
                        (slideOutHorizontally { width -> width / 3 } + fadeOut())
                }
            },
            label = "memory-screen"
        ) { screen ->
            when (screen) {
                MemoryScreen.Settings -> {
                    SettingsScreen(
                        syncEnabled = syncEnabled,
                        memoryStorage = memoryStorage,
                        syncClient = syncClient,
                        onVaultClick = { activeScreen = MemoryScreen.Vault },
                        onSyncEnabledChange = { enabled ->
                            syncEnabled = enabled
                            syncPrefs.edit()
                                .putBoolean(ContextExtractionService.KEY_SYNC_ENABLED, enabled)
                                .apply()
                            context.sendBroadcast(
                                Intent(ContextExtractionService.ACTION_SYNC_SETTING_CHANGED)
                                    .setPackage(context.packageName)
                                    .putExtra(ContextExtractionService.EXTRA_SYNC_ENABLED, enabled)
                            )
                        }
                    )
                }
                MemoryScreen.Reference -> {
                    ReferenceImageScreen(screenshotPath = selectedScreenshot)
                }
                MemoryScreen.Search -> {
                    MemorySearchScreen(
                        inputText = inputText,
                        onInputChange = { inputText = it },
                        messages = messages,
                        isGenerating = isGenerating,
                        isModelLoaded = isModelLoaded,
                        initStatus = initStatus,
                        currentStream = currentStream,
                        onReferenceClick = {
                            selectedScreenshot = it
                            activeScreen = MemoryScreen.Reference
                        },
                        onSend = {
                            if (inputText.isBlank() || isGenerating || !isModelLoaded) return@MemorySearchScreen
                            val query = inputText.trim()
                            inputText = ""
                            messages = messages + ChatMessage(query, isUser = true)

                            coroutineScope.launch {
                                val retrievedEntries = memoryStorage.searchMemoryEntries(query, limit = 3)
                                pendingScreenshots = retrievedEntries.mapNotNull { it.screenshotPath }
                                gemmaModel.generateResponseAsync(query, retrievedEntries.map { it.text })
                            }
                        }
                    )
                }
                MemoryScreen.Vault -> {
                    VaultScreen(
                        memoryStorage = memoryStorage,
                        onBack = { activeScreen = MemoryScreen.Settings }
                    )
                }
            }
        }

        FloatingHamburgerButton(
            isOpen = activeScreen == MemoryScreen.Settings,
            onClick = {
                activeScreen = if (activeScreen == MemoryScreen.Settings) {
                    if (selectedScreenshot != null) MemoryScreen.Reference else MemoryScreen.Search
                } else {
                    MemoryScreen.Settings
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 18.dp, end = 24.dp)
        )
    }
}

@Composable
private fun FloatingHamburgerButton(
    isOpen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (isOpen) 45f else 0f,
        label = "hamburger-rotation"
    )
    val lastLineWidth by animateDpAsState(
        targetValue = if (isOpen) 10.dp else 20.dp,
        label = "hamburger-last-line"
    )

    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White),
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFF181818))
    ) {
        Column(
            modifier = Modifier
                .size(width = 20.dp, height = 16.dp)
                .rotate(rotation),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            HamburgerLine(width = 10.dp)
            HamburgerLine(width = 20.dp)
            HamburgerLine(width = lastLineWidth)
        }
    }
}

@Composable
private fun HamburgerLine(width: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF181818))
    )
}

@Composable
private fun SettingsScreen(
    syncEnabled: Boolean,
    memoryStorage: MemoryStorage,
    syncClient: SyncClient,
    onVaultClick: () -> Unit,
    onSyncEnabledChange: (Boolean) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var manualIp by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MemoryBackgroundBrush())
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 96.dp, bottom = 28.dp)
        ) {
            Text(
                text = "Settings",
                color = Color(0xFF171717),
                fontSize = 34.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(32.dp))

            SettingsPanel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sync",
                            color = Color(0xFF171717),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        Text(
                            text = if (syncEnabled) {
                                "Server, discovery, and background sync are active."
                            } else {
                                "Server, discovery, and background sync are stopped."
                            },
                            color = Color(0xFF9C9C9C),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Switch(
                        checked = syncEnabled,
                        onCheckedChange = onSyncEnabledChange
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            SettingsPanel {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onVaultClick() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Memory Vault",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        Text(
                            text = "View and manage all captured memories.",
                            color = Color(0xFF9C9C9C),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }

                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.KeyboardArrowRight,
                        contentDescription = "Open Vault",
                        tint = Color(0xFF9C9C9C)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            SettingsPanel {
                Text(
                    text = "Manual Sync",
                    color = Color(0xFF171717),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (syncEnabled) {
                        "Connect to a device by IP address."
                    } else {
                        "Turn on Sync to use manual sync."
                    },
                    color = Color(0xFF9C9C9C),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        enabled = syncEnabled && !isSyncing,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                text = "192.168.1.100",
                                color = Color(0xFF777777),
                                fontSize = 12.sp
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF72E4EA),
                            unfocusedBorderColor = Color(0xFF444444),
                            disabledBorderColor = Color(0xFF303030),
                            focusedTextColor = Color(0xFF171717),
                            unfocusedTextColor = Color(0xFF171717),
                            disabledTextColor = Color(0xFF777777),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            cursorColor = Color(0xFF72E4EA)
                        )
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Button(
                        onClick = {
                            if (syncEnabled && manualIp.isNotBlank()) {
                                isSyncing = true
                                syncResult = null
                                coroutineScope.launch {
                                    val targetIp = manualIp.trim()
                                    val result = syncClient.syncWith(targetIp)
                                    isSyncing = false
                                    syncResult = if (result.success) {
                                        memoryStorage.saveSyncedIp(targetIp)
                                        "Synced. Imported ${result.imported}, exported ${result.exported}."
                                    } else {
                                        result.error ?: "Sync failed."
                                    }
                                }
                            }
                        },
                        enabled = syncEnabled && !isSyncing && manualIp.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF72E4EA),
                            contentColor = Color(0xFF111111),
                            disabledContainerColor = Color(0xFF333333),
                            disabledContentColor = Color(0xFF777777)
                        )
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF111111)
                            )
                        } else {
                            Text("Sync")
                        }
                    }
                }

                syncResult?.let { result ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = result,
                        color = if (result.startsWith("Synced")) Color(0xFF72E4EA) else Color(0xFFFF9A9A),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        content = content
    )
}

@Composable
private fun MemorySearchScreen(
    inputText: String,
    onInputChange: (String) -> Unit,
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    isModelLoaded: Boolean,
    initStatus: String,
    currentStream: String,
    onReferenceClick: (String) -> Unit,
    onSend: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MemoryBackgroundBrush())
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(380.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x66FFD96D), Color(0x22FF7058), Color.Transparent),
                        radius = 620f
                    )
                )
        )

        if (messages.isEmpty() && !isGenerating && currentStream.isBlank()) {
            HomeHeroCard(
                title = if (isModelLoaded) "How can I help\nyou remember?" else initStatus,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 22.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentPadding = PaddingValues(top = 94.dp, bottom = 122.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                items(messages) { message ->
                    MemoryMessage(message, onReferenceClick)
                }

                if (isGenerating) {
                    item {
                        ThinkingState(currentStream)
                    }
                } else if (currentStream.isNotBlank() && messages.lastOrNull()?.text != currentStream) {
                    item {
                        MemoryMessage(
                            message = ChatMessage(currentStream, isUser = false),
                            onReferenceClick = onReferenceClick
                        )
                    }
                }
            }
        }

        SearchInputBar(
            value = inputText,
            onValueChange = onInputChange,
            enabled = isModelLoaded && !isGenerating,
            onSend = onSend,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp, vertical = 16.dp)
        )
    }
}

@Composable
private fun HomeHeroCard(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(430.dp)
            .clip(RoundedCornerShape(42.dp))
            .background(Color(0xFFEFEDE8)),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 46.dp, start = 22.dp, end = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "HELLO",
                color = Color(0xFF2B2926),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                color = Color(0xFF111111),
                fontSize = 31.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SuggestionChip("Search memory")
                SuggestionChip("Find activity")
            }
            Spacer(modifier = Modifier.height(12.dp))
            SuggestionChip("Organize context")
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(190.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF6E78FF),
                            Color(0xFFFF7058),
                            Color(0xFFFFD96D),
                            Color(0xFFFFF8D8)
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .fillMaxWidth(0.72f)
                .height(128.dp)
                .clip(RoundedCornerShape(topStart = 120.dp))
                .background(Color(0xAAFFFFFF))
        )
    }
}

@Composable
private fun SuggestionChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color(0xFF282522), fontSize = 13.sp)
    }
}

@Composable
private fun MemoryMessage(message: ChatMessage, onReferenceClick: (String) -> Unit) {
    if (message.isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFFFFFFFF), Color(0xFFF2F0EA))
                        )
                    )
                    .padding(horizontal = 19.dp, vertical = 11.dp)
            ) {
                Text(
                    text = message.text,
                    color = Color(0xFF171717),
                    fontSize = 15.sp,
                    lineHeight = 19.sp
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = message.text,
                color = Color(0xFF26231F),
                fontSize = 14.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .padding(start = 3.dp)
            )

            if (message.contextScreenshots.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(message.contextScreenshots.take(3)) { path ->
                        ReferenceCard(path = path, onClick = { onReferenceClick(path) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingState(currentStream: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        if (currentStream.isBlank()) {
            Text(
                text = "Thinking...",
                color = Color(0xFF26231F),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy((-5).dp)) {
                ThinkingDot(Color(0xFFFF7890))
                ThinkingDot(Color(0xFFB86CDE))
                ThinkingDot(Color(0xFF6FE2EA))
            }
        } else {
            MemoryMessage(ChatMessage(currentStream, isUser = false), onReferenceClick = {})
        }
    }
}

@Composable
private fun ThinkingDot(color: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun ReferenceCard(path: String, onClick: () -> Unit) {
    val bitmap = remember(path) { loadBitmap(path, sampleSize = 4) }
    Box(
        modifier = Modifier
            .size(width = 116.dp, height = 170.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xFF626D6E))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Reference image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun SearchInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color.White, Color(0xFFFFFEFB))
                )
            )
            .padding(start = 18.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = "What are you looking for...",
                    color = Color(0xFF7A766F),
                    fontSize = 12.sp
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = Color(0xFF72E4EA),
                focusedTextColor = Color(0xFF171717),
                unfocusedTextColor = Color(0xFF171717),
                disabledTextColor = Color(0xFF888888)
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            singleLine = true
        )

        IconButton(
            onClick = onSend,
            enabled = enabled && value.isNotBlank(),
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF77A0FF), Color(0xFF4F7DF2)),
                        radius = 44f
                    )
                ),
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = Color.White,
                disabledContentColor = Color(0xFF505050)
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Send,
                contentDescription = "Send",
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun ReferenceImageScreen(screenshotPath: String?) {
    val bitmap = remember(screenshotPath) {
        screenshotPath?.let { loadBitmap(it, sampleSize = 1) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0x55FFD96D), Color(0xFFF7F5EF)),
                    radius = 900f
                )
            )
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 26.dp)
                .fillMaxWidth()
                .height(425.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xFFD9D9D9)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Reference image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = "Reference Image clicked",
                    color = Color.Black,
                    fontSize = 18.sp
                )
            }
        }
    }
}

@Composable
private fun MemoryBackgroundBrush(): Brush {
    return Brush.verticalGradient(
        colors = listOf(
            Color(0xFFFAF8F2),
            Color(0xFFF7F5EF),
            Color(0xFFF0EDE6)
        )
    )
}

private fun loadBitmap(path: String, sampleSize: Int) = try {
    val file = File(path)
    if (file.exists()) {
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
    } else {
        null
    }
} catch (e: Exception) {
    null
}
