package com.onyx.browser.web

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import com.onyx.browser.MainActivity
import androidx.fragment.app.FragmentActivity
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.downloads.DownloadPromptActivity
import com.onyx.browser.ui.downloads.DownloadPromptBottomSheet
import com.onyx.browser.ui.downloads.ExternalDownloaderHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import android.app.NotificationManager
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder

object DownloadHandler {

    fun sanitizeFileName(name: String): String {
        val clean = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return if (clean.isBlank()) "download_${System.currentTimeMillis()}" else clean
    }

    fun handleDownload(
        activity: Activity,
        coroutineScope: CoroutineScope,
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long,
        cookies: String = "",
        referer: String = ""
    ) {
        if (url.startsWith("data:", ignoreCase = true)) {
            handleDataUriDownload(activity, coroutineScope, url, contentDisposition, mimeType)
            return
        }

        if (url.startsWith("blob:", ignoreCase = true)) {
            handleBlobUriDownload(activity, coroutineScope, url, contentDisposition, mimeType, referer)
            return
        }

        val resolvedCookies = if (cookies.isNotBlank()) cookies else {
            try {
                CookieManager.getInstance().getCookie(url) ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        val preferences = BrowserPreferences.getInstance(activity)
        val behavior = preferences.downloadManagerBehavior

        if (behavior == 0) {
            // Ask before download
            if (activity is FragmentActivity) {
                val sheet = DownloadPromptBottomSheet.newInstance(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLength = contentLength,
                    cookies = resolvedCookies,
                    referer = referer
                )
                sheet.show(activity.supportFragmentManager, DownloadPromptBottomSheet.TAG)
            } else {
                val intent = Intent(activity, DownloadPromptActivity::class.java).apply {
                    putExtra(DownloadPromptActivity.EXTRA_URL, url)
                    putExtra(DownloadPromptActivity.EXTRA_USER_AGENT, userAgent)
                    putExtra(DownloadPromptActivity.EXTRA_CONTENT_DISPOSITION, contentDisposition)
                    putExtra(DownloadPromptActivity.EXTRA_MIME_TYPE, mimeType)
                    putExtra(DownloadPromptActivity.EXTRA_CONTENT_LENGTH, contentLength)
                    putExtra(DownloadPromptActivity.EXTRA_COOKIES, resolvedCookies)
                    putExtra(DownloadPromptActivity.EXTRA_REFERER, referer)
                }
                activity.startActivity(intent)
            }
        } else if (behavior == 1) {
            // Internal download
            val rawFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val fileName = sanitizeFileName(rawFileName)
            startSystemDownload(
                context = activity,
                coroutineScope = coroutineScope,
                url = url,
                userAgent = userAgent,
                fileName = fileName,
                mimeType = mimeType,
                contentLength = contentLength,
                cookies = resolvedCookies,
                referer = referer
            )
        } else {
            // External download manager
            val rawFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val fileName = sanitizeFileName(rawFileName)
            dispatchToExternalDownloader(
                context = activity,
                url = url,
                mimeType = mimeType,
                userAgent = userAgent,
                fileName = fileName,
                cookies = resolvedCookies,
                referer = referer
            )
        }
    }

    fun handleDataUriDownload(
        context: Context,
        coroutineScope: CoroutineScope,
        dataUri: String,
        contentDisposition: String = "",
        mimeType: String = "",
        suggestedFileName: String? = null
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val commaIndex = dataUri.indexOf(',')
                if (commaIndex == -1) throw IllegalArgumentException("Invalid data URI")

                val header = dataUri.substring(5, commaIndex)
                val isBase64 = header.contains(";base64", ignoreCase = true)
                val detectedMime = header.substringBefore(';').ifBlank { mimeType.ifBlank { "application/octet-stream" } }

                val rawData = dataUri.substring(commaIndex + 1)
                val bytes = if (isBase64) {
                    Base64.decode(rawData, Base64.DEFAULT)
                } else {
                    URLDecoder.decode(rawData, "UTF-8").toByteArray()
                }

                val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(detectedMime) ?: "bin"
                val resolvedName = if (!suggestedFileName.isNullOrBlank()) {
                    suggestedFileName
                } else {
                    val guessed = URLUtil.guessFileName(dataUri, contentDisposition, detectedMime)
                    if (guessed.isNotBlank() && !guessed.endsWith(".bin")) guessed else "download_${System.currentTimeMillis()}.$ext"
                }
                val fileName = sanitizeFileName(resolvedName)

                val savedPath: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, detectedMime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        ?: throw IllegalStateException("Failed to create MediaStore entry")
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(bytes)
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)

                    val publicFile = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        fileName
                    )
                    if (publicFile.exists()) publicFile.absolutePath else uri.toString()
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    val targetFile = File(dir, fileName)
                    FileOutputStream(targetFile).use { it.write(bytes) }
                    try {
                        MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf(detectedMime), null)
                    } catch (_: Exception) {}
                    targetFile.absolutePath
                }

                val downloadId = System.currentTimeMillis()
                val database = AppDatabase.getInstance(context)
                database.downloadDao().insertDownload(
                    DownloadItem(
                        id = downloadId,
                        url = "data:$detectedMime;base64,...",
                        fileName = fileName,
                        filePath = savedPath,
                        mimeType = detectedMime,
                        fileSize = bytes.size.toLong(),
                        downloadedBytes = bytes.size.toLong(),
                        status = DownloadItem.STATUS_COMPLETED
                    )
                )

                try {
                    val completedTask = com.onyx.browser.download.DownloadTask(
                        id = downloadId,
                        url = "data:$detectedMime",
                        fileName = fileName,
                        mimeType = detectedMime,
                        userAgent = "",
                        tempFilePath = "",
                        finalFilePath = savedPath,
                        totalBytes = bytes.size.toLong(),
                        status = com.onyx.browser.download.DownloadTask.STATUS_COMPLETED
                    )
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    val notif = com.onyx.browser.download.DownloadNotificationHelper.buildCompletedNotification(context, completedTask)
                    nm?.notify(completedTask.notificationId, notif)
                } catch (_: Exception) {}

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download complete: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Data download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun extractFileNameFromPageUrl(pageUrl: String): String? {
        if (pageUrl.isBlank()) return null
        try {
            val uri = Uri.parse(pageUrl)
            val path = uri.path ?: return null
            val segments = path.split('/').filter { it.isNotBlank() }
            if (segments.isNotEmpty()) {
                val last = segments.last()
                if (last.contains('.') && !last.endsWith(".html", ignoreCase = true) && !last.endsWith(".php", ignoreCase = true)) {
                    return sanitizeFileName(last)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun resolveFallbackUrl(pageUrl: String, fileName: String): String? {
        if (pageUrl.isBlank()) return null
        try {
            // 1. GitHub blob view: https://github.com/owner/repo/blob/branch/path/to/file
            val ghBlobRegex = Regex("""^https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$""", RegexOption.IGNORE_CASE)
            val ghBlobMatch = ghBlobRegex.find(pageUrl)
            if (ghBlobMatch != null) {
                val (owner, repo, branch, path) = ghBlobMatch.destructured
                return "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
            }

            // 2. GitHub repo root or tree: https://github.com/owner/repo or /tree/branch
            val ghRepoRegex = Regex("""^https?://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+))?/?$""", RegexOption.IGNORE_CASE)
            val ghRepoMatch = ghRepoRegex.find(pageUrl)
            if (ghRepoMatch != null && fileName.isNotBlank() && !fileName.endsWith(".bin") && !fileName.matches(Regex("^[0-9a-fA-F-]{36}.*"))) {
                val (owner, repo, branch) = ghRepoMatch.destructured
                val b = if (branch.isNotBlank()) branch else "HEAD"
                return "https://raw.githubusercontent.com/$owner/$repo/$b/$fileName"
            }

            // 3. GitLab blob view: https://gitlab.com/owner/repo/-/blob/branch/path
            val glBlobRegex = Regex("""^https?://gitlab\.com/([^/]+)/([^/]+)/-/blob/([^/]+)/(.+)$""", RegexOption.IGNORE_CASE)
            val glBlobMatch = glBlobRegex.find(pageUrl)
            if (glBlobMatch != null) {
                val (owner, repo, branch, path) = glBlobMatch.destructured
                return "https://gitlab.com/$owner/$repo/-/raw/$branch/$path"
            }

            // 4. Bitbucket src: https://bitbucket.org/owner/repo/src/branch/path
            val bbRegex = Regex("""^https?://bitbucket\.org/([^/]+)/([^/]+)/src/([^/]+)/(.+)$""", RegexOption.IGNORE_CASE)
            val bbMatch = bbRegex.find(pageUrl)
            if (bbMatch != null) {
                val (owner, repo, branch, path) = bbMatch.destructured
                return "https://bitbucket.org/$owner/$repo/raw/$branch/$path"
            }

            // 5. Codeberg / Gitea: https://codeberg.org/owner/repo/src/branch/branch/path
            val giteaRegex = Regex("""^https?://([^/]+)/([^/]+)/([^/]+)/src/branch/([^/]+)/(.+)$""", RegexOption.IGNORE_CASE)
            val giteaMatch = giteaRegex.find(pageUrl)
            if (giteaMatch != null) {
                val (host, owner, repo, branch, path) = giteaMatch.destructured
                return "https://$host/$owner/$repo/raw/branch/$branch/$path"
            }
        } catch (_: Exception) {}
        return null
    }

    fun handleBlobFallback(
        context: Context,
        coroutineScope: CoroutineScope,
        error: String,
        blobUrl: String,
        fileName: String,
        mimeType: String,
        pageUrl: String
    ) {
        val fallbackUrl = resolveFallbackUrl(pageUrl, fileName)
        if (fallbackUrl != null) {
            val resolvedName = sanitizeFileName(
                if (fileName.isNotBlank() && !fileName.endsWith(".bin")) fileName
                else (extractFileNameFromPageUrl(pageUrl) ?: "download")
            )
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Downloading from source: $resolvedName", Toast.LENGTH_SHORT).show()
            }
            startSystemDownload(
                context = context,
                coroutineScope = coroutineScope,
                url = fallbackUrl,
                userAgent = "",
                fileName = resolvedName,
                mimeType = mimeType.ifBlank { "application/octet-stream" },
                contentLength = -1L,
                referer = pageUrl
            )
            return
        }

        Handler(Looper.getMainLooper()).post {
            val userMsg = if (error.contains("Failed to fetch", ignoreCase = true)) {
                "Unable to fetch generated blob file from webpage."
            } else {
                "Blob download failed: $error"
            }
            Toast.makeText(context, userMsg, Toast.LENGTH_LONG).show()
        }
    }

    fun handleBlobUriDownload(
        activity: Activity,
        coroutineScope: CoroutineScope,
        blobUrl: String,
        contentDisposition: String = "",
        mimeType: String = "",
        referer: String = ""
    ) {
        val mainAct = activity as? MainActivity
        val webView = mainAct?.getActiveWebView()
        if (webView == null) {
            Toast.makeText(activity, "Cannot download blob without active webpage", Toast.LENGTH_SHORT).show()
            return
        }

        val pageUrl = referer.ifBlank { webView.url ?: "" }
        val guessedFileName = URLUtil.guessFileName(blobUrl, contentDisposition, mimeType)
        val initialFileName = if (guessedFileName.isNotBlank() && !guessedFileName.endsWith(".bin") && !guessedFileName.matches(Regex("^[0-9a-fA-F-]{36}.*"))) {
            sanitizeFileName(guessedFileName)
        } else {
            extractFileNameFromPageUrl(pageUrl) ?: "download_${System.currentTimeMillis()}"
        }

        val escapedBlobUrl = blobUrl.replace("'", "\\'")
        val escapedFileName = initialFileName.replace("'", "\\'")
        val escapedMimeType = mimeType.replace("'", "\\'")
        val escapedPageUrl = pageUrl.replace("'", "\\'")

        val script = """
            (function() {
                var blobUrl = '$escapedBlobUrl';
                var defaultFileName = '$escapedFileName';
                var defaultMime = '$escapedMimeType';
                var pageUrl = '$escapedPageUrl';

                function sendSuccess(dataUrl, resolvedName, resolvedMime) {
                    if (window.OnyxBlobBridge && window.OnyxBlobBridge.onBlobDownloaded) {
                        window.OnyxBlobBridge.onBlobDownloaded(
                            dataUrl,
                            resolvedName || defaultFileName,
                            resolvedMime || defaultMime || 'application/octet-stream'
                        );
                    }
                }

                function sendFailed(err) {
                    var msg = (err && err.message) ? err.message : String(err);
                    if (window.OnyxBlobBridge && window.OnyxBlobBridge.onBlobFailedWithContext) {
                        window.OnyxBlobBridge.onBlobFailedWithContext(
                            msg,
                            blobUrl,
                            defaultFileName,
                            defaultMime,
                            pageUrl
                        );
                    } else if (window.OnyxBlobBridge && window.OnyxBlobBridge.onBlobFailed) {
                        window.OnyxBlobBridge.onBlobFailed(msg);
                    }
                }

                function resolveBestFileName(entry) {
                    if (entry && entry.name && entry.name.length > 0) return entry.name;
                    if (window.__onyxLastBlobDownload && window.__onyxLastBlobDownload.fileName) {
                        var diff = Date.now() - (window.__onyxLastBlobDownload.time || 0);
                        if (diff < 60000 && window.__onyxLastBlobDownload.fileName.length > 0) {
                            return window.__onyxLastBlobDownload.fileName;
                        }
                    }
                    return defaultFileName;
                }

                // ── Tier 1: In-Memory Blob Store (Zero network, bypasses CSP and revoked URLs) ──
                try {
                    if (window.__onyxBlobStore && window.__onyxBlobStore.has(blobUrl)) {
                        var entry = window.__onyxBlobStore.get(blobUrl);
                        if (entry && entry.blob) {
                            var bestName = resolveBestFileName(entry);
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                if (reader.result) {
                                    sendSuccess(reader.result, bestName, entry.type || defaultMime);
                                } else {
                                    tryFetchTier();
                                }
                            };
                            reader.onerror = function() {
                                tryFetchTier();
                            };
                            reader.readAsDataURL(entry.blob);
                            return;
                        }
                    }
                } catch(e) {}

                // ── Tier 2: window.fetch() ──
                function tryFetchTier() {
                    try {
                        var fetchPromise;
                        if (typeof window._origFetch === 'function') {
                            fetchPromise = window._origFetch(blobUrl);
                        } else {
                            fetchPromise = fetch(blobUrl);
                        }
                        fetchPromise.then(function(res) {
                            if (!res.ok && res.status !== 0) throw new Error('HTTP ' + res.status);
                            return res.blob();
                        }).then(function(blob) {
                            var bestName = resolveBestFileName({ name: null });
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                if (reader.result) {
                                    sendSuccess(reader.result, bestName, blob.type || defaultMime);
                                } else {
                                    tryXhrTier();
                                }
                            };
                            reader.onerror = function() {
                                tryXhrTier();
                            };
                            reader.readAsDataURL(blob);
                        }).catch(function(err) {
                            tryXhrTier();
                        });
                    } catch(e) {
                        tryXhrTier();
                    }
                }

                // ── Tier 3: XMLHttpRequest (responseType = 'blob') ──
                function tryXhrTier() {
                    try {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', blobUrl, true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            if ((xhr.status === 200 || xhr.status === 0) && xhr.response) {
                                var bestName = resolveBestFileName({ name: null });
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    if (reader.result) {
                                        sendSuccess(reader.result, bestName, xhr.response.type || defaultMime);
                                    } else {
                                        sendFailed(new Error('FileReader empty result'));
                                    }
                                };
                                reader.onerror = function(e) {
                                    sendFailed(new Error('FileReader read error'));
                                };
                                reader.readAsDataURL(xhr.response);
                            } else {
                                sendFailed(new Error('XHR status ' + xhr.status));
                            }
                        };
                        xhr.onerror = function(err) {
                            sendFailed(new Error('XHR network error on blob'));
                        };
                        xhr.send();
                    } catch(e) {
                        sendFailed(e);
                    }
                }

                // Start Tier 2 if not found in Tier 1
                tryFetchTier();
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    fun startInternalDownload(
        context: Context,
        coroutineScope: CoroutineScope,
        url: String,
        userAgent: String,
        fileName: String,
        mimeType: String,
        contentLength: Long,
        cookies: String = "",
        referer: String = ""
    ) {
        val cleanFileName = sanitizeFileName(fileName)
        val resolvedCookies = if (cookies.isNotBlank()) cookies else {
            try {
                CookieManager.getInstance().getCookie(url) ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        com.onyx.browser.download.OnyxDownloadManager.enqueueDownload(
            context = context,
            url = url,
            fileName = cleanFileName,
            mimeType = mimeType,
            userAgent = userAgent,
            cookies = resolvedCookies,
            referer = referer,
            contentLength = contentLength
        )
    }

    fun startSystemDownload(
        context: Context,
        coroutineScope: CoroutineScope,
        url: String,
        userAgent: String,
        fileName: String,
        mimeType: String,
        contentLength: Long,
        cookies: String = "",
        referer: String = ""
    ) {
        startInternalDownload(
            context = context,
            coroutineScope = coroutineScope,
            url = url,
            userAgent = userAgent,
            fileName = fileName,
            mimeType = mimeType,
            contentLength = contentLength,
            cookies = cookies,
            referer = referer
        )
    }

    fun dispatchToExternalDownloader(
        context: Context,
        url: String,
        mimeType: String,
        userAgent: String,
        fileName: String = "",
        cookies: String = "",
        referer: String = ""
    ) {
        val downloaders = ExternalDownloaderHelper.getInstalledDownloaders(context, url, mimeType)
        val resolvedCookies = if (cookies.isNotBlank()) cookies else {
            try {
                CookieManager.getInstance().getCookie(url) ?: ""
            } catch (_: Exception) {
                ""
            }
        }
        val resolvedFileName = if (fileName.isNotBlank()) fileName else URLUtil.guessFileName(url, null, mimeType)

        if (downloaders.size == 1) {
            val single = downloaders.first()
            val intent = ExternalDownloaderHelper.buildDownloadIntent(
                downloader = single,
                url = url,
                fileName = resolvedFileName,
                userAgent = userAgent,
                cookies = resolvedCookies,
                referer = referer,
                mimeType = mimeType
            )
            try {
                context.startActivity(intent)
                Toast.makeText(context, "Opening in ${single.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to launch ${single.name}", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Fallback generic intent
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(url), mimeType.ifEmpty { "*/*" })
                    if (resolvedCookies.isNotBlank()) {
                        putExtra("Cookie", resolvedCookies)
                        putExtra("cookies", resolvedCookies)
                    }
                    if (userAgent.isNotBlank()) {
                        putExtra("User-Agent", userAgent)
                        putExtra("user_agent", userAgent)
                    }
                    if (referer.isNotBlank()) {
                        putExtra("Referer", referer)
                        putExtra("referer", referer)
                    }
                    putExtra("extra_filename", resolvedFileName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(intent, "Open with Downloader")
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "No external downloader found", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
