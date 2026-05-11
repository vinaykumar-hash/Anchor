package com.example.contextmemory.services

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.contextmemory.ai.ModelManager
import com.example.contextmemory.db.MemoryStorage
import com.example.contextmemory.sync.SyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class ContextExtractionService : AccessibilityService() {
    private val TAG = "ContextExtractionService"
    
    private var isVolumeUpPressed = false
    private var isVolumeDownPressed = false
    
    private lateinit var memoryStorage: MemoryStorage
    private lateinit var syncServer: com.example.contextmemory.sync.SyncServer
    private lateinit var deviceDiscovery: com.example.contextmemory.sync.DeviceDiscovery
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service Connected")
        
        memoryStorage = MemoryStorage(this)
        syncServer = com.example.contextmemory.sync.SyncServer(this, memoryStorage)
        deviceDiscovery = com.example.contextmemory.sync.DeviceDiscovery(this)

        serviceScope.launch {
            memoryStorage.init()
            // Initialize the shared model engine so we can use vision
            Log.d(TAG, "Initializing ModelManager from service...")
            ModelManager.initialize(this@ContextExtractionService)
            
            // Start sync components in background
            syncServer.start()
            deviceDiscovery.registerService(8473)
            deviceDiscovery.startDiscovery()
            Log.d(TAG, "Sync server & discovery active in background service")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We only care about explicit button combo triggers.
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val action = event.action
        val keyCode = event.keyCode

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            isVolumeUpPressed = (action == KeyEvent.ACTION_DOWN)
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            isVolumeDownPressed = (action == KeyEvent.ACTION_DOWN)
        }

        // Trigger when both volume buttons are pressed
        if (isVolumeUpPressed && isVolumeDownPressed) {
            if (action == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "Button combo detected! Capturing context...")
                captureScreenAndContext()
            }
            return true // Consume the event so the volume doesn't change
        }

        return super.onKeyEvent(event)
    }

    /**
     * Main capture pipeline:
     * 1. Take a screenshot (API 30+)
     * 2. Use the vision model to summarize the screenshot
     * 3. Save clean summary + screenshot path to memory
     */
    private fun captureScreenAndContext() {
        // Fallback: always scrape UI text in case vision isn't ready
        val uiText = scrapeUIText()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        Log.d(TAG, "Screenshot captured successfully!")
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                        screenshot.hardwareBuffer.close()

                        if (bitmap != null) {
                            processScreenshotWithVision(bitmap, uiText)
                        } else {
                            Log.w(TAG, "Failed to create bitmap from screenshot")
                            saveContextWithoutScreenshot(uiText)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                        saveContextWithoutScreenshot(uiText)
                    }
                }
            )
        } else {
            Log.d(TAG, "API < 30: Saving context without screenshot image.")
            saveContextWithoutScreenshot(uiText)
        }
    }
    /**
     * Process screenshot: save to disk, summarize the UI text using the model, save to memory.
     * Uses TEXT summarization (not vision) to avoid OOM on mobile GPUs.
     */
    private fun processScreenshotWithVision(bitmap: Bitmap, uiText: String) {
        serviceScope.launch {
            try {
                // 1. Save screenshot to disk
                val screenshotPath = saveScreenshotToDisk(bitmap)
                Log.d(TAG, "Screenshot saved to: $screenshotPath")

                // 2. Show initial toast
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ContextExtractionService, "📸 Captured, summarizing...", Toast.LENGTH_SHORT).show()
                }

                // 3. Use model to summarize the UI text
                var textToStore = uiText
                if (ModelManager.isReady.value && uiText.length > 20) {
                    Log.d(TAG, "📝 Starting text summarization of ${uiText.length} chars...")
                    val startTime = System.currentTimeMillis()
                    
                    val summary = ModelManager.summarizeText(uiText)
                    
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "✅ Summary done in ${elapsed}ms: $summary")

                    if (summary.isNotBlank()) {
                        textToStore = summary
                    }
                } else {
                    Log.w(TAG, "Model not ready or text too short, storing raw text")
                }

                // 4. Save to memory
                val packageName = getActivePackageName()
                memoryStorage.insertMemory(textToStore, packageName, screenshotPath)
                Log.d(TAG, "Context saved! Text: ${textToStore.take(100)}")

                // 5. Auto-sync if paired
                val pairedIp = memoryStorage.getSyncedIp()
                if (pairedIp != null) {
                    serviceScope.launch {
                        try {
                            val syncClient = SyncClient(memoryStorage)
                            syncClient.syncWith(pairedIp)
                            syncClient.close()
                            Log.d(TAG, "🚀 Auto-sync push to $pairedIp successful")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Auto-sync push failed", e)
                        }
                    }
                }

                // 6. Confirm with toast
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ContextExtractionService,
                        "✅ Saved: ${textToStore.take(50)}...",
                        Toast.LENGTH_LONG
                    ).show()
                }

                // 6. Clean up
                bitmap.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error processing screenshot", e)
                saveContextWithoutScreenshot(uiText)
            }
        }
    }

    /**
     * Save context without a screenshot image (fallback for older APIs or failures).
     */
    private fun saveContextWithoutScreenshot(uiText: String) {
        val packageName = getActivePackageName()
        serviceScope.launch {
            memoryStorage.insertMemory(uiText, packageName, null)
            Log.d(TAG, "Context saved (text only). ${uiText.length} chars")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ContextExtractionService, "📝 Context captured (text only)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Scrape the current UI tree using the Accessibility API.
     */
    private fun scrapeUIText(): String {
        val rootNode: AccessibilityNodeInfo? = rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "Root node is null. Cannot extract UI text.")
            return ""
        }

        val extractedText = StringBuilder()
        val packageName = rootNode.packageName?.toString() ?: "Unknown App"
        
        extractedText.append("Active App: $packageName\n")
        extractedText.append("--- Screen Content ---\n")
        
        traverseNode(rootNode, extractedText)
        
        return extractedText.toString()
    }

    // OCR and combineTexts removed — replaced by vision-based summarization

    /**
     * Save a screenshot bitmap to the app's private screenshots directory.
     * Returns the absolute file path.
     */
    private fun saveScreenshotToDisk(bitmap: Bitmap): String {
        val screenshotsDir = File(getExternalFilesDir(null), "screenshots")
        if (!screenshotsDir.exists()) screenshotsDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(screenshotsDir, "ctx_$timestamp.jpg")

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }

        return file.absolutePath
    }

    /**
     * Get the package name of the currently active app.
     */
    private fun getActivePackageName(): String {
        return rootInActiveWindow?.packageName?.toString() ?: "Unknown App"
    }

    private fun traverseNode(node: AccessibilityNodeInfo, stringBuilder: StringBuilder) {
        val text = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()
        val resId = node.viewIdResourceName?.split("/")?.lastOrNull()

        // Prioritize nodes with text or descriptions
        if (!text.isNullOrBlank() || !contentDesc.isNullOrBlank()) {
            val label = text ?: contentDesc!!
            
            // Skip massive text blocks (like Terms of Service) to save token budget
            if (label.length < 500) {
                if (resId != null) {
                    stringBuilder.append("[$resId]: ")
                }
                stringBuilder.append(label).append("\n")
            }
        }

        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i)
            if (childNode != null) {
                traverseNode(childNode, stringBuilder)
                childNode.recycle()
            }
        }
    }
}
