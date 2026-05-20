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
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.example.contextmemory.R
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
    Home,
    Chat,
    Settings,
    Reference,
    Vault
}

val Aqua = Color(0xFF6FD1D7)
val Pink = Color(0xFFD76FB3)
val Red = Color(0xFFD76F6F)
val Green = Color(0xFF6FD78D)
val HeaderGradient = Brush.horizontalGradient(
    colors = listOf(Aqua, Pink, Red, Green)
)

val FunnelDisplay = FontFamily(
    Font(R.font.funnel_display_regular, FontWeight.Normal),
    Font(R.font.funnel_display_semibold, FontWeight.SemiBold),
    Font(R.font.funnel_display_bold, FontWeight.Bold)
)

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

    var activeScreen by remember { mutableStateOf(MemoryScreen.Home) }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Persistent Header
            MemoraHeader(
                searchQuery = inputText,
                onSearchChange = { inputText = it },
                onMenuClick = { activeScreen = MemoryScreen.Settings }
            )

            Box(modifier = Modifier.weight(1f)) {
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
                            BackHandler { activeScreen = MemoryScreen.Home }
                            SettingsScreen(
                                syncEnabled = syncEnabled,
                                memoryStorage = memoryStorage,
                                syncClient = syncClient,
                                onBack = { activeScreen = MemoryScreen.Home },
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
                            BackHandler { activeScreen = MemoryScreen.Home }
                            ReferenceImageScreen(screenshotPath = selectedScreenshot)
                        }
                        MemoryScreen.Home -> {
                            MemoryHomeScreen(
                                memoryStorage = memoryStorage,
                                searchQuery = inputText,
                                onSettingsClick = { activeScreen = MemoryScreen.Settings },
                                onAskClick = { activeScreen = MemoryScreen.Chat },
                                onReferenceClick = { path ->
                                    selectedScreenshot = path
                                    activeScreen = MemoryScreen.Reference
                                }
                            )
                        }
                        MemoryScreen.Chat -> {
                            BackHandler { activeScreen = MemoryScreen.Home }
                            ChatView(
                                messages = messages,
                                isGenerating = isGenerating,
                                currentStream = currentStream,
                                onReferenceClick = { path ->
                                    selectedScreenshot = path
                                    activeScreen = MemoryScreen.Reference
                                },
                                onSend = { query ->
                                    coroutineScope.launch {
                                        val retrievedEntries = memoryStorage.searchMemoryEntries(query, limit = 3)
                                        pendingScreenshots = retrievedEntries.mapNotNull { it.screenshotPath }
                                        gemmaModel.generateResponseAsync(query, retrievedEntries.map { it.text })
                                    }
                                }
                            )
                        }
                        MemoryScreen.Vault -> {
                            BackHandler { activeScreen = MemoryScreen.Settings }
                            VaultScreen(
                                memoryStorage = memoryStorage,
                                onBack = { activeScreen = MemoryScreen.Settings }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoraHeader(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onMenuClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_logo),
                    contentDescription = "Anchor Logo",
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Text(
                    text = "Anchor",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FunnelDisplay,
                    letterSpacing = (-1).sp
                )
            }
            
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2C2C2E))
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(10.dp)
                ) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .width(18.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(Color.White.copy(alpha = 0.6f))
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        TextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth(),
            placeholder = { 
                Text(
                    "Search", 
                    color = Color.White.copy(alpha = 0.4f), 
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 0.dp)
                ) 
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp)
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1C1C1E),
                unfocusedContainerColor = Color(0xFF1C1C1E),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Aqua,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            shape = RoundedCornerShape(24.dp),
            singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HeaderGradient)
        )
    }
}


@Composable
private fun SettingsScreen(
    syncEnabled: Boolean,
    memoryStorage: MemoryStorage,
    syncClient: SyncClient,
    onBack: () -> Unit,
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
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Settings",
                color = Color.White,
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
                            color = Color.White,
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
                            color = Color.White.copy(alpha = 0.4f),
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
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = "Open Vault",
                        tint = Color.White.copy(alpha = 0.2f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            SettingsPanel {
                Text(
                    text = "Manual Sync",
                    color = Color.White,
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
                    color = Color.White.copy(alpha = 0.4f),
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
                                color = Color.White.copy(alpha = 0.2f),
                                fontSize = 12.sp
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF007AFF),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            disabledBorderColor = Color.White.copy(alpha = 0.05f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            disabledTextColor = Color.White.copy(alpha = 0.3f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            cursorColor = Color(0xFF007AFF)
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
                            containerColor = Color.White,
                            contentColor = Color.Black,
                            disabledContainerColor = Color.White.copy(alpha = 0.05f),
                            disabledContentColor = Color.White.copy(alpha = 0.1f)
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
            .background(Color(0xFF1C1C1E))
            .border(0.5.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        content = content
    )
}

@Composable
private fun MemoryHomeScreen(
    memoryStorage: MemoryStorage,
    searchQuery: String,
    onSettingsClick: () -> Unit,
    onAskClick: () -> Unit,
    onReferenceClick: (String) -> Unit
) {
    var memories by remember { mutableStateOf<List<com.example.contextmemory.db.MemoryEntry>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<com.example.contextmemory.db.MemoryEntry>>(emptyList()) }
    
    LaunchedEffect(Unit) {
        memories = memoryStorage.getAllEntries().sortedByDescending { it.timestamp }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            searchResults = memoryStorage.searchMemoryEntries(searchQuery, limit = 10)
        } else {
            searchResults = emptyList()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            if (searchQuery.isNotBlank()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)
                ) {
                    items(searchResults) { memory ->
                        MemoryCard(memory, onClick = { onReferenceClick(memory.screenshotPath ?: "") })
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(top = 24.dp, bottom = 100.dp)
                ) {
                    // Collections Section
                    item {
                        Column {
                            Text(
                                text = "Collection",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                item { 
                                    LargeCollectionCard(
                                        "Name of the Collection", 
                                        "Information about the Collection"
                                    ) 
                                }
                                item { 
                                    LargeCollectionCard(
                                        "Work Context", 
                                        "Recent professional captures"
                                    ) 
                                }
                            }
                        }
                    }

                    // All Memories Section
                    item {
                        Text(
                            text = "All Memories",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Simple Grid implementation using Column/Row as LazyVerticalGrid might need different imports
                    val chunkedMemories = memories.chunked(3)
                    items(chunkedMemories) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            row.forEach { memory ->
                                GridMemoryCard(
                                    memory, 
                                    modifier = Modifier.weight(1f),
                                    onClick = { onReferenceClick(memory.screenshotPath ?: "") }
                                )
                            }
                            // Fill empty spaces if row is not full
                            repeat(3 - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        
        // Bottom "Ask Questions" Button
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 34.dp)
        ) {
            Surface(
                onClick = onAskClick,
                shape = RoundedCornerShape(30.dp),
                color = Color(0xFFE5E5E5), // Light gray background
                modifier = Modifier
                    .height(56.dp)
                    .widthIn(min = 200.dp)
                    .border(1.5.dp, Aqua, RoundedCornerShape(30.dp)) // Colored border
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Ask Questions",
                        color = Color.Black,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun LargeCollectionCard(title: String, subtitle: String) {
    Surface(
        modifier = Modifier
            .width(320.dp)
            .height(120.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF1C1C1E)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFD1D1D6)) // Light gray placeholder
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun GridMemoryCard(
    memory: com.example.contextmemory.db.MemoryEntry,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bitmap = remember(memory.screenshotPath) { 
        memory.screenshotPath?.let { loadBitmap(it, sampleSize = 8) } 
    }
    
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
            .clickable(onClick = onClick)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun CollectionCard(title: String, count: String, color: Color) {
    Box(
        modifier = Modifier
            .size(width = 160.dp, height = 180.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1C1C1E))
            .border(0.5.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Column {
            Text(text = title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(text = count, color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(16.dp))
            // Preview Grid Placeholder
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.05f)))
                Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.05f)))
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.05f)))
                Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.05f)))
            }
        }
    }
}

@Composable
private fun MemoryCard(memory: com.example.contextmemory.db.MemoryEntry, onClick: () -> Unit) {
    val dateFormat = remember { java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()) }
    val bitmap = remember(memory.screenshotPath) { 
        memory.screenshotPath?.let { loadBitmap(it, sampleSize = 4) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1C1C1E))
            .border(0.5.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (memory.text.length > 30) memory.text.take(30) + "..." else memory.text,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Source: ${memory.packageName}",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(Color(0xFFE1306C))) // Mock app icon
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Today ${dateFormat.format(java.util.Date(memory.timestamp))}",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 12.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Box(
                modifier = Modifier
                    .size(width = 80.dp, height = 110.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.05f))
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
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
                color = Color.White.copy(alpha = 0.4f),
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
            MemoraMessage(ChatMessage(currentStream, isUser = false), onReferenceClick = {})
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
private fun ChatView(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    currentStream: String,
    onReferenceClick: (String) -> Unit,
    onSend: (String) -> Unit
) {
    var chatInput by remember { mutableStateOf("") }
    val scrollState = rememberLazyListState()

    LaunchedEffect(messages.size, currentStream) {
        if (messages.isNotEmpty() || currentStream.isNotBlank()) {
            scrollState.animateScrollToItem(if (isGenerating) messages.size else messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        LazyColumn(
            state = scrollState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(messages) { message ->
                MemoraMessage(message, onReferenceClick)
            }
            if (isGenerating) {
                item {
                    ThinkingState(currentStream)
                }
            }
        }

        // Chat Input Area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .imePadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            TextField(
                value = chatInput,
                onValueChange = { chatInput = it },
                modifier = Modifier
                    .weight(1f),
                placeholder = { 
                    Text("Ask questions", color = Color.White.copy(alpha = 0.4f), fontSize = 16.sp) 
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1C1C1E),
                    unfocusedContainerColor = Color(0xFF1C1C1E),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Aqua,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(28.dp),
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            IconButton(
                onClick = {
                    if (chatInput.isNotBlank()) {
                        onSend(chatInput)
                        chatInput = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Aqua.copy(alpha = 0.15f)),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color(0xFF16484B)
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Send, 
                    contentDescription = "Send",
                    tint = Aqua,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun MemoraMessage(message: ChatMessage, onReferenceClick: (String) -> Unit) {
    var showReferences by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (message.isUser) {
            Surface(
                color = Color(0xFF1C1C1E),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(
                    text = message.text,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FunnelDisplay,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = message.text,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontFamily = FunnelDisplay,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = if (showReferences) "Hide References" else "Show References →",
                    color = Aqua,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FunnelDisplay,
                    modifier = Modifier.clickable { 
                        showReferences = !showReferences
                    }
                )
                
                if (showReferences && message.contextScreenshots.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        items(message.contextScreenshots) { path ->
                            ReferenceCard(path = path, onClick = { onReferenceClick(path) })
                        }
                    }
                }
            }
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
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1C1C1E)),
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
                    text = "Image not found",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 16.sp
                )
            }
        }
    }
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
