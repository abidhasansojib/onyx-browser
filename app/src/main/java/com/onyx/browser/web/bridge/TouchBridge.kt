package com.onyx.browser.web.bridge

import com.onyx.browser.web.BrowserWebView

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import org.json.JSONObject

/**
 * High-performance, zero-latency bridge between web DOM touch events and native Android.
 * Pre-computes and caches element hierarchy (links, nested anchor spans, images, videos)
 * on touchstart so that when onLongClickListener triggers, all link/media metadata
 * is already synchronously accessible on the main thread.
 *
 * Supports both modern [WebViewCompat.WebMessageListener] (via "onyxTouchBridge" message port)
 * and legacy [@JavascriptInterface] (via "OnyxTouchBridge") for 100% backward compatibility.
 */
class TouchBridge {

    data class TouchedElement(
        val linkUrl: String = "",
        val linkText: String = "",
        val imageUrl: String = "",
        val videoUrl: String = ""
    ) {
        val isLink: Boolean get() = linkUrl.isNotBlank()
        val isImage: Boolean get() = imageUrl.isNotBlank()
        val isVideo: Boolean get() = videoUrl.isNotBlank()
        val isImageLink: Boolean get() = isLink && isImage
        val hasContent: Boolean get() = isLink || isImage || isVideo
    }

    @Volatile
    var lastTouchedElement: TouchedElement = TouchedElement()
        private set

    @Volatile
    var isPullToRefreshAllowed: Boolean = true
        private set

    var onDevToolsVisibilityChanged: ((Boolean) -> Unit)? = null

    @JavascriptInterface
    fun setPullToRefreshAllowed(allowed: Boolean) {
        isPullToRefreshAllowed = allowed
    }

    @JavascriptInterface
    fun onDevToolsVisibilityChanged(expanded: Boolean) {
        Handler(Looper.getMainLooper()).post {
            onDevToolsVisibilityChanged?.invoke(expanded)
        }
    }

    @JavascriptInterface
    fun onInteractiveElementTouched(
        linkUrl: String?,
        linkText: String?,
        imageUrl: String?,
        videoUrl: String?
    ) {
        lastTouchedElement = TouchedElement(
            linkUrl = linkUrl?.trim() ?: "",
            linkText = linkText?.trim() ?: "",
            imageUrl = imageUrl?.trim() ?: "",
            videoUrl = videoUrl?.trim() ?: ""
        )
    }

    @JavascriptInterface
    fun onTouchCleared() {
        lastTouchedElement = TouchedElement()
    }

    fun clear() {
        lastTouchedElement = TouchedElement()
        isPullToRefreshAllowed = true
    }

    /**
     * Creates a modern [WebViewCompat.WebMessageListener] for zero-reflection,
     * UI-thread event delivery without exposing Java reflection objects on window.
     */
    fun createWebMessageListener(): WebViewCompat.WebMessageListener {
        val mainHandler = Handler(Looper.getMainLooper())
        return WebViewCompat.WebMessageListener { _: WebView, message: WebMessageCompat, _: Uri, _: Boolean, _: JavaScriptReplyProxy ->
            val data = message.data ?: return@WebMessageListener
            try {
                val json = JSONObject(data)
                when (json.optString("type")) {
                    "ptr" -> {
                        isPullToRefreshAllowed = json.optBoolean("allowed", true)
                    }
                    "devtools_visibility" -> {
                        val expanded = json.optBoolean("expanded", false)
                        mainHandler.post {
                            onDevToolsVisibilityChanged?.invoke(expanded)
                        }
                    }
                    "touched" -> {
                        lastTouchedElement = TouchedElement(
                            linkUrl = json.optString("linkUrl").trim(),
                            linkText = json.optString("linkText").trim(),
                            imageUrl = json.optString("imageUrl").trim(),
                            videoUrl = json.optString("videoUrl").trim()
                        )
                    }
                    "cleared" -> {
                        lastTouchedElement = TouchedElement()
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    companion object {
        const val INTERFACE_NAME = "OnyxTouchBridge"

        /**
         * Ultra-lightweight touch listener script.
         * Runs passively with capture = true so it intercepts before any page framework can stopPropagation.
         *
         * Key improvements:
         * - Tracks swipe direction via touchmove (6px threshold) to prevent upward swipes from
         *   triggering pull-to-refresh (fixes scroll-up triggers page refresh bug).
         * - Caches nearest scrollable parent on touchstart and re-checks scrollTop on touchmove,
         *   blocking pull-to-refresh when the user swipes down inside a scrolled inner container.
         */
        const val TOUCH_LISTENER_JS = """
            (function() {
                var host = (location.hostname || '').toLowerCase();
                var href = (location.href || '').toLowerCase();
                var path = (location.pathname || '').toLowerCase();
                var ref = (document.referrer || '').toLowerCase();
                if (/fbsbx\.com|arkose|funcaptcha|matchkey|turnstile|recaptcha|hcaptcha|datadome|perimeterx|kasada|geetest|challenges\.cloudflare\.com/i.test(host + ' ' + href + ' ' + ref) ||
                    path.indexOf('/checkpoint/') !== -1 || path.indexOf('/challenge/') !== -1 || path.indexOf('/fc/') !== -1 ||
                    href.indexOf('/checkpoint/') !== -1 || href.indexOf('/challenge/') !== -1 || href.indexOf('/captcha/') !== -1) {
                    return;
                }

                if (window.__onyxTouchInitialized) return;
                window.__onyxTouchInitialized = true;

                // Per-gesture state for direction-aware pull-to-refresh gating
                var __touchStartY = 0;
                var __touchStartX = 0;
                var __lastTouchY = 0;
                var __scrollableTarget = null;
                var __ptrBlockedForGesture = false;
                var __clearTimer = null;

                function isErudaExpanded() {
                    try {
                        if (window.eruda && window.eruda._isInit) {
                            var dt = window.eruda._devTools || (typeof window.eruda.get === 'function' ? window.eruda.get() : null);
                            if (dt && typeof dt._isShow === 'boolean') {
                                return dt._isShow;
                            }
                        }
                    } catch (_) {}
                    return !!window.__onyx_eruda_visible;
                }

                function isErudaOrDevTools(el, path) {
                    try {
                        if (isErudaExpanded()) return true;
                        if (path && path.length > 0) {
                            for (var i = 0; i < path.length; i++) {
                                var n = path[i];
                                if (!n) continue;
                                if (n.id === 'eruda') return true;
                                var cls = n.className;
                                if (typeof cls === 'string' && cls.indexOf('eruda') !== -1) return true;
                                if (n.hasAttribute && (n.hasAttribute('data-eruda') || n.hasAttribute('eruda'))) return true;
                            }
                        }
                        if (el) {
                            if (el.id === 'eruda' || (el.className && typeof el.className === 'string' && el.className.indexOf('eruda') !== -1)) return true;
                            if (el.closest && el.closest('#eruda, [class*="eruda"], [data-eruda]')) return true;
                        }
                    } catch (_) {}
                    return false;
                }

                function isScrollableOverflow(overflowY) {
                    return overflowY === 'scroll' || overflowY === 'auto' || overflowY === 'overlay';
                }

                function isDocumentScrolled() {
                    try {
                        if ((window.scrollY && window.scrollY > 0) ||
                            (window.pageYOffset && window.pageYOffset > 0) ||
                            (document.scrollingElement && document.scrollingElement.scrollTop > 0) ||
                            (document.documentElement && document.documentElement.scrollTop > 0) ||
                            (document.body && document.body.scrollTop > 0)) {
                            return true;
                        }
                    } catch (_) {}
                    return false;
                }

                function isDocumentOrActiveContainerScrolled() {
                    try {
                        if (isDocumentScrolled()) return true;
                        if (__scrollableTarget && __scrollableTarget.isConnected && __scrollableTarget.scrollTop > 0) {
                            return true;
                        }
                    } catch (_) {}
                    return false;
                }

                /**
                 * Walk up from element or inspect composedPath to find the nearest scrollable container
                 * that has actual overflow content (scrollHeight > clientHeight).
                 */
                function findScrollableParent(el, path) {
                    try {
                        if (path && path.length > 0) {
                            for (var i = 0; i < path.length; i++) {
                                var node = path[i];
                                if (!node || !(node instanceof HTMLElement)) continue;
                                if (node === document.body || node === document.documentElement) {
                                    if (node.scrollTop > 0) return node;
                                    continue;
                                }
                                var s = window.getComputedStyle(node);
                                if (isScrollableOverflow(s.overflowY) &&
                                    node.scrollHeight > node.clientHeight + 1) {
                                    return node;
                                }
                            }
                        }
                    } catch (_) {}
                    var p = el;
                    while (p && p !== document.documentElement) {
                        try {
                            if (p === document.body) {
                                if (p.scrollTop > 0) return p;
                                break;
                            }
                            var s2 = window.getComputedStyle(p);
                            if (isScrollableOverflow(s2.overflowY) &&
                                p.scrollHeight > p.clientHeight + 1) {
                                return p;
                            }
                        } catch (_) {}
                        p = p.parentElement;
                    }
                    return null;
                }

                /**
                 * Evaluate pull-to-refresh eligibility at touchstart.
                 * Returns true if pull-to-refresh should be BLOCKED.
                 */
                function evaluatePullBlock(el, path) {
                    try {
                        if (isErudaOrDevTools(el, path) || isDocumentOrActiveContainerScrolled()) {
                            return true;
                        }

                        var docStyle = window.getComputedStyle(document.documentElement);
                        var bodyStyle = document.body ? window.getComputedStyle(document.body) : null;
                        if (docStyle.overflowY === 'hidden' || (bodyStyle && bodyStyle.overflowY === 'hidden')) {
                            return true;
                        }
                        var docOverscroll = docStyle.overscrollBehaviorY || docStyle.overscrollBehavior;
                        var bodyOverscroll = bodyStyle ? (bodyStyle.overscrollBehaviorY || bodyStyle.overscrollBehavior) : '';
                        if (docOverscroll === 'none' || docOverscroll === 'contain' || bodyOverscroll === 'none' || bodyOverscroll === 'contain') {
                            return true;
                        }

                        if (path && path.length > 0) {
                            for (var i = 0; i < path.length; i++) {
                                var node = path[i];
                                if (!node || !(node instanceof HTMLElement)) continue;
                                var tag = (node.tagName || '').toLowerCase();
                                if (tag === 'video' || tag === 'audio') return true;
                                if (node.scrollTop > 0) return true;
                                var s = window.getComputedStyle(node);
                                var ob = s.overscrollBehaviorY || s.overscrollBehavior;
                                if (ob === 'none' || ob === 'contain') return true;
                                if (isScrollableOverflow(s.overflowY) &&
                                    node.scrollHeight > node.clientHeight + 1 && node.scrollTop > 0) {
                                    return true;
                                }
                            }
                        }

                        if (el) {
                            if (el.closest('video, audio, [role="feed"], [data-pagelet*="Reel"], [aria-label*="Reel"], [class*="reel" i], [class*="Reel"], [class*="Shorts" i], [class*="shorts" i]')) {
                                return true;
                            }
                            var p = el;
                            while (p) {
                                if (p.scrollTop > 0) return true;
                                if (p === document.body || p === document.documentElement) break;
                                var s2 = window.getComputedStyle(p);
                                var ob2 = s2.overscrollBehaviorY || s2.overscrollBehavior;
                                if (ob2 === 'none' || ob2 === 'contain') {
                                    return true;
                                }
                                if (isScrollableOverflow(s2.overflowY) &&
                                    p.scrollHeight > p.clientHeight + 1 && p.scrollTop > 0) {
                                    return true;
                                }
                                p = p.parentElement;
                            }
                        }
                    } catch (_) {}
                    return false;
                }

                function postTouchMsg(msg) {
                    try {
                        if (window.onyxTouchBridge && typeof window.onyxTouchBridge.postMessage === 'function') {
                            window.onyxTouchBridge.postMessage(JSON.stringify(msg));
                            return true;
                        }
                    } catch (_) {}
                    return false;
                }

                function sendPtrAllowed(allowed) {
                    if (!postTouchMsg({ type: 'ptr', allowed: !!allowed })) {
                        if (window.OnyxTouchBridge && typeof window.OnyxTouchBridge.setPullToRefreshAllowed === 'function') {
                            window.OnyxTouchBridge.setPullToRefreshAllowed(!!allowed);
                        }
                    }
                }

                // Passive capturing scroll listener keeps native isPullToRefreshAllowed synchronized
                // in real time across document and inner SPA scroll containers BEFORE the next touchstart.
                window.addEventListener('scroll', function(e) {
                    try {
                        var t = e && e.target;
                        var targetScrolled = (t && t !== document && t !== window && typeof t.scrollTop === 'number' && t.scrollTop > 0);
                        if (targetScrolled || isDocumentOrActiveContainerScrolled() || isErudaExpanded()) {
                            __ptrBlockedForGesture = true;
                            sendPtrAllowed(false);
                        } else if (!__ptrBlockedForGesture) {
                            sendPtrAllowed(true);
                        }
                    } catch (_) {}
                }, { passive: true, capture: true });

                document.addEventListener('touchstart', function(e) {
                    try {
                        if (__clearTimer) {
                            clearTimeout(__clearTimer);
                            __clearTimer = null;
                        }
                        __scrollableTarget = null;
                        if (!e.touches || e.touches.length === 0) return;
                        if (e.touches.length > 1) {
                            __ptrBlockedForGesture = true;
                            sendPtrAllowed(false);
                            return;
                        }
                        var t = e.touches[0];
                        __touchStartY = t.clientY;
                        __touchStartX = t.clientX;
                        __lastTouchY = t.clientY;
                        var path = (e.composedPath && typeof e.composedPath === 'function') ? e.composedPath() : null;
                        var el = document.elementFromPoint(t.clientX, t.clientY);

                        // Self-healing synchronization: if Eruda visibility state changed without an event
                        var isExpanded = isErudaExpanded();
                        if (window.__onyx_last_reported_dt_visible !== isExpanded) {
                            window.__onyx_last_reported_dt_visible = isExpanded;
                            window.__onyx_eruda_visible = isExpanded;
                            postTouchMsg({ type: 'devtools_visibility', expanded: isExpanded });
                            if (window.OnyxTouchBridge && typeof window.OnyxTouchBridge.onDevToolsVisibilityChanged === 'function') {
                                window.OnyxTouchBridge.onDevToolsVisibilityChanged(isExpanded);
                            }
                        }

                        // Cache nearest scrollable parent for use in touchmove
                        __scrollableTarget = findScrollableParent(el, path);

                        // ── 1. Evaluate Pull-To-Refresh Eligibility (static checks) ─────
                        var blocked = evaluatePullBlock(el, path);
                        __ptrBlockedForGesture = blocked;
                        sendPtrAllowed(!blocked);

                        // ── 2. Interactive Element Metadata Extraction ────────────────────
                        if (!el) return;

                        var a = el.closest('a');
                        var img = el.closest('img') || (el.tagName === 'IMG' ? el : el.querySelector('img'));
                        var video = el.closest('video') || (el.tagName === 'VIDEO' ? el : el.querySelector('video'));
                        if (!video) {
                            var src = el.querySelector('source');
                            if (src && src.parentElement && src.parentElement.tagName === 'VIDEO') video = src.parentElement;
                        }

                        if (!a && !img && !video) return;

                        var linkUrl = a ? (a.href || a.getAttribute('href') || '') : '';
                        if (linkUrl && !linkUrl.startsWith('http://') && !linkUrl.startsWith('https://') && !linkUrl.startsWith('javascript:') && !linkUrl.startsWith('data:')) {
                            try { linkUrl = new URL(linkUrl, window.location.href).href; } catch(err) {}
                        }
                        var linkText = a ? (a.innerText || a.textContent || '').trim().substring(0, 150) : '';

                        var imageUrl = '';
                        if (img) {
                            imageUrl = img.currentSrc || img.src || img.getAttribute('data-src') || '';
                            if (imageUrl && !imageUrl.startsWith('http://') && !imageUrl.startsWith('https://') && !imageUrl.startsWith('data:')) {
                                try { imageUrl = new URL(imageUrl, window.location.href).href; } catch(err) {}
                            }
                        }

                        var videoUrl = '';
                        if (video) {
                            videoUrl = video.currentSrc || video.src || '';
                            if (!videoUrl) {
                                var vs = video.querySelector('source');
                                if (vs) videoUrl = vs.currentSrc || vs.src || '';
                            }
                            if (videoUrl && !videoUrl.startsWith('http://') && !videoUrl.startsWith('https://')) {
                                try { videoUrl = new URL(videoUrl, window.location.href).href; } catch(err) {}
                            }
                        }

                        if (!postTouchMsg({ type: 'touched', linkUrl: linkUrl, linkText: linkText, imageUrl: imageUrl, videoUrl: videoUrl })) {
                            if (window.OnyxTouchBridge) {
                                window.OnyxTouchBridge.onInteractiveElementTouched(linkUrl, linkText, imageUrl, videoUrl);
                            }
                        }
                    } catch (err) {}
                }, { passive: true, capture: true });

                // ── touchmove: continuous direction-aware & scroll-position pull-to-refresh gate ─────
                document.addEventListener('touchmove', function(e) {
                    try {
                        if (__ptrBlockedForGesture || !e.touches || e.touches.length === 0) return;
                        if (e.touches.length > 1) {
                            __ptrBlockedForGesture = true;
                            sendPtrAllowed(false);
                            return;
                        }
                        var curY = e.touches[0].clientY;
                        var curX = e.touches[0].clientX;
                        var deltaY = curY - __touchStartY;
                        var deltaX = curX - __touchStartX;
                        var stepY = curY - __lastTouchY;
                        __lastTouchY = curY;

                        if (isErudaExpanded() || isDocumentOrActiveContainerScrolled()) {
                            __ptrBlockedForGesture = true;
                            sendPtrAllowed(false);
                            return;
                        }

                        // Any upward movement (scrolling down the page) or dominant horizontal swipe blocks PTR for the entire gesture
                        if (deltaY < -4 || stepY < -4 || (Math.abs(deltaX) > 6 && Math.abs(deltaX) > Math.abs(deltaY))) {
                            __ptrBlockedForGesture = true;
                            sendPtrAllowed(false);
                        }
                    } catch (_) {}
                }, { passive: true, capture: true });

                function handleTouchFinish() {
                    __ptrBlockedForGesture = false;
                    var isScrolled = isDocumentOrActiveContainerScrolled();
                    __scrollableTarget = null;
                    if (isErudaExpanded() || isScrolled) {
                        sendPtrAllowed(false);
                    } else {
                        sendPtrAllowed(true);
                    }
                    if (__clearTimer) clearTimeout(__clearTimer);
                    __clearTimer = setTimeout(function() {
                        __clearTimer = null;
                        if (isErudaExpanded()) return;
                        if (!postTouchMsg({ type: 'cleared' })) {
                            if (window.OnyxTouchBridge) {
                                window.OnyxTouchBridge.onTouchCleared();
                            }
                        }
                    }, 800);
                }

                document.addEventListener('touchend', handleTouchFinish, { passive: true });
                document.addEventListener('touchcancel', handleTouchFinish, { passive: true });
            })();
        """
    }
}


typealias OnyxTouchBridge = TouchBridge
