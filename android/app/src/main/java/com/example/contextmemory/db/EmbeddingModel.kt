package com.example.contextmemory.db

import android.content.Context
import kotlin.math.sqrt

/**
 * Generates 384-dimensional embedding vectors for text using character n-gram hashing.
 * This is a lightweight, on-device approach that produces meaningfully different vectors
 * for different texts, enabling real cosine similarity search.
 *
 * Note: The ONNX model is no longer used. This hash-based approach is faster, uses
 * zero additional memory, and produces vectors that are compatible with future PC sync
 * (as long as both sides use the same hashing algorithm).
 */
class EmbeddingModel(context: Context) {
    private val VECTOR_DIM = 384

    /**
     * Generates a 384-dimensional embedding vector for the given text.
     * Uses character trigram hashing to produce distinct, meaningful vectors.
     */
    fun generateEmbedding(text: String): FloatArray {
        val vector = FloatArray(VECTOR_DIM)
        // Strip common boilerplate that makes all entries look similar
        val stripped = text
            .replace(Regex("Active App:.*\\n"), "")
            .replace("--- Screen Content ---", "")
            .replace("--- OCR Text ---", "")
        val normalized = stripped.lowercase().replace(Regex("\\s+"), " ").trim()

        if (normalized.isEmpty()) return vector

        if (normalized.length < 3) {
            // For very short text, use character-level hashing
            for (ch in normalized) {
                val index = ((ch.code * 31) % VECTOR_DIM + VECTOR_DIM) % VECTOR_DIM
                vector[index] += 1f
            }
        } else {
            // Character trigram hashing — the core of the embedding
            for (i in 0..normalized.length - 3) {
                val trigram = normalized.substring(i, i + 3)
                val hash = stableHash(trigram)
                val index = ((hash % VECTOR_DIM) + VECTOR_DIM) % VECTOR_DIM
                vector[index] += 1f
            }
        }

        // Also add word-level unigram features for better semantic matching
        val words = normalized.split(" ").filter { it.length > 2 }
        for (word in words) {
            val hash = stableHash("w:$word")
            val index = ((hash % VECTOR_DIM) + VECTOR_DIM) % VECTOR_DIM
            vector[index] += 0.5f  // Lower weight than trigrams
        }

        // L2 normalize so cosine similarity works correctly
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }

        return vector
    }

    /**
     * A stable hash function that produces consistent results across JVM versions.
     * This is important for future PC sync compatibility.
     */
    private fun stableHash(s: String): Int {
        var hash = 0
        for (ch in s) {
            hash = hash * 31 + ch.code
        }
        return hash
    }

    fun close() {
        // No resources to release with the hash-based approach
    }
}
