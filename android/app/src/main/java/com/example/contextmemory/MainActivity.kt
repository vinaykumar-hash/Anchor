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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ContextMemoryTheme {
                var permissionsGranted by remember { mutableStateOf(false) }

                if (!permissionsGranted) {
                    OnboardingScreen(onPermissionsGranted = {
                        permissionsGranted = true
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
