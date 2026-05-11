package com.example.contextmemory.db

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * A single memory entry. Stores the extracted text, source app, embedding vector,
 * timestamp, and an optional screenshot path.
 *
 * The screenshotPath is nullable for backward compatibility with existing data
 * and for entries captured via UI scraping (no screenshot).
 */
data class MemoryEntry(
    val text: String,
    val packageName: String,
    val vector: FloatArray,
    val timestamp: Long,
    val screenshotPath: String? = null
)

class MemoryStorage(private val context: Context) {
    private val TAG = "MemoryStorage"
    private val gson = Gson()
    private val mutex = Mutex()
    private val memoryFile = File(context.filesDir, "memory_store.json")
    
    private lateinit var embeddingModel: EmbeddingModel
    private val memoryStore = mutableListOf<MemoryEntry>()

    private val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    fun saveSyncedIp(ip: String) {
        prefs.edit().putString("paired_ip", ip).apply()
    }

    fun getSyncedIp(): String? {
        return prefs.getString("paired_ip", null)
    }

    suspend fun init() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                embeddingModel = EmbeddingModel(context)
                
                if (memoryFile.exists()) {
                    val json = memoryFile.readText()
                    val type = object : TypeToken<List<MemoryEntry>>() {}.type
                    val loaded: List<MemoryEntry>? = gson.fromJson(json, type)
                    if (loaded != null) {
                        memoryStore.clear()
                        memoryStore.addAll(loaded)
                        Log.d(TAG, "Loaded ${memoryStore.size} memories from storage.")
                    }
                }
                
                Log.d(TAG, "MemoryStorage initialized successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize MemoryStorage", e)
            }
        }
    }

    /**
     * Insert a memory entry with optional screenshot path.
     */
    suspend fun insertMemory(text: String, packageName: String, screenshotPath: String? = null) = withContext(Dispatchers.IO) {
        val vector = embeddingModel.generateEmbedding(text)
        val timestamp = System.currentTimeMillis()
        
        mutex.withLock {
            // Reload from file to get latest from other processes
            loadFromFile()
            
            memoryStore.add(MemoryEntry(text, packageName, vector, timestamp, screenshotPath))
            saveToFile()
            
            Log.d(TAG, "Inserted memory: [$packageName] screenshot=${screenshotPath != null}. Total: ${memoryStore.size}")
        }
    }

    /**
     * Search memory and return full MemoryEntry objects (including screenshot paths).
     * This is the primary search method used by the chat screen.
     */
    suspend fun searchMemoryEntries(query: String, limit: Int = 3): List<MemoryEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadFromFile()
            if (memoryStore.isEmpty()) return@withLock emptyList()

            // Re-embed any entries that have stale/dummy vectors (all same value)
            var needsSave = false
            for (i in memoryStore.indices) {
                val entry = memoryStore[i]
                val allSame = entry.vector.all { it == entry.vector[0] }
                if (allSame && entry.vector[0] != 0f) {
                    // This is a dummy vector from the old embedding model — re-embed
                    val newVector = embeddingModel.generateEmbedding(entry.text)
                    memoryStore[i] = entry.copy(vector = newVector)
                    needsSave = true
                }
            }
            if (needsSave) saveToFile()

            val queryVector = embeddingModel.generateEmbedding(query)
            val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
            Log.d(TAG, "Searching ${memoryStore.size} memories for words: $queryWords")

            val results = memoryStore.map { entry ->
                val cosineSim = cosineSimilarity(queryVector, entry.vector)
                
                // Bonus: word overlap between query and entry text
                val entryWords = entry.text.lowercase().split(Regex("\\s+")).toSet()
                val overlap = queryWords.count { qw -> entryWords.any { it.contains(qw) || qw.contains(it) } }
                val overlapBonus = overlap.toFloat() * 0.15f
                
                val totalScore = cosineSim + overlapBonus
                Pair(entry, totalScore)
            }
            .filter { it.second > 0.05f }  // Minimum similarity threshold
            .sortedByDescending { it.second }
            // Deduplicate: skip entries with very similar text
            .distinctBy { it.first.text.take(80).lowercase() }
            .take(limit)

            Log.d(TAG, "Top results: ${results.map { "score=${String.format("%.3f", it.second)} text=${it.first.text.take(50)}" }}")

            return@withLock results.map { it.first }
        }
    }

    /**
     * Export all entries for sync. Returns raw MemoryEntry list.
     */
    suspend fun getAllEntries(): List<MemoryEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadFromFile()
            memoryStore.toList()
        }
    }

    /**
     * Get entries after a given timestamp (for incremental sync).
     */
    suspend fun getEntriesAfter(timestamp: Long): List<MemoryEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadFromFile()
            memoryStore.filter { it.timestamp > timestamp }
        }
    }

    /**
     * Import a synced entry from another device.
     * Re-embeds text locally and deduplicates by timestamp (5-second window).
     */
    suspend fun insertSyncedEntry(
        text: String,
        packageName: String,
        timestamp: Long,
        screenshotPath: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadFromFile()

            // Dedup: skip if we already have an entry within 5 seconds with similar text
            val isDuplicate = memoryStore.any { entry ->
                Math.abs(entry.timestamp - timestamp) < 5000 &&
                entry.text.take(80).equals(text.take(80), ignoreCase = true)
            }
            if (isDuplicate) {
                Log.d(TAG, "Skipping duplicate synced entry: ${text.take(50)}")
                return@withContext false
            }

            val vector = embeddingModel.generateEmbedding(text)
            memoryStore.add(MemoryEntry(text, packageName, vector, timestamp, screenshotPath))
            saveToFile()
            Log.d(TAG, "Imported synced entry: ${text.take(50)}")
            return@withContext true
        }
    }

    /**
     * Legacy method that returns only text strings.
     * Kept for backward compatibility.
     */
    suspend fun searchMemory(query: String, limit: Int = 3): List<String> {
        return searchMemoryEntries(query, limit).map { it.text }
    }

    private fun loadFromFile() {
        try {
            if (memoryFile.exists()) {
                val json = memoryFile.readText()
                val type = object : TypeToken<List<MemoryEntry>>() {}.type
                val loaded: List<MemoryEntry>? = gson.fromJson(json, type)
                if (loaded != null) {
                    memoryStore.clear()
                    memoryStore.addAll(loaded)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading from file", e)
        }
    }

    private fun saveToFile() {
        try {
            val json = gson.toJson(memoryStore)
            memoryFile.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to file", e)
        }
    }

    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        return if (normA == 0f || normB == 0f) 0f else (dotProduct / (sqrt(normA) * sqrt(normB)))
    }

    fun close() {
        embeddingModel.close()
    }
}
