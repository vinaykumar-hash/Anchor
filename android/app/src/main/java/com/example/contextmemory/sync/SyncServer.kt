package com.example.contextmemory.sync

import android.content.Context
import android.util.Log
import com.example.contextmemory.db.MemoryStorage
import com.google.gson.Gson
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.io.File

/**
 * Embedded HTTP server for cross-device sync.
 * Runs on port 8473 and exposes REST endpoints for syncing memory entries.
 */
class SyncServer(
    private val context: Context,
    private val memoryStorage: MemoryStorage
) {
    private val TAG = "SyncServer"
    private val PORT = 8473
    private val gson = Gson()
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var lastSyncTime: Long = 0L
        private set

    fun start() {
        scope.launch {
            try {
                server = embeddedServer(CIO, port = PORT) {
                    install(ContentNegotiation) {
                        gson()
                    }
                    routing {
                        // Device status
                        get("/sync/status") {
                            val entries = memoryStorage.getAllEntries()
                            call.respond(mapOf(
                                "deviceName" to android.os.Build.MODEL,
                                "deviceType" to "android",
                                "entryCount" to entries.size,
                                "lastSyncTime" to lastSyncTime,
                                "port" to PORT
                            ))
                        }

                        // Manifest: list of all entry timestamps + text hashes for diffing
                        get("/sync/manifest") {
                            val entries = memoryStorage.getAllEntries()
                            val manifest = entries.map { entry ->
                                mapOf(
                                    "timestamp" to entry.timestamp,
                                    "textHash" to entry.text.take(80).hashCode(),
                                    "packageName" to entry.packageName
                                )
                            }
                            call.respond(mapOf("entries" to manifest))
                        }

                        // Export entries (optionally after a timestamp for incremental sync)
                        get("/sync/entries") {
                            val afterParam = call.request.queryParameters["after"]
                            val after = afterParam?.toLongOrNull() ?: 0L
                            val entries = if (after > 0) {
                                memoryStorage.getEntriesAfter(after)
                            } else {
                                memoryStorage.getAllEntries()
                            }
                            val exportData = entries.map { entry ->
                                mapOf(
                                    "text" to entry.text,
                                    "packageName" to entry.packageName,
                                    "timestamp" to entry.timestamp,
                                    "screenshotFilename" to (entry.screenshotPath?.let { File(it).name } ?: ""),
                                    "sourceDevice" to "android"
                                )
                            }
                            call.respond(mapOf("entries" to exportData))
                        }

                        // Import entries from another device
                        post("/sync/entries") {
                            try {
                                val body = call.receiveText()
                                val data = gson.fromJson(body, SyncPayload::class.java)
                                var imported = 0
                                var skipped = 0

                                for (entry in data.entries) {
                                    val success = memoryStorage.insertSyncedEntry(
                                        text = entry.text,
                                        packageName = entry.packageName,
                                        timestamp = entry.timestamp,
                                        screenshotPath = null // Screenshots are transferred separately
                                    )
                                    if (success) imported++ else skipped++
                                }

                                lastSyncTime = System.currentTimeMillis()
                                call.respond(mapOf(
                                    "status" to "success",
                                    "imported" to imported,
                                    "skipped" to skipped
                                ))
                                Log.d(TAG, "Sync import: $imported imported, $skipped skipped")
                            } catch (e: Exception) {
                                Log.e(TAG, "Sync import failed", e)
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to (e.message ?: "Unknown error"))
                                )
                            }
                        }

                        // Serve a screenshot file on demand
                        get("/sync/screenshot/{filename}") {
                            val filename = call.parameters["filename"] ?: return@get call.respond(
                                HttpStatusCode.BadRequest, "Missing filename"
                            )
                            // Look in the app's screenshots directory
                            val screenshotsDir = File(context.filesDir, "screenshots")
                            val file = File(screenshotsDir, filename)
                            if (file.exists()) {
                                call.respondFile(file)
                            } else {
                                call.respond(HttpStatusCode.NotFound, "Screenshot not found")
                            }
                        }
                    }
                }
                server?.start(wait = false)
                Log.d(TAG, "Sync server started on port $PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start sync server", e)
            }
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        scope.cancel()
        Log.d(TAG, "Sync server stopped")
    }

    fun getPort() = PORT
}

/** Data class for deserializing incoming sync payloads. */
data class SyncEntry(
    val text: String = "",
    val packageName: String = "",
    val timestamp: Long = 0L,
    val screenshotFilename: String = "",
    val sourceDevice: String = ""
)

data class SyncPayload(
    val entries: List<SyncEntry> = emptyList()
)
