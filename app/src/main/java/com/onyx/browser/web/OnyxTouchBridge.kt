package com.onyx.browser.web

import android.webkit.JavascriptInterface

/**
 * High-performance, zero-latency bridge between web DOM touch events and native Android.
 * Pre-computes and caches element hierarchy (links, nested anchor spans, images, videos)
 * on touchstart so that when onLongClickListener triggers, all link/media metadata
 * is already synchronously accessible on the main thread.
 */
class OnyxTouchBridge {

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

    @JavascriptInterface
    fun setPullToRefreshAllowed(allowed: Boolean) {
        isPullToRefreshAllowed = allowed
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

    companion object {
        const val INTERFACE_NAME = "OnyxTouchBridge"

        /**
         * Ultra-lightweight touch listener script.
         * Runs passively with capture = true so it intercepts before any page framework can stopPropagation.
         * Traverses upwards to nearest anchor, image, or video element.
         */
        const val TOUCH_LISTENER_JS = """
            (function() {
                var host = (location.hostname || '').toLowerCase();
                var href = (location.href || '').toLowerCase();
                var path = (location.pathname || '').toLowerCase();
                if (/facebook\.com|meta\.com|fb\.com|fb\.me|fbcdn\.net|facebook\.net|fbsbx\.com|instagram\.com|cdninstagram\.com|messenger\.com|threads\.net|accountkit\.com|arkose|funcaptcha|matchkey|turnstile|recaptcha|hcaptcha|datadome|perimeterx|kasada|geetest|challenges\.cloudflare\.com/i.test(host + ' ' + href + ' ' + ref) ||
                    path.indexOf('/checkpoint/') !== -1 || path.indexOf('/login/') !== -1 || path.indexOf('/auth/') !== -1 ||
                    path.indexOf('/captcha/') !== -1 || path.indexOf('/challenge/') !== -1 || path.indexOf('/fc/') !== -1 ||
                    href.indexOf('/checkpoint/') !== -1 || href.indexOf('/challenge/') !== -1 || href.indexOf('/captcha/') !== -1 ||
                    href.indexOf('/login/') !== -1 || href.indexOf('/auth/') !== -1 || href.indexOf('lsd=') !== -1 ||
                    href.indexOf('jazoest=') !== -1 || href.indexOf('datr=') !== -1) {
                    return;
                }

                if (window.__onyxTouchInitialized) return;
                window.__onyxTouchInitialized = true;

                document.addEventListener('touchstart', function(e) {
                    try {
                        if (!e.touches || e.touches.length === 0) return;
                        var t = e.touches[0];
                        var el = document.elementFromPoint(t.clientX, t.clientY);

                        // ── 1. Evaluate Pull-To-Refresh Eligibility ──────────────────────
                        var canPull = true;
                        try {
                            var docStyle = window.getComputedStyle(document.documentElement);
                            var bodyStyle = document.body ? window.getComputedStyle(document.body) : null;
                            if (docStyle.overflowY === 'hidden' || (bodyStyle && bodyStyle.overflowY === 'hidden')) {
                                canPull = false;
                            } else {
                                var docOverscroll = docStyle.overscrollBehaviorY || docStyle.overscrollBehavior;
                                var bodyOverscroll = bodyStyle ? (bodyStyle.overscrollBehaviorY || bodyStyle.overscrollBehavior) : '';
                                if (docOverscroll === 'none' || docOverscroll === 'contain' || bodyOverscroll === 'none' || bodyOverscroll === 'contain') {
                                    canPull = false;
                                }
                            }

                            if (canPull && el) {
                                if (el.closest('video, audio, [role="feed"], [data-pagelet*="Reel"], [aria-label*="Reel"], [class*="reel" i], [class*="Reel"], [class*="Shorts" i], [class*="shorts" i]')) {
                                    canPull = false;
                                } else {
                                    var p = el;
                                    while (p && p !== document.body && p !== document.documentElement) {
                                        var s = window.getComputedStyle(p);
                                        if (s.overflowY === 'scroll' || s.overflowY === 'auto') {
                                            if (p.scrollTop > 0) {
                                                canPull = false;
                                                break;
                                            }
                                        }
                                        var ob = s.overscrollBehaviorY || s.overscrollBehavior;
                                        if (ob === 'none' || ob === 'contain') {
                                            canPull = false;
                                            break;
                                        }
                                        p = p.parentElement;
                                    }
                                }
                            }
                        } catch (_) { canPull = true; }

                        if (window.OnyxTouchBridge && typeof window.OnyxTouchBridge.setPullToRefreshAllowed === 'function') {
                            window.OnyxTouchBridge.setPullToRefreshAllowed(canPull);
                        }

                        // ── 2. Interactive Element Metadata Extraction ────────────────────
                        if (!el) {
                            return;
                        }

                        var a = el.closest('a');
                        var img = el.closest('img') || (el.tagName === 'IMG' ? el : el.querySelector('img'));
                        var video = el.closest('video') || (el.tagName === 'VIDEO' ? el : el.querySelector('video'));
                        if (!video) {
                            var s = el.querySelector('source');
                            if (s && s.parentElement && s.parentElement.tagName === 'VIDEO') video = s.parentElement;
                        }

                        if (!a && !img && !video) {
                            return;
                        }

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

                        if (window.OnyxTouchBridge) {
                            window.OnyxTouchBridge.onInteractiveElementTouched(linkUrl, linkText, imageUrl, videoUrl);
                        }
                    } catch (err) {}
                }, { passive: true, capture: true });

                document.addEventListener('touchend', function() {
                    setTimeout(function() {
                        if (window.OnyxTouchBridge) {
                            window.OnyxTouchBridge.onTouchCleared();
                            window.OnyxTouchBridge.setPullToRefreshAllowed(true);
                        }
                    }, 800);
                }, { passive: true });

                document.addEventListener('touchcancel', function() {
                    if (window.OnyxTouchBridge) {
                        window.OnyxTouchBridge.onTouchCleared();
                        window.OnyxTouchBridge.setPullToRefreshAllowed(true);
                    }
                }, { passive: true });
            })();
        """
    }
}
