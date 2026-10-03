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
                                      host.endsWith('fbcdn.net') || host.endsWith('facebook.net') ||
                                      host.indexOf('arkose') !== -1 || host.indexOf('recaptcha') !== -1 ||
                                      host.indexOf('hcaptcha') !== -1 || host.indexOf('turnstile') !== -1 ||
                                      host.indexOf('funcaptcha') !== -1 || host.indexOf('datadome') !== -1 ||
                                      host.indexOf('perimeterx') !== -1 || host.indexOf('kasada') !== -1 ||
                                      path.indexOf('/checkpoint/') !== -1 || path.indexOf('/challenge/') !== -1 ||
                                      path.indexOf('/captcha/') !== -1 || href.indexOf('/checkpoint/') !== -1;

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

            // ── Function signature masking helper ──────────────────────────────────
            function makeNative(fn, name) {
                try {
                    fn.toString = function() { return 'function ' + name + '() { [native code] }'; };
                    Object.defineProperty(fn, 'name', { value: name, configurable: true });
                } catch(_) {}
                return fn;
            }

            // ── Standard Ad & Tracker URL Detection Patterns ──────────────────────
            var stdTrackerPattern = /(\.|\/)(doubleclick\.net|googlesyndication\.com|googleadservices\.com|googletagservices\.com|googletagmanager\.com|google-analytics\.com|criteo\.(com|net)|taboola\.com|outbrain\.com|rubiconproject\.com|casalemedia\.com|openx\.net|pubmatic\.com|adnxs\.com|amazon-adsystem\.com|adroll\.com|scorecardresearch\.com|quantserve\.com|quantcast\.com|advertising\.com|bidswitch\.net|moatads\.com|smartadserver\.com|adsafeprotected\.com|doubleverify\.com|hotjar\.com|clarity\.ms|mixpanel\.com|amplitude\.com|segment\.(io|com)|chartboost\.com|applovin\.com|vungle\.com|inmobi\.com|ironsource\.mobi|unityads\.unity3d\.com|adcolony\.com|mgid\.com|propellerads\.com|propellerclick\.com|onclickads\.net|media\.net|fls-na\.amazon\.com|bat\.bing\.com|claritybt\.freshmarketer\.com|fwtracks\.freshmarketer\.com|mouseflow\.com|luckyorange\.(com|net)|heapanalytics\.com|fullstory\.com|newrelic\.com|nr-data\.net|datadoghq\.com|sentry\.io|bugsnag\.com|branch\.io|appsflyer\.com|stats\.wp\.com|connatix\.com|innovid\.com|tremorhub\.com|crwdcntrl\.net|fwmrm\.net|jwpltx\.com|rlcdn\.com|impactradius-event\.com|shareasale\.com|awin1\.com|partnerstack\.com|refersion\.com|fingerprintjs\.com|fpjs\.io|adlog\.vivo\.com|ads-api\.vivo\.com|click\.oneplus\.cn|open\.oneplus\.net|a\.lenovo\.com|ad\.mail\.ru|top-fwz1\.mail\.ru|ads\.vk\.com|mc\.yandex\.ru|adfox\.yandex\.ru|adfstat\.yandex\.ru|appmetrica\.yandex\.ru|driftt\.com|intercom\.io|wzrkt\.com|zenaps\.com|statdynamic\.com|srvcs\.tumblr\.com|quora\.com\/qevents|redditmedia\.com\/pixel|events\.reddit\.com|d\.reddit\.com|ct\.pinterest\.com|analytics\.tiktok\.com|an\.facebook\.com|pixel\.facebook\.com|analytics\.twitter\.com|ads-api\.twitter\.com|snap\.licdn\.com|analytics\.linkedin\.com|liftoff\.io|pangleglobal\.com|adservetx\.media\.net|spotxchange\.com|htlbid\.com|stickyadstv\.com|3lift\.com|sonobi\.com|gumgum\.com|teads\.tv|kargo\.com|omtrdc\.net|metrics\.adobe\.com|lr-ingest\.com|brightcove\.com\/metrics)(\/|\?|:|$)/i;

            var adPathPattern = /\/(pagead\/|adservice\/|google-analytics\.com\/g\/collect|collect\?|telemetry|analytics\.js|gtm\.js|ads\.js|prebid|show_ads\.js)/i;

            var aggRootPattern = /(\.|\/)(2o7\.net|ad\.gt|adjust\.com|adobe\.io|ads-twitter\.com|adsrvr\.org|anrdoezrs\.net|appspot\.com|bluekai\.com|bnc\.lt|braze\.com|browser-intake-datadoghq\.com|byteoversea\.com|clickadu\.com|cloudflareinsights\.com|coinimp\.com|consensu\.org|contextweb\.com|cookiebot\.com|cookielaw\.org|customer\.io|dpbolvw\.net|dynamicyield\.com|everesttech\.net|exoclick\.com|fyber\.com|getsentry\.com|googleanalytics\.com|hotjar\.io|hubspot\.com|icloud\.com|id5-sync\.com|indexexchange\.com|insightexpressai\.com|juicyads\.com|klaviyo\.com|kochava\.com|launchdarkly\.com|lgappstv\.com|lge\.com|lgsmartad\.com|linkedin\.com|linksynergy\.com|list-manage\.com|mailchimp\.com|marketo\.net|mathtag\.com|mineralt\.io|minero\.cc|monerominer\.rocks|mzstatic\.com|onesignal\.com|onetag-sys\.com|onetrust\.com|oppomobile\.com|optimizely\.com|osano\.com|pepperjamnetwork\.com|permutive\.com|pippio\.com|popads\.net|popcash\.net|popmyads\.com|posthog\.com|prf\.hn|privacy-center\.org|privacy-mgmt\.com|realmemobile\.com|redditmedia\.com|redirectingat\.com|roku\.com|rudderlabs\.com|rudderstack\.com|samsungads\.com|samsunghealthcn\.com|sc-static\.net|sentry-cdn\.com|sharethrough\.com|siftscience\.com|singular\.net|skimresources\.com|smartclip\.com|smartclip\.net|smartyads\.com|snapchat\.com|snowplowanalytics\.com|stackadapt\.com|supersonicads\.com|tiktokv\.com|tkqlhce\.com|trafficjunky\.net|trustarc\.com|tvinteractive\.tv|tvpixel\.com|uidapi\.com|usercentrics\.eu|viglink\.com|vizio\.com|webminepool\.com|yumenetworks\.com)(\/|\?|:|$)/i;

            var aggSubPattern = /(aan\.amazon\.com|ads-api\.tiktok\.com|ads-api\.x\.com|ads-sg\.tiktok\.com|ads\.huawei\.com|ads\.microsoft\.com|ads\.pinterest\.com|ads\.tiktok\.com|ads\.x\.com|ads\.yahoo\.com|ads\.youtube\.com|adservice\.google\.com|adtago\.s3\.amazonaws\.com|adtech\.yahooinc\.com|advertising-api-eu\.amazon\.com|advertising\.apple\.com|advertising\.yahoo\.com|advertising\.yandex\.ru|advice-ads\.s3\.amazonaws\.com|analytics-sg\.tiktok\.com|analytics\.google\.com|analytics\.pinterest\.com|analytics\.query\.yahoo\.com|analytics\.x\.com|analytics\.yahoo\.com|analyticsengine\.s3\.amazonaws\.com|api-adservices\.apple\.com|api\.ad\.xiaomi\.com|bingads\.microsoft\.com|books-analytics-events\.apple\.com|browser\.events\.data\.msn\.com|business-api\.tiktok\.com|c\.bing\.com|dai\.google\.com|data\.mistat\.india\.xiaomi\.com|data\.mistat\.rus\.xiaomi\.com|data\.mistat\.xiaomi\.com|device-metrics-us-2\.amazon\.com|device-metrics-us\.amazon\.com|extmaps-api\.yandex\.net|firebase-settings\.crashlytics\.com|fundingchoicesmessages\.google\.com|gemini\.yahoo\.com|geo\.yahoo\.com|globalapi\.ad\.xiaomi\.com|graph\.facebook\.com|graph\.instagram\.com|grs\.hicloud\.com|i\.instagram\.com|iadsdk\.apple\.com|iot-eu-logser\.realme\.com|iot-logser\.realme\.com|log\.fc\.yahoo\.com|log\.pinterest\.com|logbak\.hicloud\.com|logservice\.hicloud\.com|logservice1\.hicloud\.com|mads-eu\.amazon\.com|metrics\.apple\.com|metrics\.data\.hicloud\.com|metrics2\.data\.hicloud\.com|metrika\.yandex\.ru|nmetrics\.samsung\.com|notes-analytics-events\.apple\.com|offerwall\.yandex\.net|partnerads\.ysm\.yahoo\.com|pixel\.quora\.com|qevents\.quora\.com|s\.youtube\.com|sdkconfig\.ad\.intl\.xiaomi\.com|sdkconfig\.ad\.xiaomi\.com|settings-win\.data\.microsoft\.com|smetrics\.samsung\.com|tagmanager\.google\.com|telemetry\.microsoft\.com|tr\.facebook\.com|tr\.iadsdk\.apple\.com|tracking\.miui\.com|tracking\.rus\.miui\.com|trk\.pinterest\.com|udc\.yahoo\.com|udcm\.yahoo\.com|vk\.com|vortex-win\.data\.microsoft\.com|vortex\.data\.microsoft\.com|watson\.telemetry\.microsoft\.com|widgets\.pinterest\.com|xp\.apple\.com)/i;

            function isBlockedUrl(rawUrl, level) {
                if (!rawUrl || typeof rawUrl !== 'string') return false;
                if (rawUrl.startsWith('blob:') || rawUrl.startsWith('data:') || rawUrl.startsWith('javascript:')) return false;

                // Never block CAPTCHAs, bot challenges, or checkpoint verification
                if (/recaptcha|hcaptcha|arkose|turnstile|checkpoint|challenge|geetest|funcaptcha|datadome|perimeterx/i.test(rawUrl)) {
                    return false;
                }

                // Authentication and Facebook endpoints are never blocked
                if (/(facebook\.com|facebook\.net|fbcdn\.net|fb\.com|instagram\.com|messenger\.com)/i.test(rawUrl)) {
                    return false;
                }

                try {
                    if (stdTrackerPattern.test(rawUrl) || adPathPattern.test(rawUrl)) return true;
                    if (level === 1) {
                        if (aggSubPattern.test(rawUrl) || aggRootPattern.test(rawUrl)) return true;
                    }
                    if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isUrlBlocked === 'function') {
                        return window.OnyxShieldBridge.isUrlBlocked(rawUrl, location.href);
                    }
                    return false;
                } catch(e) {
                    return false;
                }
            }

            // ── 1. Proxy window.fetch (Throws TypeError net::ERR_BLOCKED_BY_CLIENT) ───
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
                        if (!targetUrl || targetUrl.startsWith('blob:') || targetUrl.startsWith('data:') || targetUrl.startsWith('javascript:')) {
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

            // ── 2. Proxy window.XMLHttpRequest ───────────────────────────────────────
            try {
                if (window.XMLHttpRequest) {
                    var _origOpen = XMLHttpRequest.prototype.open;
                    var _origSend = XMLHttpRequest.prototype.send;
                    window._origXHR = { open: _origOpen, send: _origSend };
                    XMLHttpRequest.prototype.open = makeNative(function(method, url) {
                        this._onyxTargetUrl = url;
                        var isBlobOrData = typeof url === 'string' && (url.startsWith('blob:') || url.startsWith('data:') || url.startsWith('javascript:'));
                        var lvl = window.__onyx_blocking_level || 0;
                        this._onyxBlocked = !isBlobOrData && isBlockedUrl(url, lvl);
                        return _origOpen.apply(this, arguments);
                    }, 'open');
                    XMLHttpRequest.prototype.send = makeNative(function() {
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
                    }, 'send');
                }
            } catch(e) {}

            // ── 3. Proxy window.WebSocket ────────────────────────────────────────────
            try {
                var OriginalWebSocket = window.WebSocket;
                if (OriginalWebSocket) {
                    var PatchedWebSocket = makeNative(function(url, protocols) {
                        var lvl = window.__onyx_blocking_level || 0;
                        if (typeof url === 'string' && (isBlockedUrl(url, lvl) || /ad|track|telemetry|analytics|stat|pixel|beacon|counter/i.test(url))) {
                            throw new Error('Blocked by Onyx Shields');
                        }
                        if (arguments.length === 1) {
                            return new OriginalWebSocket(url);
                        } else {
                            return new OriginalWebSocket(url, protocols);
                        }
                    }, 'WebSocket');
                    PatchedWebSocket.prototype = OriginalWebSocket.prototype;
                    PatchedWebSocket.CONNECTING = OriginalWebSocket.CONNECTING;
                    PatchedWebSocket.OPEN = OriginalWebSocket.OPEN;
                    PatchedWebSocket.CLOSING = OriginalWebSocket.CLOSING;
                    PatchedWebSocket.CLOSED = OriginalWebSocket.CLOSED;
                    window.WebSocket = PatchedWebSocket;
                }
            } catch(e) {}

            // ── 4. Preemptive Anti-Adblock, Analytics & Consent Stubs ─────────────────
            // Only injected when the user has enabled "Adblocker Spoofing" in Privacy & Shields.
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

                    // ── Honeypot / Bait Defuser (Defuses BlockAdBlock, FuckAdBlock & DOM Honeypots) ──
                    var baitPattern = /(?:^|[\s\-_])(?:ad|ads|adsbygoogle|adzone|google-ad|google_ad|advert|advertising|advertisement|pub_300x250|pub_728x90|textads|GoogleActiveViewInnerContainer)(?:[\s\-_]|$)/i;

                    function isBait(el) {
                        if (!el) return false;
                        var c = typeof el.className === 'string' ? el.className : (el.className && el.className.baseVal ? el.className.baseVal : '');
                        var i = typeof el.id === 'string' ? el.id : '';
                        return (c && baitPattern.test(c)) || (i && baitPattern.test(i));
                    }

                    var origDescH = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
                    if (origDescH && origDescH.get) {
                        var origGetH = origDescH.get;
                        Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
                            get: makeNative(function() {
                                var val = origGetH.call(this);
                                if (val === 0 && isBait(this)) {
                                    var p = this.style && parseInt(this.style.height, 10);
                                    return (p && p > 0) ? p : 10;
                                }
                                return val;
                            }, 'get offsetHeight'),
                            configurable: true,
                            enumerable: origDescH.enumerable
                        });
                    }

                    var origDescW = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
                    if (origDescW && origDescW.get) {
                        var origGetW = origDescW.get;
                        Object.defineProperty(HTMLElement.prototype, 'offsetWidth', {
                            get: makeNative(function() {
                                var val = origGetW.call(this);
                                if (val === 0 && isBait(this)) {
                                    var p = this.style && parseInt(this.style.width, 10);
                                    return (p && p > 0) ? p : 10;
                                }
                                return val;
                            }, 'get offsetWidth'),
                            configurable: true,
                            enumerable: origDescW.enumerable
                        });
                    }

                    var origDescP = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetParent');
                    if (origDescP && origDescP.get) {
                        var origGetP = origDescP.get;
                        Object.defineProperty(HTMLElement.prototype, 'offsetParent', {
                            get: makeNative(function() {
                                var val = origGetP.call(this);
                                if (val === null && isBait(this)) {
                                    return this.parentElement || (document && (document.body || document.documentElement)) || null;
                                }
                                return val;
                            }, 'get offsetParent'),
                            configurable: true,
                            enumerable: origDescP.enumerable
                        });
                    }

                    var origDescCH = Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight');
                    if (origDescCH && origDescCH.get) {
                        var origGetCH = origDescCH.get;
                        Object.defineProperty(Element.prototype, 'clientHeight', {
                            get: makeNative(function() {
                                var val = origGetCH.call(this);
                                if (val === 0 && isBait(this)) {
                                    var p = this.style && parseInt(this.style.height, 10);
                                    return (p && p > 0) ? p : 10;
                                }
                                return val;
                            }, 'get clientHeight'),
                            configurable: true,
                            enumerable: origDescCH.enumerable
                        });
                    }

                    var origDescCW = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');
                    if (origDescCW && origDescCW.get) {
                        var origGetCW = origDescCW.get;
                        Object.defineProperty(Element.prototype, 'clientWidth', {
                            get: makeNative(function() {
                                var val = origGetCW.call(this);
                                if (val === 0 && isBait(this)) {
                                    var p = this.style && parseInt(this.style.width, 10);
                                    return (p && p > 0) ? p : 10;
                                }
                                return val;
                            }, 'get clientWidth'),
                            configurable: true,
                            enumerable: origDescCW.enumerable
                        });
                    }

                    var origDescBCR = Element.prototype.getBoundingClientRect;
                    if (origDescBCR) {
                        Element.prototype.getBoundingClientRect = makeNative(function() {
                            var rect = origDescBCR.call(this);
                            if (rect.width === 0 && rect.height === 0 && isBait(this)) {
                                return {
                                    width: 300,
                                    height: 250,
                                    top: 0,
                                    left: 0,
                                    right: 300,
                                    bottom: 250,
                                    x: 0,
                                    y: 0,
                                    toJSON: function() { return this; }
                                };
                            }
                            return rect;
                        }, 'getBoundingClientRect');
                    }
                } catch(e) {}
            }

            // ── 5. Brave Parity Generic Cosmetic Filter Engine (hidden_class_id_selectors) ───────
            try {
                var seenSelectors = new Set();
                var checkedIdentifiers = new Set();
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

                    for (var ci = 0; ci < classesArr.length; ci++) {
                        checkedIdentifiers.add('.' + classesArr[ci]);
                    }
                    for (var ii = 0; ii < idsArr.length; ii++) {
                        checkedIdentifiers.add('#' + idsArr[ii]);
                    }

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
                        if (!seenSelectors.has(idSel) && !checkedIdentifiers.has(idSel)) {
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
                                if (!seenSelectors.has(clsSel) && !checkedIdentifiers.has(clsSel)) {
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

                // Procedural cosmetic filtering engine (:has-text, :upward, :min-text-length, actions)
                var proceduralRules = null;
                var proceduralTimer = null;

                function runProceduralFilters() {
                    try {
                        if (!window.OnyxShieldBridge || !window.OnyxShieldBridge.getProceduralActions) return;
                        if (proceduralRules === null) {
                            var raw = window.OnyxShieldBridge.getProceduralActions(window.location.href);
                            if (!raw || raw === '[]') {
                                proceduralRules = [];
                                return;
                            }
                            var parsed = JSON.parse(raw);
                            proceduralRules = [];
                            for (var r = 0; r < parsed.length; r++) {
                                var rule = parsed[r];
                                if (typeof rule === 'string') {
                                    try { rule = JSON.parse(rule); } catch(e) { continue; }
                                }
                                if (rule && rule.selector && Array.isArray(rule.selector)) {
                                    proceduralRules.push(rule);
                                }
                            }
                        }

                        if (proceduralRules.length === 0) return;

                        for (var i = 0; i < proceduralRules.length; i++) {
                            executeProceduralRule(proceduralRules[i]);
                        }
                    } catch(e) {}
                }

                function scheduleProceduralRun() {
                    if (proceduralTimer) return;
                    proceduralTimer = setTimeout(function() {
                        proceduralTimer = null;
                        runProceduralFilters();
                    }, 100);
                }

                function executeProceduralRule(rule) {
                    try {
                        var ops = rule.selector;
                        if (!ops || ops.length === 0) return;

                        var currentElements = [];
                        var firstOp = ops[0];
                        var startIndex = 0;

                        if (firstOp.type === 'css-selector') {
                            try {
                                currentElements = Array.prototype.slice.call(document.querySelectorAll(firstOp.arg));
                            } catch(e) {
                                return;
                            }
                            startIndex = 1;
                        } else {
                            currentElements = [document.documentElement || document.body];
                        }

                        for (var s = startIndex; s < ops.length; s++) {
                            if (currentElements.length === 0) break;
                            var op = ops[s];
                            var nextElements = [];

                            if (op.type === 'has-text') {
                                var pattern = op.arg;
                                var isRegex = false;
                                var regex = null;
                                if (pattern && pattern.charAt(0) === '/' && pattern.lastIndexOf('/') > 0) {
                                    try {
                                        var lastSlash = pattern.lastIndexOf('/');
                                        regex = new RegExp(pattern.substring(1, lastSlash), pattern.substring(lastSlash + 1));
                                        isRegex = true;
                                    } catch(e) {}
                                }
                                for (var e = 0; e < currentElements.length; e++) {
                                    var el = currentElements[e];
                                    var text = el.textContent || '';
                                    if (isRegex ? regex.test(text) : text.indexOf(pattern) !== -1) {
                                        nextElements.push(el);
                                    }
                                }
                            } else if (op.type === 'upward') {
                                var arg = op.arg;
                                var steps = parseInt(arg, 10);
                                if (!isNaN(steps) && steps > 0) {
                                    for (var e = 0; e < currentElements.length; e++) {
                                        var cur = currentElements[e];
                                        for (var st = 0; st < steps && cur; st++) {
                                            cur = cur.parentElement;
                                        }
                                        if (cur && nextElements.indexOf(cur) === -1) {
                                            nextElements.push(cur);
                                        }
                                    }
                                } else if (typeof arg === 'string' && arg.length > 0) {
                                    for (var e = 0; e < currentElements.length; e++) {
                                        var ancestor = currentElements[e].closest(arg);
                                        if (ancestor && nextElements.indexOf(ancestor) === -1) {
                                            nextElements.push(ancestor);
                                        }
                                    }
                                }
                            } else if (op.type === 'min-text-length') {
                                var minLen = parseInt(op.arg, 10) || 0;
                                for (var e = 0; e < currentElements.length; e++) {
                                    var el = currentElements[e];
                                    if ((el.textContent || '').trim().length >= minLen) {
                                        nextElements.push(el);
                                    }
                                }
                            } else if (op.type === 'css-selector') {
                                for (var e = 0; e < currentElements.length; e++) {
                                    try {
                                        var sub = currentElements[e].querySelectorAll(op.arg);
                                        for (var k = 0; k < sub.length; k++) {
                                            if (nextElements.indexOf(sub[k]) === -1) {
                                                nextElements.push(sub[k]);
                                            }
                                        }
                                    } catch(e) {}
                                }
                            } else {
                                nextElements = currentElements;
                            }

                            currentElements = nextElements;
                        }

                        var action = rule.action;
                        for (var a = 0; a < currentElements.length; a++) {
                            var targetEl = currentElements[a];
                            if (!targetEl || targetEl.nodeType !== 1) continue;

                            if (!action || action.type === 'remove') {
                                targetEl.style.setProperty('display', 'none', 'important');
                                targetEl.setAttribute('data-onyx-procedural-hidden', 'true');
                            } else if (action.type === 'style' && action.arg) {
                                targetEl.style.cssText += ';' + action.arg;
                            } else if (action.type === 'remove-attr' && action.arg) {
                                targetEl.removeAttribute(action.arg);
                            } else if (action.type === 'remove-class' && action.arg) {
                                targetEl.classList.remove(action.arg);
                            }
                        }
                    } catch(e) {}
                }

                // Initial baseline collapsed styles
                var initStyle = getOrCreateCosmeticStyle();
                initStyle.textContent = [
                    '#cts_test, #ctd_test, #ad_ctd, .ad-banner, .adsbox, .textads, .banner-ad, .banner_ads,',
                    'ins.adsbygoogle, [id*="google_ads"], [class*="google-ads"], [class*="sponsor-ad"],',
                    '.afs_ads, .sponsor-content, .commercial-unit, #banner-ad, #ad-banner,',
                    '.dfp-ad-container, .taboola-ad, .outbrain-ad,',
                    '[class*="native-ad"], [id*="native-ad"], .ad-placeholder, .advertisement-box,',
                    '.adblockHostDiv_probe, [id*="ad_banner"], [id*="ad_unit"], [class*="sponsored-item"],',
                    '[id*="yandex_rtb"], [class*="yandex_rtb"], [id*="pr_advertising"], [class*="pr_advertising"]' +
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
                    scheduleProceduralRun();
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
                    if (hasNew) {
                        if (pendingClasses.size > 0 || pendingIds.size > 0) {
                            scheduleQuery();
                        }
                        scheduleProceduralRun();
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
                        scheduleProceduralRun();
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
