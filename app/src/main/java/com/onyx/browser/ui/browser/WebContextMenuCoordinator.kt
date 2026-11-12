package com.onyx.browser.ui.browser

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.onyx.browser.MainActivity
import com.onyx.browser.ui.menu.ContextMenuBottomSheet
import com.onyx.browser.ui.menu.ImagePreviewDialog
import com.onyx.browser.ui.menu.ImageSearchPickerBottomSheet
import com.onyx.browser.download.DownloadHandler
import com.onyx.browser.web.bridge.FormPostRegistry
import com.onyx.browser.web.OnyxWebView
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Coordinates WebView long-press context menus (links, images, image-links, videos),
 * full-screen image preview dialogs, reverse image search routing, and unified download handoff.
 */
class WebContextMenuCoordinator(
    private val activity: MainActivity,
    private val coroutineScope: LifecycleCoroutineScope,
    private val isTabIncognito: (tabId: String) -> Boolean,
    private val onOpenInNewTab: (url: String, isIncognito: Boolean?) -> Unit,
    private val onOpenInIncognitoTab: (url: String) -> Unit,
    private val onEditUrlInSearchBar: (url: String) -> Unit
) {

    fun attachToWebView(webView: OnyxWebView) {
        webView.setOnLongClickListener {
            showCustomContextMenu(webView, webView.hitTestResult)
        }
    }

    fun showCustomContextMenu(webView: OnyxWebView, hitResult: WebView.HitTestResult): Boolean {
        val touched = webView.touchBridge.lastTouchedElement
        val hitType = hitResult.type
        val hitExtra = hitResult.extra

        // 1. Tier 1: Real-time touch bridge pre-computed element
        if (touched.hasContent) {
            val linkUrl = touched.linkUrl.ifBlank {
                if (hitType == WebView.HitTestResult.SRC_ANCHOR_TYPE) hitExtra ?: "" else ""
            }
            val imgUrl = touched.imageUrl.ifBlank {
                if (hitType == WebView.HitTestResult.IMAGE_TYPE ||
                    hitType == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                ) hitExtra ?: "" else ""
            }
            showContextMenuForElement(
                webView = webView,
                linkUrl = linkUrl,
                imageUrl = imgUrl,
                videoUrl = touched.videoUrl,
                linkText = touched.linkText
            )
            return true
        }

        // 2. Tier 2: HitTestResult matching
        return when (hitType) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE,
            WebView.HitTestResult.ANCHOR_TYPE -> {
                val url = hitExtra ?: return false
                showContextMenuForElement(webView = webView, linkUrl = url)
                true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                val imgUrl = hitExtra ?: return false
                showContextMenuForElement(webView = webView, imageUrl = imgUrl)
                true
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val imgUrl = hitExtra ?: ""
                val msg = Handler(Looper.getMainLooper()) { m ->
                    val linkUrl = m.data.getString("url")
                    val title = m.data.getString("title")
                    val src = m.data.getString("src") ?: imgUrl
                    showContextMenuForElement(
                        webView = webView,
                        linkUrl = linkUrl,
                        imageUrl = src,
                        linkText = title
                    )
                    true
                }.obtainMessage()
                webView.requestFocusNodeHref(msg)
                true
            }
            WebView.HitTestResult.EMAIL_TYPE -> {
                val mail = hitExtra ?: return false
                val mailto = if (mail.startsWith("mailto:", ignoreCase = true)) mail else "mailto:$mail"
                showContextMenuForElement(webView = webView, linkUrl = mailto, linkText = mail)
                true
            }
            WebView.HitTestResult.PHONE_TYPE -> {
                val phone = hitExtra ?: return false
                val tel = if (phone.startsWith("tel:", ignoreCase = true)) phone else "tel:$phone"
                showContextMenuForElement(webView = webView, linkUrl = tel, linkText = phone)
                true
            }
            WebView.HitTestResult.GEO_TYPE -> {
                val geo = hitExtra ?: return false
                val geoUri = if (geo.startsWith("geo:", ignoreCase = true)) geo else "geo:0,0?q=$geo"
                showContextMenuForElement(webView = webView, linkUrl = geoUri, linkText = geo)
                true
            }
            WebView.HitTestResult.EDIT_TEXT_TYPE -> {
                false
            }
            else -> {
                // Tier 3: UNKNOWN_TYPE fallback via elementFromPoint and requestFocusNodeHref
                val density = activity.resources.displayMetrics.density
                val cssX = (webView.lastTouchX / density).toInt()
                val cssY = (webView.lastTouchY / density).toInt()
                val jsCheck = """
                    (function(x, y) {
                        try {
                            var el = document.elementFromPoint(x, y);
                            if (!el) return null;
                            var a = el.closest('a');
                            var img = el.closest('img') || (el.tagName === 'IMG' ? el : el.querySelector('img'));
                            var video = el.closest('video') || (el.tagName === 'VIDEO' ? el : el.querySelector('video'));
                            if (!video) {
                                var s = el.querySelector('source');
                                if (s && s.parentElement && s.parentElement.tagName === 'VIDEO') video = s.parentElement;
                            }
                            if (!a && !img && !video) return null;
                            var linkUrl = a ? (a.href || a.getAttribute('href') || '') : '';
                            var linkText = a ? (a.innerText || a.textContent || '').trim().substring(0, 150) : '';
                            var imageUrl = img ? (img.currentSrc || img.src || '') : '';
                            var videoUrl = video ? (video.currentSrc || video.src || '') : '';
                            if (!videoUrl && video) {
                                var vs = video.querySelector('source');
                                if (vs) videoUrl = vs.currentSrc || vs.src || '';
                            }
                            if (videoUrl && !videoUrl.startsWith('http://') && !videoUrl.startsWith('https://') && !videoUrl.startsWith('blob:') && !videoUrl.startsWith('data:')) {
                                try { videoUrl = new URL(videoUrl, window.location.href).href; } catch(err) {}
                            }
                            return JSON.stringify({
                                linkUrl: linkUrl,
                                linkText: linkText,
                                imageUrl: imageUrl,
                                videoUrl: videoUrl
                            });
                        } catch(e) { return null; }
                    })($cssX, $cssY);
                """.trimIndent()

                webView.evaluateJavascript(jsCheck) { jsonResult ->
                    if (!jsonResult.isNullOrBlank() && jsonResult != "null") {
                        try {
                            val cleanJson = if (jsonResult.startsWith("\"") && jsonResult.endsWith("\"")) {
                                JSONTokener(jsonResult).nextValue().toString()
                            } else jsonResult
                            val obj = JSONObject(cleanJson)
                            val lUrl = obj.optString("linkUrl").takeIf { it.isNotBlank() }
                            val lText = obj.optString("linkText").takeIf { it.isNotBlank() }
                            val iUrl = obj.optString("imageUrl").takeIf { it.isNotBlank() }
                            val vUrl = obj.optString("videoUrl").takeIf { it.isNotBlank() }
                            if (lUrl != null || iUrl != null || vUrl != null) {
                                showContextMenuForElement(
                                    webView = webView,
                                    linkUrl = lUrl,
                                    imageUrl = iUrl,
                                    videoUrl = vUrl,
                                    linkText = lText
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }

                val msg = Handler(Looper.getMainLooper()) { m ->
                    val linkUrl = m.data.getString("url")
                    val title = m.data.getString("title")
                    val src = m.data.getString("src")
                    if (!linkUrl.isNullOrBlank() || !src.isNullOrBlank()) {
                        showContextMenuForElement(
                            webView = webView,
                            linkUrl = linkUrl,
                            imageUrl = src,
                            linkText = title
                        )
                    }
                    true
                }.obtainMessage()
                webView.requestFocusNodeHref(msg)

                false
            }
        }
    }

    fun showContextMenuForElement(
        webView: OnyxWebView,
        linkUrl: String? = null,
        imageUrl: String? = null,
        videoUrl: String? = null,
        linkText: String? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val cleanLink = linkUrl?.takeIf { it.isNotBlank() }
        val cleanImg = imageUrl?.takeIf { it.isNotBlank() }
        val cleanVid = videoUrl?.takeIf { it.isNotBlank() }
        val cleanText = linkText?.takeIf { it.isNotBlank() }

        val isIncognito = isTabIncognito(webView.tabId)

        val sheet = when {
            cleanLink != null && cleanImg != null ->
                ContextMenuBottomSheet.forImageLink(cleanLink, cleanImg, cleanText, isIncognito = isIncognito)
            cleanVid != null ->
                ContextMenuBottomSheet.forVideo(cleanVid, cleanLink, cleanText, isIncognito = isIncognito)
            cleanImg != null ->
                ContextMenuBottomSheet.forImage(cleanImg, isIncognito = isIncognito)
            cleanLink != null ->
                ContextMenuBottomSheet.forLink(cleanLink, cleanText, isIncognito = isIncognito)
            else -> return
        }

        sheet.setOnOpenInNewTab { u -> onOpenInNewTab(u, isIncognito) }
        sheet.setOnOpenInIncognitoTab { u -> onOpenInIncognitoTab(u) }
        sheet.setOnEditUrl { u -> onEditUrlInSearchBar(u) }
        sheet.setOnDownloadUrl { u, mime ->
            val cookies = try {
                webView.cookieManager.getCookie(u) ?: ""
            } catch (_: Exception) { "" }
            val userAgent = webView.settings.userAgentString ?: ""
            FormPostRegistry.claimForDownload(u)
            DownloadHandler.handleDownload(
                activity = activity,
                coroutineScope = coroutineScope,
                url = u,
                userAgent = userAgent,
                contentDisposition = "",
                mimeType = mime,
                contentLength = -1L,
                cookies = cookies,
                referer = webView.url ?: ""
            )
        }
        sheet.show(activity.supportFragmentManager, ContextMenuBottomSheet.TAG)
    }

    fun showImagePreviewDialog(imageUrl: String) {
        if (activity.isFinishing || activity.isDestroyed || imageUrl.isBlank()) return
        val previewDialog = ImagePreviewDialog.newInstance(imageUrl)
        previewDialog.setOnOpenInNewTab { u ->
            onOpenInNewTab(u, null)
        }
        previewDialog.show(activity.supportFragmentManager, ImagePreviewDialog.TAG)
    }

    fun showReverseImageSearchPicker(imageUrl: String) {
        if (activity.isFinishing || activity.isDestroyed || imageUrl.isBlank()) return
        val picker = ImageSearchPickerBottomSheet(imageUrl) { searchUrl, engineName ->
            onOpenInNewTab(searchUrl, null)
            Toast.makeText(activity, "Searching with $engineName…", Toast.LENGTH_SHORT).show()
        }
        picker.show(activity.supportFragmentManager, ImageSearchPickerBottomSheet.TAG)
    }
}


typealias WebContextMenuController = WebContextMenuCoordinator
