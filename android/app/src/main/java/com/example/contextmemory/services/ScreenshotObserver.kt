package com.example.contextmemory.services

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log

class ScreenshotObserver(
    private val context: Context,
    handler: Handler = Handler(Looper.getMainLooper()),
    private val onScreenshotDetected: () -> Unit
) : ContentObserver(handler) {

    private val TAG = "ScreenshotObserver"

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        
        if (uri == null) return

        if (uri.toString().contains(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString())) {
            // Check the latest image in the MediaStore to see if it's a screenshot
            checkLatestImage()
        }
    }

    private fun checkLatestImage() {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    
                    val name = cursor.getString(nameColumn)
                    val path = cursor.getString(dataColumn)

                    if (name.lowercase().contains("screenshot") || path.lowercase().contains("screenshot")) {
                        Log.d(TAG, "Screenshot detected: $path")
                        processScreenshot(path)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore", e)
        }
    }

    private fun processScreenshot(path: String) {
        Log.d(TAG, "Processing screenshot for ContextMemory: $path")
        // Trigger the service to extract text from the current screen
        onScreenshotDetected()
    }

    fun register() {
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            this
        )
        Log.d(TAG, "ScreenshotObserver registered")
    }

    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
        Log.d(TAG, "ScreenshotObserver unregistered")
    }
}
