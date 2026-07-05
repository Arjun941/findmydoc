package com.example.core.indexing

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MediaStoreObserver(
    private val context: Context,
    private val onMediaStoreChanged: () -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val tag = "MediaStoreObserver"
    private var isRegistered = false

    fun register() {
        if (isRegistered) return
        try {
            val externalUri = MediaStore.Files.getContentUri("external")
            context.contentResolver.registerContentObserver(
                externalUri,
                true, // descendents (recursive observation)
                this
            )
            isRegistered = true
            Log.d(tag, "Registered MediaStore content observer on $externalUri")
        } catch (e: Exception) {
            Log.e(tag, "Failed to register MediaStore content observer", e)
        }
    }

    fun unregister() {
        if (!isRegistered) return
        try {
            context.contentResolver.unregisterContentObserver(this)
            isRegistered = false
            Log.d(tag, "Unregistered MediaStore content observer")
        } catch (e: Exception) {
            Log.e(tag, "Failed to unregister MediaStore content observer", e)
        }
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        onChange(selfChange, null)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        Log.d(tag, "Change detected in MediaStore. Uri: $uri")
        // Run incremental indexing task
        CoroutineScope(Dispatchers.Main).launch {
            onMediaStoreChanged()
        }
    }
}
