package com.example.contextmemory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.contextmemory.ai.ModelManager
import com.example.contextmemory.ui.ChatScreen
import com.example.contextmemory.ui.OnboardingScreen
import com.example.contextmemory.ui.theme.ContextMemoryTheme

import java.io.File
import androidx.compose.ui.platform.LocalContext
import com.example.contextmemory.ui.ModelDownloadScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ContextMemoryTheme {
                val context = LocalContext.current
                var permissionsGranted by remember { mutableStateOf(false) }
                var modelExists by remember {
                    mutableStateOf(
                        File(context.getExternalFilesDir(null), "gemma-4-E2B-it.litertlm").exists() ||
                        File(context.filesDir, "gemma-4-E2B-it.litertlm").exists() ||
                        File(context.getExternalFilesDir(null), "gemma-4-e2b-int4.litertlm").exists() ||
                        File(context.filesDir, "gemma-4-e2b-int4.litertlm").exists()
                    )
                }

                if (!permissionsGranted) {
                    OnboardingScreen(onPermissionsGranted = {
                        permissionsGranted = true
                    })
                } else if (!modelExists) {
                    ModelDownloadScreen(onDownloadComplete = {
                        modelExists = true
                    })
                } else {
                    ChatScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        ModelManager.close()
        super.onDestroy()
    }

    override fun onStop() {
        ModelManager.close()
        super.onStop()
    }
}
