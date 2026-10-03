package com.onyx.browser.download

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

object FileUtils {

    /**
     * Resolves the safest and most accurate MIME type for a file download.
     * Prevents Android MediaStore from corrupting filenames (such as appending .txt to .md, .json, or code files)
     * when web servers return generic "text/plain" or "application/octet-stream" headers.
     */
    fun resolveMimeTypeForDownload(fileName: String, serverMime: String?): String {
        val cleanMime = serverMime?.substringBefore(';')?.trim()?.lowercase() ?: ""
        val extension = fileName.substringAfterLast('.', "").lowercase()

        // 1. If extension is empty, use server MIME or octet-stream
        if (extension.isEmpty()) {
            return if (cleanMime.isNotBlank() && cleanMime != "*/*") cleanMime else "application/octet-stream"
        }

        // 2. Explicit extension-to-MIME overrides for types commonly misreported by servers
        when (extension) {
            "apk" -> return "application/vnd.android.package-archive"
            "md", "markdown", "mdown", "mkd" -> return "text/markdown"
            "mht", "mhtml" -> return "multipart/related"
            "json" -> return "application/json"
            "xml" -> return "text/xml"
            "csv" -> return "text/csv"
            "js" -> return "application/javascript"
            "css" -> return "text/css"
            "svg" -> return "image/svg+xml"
            "pdf" -> return "application/pdf"
            "txt", "log" -> return "text/plain"
            "yaml", "yml" -> return "text/yaml"
            "html", "htm" -> return "text/html"
            "epub" -> return "application/epub+zip"
            "zip" -> return "application/zip"
            "tar" -> return "application/x-tar"
            "gz" -> return "application/gzip"
            "7z" -> return "application/x-7z-compressed"
        }

        // 3. System MimeTypeMap lookup based on file extension
        val extMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        if (!extMime.isNullOrBlank()) {
            // If server sent generic text/plain, octet-stream, or wildcard, prefer the specific extension MIME
            if (cleanMime.isBlank() || cleanMime == "text/plain" || cleanMime == "application/octet-stream" || cleanMime == "*/*") {
                return extMime
            }
            return cleanMime
        }

        // 4. For developer/code extensions not recognized by Android MimeTypeMap (e.g. .kt, .py, .rs, .go, .c, .cpp, .sh):
        // If server sent text/plain, MediaStore MediaProvider will force-append .txt to the file!
        // Returning "application/octet-stream" stops MediaStore from altering the file extension.
        if (cleanMime == "text/plain") {
            return "application/octet-stream"
        }

        return if (cleanMime.isNotBlank() && cleanMime != "*/*") cleanMime else "application/octet-stream"
    }

    /**
     * Resolves the actual, accessible filesystem path or URI for a file.
     * Handles:
     * 1. Direct file existence
     * 2. Android MediaStore Scoped Storage renaming (e.g. .md.txt fallback)
     * 3. Reverse .txt removal fallback
     * 4. MediaStore queries on Android 10+
     */
    fun resolveExistingPath(filePath: String?, context: Context? = null): String? {
        if (filePath.isNullOrBlank()) return null
        if (filePath.startsWith("content://", ignoreCase = true)) {
            val uri = Uri.parse(filePath)
            val accessible = try {
                context?.contentResolver?.openFileDescriptor(uri, "r")?.use { true } ?: false
            } catch (_: Exception) { false }
            return if (accessible) filePath else null
        }

        val cleanPath = if (filePath.startsWith("file://", ignoreCase = true)) {
            Uri.parse(filePath).path ?: filePath.removePrefix("file://")
        } else {
            filePath
        }

        // 1. Exact match on disk
        val directFile = File(cleanPath)
        if (directFile.exists()) return directFile.absolutePath

        // 2. Fallback check for .txt extension appended by MediaStore (e.g. README.md -> README.md.txt)
        val txtAppended = File("$cleanPath.txt")
        if (txtAppended.exists()) return txtAppended.absolutePath

        // 3. Fallback check if cleanPath ended in .txt but on disk it has no .txt
        if (cleanPath.endsWith(".txt", ignoreCase = true)) {
            val withoutTxt = File(cleanPath.substringBeforeLast(".txt"))
            if (withoutTxt.exists()) return withoutTxt.absolutePath
        }

        // 4. MediaStore check for Scoped Storage (Android 10+)
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val candidates = mutableListOf(cleanPath, "$cleanPath.txt")
                if (cleanPath.endsWith(".txt", ignoreCase = true)) {
                    candidates.add(cleanPath.substringBeforeLast(".txt"))
                }
                for (candidate in candidates) {
                    val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
                    val selection = "${MediaStore.MediaColumns.DATA} = ?"
                    val selectionArgs = arrayOf(candidate)
                    val queryUri = MediaStore.Files.getContentUri("external")
                    context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                            if (dataIdx != -1) {
                                val realData = cursor.getString(dataIdx)
                                if (!realData.isNullOrBlank() && File(realData).exists()) {
                                    return realData
                                }
                            }
                            return candidate
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return null
    }

    /**
     * Determines whether a downloaded file actually exists on the filesystem or storage.
     * Safely handles content:// URIs, file:// URIs, raw filesystem paths, Scoped Storage MediaStore queries,
     * and automatic .txt extension reconciliation.
     */
    fun doesFileExist(filePath: String?, context: Context? = null): Boolean {
        return resolveExistingPath(filePath, context) != null
    }
}
