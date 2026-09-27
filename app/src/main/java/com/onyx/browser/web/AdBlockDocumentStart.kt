package com.onyx.browser.web

/**
 * Generates the document_start JavaScript injected into all WebViews.
 *
 * This runs before HTML parsing or inline scripts execute to ensure web standards compatibility:
 * 1. Preemptively stubs core analytics and anti-adblock globals (ga, gtag, adsbygoogle, fbq, datalayer).
 * 2. Injects Brave-parity generic cosmetic filter engine (hidden_class_id_selectors via Rust JNI) to hide ad containers.
 * 3. Preserves in-memory Blobs and ObjectURLs for reliable Android download handling.
 */
object AdBlockDocumentStart {

    val SCRIPT: String
        get() = getScript(0)

    fun getScript(blockingLevel: Int): String {
        return """
        (function() {
            // ── 0. Synchronous Shield & Whitelist Status Check ─────────────────────
            var isShieldActive = true;
            try {
                if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isAdBlockActive === 'function') {
                    isShieldActive = window.OnyxShieldBridge.isAdBlockActive(location.hostname || location.href);
                }
            } catch(e) {}

            var host = (location.hostname || '').toLowerCase();
            var href = (location.href || '').toLowerCase();
            var path = (location.pathname || '').toLowerCase();

            // Cloudflare challenge detection: managed challenge, JS challenge, Turnstile widget
            var isCloudflareChallenge =
                typeof window.__cf_chl_opt !== 'undefined' ||
                typeof window.__cf_chl_ctx !== 'undefined' ||
                host === 'challenges.cloudflare.com' ||
                host.endsWith('.challenges.cloudflare.com') ||
                path.startsWith('/cdn-cgi/challenge-platform') ||
                path.startsWith('/cdn-cgi/cf-challenge') ||
                href.indexOf('__cf_chl') !== -1 ||
                href.indexOf('/cdn-cgi/') !== -1;

            var isMetaOrAuthContext = isCloudflareChallenge ||
                                      host.endsWith('facebook.com') || host.endsWith('fb.com') ||
                                      host.endsWith('messenger.com') || host.endsWith('instagram.com') ||
                                      host.endsWith('fbcdn.net') || host.indexOf('arkose') !== -1 ||
                                      host.indexOf('recaptcha') !== -1 || host.indexOf('hcaptcha') !== -1 ||
                                      host.indexOf('turnstile') !== -1 || host.indexOf('funcaptcha') !== -1 ||
                                      host.indexOf('datadome') !== -1 || host.indexOf('perimeterx') !== -1 ||
                                      host.indexOf('kasada') !== -1;

            if (isMetaOrAuthContext || !isShieldActive || window.__onyx_shields_active === false || window.__onyxAdBlockEnabled === false) {
                // Do not run adblock/cosmetic injections or stub globals on Cloudflare challenges,
                // Meta login, CAPTCHA verifications, or whitelisted sites!
                try {
                    var oldCosm = document.getElementById('onyx-universal-cosmetic');
                    if (oldCosm) oldCosm.remove();
                    var oldAdCosm = document.getElementById('onyx-adblock-cosmetic');
                    if (oldAdCosm) oldAdCosm.remove();
                } catch(e) {}
                return;
            }

            window.__onyx_blocking_level = $blockingLevel;
            window.__onyx_set_blocking_level = function(lvl) {
                window.__onyx_blocking_level = lvl;
            };

            if (window.__onyx_shields_active) return;
            window.__onyx_shields_active = true;

            // ── 1. Preemptive Anti-Adblock, Analytics & Consent Stubs ─────────────────
            // Only injected when the user has enabled "Ad Detection Spoofing" in Privacy & Shields.
            // Off by default — allows adblock test sites and Cloudflare challenges to function correctly.
            var isAntiDetectEnabled = false;
            try {
                if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isAntiAdblockDetectionEnabled === 'function') {
                    isAntiDetectEnabled = window.OnyxShieldBridge.isAntiAdblockDetectionEnabled();
                }
            } catch(e) {}

            if (isAntiDetectEnabled) {
                try {
                    window.ga = window.ga || function() { (window.ga.q = window.ga.q || []).push(arguments); };
                    window.ga.l = +new Date;
                    window.gtag = window.gtag || function() {};
                    window.adsbygoogle = window.adsbygoogle || [];
                    window.adsbygoogle.loaded = true;
                    window.google_ad_client = true;
                    window.google_ad_width = window.innerWidth || 1024;
                    window.google_ad_height = window.innerHeight || 768;
                    if (!window.fbq) {
                        var fbqStub = function() {
                            if (fbqStub.callMethod) { fbqStub.callMethod.apply(fbqStub, arguments); }
                            else if (fbqStub.queue) { fbqStub.queue.push(arguments); }
                        };
                        fbqStub.push = fbqStub; fbqStub.loaded = true; fbqStub.version = '2.0'; fbqStub.queue = [];
                        window.fbq = fbqStub;
                    }
                    window._paq = window._paq || [];
                    window.dataLayer = window.dataLayer || [];
                    window.outbrain = window.outbrain || { ready: function() {} };
                    window.taboola = window.taboola || { push: function() {} };
                } catch(e) {}
            }

            // ── 2. Brave Parity Generic Cosmetic Filter Engine (hidden_class_id_selectors) ───────
            try {
                var seenSelectors = new Set();
                var pendingClasses = new Set();
                var pendingIds = new Set();
                var queryTimer = null;
                var cosmeticStyleEl = null;

                function getOrCreateCosmeticStyle() {
                    if (cosmeticStyleEl && document.contains(cosmeticStyleEl)) return cosmeticStyleEl;
                    cosmeticStyleEl = document.getElementById('onyx-universal-cosmetic');
                    if (!cosmeticStyleEl) {
                        cosmeticStyleEl = document.createElement('style');
                        cosmeticStyleEl.id = 'onyx-universal-cosmetic';
                        cosmeticStyleEl.type = 'text/css';
                        var target = document.head || document.documentElement;
                        if (target) target.appendChild(cosmeticStyleEl);
                    }
                    return cosmeticStyleEl;
                }

                function flushPendingSelectors() {
                    if (pendingClasses.size === 0 && pendingIds.size === 0) return;
                    if (!window.OnyxShieldBridge || typeof window.OnyxShieldBridge.getHiddenSelectors !== 'function') return;

                    var classesArr = Array.from(pendingClasses);
                    var idsArr = Array.from(pendingIds);
                    pendingClasses.clear();
                    pendingIds.clear();

                    try {
                        var resJson = window.OnyxShieldBridge.getHiddenSelectors(
                            JSON.stringify(classesArr),
                            JSON.stringify(idsArr),
                            location.href
                        );
                        if (!resJson || resJson === '[]') return;
                        var selectors = JSON.parse(resJson);
                        if (selectors && selectors.length > 0) {
                            // Filter out any selectors that could match video, audio, or media player wrappers
                            var safeSelectors = selectors.filter(function(sel) {
                                if (!sel || typeof sel !== 'string') return false;
                                return !/(video|audio|player|stream|media|vjs|jwplayer|html5|playing|paused)/i.test(sel);
                            });
                            if (safeSelectors.length > 0) {
                                var styleTag = getOrCreateCosmeticStyle();
                                if (styleTag) {
                                    var cssRule = safeSelectors.join(', ') + ' { display: none !important; }\n';
                                    styleTag.appendChild(document.createTextNode(cssRule));
                                    var target = document.head || document.documentElement || document.body;
                                    if (target && !document.contains(styleTag)) {
                                        target.appendChild(styleTag);
                                    }
                                }
                            }
                        }
                    } catch(e) {}
                }

                function scheduleQuery() {
                    if (queryTimer) return;
                    queryTimer = setTimeout(function() {
                        queryTimer = null;
                        flushPendingSelectors();
                    }, 50);
                }

                function inspectElement(el) {
                    if (!el || el.nodeType !== 1) return;
                    var id = el.id;
                    if (id && typeof id === 'string') {
                        var idSel = '#' + id;
                        if (!seenSelectors.has(idSel)) {
                            seenSelectors.add(idSel);
                            pendingIds.add(id);
                        }
                    }
                    var classList = el.classList;
                    if (classList && classList.length > 0) {
                        for (var i = 0; i < classList.length; i++) {
                            var cls = classList[i];
                            if (cls) {
                                var clsSel = '.' + cls;
                                if (!seenSelectors.has(clsSel)) {
                                    seenSelectors.add(clsSel);
                                    pendingClasses.add(cls);
                                }
                            }
                        }
                    }
                }

                function inspectSubtree(root) {
                    if (!root || root.nodeType !== 1) return;
                    inspectElement(root);
                    var elms = root.querySelectorAll('[id], [class]');
                    for (var i = 0; i < elms.length; i++) {
                        inspectElement(elms[i]);
                    }
                    if (pendingClasses.size > 0 || pendingIds.size > 0) {
                        scheduleQuery();
                    }
                }

                // Initial baseline collapsed styles
                var initStyle = getOrCreateCosmeticStyle();
                initStyle.textContent = [
                    '#cts_test, #ctd_test, #ad_ctd, .ad-banner, .adsbox, .textads, .banner-ad, .banner_ads,',
                    'ins.adsbygoogle, [id*="google_ads"], [class*="google-ads"], [class*="sponsor-ad"],',
                    '.afs_ads, .sponsor-content, .commercial-unit, #banner-ad, #ad-banner,',
                    '.dfp-ad-container, .taboola-ad, .outbrain-ad,',
                    '[class*="native-ad"], [id*="native-ad"], .ad-placeholder, .advertisement-box,',
                    '.adblockHostDiv_probe, [id*="ad_banner"], [id*="ad_unit"], [class*="sponsored-item"]' +
                    (window.__onyx_blocking_level === 1 ? ', #onetrust-banner-sdk, #cookie-law-info-bar, .cc-window, .qc-cmp2-container, #CybotCookiebotDialog' : '') + ' {',
                    '  display: none !important;',
                    '  visibility: hidden !important;',
                    '  height: 0 !important;',
                    '  width: 0 !important;',
                    '  opacity: 0 !important;',
                    '  pointer-events: none !important;',
                    '  position: absolute !important;',
                    '  left: -9999px !important;',
                    '}'
                ].join('\n');

                // Inspect any DOM elements currently available
                if (document.documentElement) {
                    inspectSubtree(document.documentElement);
                    flushPendingSelectors();
                }

                // MutationObserver for dynamic insertions, interstitials, and dynamic ads
                var cosmeticObserver = new MutationObserver(function(mutations) {
                    var hasNew = false;
                    for (var i = 0; i < mutations.length; i++) {
                        var m = mutations[i];
                        if (m.type === 'childList') {
                            for (var j = 0; j < m.addedNodes.length; j++) {
                                var node = m.addedNodes[j];
                                if (node.nodeType === 1) {
                                    inspectElement(node);
                                    if (node.firstElementChild) {
                                        var nodes = node.querySelectorAll('[id], [class]');
                                        for (var k = 0; k < nodes.length; k++) {
                                            inspectElement(nodes[k]);
                                        }
                                    }
                                    hasNew = true;
                                }
                            }
                        } else if (m.type === 'attributes') {
                            inspectElement(m.target);
                            hasNew = true;
                        }
                    }
                    if (hasNew && (pendingClasses.size > 0 || pendingIds.size > 0)) {
                        scheduleQuery();
                    }
                });

                function startCosmeticObserver() {
                    var target = document.documentElement || document.body;
                    if (target) {
                        try {
                            cosmeticObserver.observe(target, {
                                subtree: true,
                                childList: true,
                                attributeFilter: ['id', 'class']
                            });
                        } catch(e) {}
                        inspectSubtree(target);
                        flushPendingSelectors();
                    }
                }

                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', startCosmeticObserver);
                } else {
                    startCosmeticObserver();
                }
            } catch(e) {}

            // ── 9. Universal In-Memory Blob & ObjectURL Preserver ────────────────────
            try {
                window.__onyxBlobStore = window.__onyxBlobStore || new Map();
                window.__onyxLastBlobDownload = null;

                var origCreateObjectURL = (window.URL && window.URL.createObjectURL) || (window.webkitURL && window.webkitURL.createObjectURL);
                var origRevokeObjectURL = (window.URL && window.URL.revokeObjectURL) || (window.webkitURL && window.webkitURL.revokeObjectURL);

                if (origCreateObjectURL) {
                    var patchedCreateObjectURL = function(blob) {
                        var url = origCreateObjectURL.apply(window.URL || window.webkitURL, arguments);
                        try {
                            if (blob && (blob instanceof Blob || blob instanceof File)) {
                                if (window.__onyxBlobStore.size > 50) {
                                    var firstKey = window.__onyxBlobStore.keys().next().value;
                                    window.__onyxBlobStore.delete(firstKey);
                                }
                                window.__onyxBlobStore.set(url, {
                                    blob: blob,
                                    name: (blob instanceof File && blob.name) ? blob.name : null,
                                    type: blob.type || 'application/octet-stream',
                                    size: blob.size || 0,
                                    created: Date.now()
                                });
                            }
                        } catch(e) {}
                        return url;
                    };
                    if (window.URL) window.URL.createObjectURL = patchedCreateObjectURL;
                    if (window.webkitURL) window.webkitURL.createObjectURL = patchedCreateObjectURL;
                }

                if (origRevokeObjectURL) {
                    var patchedRevokeObjectURL = function(url) {
                        // Delay actual revocation by 60 seconds so Android WebView
                        // asynchronous DownloadListener can reliably retrieve it.
                        setTimeout(function() {
                            try {
                                origRevokeObjectURL.call(window.URL || window.webkitURL, url);
                            } catch(e) {}
                            try {
                                if (window.__onyxBlobStore) {
                                    window.__onyxBlobStore.delete(url);
                                }
                            } catch(e) {}
                        }, 60000);
                    };
                    if (window.URL) window.URL.revokeObjectURL = patchedRevokeObjectURL;
                    if (window.webkitURL) window.webkitURL.revokeObjectURL = patchedRevokeObjectURL;
                }

                function captureAnchorDownload(elem) {
                    try {
                        if (!elem) return;
                        var href = elem.href || elem.getAttribute('href') || '';
                        var downloadAttr = elem.getAttribute('download') || elem.download || '';
                        if (href && href.startsWith('blob:')) {
                            window.__onyxLastBlobDownload = {
                                url: href,
                                fileName: downloadAttr || '',
                                time: Date.now()
                            };
                            if (window.__onyxBlobStore && window.__onyxBlobStore.has(href)) {
                                var entry = window.__onyxBlobStore.get(href);
                                if (entry && downloadAttr && !entry.name) {
                                    entry.name = downloadAttr;
                                }
                            }
                        }
                    } catch(e) {}
                }

                if (window.HTMLAnchorElement && window.HTMLAnchorElement.prototype) {
                    var origAnchorClick = HTMLAnchorElement.prototype.click;
                    HTMLAnchorElement.prototype.click = function() {
                        captureAnchorDownload(this);
                        return origAnchorClick.apply(this, arguments);
                    };
                }

                document.addEventListener('click', function(e) {
                    var target = e.target;
                    while (target && target !== document) {
                        if (target.tagName === 'A') {
                            captureAnchorDownload(target);
                            break;
                        }
                        target = target.parentElement;
                    }
                }, true);
            } catch(e) {}
        })();
        """.trimIndent()
    }
}
