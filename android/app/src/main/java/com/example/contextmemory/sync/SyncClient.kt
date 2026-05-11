package com.example.contextmemory.sync

import android.util.Log
import com.example.contextmemory.db.MemoryStorage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HTTP client that connects to a remote ContextMemory device (PC or another Android)
 * and performs bidirectional sync.
 */
class SyncClient(private val memoryStorage: MemoryStorage) {
    private val TAG = "SyncClient"
    private val gson = Gson()
    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 60_000
        }
    }

    data class SyncResult(
        val success: Boolean,
        val imported: Int = 0,
        val exported: Int = 0,
        val skipped: Int = 0,
        val error: String? = null
    )

    /**
     * Perform a full bidirectional sync with the device at the given IP and port.
     */
    suspend fun syncWith(ip: String, port: Int = 8473): SyncResult = withContext(Dispatchers.IO) {
        try {
            val baseUrl = "http://$ip:$port"

            // 1. Check remote status
            Log.d(TAG, "Connecting to $baseUrl...")
            val statusResp = client.get("$baseUrl/sync/status")
            if (statusResp.status != HttpStatusCode.OK) {
                return@withContext SyncResult(false, error = "Remote device returned ${statusResp.status}")
            }
            val statusJson = gson.fromJson(statusResp.bodyAsText(), Map::class.java)
            Log.d(TAG, "Connected to: ${statusJson["deviceName"]} (${statusJson["deviceType"]}), ${statusJson["entryCount"]} entries")

            // 2. Pull: Get remote entries and import them locally
            val remoteEntriesResp = client.get("$baseUrl/sync/entries")
            val remoteBody = remoteEntriesResp.bodyAsText()
            val remoteData = gson.fromJson(remoteBody, SyncPayload::class.java)

            var imported = 0
            var skipped = 0
            for (entry in remoteData.entries) {
                val success = memoryStorage.insertSyncedEntry(
                    text = entry.text,
                    packageName = entry.packageName,
                    timestamp = entry.timestamp,
                    screenshotPath = null
                )
                if (success) imported++ else skipped++
            }
            Log.d(TAG, "Pull complete: $imported imported, $skipped skipped")

            // 3. Push: Send our entries to the remote device
            val localEntries = memoryStorage.getAllEntries()
            val exportPayload = SyncPayload(
                entries = localEntries.map { entry ->
                    SyncEntry(
                        text = entry.text,
                        packageName = entry.packageName,
                        timestamp = entry.timestamp,
                        screenshotFilename = entry.screenshotPath?.let { java.io.File(it).name } ?: "",
                        sourceDevice = "android"
                    )
                }
            )

            val pushResp = client.post("$baseUrl/sync/entries") {
                contentType(ContentType.Application.Json)
                setBody(gson.toJson(exportPayload))
            }
            val pushResult = gson.fromJson(pushResp.bodyAsText(), Map::class.java)
            val exported = (pushResult["imported"] as? Double)?.toInt() ?: 0
            Log.d(TAG, "Push complete: $exported accepted by remote")

            return@withContext SyncResult(
                success = true,
                imported = imported,
                exported = exported,
                skipped = skipped
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            return@withContext SyncResult(false, error = e.message ?: "Unknown error")
        }
    }

    /**
     * Check if a remote device is reachable.
     */
    suspend fun checkDevice(ip: String, port: Int = 8473): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            val resp = client.get("http://$ip:$port/sync/status")
            if (resp.status == HttpStatusCode.OK) {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                gson.fromJson<Map<String, Any>>(resp.bodyAsText(), type)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        client.close()
    }
}
