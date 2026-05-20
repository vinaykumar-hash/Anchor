package com.example.contextmemory.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Singleton that owns the LiteRT-LM Engine instance.
 * Shared between the Activity (for Q&A) and the AccessibilityService (for screenshot summarization).
 */
object ModelManager {
    private const val TAG = "ModelManager"

    private var engine: Engine? = null
    private val initMutex = Mutex()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    private val _initStatus = MutableStateFlow("Not initialized")
    val initStatus: StateFlow<String> = _initStatus

    private var visionAvailable = false

    /**
     * Initialize the LiteRT-LM Engine. Call from a background thread.
     * Safe to call multiple times — will skip if already initialized.
     */
    suspend fun initialize(context: Context, modelFileName: String = "gemma-4-E2B-it.litertlm") {
        if (_isReady.value) return

        initMutex.withLock {
            if (_isReady.value) return  // Double-check after acquiring lock

            withContext(Dispatchers.IO) {
                try {
                    _initStatus.value = "Locating model..."
                    // Check external files dir first (where adb push puts it)
                    var modelFile = File(context.getExternalFilesDir(null), modelFileName)
                    if (!modelFile.exists()) {
                        modelFile = File(context.filesDir, modelFileName)
                    }
                    
                    // Fallback to legacy filename if the new one is not found
                    if (!modelFile.exists() && modelFileName == "gemma-4-E2B-it.litertlm") {
                        val legacyName = "gemma-4-e2b-int4.litertlm"
                        modelFile = File(context.getExternalFilesDir(null), legacyName)
                        if (!modelFile.exists()) {
                            modelFile = File(context.filesDir, legacyName)
                        }
                    }

                    if (!modelFile.exists()) {
                        _initStatus.value = "Model file not found"
                        Log.e(TAG, "Model file not found in external or internal dir")
                        return@withContext
                    }
                    Log.d(TAG, "Model found at: ${modelFile.absolutePath}")

                    // Try CPU first for maximum stability on mobile devices
                    _initStatus.value = "Loading model (CPU)..."
                    Log.d(TAG, "Initializing LiteRT-LM engine from: ${modelFile.absolutePath}")

                    try {
                        val cpuConfig = EngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = Backend.CPU(),
                            cacheDir = context.cacheDir.absolutePath
                        )
                        val cpuEngine = Engine(cpuConfig)
                        cpuEngine.initialize()
                        engine = cpuEngine
                        _isReady.value = true
                        _initStatus.value = "Ready (CPU)"
                        Log.d(TAG, "✅ Engine initialized with CPU backend")
                    } catch (cpuError: Exception) {
                        Log.w(TAG, "CPU init failed, falling back to GPU", cpuError)
                        _initStatus.value = "CPU failed, trying GPU..."

                        val gpuConfig = EngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = Backend.GPU(),
                            cacheDir = context.cacheDir.absolutePath
                        )
                        val gpuEngine = Engine(gpuConfig)
                        gpuEngine.initialize()
                        engine = gpuEngine
                        _isReady.value = true
                        _initStatus.value = "Ready (GPU)"
                        Log.d(TAG, "✅ Engine initialized with GPU fallback")
                    }
                } catch (e: Exception) {
                    _initStatus.value = "Failed: ${e.message}"
                    Log.e(TAG, "Engine initialization failed", e)
                }
            }
        }
    }

    /**
     * Summarize raw screen text using the LLM (text-only, no vision).
     * Takes the noisy UI-scraped text and returns a clean 1-2 sentence summary.
     * This avoids OOM crashes from the vision encoder.
     */
    suspend fun summarizeText(rawText: String): String = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext ""
        
        try {
            Log.d(TAG, "📝 Starting text summarization...")
            val startTime = System.currentTimeMillis()

            // Truncate input to avoid overwhelming the model, but keep enough for product names
            val truncated = rawText.take(1500)

            val config = ConversationConfig(
                systemInstruction = Contents.of(
                    "You are a highly detailed screen analysis tool. " +
                    "Analyze the provided UI text and summarize it in 1-2 sentences. " +
                    "CRITICAL: Always include specific proper nouns like Product Names, Item Titles, Prices, and User Names if visible. " +
                    "Do NOT just say 'a product' or 'an interface'; say exactly WHAT product or WHICH interface. " +
                    "Format: [App Name] - [Action/Content] - [Specific Details]."
                ),
                samplerConfig = SamplerConfig(topK = 5, topP = 0.9, temperature = 0.3)
            )

            val summary = StringBuilder()
            eng.createConversation(config).use { conversation ->
                val response = conversation.sendMessage("Screen text:\n$truncated")
                summary.append(response.toString())
            }

            val elapsed = System.currentTimeMillis() - startTime
            val result = summary.toString().trim()
            Log.d(TAG, "✅ Text summarized in ${elapsed}ms: $result")
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "❌ Text summarization failed", e)
            return@withContext ""  // Empty = fallback to raw text
        }
    }

    /**
     * Create a new conversation for Q&A chat.
     * The caller is responsible for closing the conversation.
     */
    fun createConversation(config: ConversationConfig? = null): Conversation? {
        val eng = engine ?: return null
        return try {
            if (config != null) eng.createConversation(config) else eng.createConversation()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create conversation", e)
            null
        }
    }

    /**
     * Clean up — call when the app is destroyed.
     */
    fun close() {
        try {
            engine?.close()
            engine = null
            _isReady.value = false
            _initStatus.value = "Closed"
            Log.d(TAG, "Engine closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing engine", e)
        }
    }
}
