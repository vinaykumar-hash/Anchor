package com.example.contextmemory.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Helper to download the Gemma LiteRT-LM model file (~1.4 GB) directly from a Hugging Face LFS repo.
 * Reports real-time download progress to the Compose UI.
 */
object ModelDownloader {
    private const val TAG = "ModelDownloader"
    
    // Configurable Hugging Face model URL.
    var modelUrl = "https://huggingface.co/vinay7525/Gemma4_e2b_it_litertlm/resolve/main/gemma-4-E2B-it.litertlm"

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError

    /**
     * Download the model from [modelUrl] and save it to the internal app files directory.
     * Automatically handles redirects (which are required for Hugging Face LFS).
     */
    suspend fun downloadModel(context: Context, modelFileName: String = "gemma-4-E2B-it.litertlm"): Boolean = withContext(Dispatchers.IO) {
        _isDownloading.value = true
        _downloadProgress.value = 0f
        _downloadError.value = null

        val destFile = File(context.filesDir, modelFileName)
        val tempFile = File(context.cacheDir, "$modelFileName.tmp")

        try {
            Log.d(TAG, "Starting download from $modelUrl")
            var url = URL(modelUrl)
            var connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 600000 // 10 minutes for large downloads
            connection.instanceFollowRedirects = true

            var status = connection.responseCode
            var redirectCount = 0
            
            // Hugging Face points LFS resolves to Cloudfront/S3 and returns a redirect.
            // We manually follow redirects to ensure the download channel remains active.
            while (status == HttpURLConnection.HTTP_MOVED_TEMP || 
                   status == HttpURLConnection.HTTP_MOVED_PERM || 
                   status == HttpURLConnection.HTTP_SEE_OTHER) {
                if (redirectCount > 8) throw Exception("Too many redirects")
                val newUrl = connection.getHeaderField("Location") ?: throw Exception("Redirect location not found")
                Log.d(TAG, "Redirected to: $newUrl")
                url = URL(newUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 600000
                connection.instanceFollowRedirects = true
                status = connection.responseCode
                redirectCount++
            }

            if (status != HttpURLConnection.HTTP_OK) {
                throw Exception("Server returned HTTP status $status")
            }

            val contentLength = connection.contentLengthLong
            Log.d(TAG, "Content Length: $contentLength")

            BufferedInputStream(connection.inputStream).use { input ->
                tempFile.outputStream().use { output ->
                    val data = ByteArray(8192)
                    var totalRead = 0L
                    var count: Int
                    while (input.read(data).also { count = it } != -1) {
                        output.write(data, 0, count)
                        totalRead += count
                        if (contentLength > 0) {
                            _downloadProgress.value = totalRead.toFloat() / contentLength.toFloat()
                        }
                    }
                }
            }

            // Move temp file to the final app files directory
            if (destFile.exists()) {
                destFile.delete()
            }
            
            // Ensure parent directories exist
            destFile.parentFile?.mkdirs()
            
            if (!tempFile.renameTo(destFile)) {
                // Fallback copy if rename fails
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }

            Log.d(TAG, "Model download completed successfully!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading model", e)
            _downloadError.value = e.message ?: "Unknown download error"
            if (tempFile.exists()) {
                tempFile.delete()
            }
            false
        } finally {
            _isDownloading.value = false
        }
    }
}
