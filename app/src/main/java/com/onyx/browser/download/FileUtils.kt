package com.onyx.browser.download

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

object FileUtils {

    /**
     * Determines whether a downloaded file actually exists on the filesystem or storage.
     * Safely handles content:// URIs, file:// URIs, raw filesystem paths, and Scoped Storage MediaStore queries.
     */
    fun doesFileExist(filePath: String?, context: Context? = null): Boolean {
        if (filePath.isNullOrBlank()) return false
        return try {
            if (filePath.startsWith("content://", ignoreCase = true)) {
                val uri = Uri.parse(filePath)
                context?.contentResolver?.openFileDescriptor(uri, "r")?.use { true } ?: false
            } else {
                val cleanPath = if (filePath.startsWith("file://", ignoreCase = true)) {
                    Uri.parse(filePath).path ?: filePath.removePrefix("file://")
                } else {
                    filePath
                }
                val file = File(cleanPath)
                if (file.exists()) {
                    true
                } else {
                    // Check if accessible via MediaStore on Android 10+ Scoped Storage
                    if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val projection = arrayOf(MediaStore.MediaColumns._ID)
                            val selection = "${MediaStore.MediaColumns.DATA} = ?"
                            val selectionArgs = arrayOf(cleanPath)
                            val queryUri = MediaStore.Files.getContentUri("external")
                            context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                                cursor.moveToFirst()
                            } ?: false
                        } catch (_: Exception) {
                            false
                        }
                    } else {
                        false
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
