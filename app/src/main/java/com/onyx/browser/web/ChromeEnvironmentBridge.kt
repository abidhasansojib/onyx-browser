package com.onyx.browser.web

/**
 * Polyfills native Chrome browser environment in Android WebView.
 *
 * Android WebView by default omits key browser environment APIs present in standalone
 * Chromium/Chrome/Brave browsers:
 * 1. window.chrome (with csi, loadTimes, and app methods)
 * 2. navigator.userAgentData (User-Agent Client Hints matching system WebView Chromium version)
 * 3. navigator.webdriver = false
 * 4. Transparently filters custom Android WebView Java bridge properties (OnyxShieldBridge,
 *    OnyxMediaBridge, etc.) from Object.getOwnPropertyNames, Object.keys, and Reflect.ownKeys
 *    on authentication, CAPTCHA, and security challenge pages so anti-bot engines
 *    like Arkose Labs FunCaptcha don't detect an embedded/automated WebView or compute an invalid
 *    Window Hash (wh).
 */
object ChromeEnvironmentBridge {

    val SCRIPT: String = """
        (function() {
            try {
                var host = (location.hostname || '').toLowerCase();
                var href = (location.href || '').toLowerCase();
                var ref = (document.referrer || '').toLowerCase();
                var path = (location.pathname || '').toLowerCase();

                var isAuthOrChallenge = /facebook\.com|meta\.com|fb\.com|fb\.me|fbcdn\.net|facebook\.net|fbsbx\.com|instagram\.com|cdninstagram\.com|messenger\.com|threads\.net|accountkit\.com|arkose|funcaptcha|matchkey|turnstile|recaptcha|hcaptcha|datadome|perimeterx|kasada|geetest|challenges\.cloudflare\.com/i.test(host + ' ' + href + ' ' + ref) ||
                    path.indexOf('/checkpoint/') !== -1 || path.indexOf('/login/') !== -1 || path.indexOf('/auth/') !== -1 ||
                    path.indexOf('/captcha/') !== -1 || path.indexOf('/challenge/') !== -1 || path.indexOf('/fc/') !== -1 ||
                    href.indexOf('/checkpoint/') !== -1 || href.indexOf('/challenge/') !== -1 || href.indexOf('/captcha/') !== -1 ||
                    href.indexOf('/login/') !== -1 || href.indexOf('/auth/') !== -1 || href.indexOf('lsd=') !== -1 ||
                    href.indexOf('jazoest=') !== -1 || href.indexOf('datr=') !== -1;

                if (!isAuthOrChallenge) {
                    if (window.__onyx_chrome_env_installed) return;
                    window.__onyx_chrome_env_installed = true;
                }

                function makeNative(fn, name) {
                    try {
                        fn.toString = function() { return 'function ' + name + '() { [native code] }'; };
                        Object.defineProperty(fn, 'name', { value: name, configurable: true });
                    } catch (_) {}
                    return fn;
                }

                // ── 1. Polyfill window.chrome for Android WebView ──────────────────
                if (!window.chrome) {
                    window.chrome = {
                        app: { isInstalled: false },
                        csi: makeNative(function() {}, 'csi'),
                        loadTimes: makeNative(function() {
                            var now = (Date.now ? Date.now() : +new Date()) / 1000;
                            return {
                                requestTime: now,
                                startLoadTime: now,
                                commitLoadTime: now,
                                finishDocumentLoadTime: now,
                                finishLoadTime: now,
                                firstPaintTime: now,
                                firstPaintAfterLoadTime: 0,
                                navigationType: 'Other',
                                wasFetchedViaSpdy: false,
                                wasNpnNegotiated: false,
                                npnNegotiatedProtocol: '',
                                wasAlternateProtocolAvailable: false,
                                connectionInfo: 'http/1.1'
                            };
                        }, 'loadTimes')
                    };
                }

                // ── 2. Polyfill navigator.userAgentData for Android WebView ─────────
                if (!navigator.userAgentData) {
                    var ua = navigator.userAgent || '';
                    var match = ua.match(/Chrome\/([0-9]+)/);
                    var chromeMajor = match ? match[1] : '131';
                    var fullMatch = ua.match(/Chrome\/([0-9.]+)/);
                    var chromeFull = fullMatch ? fullMatch[1] : '131.0.0.0';

                    var brands = [
                        { brand: 'Chromium', version: chromeMajor },
                        { brand: 'Not=A?Brand', version: '24' },
                        { brand: 'Google Chrome', version: chromeMajor }
                    ];

                    var uaData = {
                        brands: brands,
                        mobile: true,
                        platform: 'Android',
                        getHighEntropyValues: makeNative(function(hints) {
                            return Promise.resolve({
                                architecture: 'arm',
                                bitness: '64',
                                brands: brands,
                                mobile: true,
                                model: '',
                                platform: 'Android',
                                platformVersion: '14.0.0',
                                uaFullVersion: chromeFull
                            });
                        }, 'getHighEntropyValues'),
                        toJSON: makeNative(function() {
                            return { brands: brands, mobile: true, platform: 'Android' };
                        }, 'toJSON')
                    };

                    try {
                        Object.defineProperty(navigator, 'userAgentData', {
                            get: function() { return uaData; },
                            enumerable: true,
                            configurable: true
                        });
                    } catch (_) {}
                }

                // ── 3. Enforce navigator.webdriver = false ───────────────────────────
                try {
                    Object.defineProperty(navigator, 'webdriver', {
                        get: function() { return false; },
                        enumerable: true,
                        configurable: true
                    });
                } catch (_) {}

                // ── 4. Clean Java bridge references on Meta / Arkose / Auth domains ───
                if (isAuthOrChallenge) {
                    var bridgePattern = /^(Onyx|__onyx|Passkey)/;

                    var bridgeNames = [
                        'OnyxShieldBridge', 'OnyxMediaBridge', 'OnyxErrorBridge',
                        'OnyxBlobBridge', 'OnyxTouchBridge', 'OnyxTranslateBridge',
                        'PasskeyWebAuthnBridge', '__onyx_shields_active', '__onyxAdBlockEnabled',
                        '__onyxTouchInitialized', '__onyxPasskeyInstalled'
                    ];

                    for (var i = 0; i < bridgeNames.length; i++) {
                        var b = bridgeNames[i];
                        try { delete window[b]; } catch (_) {}
                        try {
                            if (window[b] !== undefined) {
                                Object.defineProperty(window, b, {
                                    get: function() { return undefined; },
                                    enumerable: false,
                                    configurable: true
                                });
                            }
                        } catch (_) {}
                    }

                    // Mask custom bridges from window reflection (Object.getOwnPropertyNames, Object.keys, Reflect.ownKeys)
                    // so Arkose Labs' Window Hash (wh) and anti-bot scanners compute a 100% clean authentic Chrome hash
                    try {
                        var _origGetOwnPropertyNames = Object.getOwnPropertyNames;
                        Object.getOwnPropertyNames = makeNative(function(obj) {
                            var names = _origGetOwnPropertyNames.apply(this, arguments);
                            if (obj === window || obj === window.__proto__) {
                                return names.filter(function(n) { return !bridgePattern.test(n); });
                            }
                            return names;
                        }, 'getOwnPropertyNames');

                        var _origKeys = Object.keys;
                        Object.keys = makeNative(function(obj) {
                            var keys = _origKeys.apply(this, arguments);
                            if (obj === window || obj === window.__proto__) {
                                return keys.filter(function(k) { return !bridgePattern.test(k); });
                            }
                            return keys;
                        }, 'keys');

                        if (typeof Reflect !== 'undefined' && Reflect.ownKeys) {
                            var _origOwnKeys = Reflect.ownKeys;
                            Reflect.ownKeys = makeNative(function(obj) {
                                var keys = _origOwnKeys.apply(this, arguments);
                                if (obj === window || obj === window.__proto__) {
                                    return keys.filter(function(k) { return typeof k !== 'string' || !bridgePattern.test(k); });
                                }
                                return keys;
                            }, 'ownKeys');
                        }
                    } catch (_) {}
                }
            } catch (_) {}
        })();
    """.trimIndent()
}
