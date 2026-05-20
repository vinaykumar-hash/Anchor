package com.example.contextmemory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.contextmemory.ai.ModelDownloader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadScreen(onDownloadComplete: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val isDownloading by ModelDownloader.isDownloading.collectAsState()
    val downloadProgress by ModelDownloader.downloadProgress.collectAsState()
    val downloadError by ModelDownloader.downloadError.collectAsState()

    var customUrl by remember { mutableStateOf(ModelDownloader.modelUrl) }
    var showUrlConfig by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF121214), // Premium deep dark background
                        Color(0xFF1E1E24)
                    )
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = "Cloud Download",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Download local AI model",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Anchor runs fully offline using a local Gemma-2B AI model. We need to download the model file (~1.4 GB) to get started.",
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (isDownloading) {
                // Progress Bar
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color(0xFF2A2A35)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Percentage and details
                val percentage = (downloadProgress * 100).toInt()
                val downloadedMB = (downloadProgress * 1400).toInt() // Approximate size in MB

                Text(
                    text = "Downloading: $percentage% ($downloadedMB MB / 1400 MB)",
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            } else {
                if (downloadError != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .background(Color(0x20FF5555), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Error",
                            tint = Color(0xFFFF5555)
                        )
                        Text(
                            text = "Download failed: ${downloadError?.take(60)}...",
                            color = Color(0xFFFF5555),
                            fontSize = 12.sp,
                            maxLines = 2
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                Button(
                    onClick = {
                        ModelDownloader.modelUrl = customUrl
                        coroutineScope.launch {
                            val success = ModelDownloader.downloadModel(context)
                            if (success) {
                                onDownloadComplete()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth(0.8f),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (downloadError != null) "Retry Download" else "Start Download",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(onClick = { showUrlConfig = !showUrlConfig }) {
                    Text(
                        text = if (showUrlConfig) "Hide Configuration" else "Configure URL",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                if (showUrlConfig) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("Hugging Face Model URL") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF1E1E24),
                            unfocusedContainerColor = Color(0xFF121214)
                        ),
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                }
            }
        }
    }
}
