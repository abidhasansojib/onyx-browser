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

                // Per-gesture state for direction-aware pull-to-refresh gating
                var __touchStartY = 0;
                var __touchStartX = 0;
                var __scrollableTarget = null;
                var __directionLocked = false;

                /**
                 * Walk up from element to find the nearest scrollable container
                 * that has actual overflow content (scrollHeight > clientHeight).
                 */
                function findScrollableParent(el) {
                    var p = el;
                    while (p && p !== document.body && p !== document.documentElement) {
                        var s = window.getComputedStyle(p);
                        if ((s.overflowY === 'scroll' || s.overflowY === 'auto') &&
                            p.scrollHeight > p.clientHeight) {
                            return p;
                        }
                        p = p.parentElement;
                    }
                    return null;
                }

                /**
                 * Evaluate pull-to-refresh eligibility at touchstart.
                 * Returns true if pull-to-refresh should be BLOCKED.
                 */
                function evaluatePullBlock(el) {
                    try {
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

                        if (el) {
                            if (el.closest('video, audio, [role="feed"], [data-pagelet*="Reel"], [aria-label*="Reel"], [class*="reel" i], [class*="Reel"], [class*="Shorts" i], [class*="shorts" i]')) {
                                return true;
                            }
                            var p = el;
                            while (p && p !== document.body && p !== document.documentElement) {
                                var s = window.getComputedStyle(p);
                                var ob = s.overscrollBehaviorY || s.overscrollBehavior;
                                if (ob === 'none' || ob === 'contain') {
                                    return true;
                                }
                                p = p.parentElement;
                            }
                        }
                    } catch (_) {}
                    return false;
                }

                document.addEventListener('touchstart', function(e) {
                    try {
                        __directionLocked = false;
                        __scrollableTarget = null;
                        if (!e.touches || e.touches.length === 0) return;
                        var t = e.touches[0];
                        __touchStartY = t.clientY;
                        __touchStartX = t.clientX;
                        var el = document.elementFromPoint(t.clientX, t.clientY);

                        // Cache nearest scrollable parent for use in touchmove
                        if (el) {
                            __scrollableTarget = findScrollableParent(el);
                        }

                        // ── 1. Evaluate Pull-To-Refresh Eligibility (static checks) ─────
                        var blocked = evaluatePullBlock(el);
                        if (window.OnyxTouchBridge && typeof window.OnyxTouchBridge.setPullToRefreshAllowed === 'function') {
                            window.OnyxTouchBridge.setPullToRefreshAllowed(!blocked);
                        }

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

                        if (window.OnyxTouchBridge) {
                            window.OnyxTouchBridge.onInteractiveElementTouched(linkUrl, linkText, imageUrl, videoUrl);
                        }
                    } catch (err) {}
                }, { passive: true, capture: true });

                // ── touchmove: direction-aware pull-to-refresh gate ─────────────────────
                // This is the primary fix for "scroll-up triggers pull-to-refresh" bug.
                // Direction is determined here (not on touchstart, where it's unknown).
                document.addEventListener('touchmove', function(e) {
                    try {
                        if (__directionLocked || !e.touches || e.touches.length === 0) return;
                        var deltaY = e.touches[0].clientY - __touchStartY;
                        var deltaX = e.touches[0].clientX - __touchStartX;
                        // Require meaningful vertical movement before locking direction
                        if (Math.abs(deltaY) < 6) return;
                        __directionLocked = true;

                        // Horizontal swipe — don't interfere with pull-to-refresh
                        if (Math.abs(deltaX) > Math.abs(deltaY)) return;

                        var bridge = window.OnyxTouchBridge;
                        if (!bridge || typeof bridge.setPullToRefreshAllowed !== 'function') return;

                        if (deltaY < 0) {
                            // Swiping UP — pull-to-refresh requires a downward gesture, block it
                            bridge.setPullToRefreshAllowed(false);
                        } else {
                            // Swiping DOWN — block if an inner scrollable container is mid-scroll
                            if (__scrollableTarget && __scrollableTarget.scrollTop > 0) {
                                bridge.setPullToRefreshAllowed(false);
                            }
                        }
                    } catch (_) {}
                }, { passive: true, capture: true });

                document.addEventListener('touchend', function() {
                    __directionLocked = false;
                    __scrollableTarget = null;
                    setTimeout(function() {
                        if (window.OnyxTouchBridge) {
                            window.OnyxTouchBridge.onTouchCleared();
                            window.OnyxTouchBridge.setPullToRefreshAllowed(true);
                        }
                    }, 800);
                }, { passive: true });

                document.addEventListener('touchcancel', function() {
                    __directionLocked = false;
                    __scrollableTarget = null;
                    if (window.OnyxTouchBridge) {
                        window.OnyxTouchBridge.onTouchCleared();
                        window.OnyxTouchBridge.setPullToRefreshAllowed(true);
                    }
                }, { passive: true });
            })();
        """
    }
}
