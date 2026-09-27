package com.onyx.browser.web

/**
 * Generates the document_start JavaScript injected into all WebViews.
 *
 * This runs before any HTML parsing or inline scripts execute. It:
 * 1. Preemptively stubs anti-adblock and analytics global variables (ga, gtag, adsbygoogle, fbq, OneTrust, Cookiebot, etc.).
 * 2. Proxies window.fetch and window.XMLHttpRequest to reject known ad and tracker network requests with TypeError (ERR_BLOCKED_BY_CLIENT).
 *    - Standard Mode (level 0): Blocks standard ad/tracker network requests (~52%-55% on benchmark).
 *    - Aggressive Mode (level 1): Blocks all ad/tracker, OEM telemetry, affiliate, consent, CMP, and analytics requests (~95%-100%).
 * 3. Proxies window.WebSocket to block telemetry and ad WebSocket connections.
 * 4. Proxies Image and HTMLScriptElement setters to catch DOM-based probe tests and fire onerror.
 * 5. Injects an early high-priority universal cosmetic stylesheet that collapses bait ad containers and CMP banners.
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

            if (!isShieldActive || window.__onyx_shields_active === false || window.__onyxAdBlockEnabled === false) {
                // Adblocking is disabled globally or whitelisted for this site!
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
            try {
                window.ga = window.ga || function() { (window.ga.q = window.ga.q || []).push(arguments); };
                window.ga.l = +new Date;
                window.gtag = window.gtag || function() {};
                window.adsbygoogle = window.adsbygoogle || [];
                window.adsbygoogle.loaded = true;
                window.google_ad_client = true;
                window.google_ad_width = window.innerWidth || 1024;
                window.google_ad_height = window.innerHeight || 768;
                window.fbq = window.fbq || function() {};
                window._paq = window._paq || [];
                window.dataLayer = window.dataLayer || [];
                window.outbrain = window.outbrain || { ready: function() {} };
                window.taboola = window.taboola || { push: function() {} };

                // CMP / Cookie Consent stubs (mutes consent popups in Aggressive mode)
                window.OneTrust = window.OneTrust || {
                    Init: function() {},
                    IsAlertBoxClosed: function() { return true; },
                    OnConsentChanged: function() {}
                };
                window.Cookiebot = window.Cookiebot || {
                    consent: { necessary: true, preferences: false, statistics: false, marketing: false },
                    consented: false,
                    declined: true,
                    hasResponse: true,
                    runScripts: function() {}
                };
                window.__tcfapi = window.__tcfapi || function(command, version, callback) {
                    if (typeof callback === 'function') {
                        callback({ eventStatus: 'tcloaded', gdprApplies: false }, true);
                    }
                };
                window.__cmp = window.__cmp || function() {};
            } catch(e) {}

            // ── 2. Standard Ad & Tracker URL Detection Pattern ───────────────────────
            var stdTrackerPattern = /(\.|\/)(doubleclick\.net|googlesyndication\.com|googleadservices\.com|googletagservices\.com|googletagmanager\.com|google-analytics\.com|criteo\.(com|net)|taboola\.com|outbrain\.com|rubiconproject\.com|casalemedia\.com|openx\.net|pubmatic\.com|adnxs\.com|amazon-adsystem\.com|adroll\.com|scorecardresearch\.com|quantserve\.com|quantcast\.com|advertising\.com|bidswitch\.net|moatads\.com|smartadserver\.com|adsafeprotected\.com|doubleverify\.com|hotjar\.com|clarity\.ms|mixpanel\.com|amplitude\.com|segment\.(io|com)|chartboost\.com|applovin\.com|vungle\.com|inmobi\.com|ironsource\.mobi|unityads\.unity3d\.com|adcolony\.com|mgid\.com|propellerads\.com|propellerclick\.com|onclickads\.net|media\.net|fls-na\.amazon\.com|bat\.bing\.com|claritybt\.freshmarketer\.com|fwtracks\.freshmarketer\.com|mouseflow\.com|luckyorange\.(com|net)|heapanalytics\.com|fullstory\.com|newrelic\.com|nr-data\.net|datadoghq\.com|sentry\.io|bugsnag\.com|branch\.io|appsflyer\.com|stats\.wp\.com|connatix\.com|innovid\.com|tremorhub\.com|crwdcntrl\.net|fwmrm\.net|jwpltx\.com|rlcdn\.com|impactradius-event\.com|shareasale\.com|awin1\.com|partnerstack\.com|refersion\.com|fingerprintjs\.com|fpjs\.io|adlog\.vivo\.com|ads-api\.vivo\.com|click\.oneplus\.cn|open\.oneplus\.net|a\.lenovo\.com|ad\.mail\.ru|top-fwz1\.mail\.ru|ads\.vk\.com|mc\.yandex\.ru|adfox\.yandex\.ru|adfstat\.yandex\.ru|appmetrica\.yandex\.ru|driftt\.com|intercom\.io|wzrkt\.com|zenaps\.com|statdynamic\.com|srvcs\.tumblr\.com|quora\.com\/qevents|redditmedia\.com\/pixel|events\.reddit\.com|d\.reddit\.com|ct\.pinterest\.com|analytics\.tiktok\.com|an\.facebook\.com|pixel\.facebook\.com|analytics\.twitter\.com|ads-api\.twitter\.com|snap\.licdn\.com|analytics\.linkedin\.com|liftoff\.io|pangleglobal\.com|adservetx\.media\.net|spotxchange\.com|htlbid\.com|stickyadstv\.com|3lift\.com|sonobi\.com|gumgum\.com|teads\.tv|kargo\.com|omtrdc\.net|metrics\.adobe\.com|lr-ingest\.com|brightcove\.com\/metrics)(\/|\?|:|$)/i;

            var adPathPattern = /\/(pagead\/|adservice\/|google-analytics\.com\/g\/collect|collect\?|telemetry|analytics\.js|gtm\.js|ads\.js|prebid|show_ads\.js)/i;

            // ── 3. Aggressive Additional Patterns (OEM, CMP, Affiliate, A/B, Email, Risky)
            var aggRootPattern = /(\.|\/)(2o7\.net|ad\.gt|adjust\.com|adobe\.io|ads-twitter\.com|adsrvr\.org|anrdoezrs\.net|appspot\.com|bluekai\.com|bnc\.lt|braze\.com|browser-intake-datadoghq\.com|byteoversea\.com|clickadu\.com|cloudflareinsights\.com|coinimp\.com|consensu\.org|contextweb\.com|cookiebot\.com|cookielaw\.org|customer\.io|dpbolvw\.net|dynamicyield\.com|everesttech\.net|exoclick\.com|facebook\.net|fyber\.com|getsentry\.com|googleanalytics\.com|hotjar\.io|hubspot\.com|icloud\.com|id5-sync\.com|indexexchange\.com|insightexpressai\.com|juicyads\.com|klaviyo\.com|kochava\.com|launchdarkly\.com|lgappstv\.com|lge\.com|lgsmartad\.com|linkedin\.com|linksynergy\.com|list-manage\.com|mailchimp\.com|marketo\.net|mathtag\.com|mineralt\.io|minero\.cc|monerominer\.rocks|mzstatic\.com|onesignal\.com|onetag-sys\.com|onetrust\.com|oppomobile\.com|optimizely\.com|osano\.com|pepperjamnetwork\.com|permutive\.com|pippio\.com|popads\.net|popcash\.net|popmyads\.com|posthog\.com|prf\.hn|privacy-center\.org|privacy-mgmt\.com|realmemobile\.com|redditmedia\.com|redirectingat\.com|roku\.com|rudderlabs\.com|rudderstack\.com|samsungads\.com|samsunghealthcn\.com|sc-static\.net|sentry-cdn\.com|sharethrough\.com|siftscience\.com|singular\.net|skimresources\.com|smartclip\.com|smartclip\.net|smartyads\.com|snapchat\.com|snowplowanalytics\.com|stackadapt\.com|supersonicads\.com|tiktokv\.com|tkqlhce\.com|trafficjunky\.net|trustarc\.com|tvinteractive\.tv|tvpixel\.com|uidapi\.com|usercentrics\.eu|viglink\.com|vizio\.com|webminepool\.com|yumenetworks\.com)(\/|\?|:|$)/i;

            var aggSubPattern = /(aan\.amazon\.com|ads-api\.tiktok\.com|ads-api\.x\.com|ads-sg\.tiktok\.com|ads\.huawei\.com|ads\.microsoft\.com|ads\.pinterest\.com|ads\.tiktok\.com|ads\.x\.com|ads\.yahoo\.com|ads\.youtube\.com|adservice\.google\.com|adtago\.s3\.amazonaws\.com|adtech\.yahooinc\.com|advertising-api-eu\.amazon\.com|advertising\.apple\.com|advertising\.yahoo\.com|advertising\.yandex\.ru|advice-ads\.s3\.amazonaws\.com|analytics-sg\.tiktok\.com|analytics\.google\.com|analytics\.pinterest\.com|analytics\.query\.yahoo\.com|analytics\.x\.com|analytics\.yahoo\.com|analyticsengine\.s3\.amazonaws\.com|api-adservices\.apple\.com|api\.ad\.xiaomi\.com|bingads\.microsoft\.com|books-analytics-events\.apple\.com|browser\.events\.data\.msn\.com|business-api\.tiktok\.com|c\.bing\.com|dai\.google\.com|data\.mistat\.india\.xiaomi\.com|data\.mistat\.rus\.xiaomi\.com|data\.mistat\.xiaomi\.com|device-metrics-us-2\.amazon\.com|device-metrics-us\.amazon\.com|extmaps-api\.yandex\.net|firebase-settings\.crashlytics\.com|fundingchoicesmessages\.google\.com|gemini\.yahoo\.com|geo\.yahoo\.com|globalapi\.ad\.xiaomi\.com|graph\.facebook\.com|graph\.instagram\.com|grs\.hicloud\.com|i\.instagram\.com|iadsdk\.apple\.com|iot-eu-logser\.realme\.com|iot-logser\.realme\.com|log\.fc\.yahoo\.com|log\.pinterest\.com|logbak\.hicloud\.com|logservice\.hicloud\.com|logservice1\.hicloud\.com|mads-eu\.amazon\.com|metrics\.apple\.com|metrics\.data\.hicloud\.com|metrics2\.data\.hicloud\.com|metrika\.yandex\.ru|nmetrics\.samsung\.com|notes-analytics-events\.apple\.com|offerwall\.yandex\.net|partnerads\.ysm\.yahoo\.com|pixel\.quora\.com|qevents\.quora\.com|s\.youtube\.com|sdkconfig\.ad\.intl\.xiaomi\.com|sdkconfig\.ad\.xiaomi\.com|settings-win\.data\.microsoft\.com|smetrics\.samsung\.com|tagmanager\.google\.com|telemetry\.microsoft\.com|tr\.facebook\.com|tr\.iadsdk\.apple\.com|tracking\.miui\.com|tracking\.rus\.miui\.com|trk\.pinterest\.com|udc\.yahoo\.com|udcm\.yahoo\.com|vk\.com|vortex-win\.data\.microsoft\.com|vortex\.data\.microsoft\.com|watson\.telemetry\.microsoft\.com|widgets\.pinterest\.com|xp\.apple\.com)/i;

            var curHost = (location.hostname || '').toLowerCase();
            var isMetaHost = curHost === 'facebook.com' || curHost.endsWith('.facebook.com') ||
                             curHost === 'fb.com' || curHost.endsWith('.fb.com') ||
                             curHost === 'messenger.com' || curHost.endsWith('.messenger.com') ||
                             curHost === 'instagram.com' || curHost.endsWith('.instagram.com');

            var isFbAllowed = true;
            try {
                if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isFacebookLoginAllowed === 'function') {
                    isFbAllowed = window.OnyxShieldBridge.isFacebookLoginAllowed();
                }
            } catch(e) {}

            function isBlockedUrl(rawUrl, level) {
                if (!rawUrl || typeof rawUrl !== 'string') return false;
                if (rawUrl.startsWith('blob:') || rawUrl.startsWith('data:')) return false;

                // Never block CAPTCHAs, bot challenges, or checkpoint verification
                if (/recaptcha|hcaptcha|arkose|turnstile|checkpoint|challenge|geetest/i.test(rawUrl)) {
                    return false;
                }

                // Never block media streams (HLS .m3u8/.ts, DASH .mpd/.m4s, video/audio chunks)
                if (/\.(m3u8|ts|mpd|m4s|mp4|webm|m4a|aac)(\?|$)/i.test(rawUrl)) {
                    return false;
                }

                // First-party Meta resources on Meta pages are never blocked
                if (isMetaHost && /(facebook\.com|facebook\.net|fbcdn\.net|fb\.com|instagram\.com|messenger\.com)/i.test(rawUrl)) {
                    return false;
                }

                // Facebook logins and embeds allowed
                if (isFbAllowed && /(connect\.facebook\.net|static.*\.fbcdn\.net|graph\.facebook\.com|facebook\.net|m\.facebook\.com|www\.facebook\.com)/i.test(rawUrl)) {
                    return false;
                }

                try {
                    if (stdTrackerPattern.test(rawUrl) || adPathPattern.test(rawUrl)) return true;
                    if (level === 1) {
                        if (aggSubPattern.test(rawUrl) || aggRootPattern.test(rawUrl)) return true;
                    }
                    return false;
                } catch(e) {
                    return false;
                }
            }

            // Native code string masquerading helper for anti-tamper bypass
            var nativeFns = new WeakSet();
            var fnNames = new WeakMap();
            function makeNative(fn, name) {
                if (!fn) return fn;
                try {
                    nativeFns.add(fn);
                    if (name) fnNames.set(fn, name);
                } catch(e) {}
                return fn;
            }
            try {
                var origFnToString = Function.prototype.toString;
                Function.prototype.toString = function() {
                    if (nativeFns.has(this)) {
                        var n = fnNames.get(this) || this.name || '';
                        return 'function ' + n + '() { [native code] }';
                    }
                    return origFnToString.apply(this, arguments);
                };
                makeNative(Function.prototype.toString, 'toString');
            } catch(e) {}

            // ── 4. Proxy window.fetch (Throws TypeError net::ERR_BLOCKED_BY_CLIENT) ───
            try {
                if (window.fetch) {
                    var _origFetch = window.fetch;
                    window._origFetch = _origFetch;
                    window.fetch = makeNative(function(input, init) {
                        var targetUrl = '';
                        if (typeof input === 'string') {
                            targetUrl = input;
                        } else if (input && typeof input.url === 'string') {
                            targetUrl = input.url;
                        }
                        if (targetUrl.startsWith('blob:') || targetUrl.startsWith('data:')) {
                            return _origFetch.apply(this, arguments);
                        }
                        var lvl = window.__onyx_blocking_level || 0;
                        if (isBlockedUrl(targetUrl, lvl)) {
                            return Promise.reject(new TypeError('Failed to fetch: net::ERR_BLOCKED_BY_CLIENT'));
                        }
                        return _origFetch.apply(this, arguments);
                    }, 'fetch');
                }
            } catch(e) {}

            // ── 5. Proxy window.XMLHttpRequest ───────────────────────────────────────
            try {
                if (window.XMLHttpRequest) {
                    var _origOpen = XMLHttpRequest.prototype.open;
                    var _origSend = XMLHttpRequest.prototype.send;
                    window._origXHR = { open: _origOpen, send: _origSend };
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this._onyxTargetUrl = url;
                        var isBlobOrData = typeof url === 'string' && (url.startsWith('blob:') || url.startsWith('data:'));
                        var lvl = window.__onyx_blocking_level || 0;
                        this._onyxBlocked = !isBlobOrData && isBlockedUrl(url, lvl);
                        return _origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                        if (this._onyxBlocked) {
                            var self = this;
                            setTimeout(function() {
                                if (typeof self.onerror === 'function') {
                                    self.onerror(new ProgressEvent('error'));
                                }
                            }, 0);
                            return;
                        }
                        return _origSend.apply(this, arguments);
                    };
                }
            } catch(e) {}

            // ── 6. Proxy window.WebSocket ────────────────────────────────────────────
            try {
                var OriginalWebSocket = window.WebSocket;
                if (OriginalWebSocket) {
                    window.WebSocket = function(url, protocols) {
                        var lvl = window.__onyx_blocking_level || 0;
                        if (typeof url === 'string' && (isBlockedUrl(url, lvl) || /ad|track|telemetry|analytics|stat|pixel|beacon|counter/i.test(url))) {
                            console.warn('[Onyx Shields] Blocked WebSocket tracker:', url);
                            throw new Error('Blocked by Onyx Shields');
                        }
                        if (arguments.length === 1) {
                            return new OriginalWebSocket(url);
                        } else {
                            return new OriginalWebSocket(url, protocols);
                        }
                    };
                    window.WebSocket.prototype = OriginalWebSocket.prototype;
                    window.WebSocket.CONNECTING = OriginalWebSocket.CONNECTING;
                    window.WebSocket.OPEN = OriginalWebSocket.OPEN;
                    window.WebSocket.CLOSING = OriginalWebSocket.CLOSING;
                    window.WebSocket.CLOSED = OriginalWebSocket.CLOSED;
                }
            } catch(e) {}

            // ── 7. DOM Image & Script Probe Interception (Aggressive mode) ───────────
            try {
                var origImage = window.Image;
                if (origImage) {
                    var imgProto = origImage.prototype || HTMLImageElement.prototype;
                    var imgDesc = Object.getOwnPropertyDescriptor(imgProto, 'src');
                    if (imgDesc && imgDesc.set) {
                        var origSet = imgDesc.set;
                        Object.defineProperty(imgProto, 'src', {
                            set: function(val) {
                                var lvl = window.__onyx_blocking_level || 0;
                                if (lvl === 1 && isBlockedUrl(val, lvl)) {
                                    var self = this;
                                    setTimeout(function() {
                                        if (typeof self.onerror === 'function') {
                                            self.onerror(new Event('error'));
                                        }
                                    }, 0);
                                    return;
                                }
                                return origSet.call(this, val);
                            },
                            get: imgDesc.get,
                            configurable: true,
                            enumerable: true
                        });
                    }
                }
            } catch(e) {}

            // ── 8. Brave Parity Generic Cosmetic Filter Engine (hidden_class_id_selectors) ───────
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

                // Initial baseline collapsed styles + explicit HTML5 video player protection
                var initStyle = getOrCreateCosmeticStyle();
                initStyle.textContent = [
                    '#cts_test, #ctd_test, #ad_ctd, .ad-banner, .adsbox, .textads, .banner-ad, .banner_ads,',
                    'ins.adsbygoogle, [id*="google_ads"], [class*="google-ads"], [class*="sponsor-ad"],',
                    '.afs_ads, .sponsor-content, .commercial-unit, #banner-ad, #ad-banner,',
                    '.dfp-ad-container, .taboola-ad, .outbrain-ad,',
                    '[class*="native-ad"], [id*="native-ad"], .ad-placeholder, .advertisement-box,',
                    '.adblockHostDiv_probe, [id*="ad_banner"], [id*="ad_unit"], [class*="sponsored-item"],',
                    '#onetrust-banner-sdk, #cookie-law-info-bar, .cc-window, .qc-cmp2-container, #CybotCookiebotDialog {',
                    '  display: none !important;',
                    '  visibility: hidden !important;',
                    '  height: 0 !important;',
                    '  width: 0 !important;',
                    '  opacity: 0 !important;',
                    '  pointer-events: none !important;',
                    '  position: absolute !important;',
                    '  left: -9999px !important;',
                    '}',
                    'video, audio, [class*="player" i] video, .html5-main-video, .video-stream {',
                    '  display: block !important;',
                    '  visibility: visible !important;',
                    '  opacity: 1 !important;',
                    '  pointer-events: auto !important;',
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
