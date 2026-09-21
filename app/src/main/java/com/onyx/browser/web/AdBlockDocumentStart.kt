package com.onyx.browser.web

/**
 * Generates the document_start JavaScript injected into all WebViews.
 *
 * This runs before any HTML parsing or inline scripts execute. It:
 * 1. Preemptively stubs anti-adblock and analytics global variables (window.ga, window.gtag, window.adsbygoogle, window.fbq, etc.).
 * 2. Proxies window.fetch and window.XMLHttpRequest to reject known ad and tracker network requests with TypeError (ERR_BLOCKED_BY_CLIENT).
 * 3. Proxies window.WebSocket to block telemetry and ad WebSocket connections.
 * 4. Injects an early high-priority universal cosmetic stylesheet that collapses bait ad containers (display: none !important; height: 0 !important;).
 */
object AdBlockDocumentStart {

    val SCRIPT: String = """
        (function() {
            if (window.__onyx_shields_active) return;
            window.__onyx_shields_active = true;

            // ── 1. Preemptive Anti-Adblock & Analytics Stubs ──────────────────────────
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
            } catch(e) {}

            // ── 2. Ad & Tracker URL Detection Pattern ────────────────────────────────
            var adTrackerPattern = /(\.|\/)(doubleclick\.net|googlesyndication\.com|googleadservices\.com|googletagservices\.com|googletagmanager\.com|google-analytics\.com|criteo\.(com|net)|taboola\.com|outbrain\.com|rubiconproject\.com|casalemedia\.com|openx\.net|pubmatic\.com|adnxs\.com|amazon-adsystem\.com|adroll\.com|scorecardresearch\.com|quantserve\.com|quantcast\.com|advertising\.com|bidswitch\.net|moatads\.com|smartadserver\.com|adsafeprotected\.com|doubleverify\.com|hotjar\.com|clarity\.ms|mixpanel\.com|amplitude\.com|segment\.(io|com)|chartboost\.com|applovin\.com|vungle\.com|inmobi\.com|ironsource\.mobi|unityads\.unity3d\.com|adcolony\.com|mgid\.com|propellerads\.com|propellerclick\.com|onclickads\.net|media\.net|fls-na\.amazon\.com|bat\.bing\.com|claritybt\.freshmarketer\.com|fwtracks\.freshmarketer\.com|mouseflow\.com|luckyorange\.(com|net)|heapanalytics\.com|fullstory\.com|newrelic\.com|nr-data\.net|datadoghq\.com|sentry\.io|bugsnag\.com|branch\.io|appsflyer\.com|stats\.wp\.com|connatix\.com|innovid\.com|tremorhub\.com|crwdcntrl\.net|fwmrm\.net|jwpltx\.com|rlcdn\.com|impactradius-event\.com|shareasale\.com|awin1\.com|partnerstack\.com|refersion\.com|fingerprintjs\.com|fpjs\.io|adlog\.vivo\.com|ads-api\.vivo\.com|click\.oneplus\.cn|open\.oneplus\.net|a\.lenovo\.com|ad\.mail\.ru|top-fwz1\.mail\.ru|ads\.vk\.com|mc\.yandex\.ru|adfox\.yandex\.ru|adfstat\.yandex\.ru|appmetrica\.yandex\.ru|driftt\.com|intercom\.io|wzrkt\.com|zenaps\.com|statdynamic\.com|srvcs\.tumblr\.com|quora\.com\/qevents|redditmedia\.com\/pixel|events\.reddit\.com|d\.reddit\.com|ct\.pinterest\.com|analytics\.tiktok\.com|an\.facebook\.com|pixel\.facebook\.com|static\.xx\.fbcdn\.net|analytics\.twitter\.com|ads-api\.twitter\.com|snap\.licdn\.com|analytics\.linkedin\.com|liftoff\.io|pangleglobal\.com|adservetx\.media\.net|spotxchange\.com|htlbid\.com|stickyadstv\.com|3lift\.com|sonobi\.com|gumgum\.com|teads\.tv|kargo\.com|omtrdc\.net|metrics\.adobe\.com|lr-ingest\.com|brightcove\.com\/metrics)(\/|\?|:|$)/i;

            var adPathPattern = /\/(pagead\/|adservice\/|google-analytics\.com\/g\/collect|collect\?|telemetry|analytics\.js|gtm\.js|ads\.js|prebid|show_ads\.js)/i;

            function isBlockedUrl(rawUrl) {
                if (!rawUrl || typeof rawUrl !== 'string') return false;
                try {
                    return adTrackerPattern.test(rawUrl) || adPathPattern.test(rawUrl);
                } catch(e) {
                    return false;
                }
            }

            // ── 3. Proxy window.fetch (Throws TypeError net::ERR_BLOCKED_BY_CLIENT) ───
            try {
                if (window.fetch) {
                    var _origFetch = window.fetch;
                    window.fetch = function(input, init) {
                        var targetUrl = '';
                        if (typeof input === 'string') {
                            targetUrl = input;
                        } else if (input && typeof input.url === 'string') {
                            targetUrl = input.url;
                        }
                        if (isBlockedUrl(targetUrl)) {
                            return Promise.reject(new TypeError('Failed to fetch: net::ERR_BLOCKED_BY_CLIENT'));
                        }
                        return _origFetch.apply(this, arguments);
                    };
                }
            } catch(e) {}

            // ── 4. Proxy window.XMLHttpRequest ───────────────────────────────────────
            try {
                if (window.XMLHttpRequest) {
                    var _origOpen = XMLHttpRequest.prototype.open;
                    var _origSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this._onyxTargetUrl = url;
                        this._onyxBlocked = isBlockedUrl(url);
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

            // ── 5. Proxy window.WebSocket ────────────────────────────────────────────
            try {
                var OriginalWebSocket = window.WebSocket;
                if (OriginalWebSocket) {
                    window.WebSocket = function(url, protocols) {
                        if (typeof url === 'string' && (isBlockedUrl(url) || /ad|track|telemetry|analytics|stat|pixel|beacon|counter/i.test(url))) {
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

            // ── 6. Universal Generic Cosmetic CSS Sheet ──────────────────────────────
            try {
                var injectCosmeticStyle = function() {
                    if (document.getElementById('onyx-universal-cosmetic')) return;
                    var style = document.createElement('style');
                    style.id = 'onyx-universal-cosmetic';
                    style.type = 'text/css';
                    style.textContent = [
                        '.ad-banner, .adsbox, .textads, .banner-ad, .ad-unit, .ads-wrapper,',
                        '[class*="ad-container"], [id*="ad-container"], ins.adsbygoogle,',
                        '[id*="google_ads"], [class*="google-ads"], [class*="sponsor-ad"],',
                        '.afs_ads, .sponsor-content, .commercial-unit, #banner-ad, #ad-banner,',
                        '.dfp-ad-container, [data-ad-unit], [data-ad-slot], .taboola-ad, .outbrain-ad,',
                        '[class*="native-ad"], [id*="native-ad"], .ad-placeholder, .advertisement-box,',
                        '.adblockHostDiv_probe, [id*="ad_banner"], [id*="ad_unit"], [class*="sponsored-item"] {',
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
                    var target = document.head || document.documentElement;
                    if (target) {
                        target.appendChild(style);
                    } else {
                        document.addEventListener('DOMContentLoaded', function() {
                            (document.head || document.documentElement).appendChild(style);
                        });
                    }
                };
                injectCosmeticStyle();
                if (document.readyState === 'loading') {
                    document.addEventListener('readystatechange', injectCosmeticStyle);
                }
            } catch(e) {}
        })();
    """.trimIndent()
}
