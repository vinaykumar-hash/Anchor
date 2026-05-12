package com.example.contextmemory.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat-facing inference model that delegates to the shared ModelManager.
 * Handles Q&A with context from memory, streaming responses, and debug info.
 */
class GemmaInferenceModel(private val context: Context) {
    private val TAG = "GemmaInferenceModel"

    private val _currentStream = MutableStateFlow("")
    val currentStream: StateFlow<String> = _currentStream

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded

    private val _initStatus = MutableStateFlow("Initializing...")
    val initStatus: StateFlow<String> = _initStatus

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    // Debug info for display in the chat
    private val _lastDebugInfo = MutableStateFlow("")
    val lastDebugInfo: StateFlow<String> = _lastDebugInfo

    /**
     * Initialize via the shared ModelManager singleton.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        _initStatus.value = "Initializing model..."
        ModelManager.initialize(context)
        _isModelLoaded.value = ModelManager.isReady.value
        _initStatus.value = ModelManager.initStatus.value
        Log.d(TAG, "Model initialization: ${_initStatus.value}")
    }

    /**
     * Generate a response using context from memory.
     * Uses the LiteRT-LM Conversation API with streaming.
     */
    fun generateResponseAsync(userQuery: String, retrievedContext: List<String>) {
        if (!ModelManager.isReady.value) {
            _currentStream.value = "Error: AI Model is not initialized."
            return
        }

        // Clean context text before sending to model
        val cleanedContext = retrievedContext
            .take(3)
            .map { raw ->
                raw.replace(Regex("Active App:.*?\n"), "")
                    .replace("--- Screen Content ---", "")
                    .replace("--- OCR Text ---", "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(1000) // Increased from 200 to 1000 to keep product names
            }
            .filter { it.length > 5 }
            .distinct()

        // Build the system instruction with context
        val systemPrompt = if (cleanedContext.isNotEmpty()) {
            "You are an expert at analyzing the user's past screen history. " +
            "The user will ask questions about what they saw on their screen. " +
            "Use the provided screen memories below to answer accurately. " +
            "If the information is in the context, state it clearly. " +
            "Screen Context:\n" +
                cleanedContext.joinToString("\n") { "- $it" } +
                "\nBe concise and factual. Answer in 1-2 sentences."
        } else {
            "You are an expert at analyzing the user's screen history. No relevant memories were found for this query."
        }

        val debugPrompt = "📋 SYSTEM:\n$systemPrompt\n\n💬 USER: $userQuery\n\n📦 CONTEXT ENTRIES: ${cleanedContext.size}\n" +
            cleanedContext.mapIndexed { i, ctx -> "[$i] ${ctx.take(100)}..." }.joinToString("\n")

        Log.d(TAG, "Starting inference. Context entries: ${cleanedContext.size}")
        Log.d(TAG, "SYSTEM PROMPT:\n$systemPrompt")
        Log.d(TAG, "USER QUERY: $userQuery")

        _lastDebugInfo.value = debugPrompt

        _currentStream.value = ""
        _isGenerating.value = true

        // Launch inference in a coroutine
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val config = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.7)
                )

                val conversation = ModelManager.createConversation(config)
                if (conversation == null) {
                    _currentStream.value = "Error: Could not create conversation."
                    _isGenerating.value = false
                    return@launch
                }

                conversation.use { conv ->
                    val fullResponse = StringBuilder()

                    conv.sendMessageAsync(userQuery)
                        .collect { message ->
                            val chunk = message.toString()
                            fullResponse.append(chunk)
                            _currentStream.value = fullResponse.toString().trim()
                        }

                    // Final cleanup
                    val rawResponse = fullResponse.toString()
                    val cleaned = cleanResponse(rawResponse)
                    _currentStream.value = cleaned

                    // Append debug info
                    _lastDebugInfo.value += "\n\n🔤 RAW RESPONSE:\n${rawResponse.take(500)}" +
                        "\n\n✅ CLEANED:\n${cleaned.take(300)}"

                    if (cleaned.isBlank()) {
                        _currentStream.value = "I couldn't generate a response. Please try again."
                    }

                    Log.d(TAG, "Inference finished. Raw: ${rawResponse.take(200)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference error", e)
                _currentStream.value = "Error: ${e.message}"
                _lastDebugInfo.value += "\n\n❌ ERROR: ${e.message}"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /**
     * Clean the model response — remove any leaked technical tokens.
     * Much simpler now since the new SDK handles chat templating properly.
     */
    private fun cleanResponse(rawResponse: String): String {
        var response = rawResponse.trim()

        // Cut off at any narration/meta markers the model might still produce
        val cutoffMarkers = listOf(
            "<tool_call", "<|tool_call", "Thinking Process:",
            "**Analysis:", "<|channel|",
            "Active App:", "Screen Content",
            "--- OCR Text ---", "--- Screen Content ---"
        )
        for (marker in cutoffMarkers) {
            val idx = response.indexOf(marker, ignoreCase = true)
            if (idx != -1) {
                response = response.substring(0, idx)
            }
        }

        // Remove leftover XML/HTML tags
        response = response
            .replace(Regex("<[^>]*>"), "")
            .trim()

        return response.trimStart('-', ' ', '\n', '>', '*', '|')
    }

    fun close() {
        _currentStream.value = ""
        _isGenerating.value = false
        _isModelLoaded.value = false
        _initStatus.value = "Closed"
        ModelManager.close()
    }
}
