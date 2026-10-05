package com.onyx.browser.web

import android.content.Context
import android.webkit.WebView

/**
 * Manages Desktop Mode viewport scaling, zoom-out auto-fitting, and Client Hints spoofing.
 *
 * Replicates Brave/Chrome Android desktop mode behavior where the layout viewport is pinned to
 * the industry-standard 980 CSS pixels and the initial page scale is computed to fit the device
 * display width (~40% on standard mobile displays), allowing full desktop layout visibility
 * and smooth pinch-to-zoom.
 */
object DesktopModeManager {

    /** Standard desktop layout viewport width in CSS pixels (Chromium / WebKit default) */
    const val DEFAULT_DESKTOP_WIDTH = 980

    /**
     * Calculates the initial zoom percentage for WebView.setInitialScale() based on the device's
     * screen width in dp relative to [targetWidth].
     *
     * Example: On a 392dp mobile display with a 980px desktop width:
     *   scale = (392 / 980) * 100 = 40%
     */
    fun calculateDesktopScalePercent(context: Context, targetWidth: Int = DEFAULT_DESKTOP_WIDTH): Int {
        val displayMetrics = context.resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        return ((screenWidthDp / targetWidth.toFloat()) * 100).toInt().coerceIn(20, 100)
    }

    /**
     * Document-Start script injected into every page via [androidx.webkit.WebViewCompat.addDocumentStartJavaScript].
     *
     * Intercepts mobile `<meta name="viewport">` elements as the HTML parser attaches them to the DOM,
     * rewriting them to desktop width (980px) and calculated zoom-out scale before layout calculation occurs.
     * Also spoofs `navigator.userAgentData` (Client Hints) and `navigator.platform` for desktop parity.
     */
    val DESKTOP_VIEWPORT_SCRIPT = """
        (function() {
            var host = (location.hostname || '').toLowerCase();
            var href = (location.href || '').toLowerCase();
            var ref = (document.referrer || '').toLowerCase();
            var path = (location.pathname || '').toLowerCase();
            if (/facebook\.com|meta\.com|fb\.com|fb\.me|fbcdn\.net|facebook\.net|fbsbx\.com|instagram\.com|cdninstagram\.com|messenger\.com|threads\.net|accountkit\.com|arkose|funcaptcha|matchkey|turnstile|recaptcha|hcaptcha|datadome|perimeterx|kasada|geetest|challenges\.cloudflare\.com/i.test(host + ' ' + href + ' ' + ref) ||
                path.indexOf('/checkpoint/') !== -1 || path.indexOf('/login/') !== -1 || path.indexOf('/auth/') !== -1 ||
                path.indexOf('/captcha/') !== -1 || path.indexOf('/challenge/') !== -1 || path.indexOf('/fc/') !== -1 ||
                href.indexOf('/checkpoint/') !== -1 || href.indexOf('/challenge/') !== -1 || href.indexOf('/captcha/') !== -1 ||
                href.indexOf('/login/') !== -1 || href.indexOf('/auth/') !== -1 || href.indexOf('lsd=') !== -1 ||
                href.indexOf('jazoest=') !== -1 || href.indexOf('datr=') !== -1) {
                return;
            }

            if (window.__onyxDesktopModeInjected) return;
            window.__onyxDesktopModeInjected = true;

            var isDesktop = false;
            try {
                if (window.OnyxShieldBridge && window.OnyxShieldBridge.isDesktopModeActive) {
                    isDesktop = window.OnyxShieldBridge.isDesktopModeActive(window.location.href);
                }
            } catch (e) {}

            if (!isDesktop) return;

            // ── 1. Spoof Client Hints (navigator.userAgentData) ─────────────────────────
            try {
                if (navigator.userAgentData) {
                    var desktopBrands = [
                        { brand: 'Chromium', version: '131' },
                        { brand: 'Google Chrome', version: '131' },
                        { brand: 'Not_A Brand', version: '24' }
                    ];
                    var desktopUaData = {
                        mobile: false,
                        platform: 'Windows',
                        brands: desktopBrands,
                        getHighEntropyValues: function(hints) {
                            return Promise.resolve({
                                mobile: false,
                                platform: 'Windows',
                                platformVersion: '15.0.0',
                                architecture: 'x86',
                                bitness: '64',
                                model: '',
                                uaFullVersion: '131.0.0.0',
                                fullVersionList: desktopBrands
                            });
                        },
                        toJSON: function() {
                            return { mobile: false, platform: 'Windows', brands: desktopBrands };
                        }
                    };
                    Object.defineProperty(navigator, 'userAgentData', {
                        get: function() { return desktopUaData; },
                        configurable: true,
                        enumerable: true
                    });
                }
            } catch (e) {}

            // ── 2. Spoof navigator.platform ─────────────────────────────────────────────
            try {
                Object.defineProperty(navigator, 'platform', {
                    get: function() { return 'Win32'; },
                    configurable: true,
                    enumerable: true
                });
            } catch (e) {}

            // ── 3. Desktop Viewport Scaling & Zoom-out Enforcement ─────────────────────
            var TARGET_DESKTOP_WIDTH = $DEFAULT_DESKTOP_WIDTH;

            function computeDesktopViewportContent() {
                var screenW = (window.screen && window.screen.width) ? window.screen.width : 390;
                if (window.innerWidth && window.innerHeight && window.innerWidth > window.innerHeight && screenW < window.screen.height) {
                    screenW = window.screen.height;
                }
                var scale = Math.min(1.0, screenW / TARGET_DESKTOP_WIDTH);
                scale = Math.round(scale * 1000) / 1000;
                return 'width=' + TARGET_DESKTOP_WIDTH + ', initial-scale=' + scale + ', minimum-scale=0.25, maximum-scale=5.0, user-scalable=yes';
            }

            function enforceMetaViewport(metaNode) {
                if (!metaNode) return;
                try {
                    var desiredContent = computeDesktopViewportContent();
                    if (metaNode.getAttribute('content') !== desiredContent) {
                        metaNode.setAttribute('content', desiredContent);
                    }
                } catch (e) {}
            }

            try {
                var observer = new MutationObserver(function(mutations) {
                    for (var i = 0; i < mutations.length; i++) {
                        var added = mutations[i].addedNodes;
                        for (var j = 0; j < added.length; j++) {
                            var node = added[j];
                            if (node.nodeType === 1 && node.nodeName === 'META') {
                                var name = (node.getAttribute('name') || '').toLowerCase();
                                if (name === 'viewport') {
                                    enforceMetaViewport(node);
                                }
                            }
                        }
                    }
                });

                var target = document.documentElement || document;
                observer.observe(target, { childList: true, subtree: true });
            } catch (e) {}

            function ensureMetaViewport() {
                try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        enforceMetaViewport(meta);
                        var head = document.head || document.getElementsByTagName('head')[0] || document.documentElement;
                        if (head) {
                            head.appendChild(meta);
                        }
                    } else {
                        enforceMetaViewport(meta);
                    }
                } catch (e) {}
            }

            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', ensureMetaViewport);
            } else {
                ensureMetaViewport();
            }

            window.addEventListener('orientationchange', function() {
                setTimeout(ensureMetaViewport, 150);
            }, { passive: true });
        })();
    """.trimIndent()

    /**
     * Executes post-load reinforcement to guarantee desktop viewport attributes and overview
     * scaling on pages loaded dynamically or via Single Page App routing.
     */
    fun enforceDesktopViewport(view: WebView?) {
        val js = """
            (function() {
                try {
                    var TARGET_DESKTOP_WIDTH = $DEFAULT_DESKTOP_WIDTH;
                    var screenW = (window.screen && window.screen.width) ? window.screen.width : 390;
                    if (window.innerWidth && window.innerHeight && window.innerWidth > window.innerHeight && screenW < window.screen.height) {
                        screenW = window.screen.height;
                    }
                    var scale = Math.min(1.0, screenW / TARGET_DESKTOP_WIDTH);
                    scale = Math.round(scale * 1000) / 1000;
                    var desiredContent = 'width=' + TARGET_DESKTOP_WIDTH + ', initial-scale=' + scale + ', minimum-scale=0.25, maximum-scale=5.0, user-scalable=yes';

                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        meta.setAttribute('content', desiredContent);
                        var head = document.head || document.getElementsByTagName('head')[0] || document.documentElement;
                        if (head) head.appendChild(meta);
                    } else if (meta.getAttribute('content') !== desiredContent) {
                        meta.setAttribute('content', desiredContent);
                    }
                } catch (e) {}
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }
}
