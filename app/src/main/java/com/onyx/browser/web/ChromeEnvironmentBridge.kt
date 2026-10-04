package com.onyx.browser.web

/**
 * Polyfills native Chrome browser environment in Android WebView.
 *
 * Android WebView by default omits key browser environment APIs present in standalone
 * Chromium/Chrome/Brave browsers:
 * 1. window.chrome
 * 2. navigator.userAgentData (User-Agent Client Hints matching system WebView Chromium version)
 * 3. navigator.webdriver = false
 * 4. Masks custom Android WebView Java bridge properties (OnyxShieldBridge, OnyxMediaBridge, etc.)
 *    on authentication, CAPTCHA, and security challenge pages so anti-bot engines
 *    like Arkose Labs FunCaptcha don't detect an embedded/automated WebView.
 */
object ChromeEnvironmentBridge {

    val SCRIPT: String = """
        (function() {
            try {
                if (window.__onyx_chrome_env_installed) return;
                window.__onyx_chrome_env_installed = true;

                // ── 1. Polyfill window.chrome for Android WebView ──────────────────
                if (!window.chrome) {
                    window.chrome = {};
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
                        getHighEntropyValues: function(hints) {
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
                        },
                        toJSON: function() {
                            return { brands: brands, mobile: true, platform: 'Android' };
                        }
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
                var host = (location.hostname || '').toLowerCase();
                var href = (location.href || '').toLowerCase();
                var ref = (document.referrer || '').toLowerCase();

                var isAuthOrChallenge = /facebook\.com|meta\.com|fb\.com|fb\.me|fbcdn\.net|facebook\.net|fbsbx\.com|instagram\.com|messenger\.com|arkose|funcaptcha|matchkey|turnstile|recaptcha|hcaptcha|challenges\.cloudflare\.com/i.test(host + ' ' + href + ' ' + ref) ||
                    href.indexOf('/checkpoint/') !== -1 || href.indexOf('/login/') !== -1 || href.indexOf('/auth/') !== -1 ||
                    href.indexOf('/challenge/') !== -1 || href.indexOf('/captcha/') !== -1 ||
                    href.indexOf('lsd=') !== -1 || href.indexOf('jazoest=') !== -1 || href.indexOf('datr=') !== -1;

                var bridgeNames = [
                    'OnyxShieldBridge', 'OnyxMediaBridge', 'OnyxErrorBridge',
                    'OnyxBlobBridge', 'OnyxTouchBridge', 'OnyxTranslateBridge',
                    'PasskeyWebAuthnBridge', '__onyx_shields_active', '__onyxAdBlockEnabled'
                ];

                for (var i = 0; i < bridgeNames.length; i++) {
                    var b = bridgeNames[i];
                    if (isAuthOrChallenge) {
                        try { delete window[b]; } catch (_) {}
                        try {
                            Object.defineProperty(window, b, {
                                value: undefined,
                                writable: true,
                                enumerable: false,
                                configurable: true
                            });
                        } catch (_) {}
                    } else if (typeof window[b] !== 'undefined') {
                        try {
                            var val = window[b];
                            Object.defineProperty(window, b, {
                                value: val,
                                writable: true,
                                enumerable: false,
                                configurable: true
                            });
                        } catch (_) {}
                    }
                }
            } catch (_) {}
        })();
    """.trimIndent()
}
