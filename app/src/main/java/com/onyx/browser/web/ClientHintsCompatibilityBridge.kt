package com.onyx.browser.web

/**
 * Injects document-start JavaScript to ensure Chromium client hints (navigator.userAgentData),
 * window.chrome, and automation flags match an authentic Google Chrome browser.
 *
 * Prevents anti-bot and security verification engines (Arkose Labs / FunCaptcha, Meta Risk Engine,
 * Cloudflare Turnstile, Google reCAPTCHA) from flagging Onyx as an automated Android WebView,
 * which resolves "Confirmation failed in captcha" and session rejection during Facebook login.
 */
object ClientHintsCompatibilityBridge {

    val SCRIPT: String = """
    (function() {
        try {
            // 1. Authentic window.chrome object polyfill
            if (!window.chrome) {
                window.chrome = {};
            }
            if (!window.chrome.csi) {
                window.chrome.csi = function() { return {}; };
            }
            if (!window.chrome.loadTimes) {
                window.chrome.loadTimes = function() { return {}; };
            }
            if (!window.chrome.app) {
                window.chrome.app = {
                    isInstalled: false,
                    InstallState: { DISABLED: "disabled", INSTALLED: "installed", NOT_INSTALLED: "not_installed" },
                    RunningState: { CANNOT_RUN: "cannot_run", READY_TO_RUN: "ready_to_run", RUNNING: "running" }
                };
            }

            // 2. Eradicate navigator.webdriver automation indicator
            if (navigator.webdriver !== undefined) {
                try {
                    Object.defineProperty(navigator, 'webdriver', {
                        get: function() { return false; },
                        configurable: true,
                        enumerable: true
                    });
                } catch(_) {}
            }

            // 3. Client Hints (navigator.userAgentData) Spoofing
            var ua = navigator.userAgent || '';
            var chromeMatch = ua.match(/Chrome\/([0-9]+)(\.[0-9.]+)?/);
            var majorVersion = chromeMatch ? chromeMatch[1] : "131";
            var fullVersion = chromeMatch ? (chromeMatch[1] + (chromeMatch[2] || ".0.0.0")) : "131.0.6778.135";
            var isMobile = /Android|Mobile|Phone/i.test(ua);
            var platform = "Android";
            var platformVersion = "14.0.0";
            var model = "Pixel 8";

            if (/Windows/i.test(ua)) {
                platform = "Windows";
                platformVersion = "15.0.0";
                model = "";
                isMobile = false;
            } else if (/Macintosh|Mac OS X/i.test(ua)) {
                platform = "macOS";
                platformVersion = "14.6.1";
                model = "";
                isMobile = false;
            } else if (/CrOS/i.test(ua)) {
                platform = "Chrome OS";
                platformVersion = "15662.76.0";
                model = "";
                isMobile = false;
            } else if (/Linux/i.test(ua) && !isMobile) {
                platform = "Linux";
                platformVersion = "6.6.0";
                model = "";
                isMobile = false;
            }

            var brands = [
                { brand: "Google Chrome", version: majorVersion },
                { brand: "Chromium", version: majorVersion },
                { brand: "Not_A Brand", version: "24" }
            ];

            var fullVersionList = [
                { brand: "Google Chrome", version: fullVersion },
                { brand: "Chromium", version: fullVersion },
                { brand: "Not_A Brand", version: "24.0.0.0" }
            ];

            var uaData = {
                brands: brands,
                mobile: isMobile,
                platform: platform,
                getHighEntropyValues: function(hints) {
                    return Promise.resolve({
                        architecture: (platform === "Windows" || platform === "macOS" || platform === "Linux") ? "x86" : "arm",
                        bitness: "64",
                        brands: brands,
                        fullVersionList: fullVersionList,
                        mobile: isMobile,
                        model: model,
                        platform: platform,
                        platformVersion: platformVersion,
                        uaFullVersion: fullVersion
                    });
                },
                toJSON: function() {
                    return {
                        brands: brands,
                        mobile: isMobile,
                        platform: platform
                    };
                }
            };

            try {
                Object.defineProperty(navigator, 'userAgentData', {
                    get: function() { return uaData; },
                    configurable: true,
                    enumerable: true
                });
            } catch(_) {}

        } catch(e) {}
    })();
    """.trimIndent()
}
