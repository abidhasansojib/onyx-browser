package com.onyx.browser.web.bridge

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
                    path.indexOf('/checkpoint/') !== -1 || path.indexOf('/auth/') !== -1 ||
                    path.indexOf('/captcha/') !== -1 || path.indexOf('/challenge/') !== -1 || path.indexOf('/fc/') !== -1 ||
                    href.indexOf('/checkpoint/') !== -1 || href.indexOf('/challenge/') !== -1 || href.indexOf('/captcha/') !== -1 ||
                    href.indexOf('/auth/') !== -1 || href.indexOf('lsd=') !== -1 ||
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
                        app: {
                            isInstalled: false,
                            InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
                            RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' }
                        },
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
                // Always define authentic Chrome brands on userAgentData (removes "Android WebView" leak that blocks Google Login)
                try {
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

                    Object.defineProperty(navigator, 'userAgentData', {
                        get: makeNative(function() { return uaData; }, 'get userAgentData'),
                        enumerable: true,
                        configurable: true
                    });
                    if (window.Navigator && window.Navigator.prototype) {
                        try {
                            Object.defineProperty(window.Navigator.prototype, 'userAgentData', {
                                get: makeNative(function() { return uaData; }, 'get userAgentData'),
                                enumerable: true,
                                configurable: true
                            });
                        } catch (_) {}
                    }
                } catch (_) {}

                // ── 3. Enforce navigator.webdriver = false ───────────────────────────
                try {
                    Object.defineProperty(navigator, 'webdriver', {
                        get: function() { return false; },
                        enumerable: true,
                        configurable: true
                    });
                } catch (_) {}

                // ── 4. Mask Java bridge references from Window reflection ────────────
                // Never delete or shadow OnyxShieldBridge / OnyxMediaBridge / etc. to undefined,
                // because Document-Start Shields, Element Picker persistence, Media PiP, and Translation
                // depend on calling these bridges. Hiding them from Object.getOwnPropertyNames,
                // Object.keys, and Reflect.ownKeys is sufficient for anti-bot window-hash scanners.
                var bridgePattern = /^(Onyx|onyx|__onyx|_Onyx|__us|Passkey|_orig|WebErrorBridge|PageTranslateBridge)/;
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

                // ── 5. Motion & Light Sensors Control (Brave parity) ─────────────────
                if (!isAuthOrChallenge) {
                    try {
                        var allowSensors = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isSensorsAllowed === 'function') {
                            allowSensors = window.OnyxShieldBridge.isSensorsAllowed(host);
                        }
                        if (!allowSensors) {
                            var sensorTypes = ['Accelerometer', 'LinearAccelerationSensor', 'Gyroscope',
                                               'AbsoluteOrientationSensor', 'RelativeOrientationSensor',
                                               'AmbientLightSensor', 'Magnetometer'];
                            for (var si = 0; si < sensorTypes.length; si++) {
                                var sName = sensorTypes[si];
                                if (window[sName]) {
                                    window[sName] = makeNative(function() {
                                        throw new DOMException('Sensor access blocked', 'NotAllowedError');
                                    }, sName);
                                }
                            }
                            if (window.DeviceMotionEvent && typeof window.DeviceMotionEvent.requestPermission === 'function') {
                                window.DeviceMotionEvent.requestPermission = makeNative(function() {
                                    return Promise.reject(new DOMException('Permission denied', 'NotAllowedError'));
                                }, 'requestPermission');
                            }
                            if (window.DeviceOrientationEvent && typeof window.DeviceOrientationEvent.requestPermission === 'function') {
                                window.DeviceOrientationEvent.requestPermission = makeNative(function() {
                                    return Promise.reject(new DOMException('Permission denied', 'NotAllowedError'));
                                }, 'requestPermission');
                            }
                        }
                    } catch (_) {}

                    // ── 6. Clipboard Access Control ───────────────────────────────────
                    try {
                        var allowClipboard = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isClipboardAllowed === 'function') {
                            allowClipboard = window.OnyxShieldBridge.isClipboardAllowed(host);
                        }
                        if (!allowClipboard && navigator.clipboard) {
                            if (typeof navigator.clipboard.readText === 'function') {
                                navigator.clipboard.readText = makeNative(function() {
                                    return Promise.reject(new DOMException('Clipboard read permission denied', 'NotAllowedError'));
                                }, 'readText');
                            }
                            if (typeof navigator.clipboard.read === 'function') {
                                navigator.clipboard.read = makeNative(function() {
                                    return Promise.reject(new DOMException('Clipboard read permission denied', 'NotAllowedError'));
                                }, 'read');
                            }
                        }
                    } catch (_) {}

                    // ── 7. Sound / Audio Muting ────────────────────────────────────────
                    try {
                        var allowSound = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isSoundAllowed === 'function') {
                            allowSound = window.OnyxShieldBridge.isSoundAllowed(host);
                        }
                        if (!allowSound) {
                            var origPlay = HTMLMediaElement.prototype.play;
                            HTMLMediaElement.prototype.play = function() {
                                this.muted = true;
                                return origPlay.apply(this, arguments);
                            };
                            try {
                                Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
                                    get: function() { return 0; },
                                    set: function(_) {},
                                    configurable: true
                                });
                            } catch (_) {}
                        }
                    } catch (_) {}

                    // ── 8. Screen Wake Lock Access Control ────────────────────────────
                    try {
                        var allowWakeLock = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isWakeLockAllowed === 'function') {
                            allowWakeLock = window.OnyxShieldBridge.isWakeLockAllowed(host);
                        }
                        if (!allowWakeLock && navigator.wakeLock && typeof navigator.wakeLock.request === 'function') {
                            navigator.wakeLock.request = makeNative(function() {
                                return Promise.reject(new DOMException('Wake Lock permission denied.', 'NotAllowedError'));
                            }, 'request');
                        }
                    } catch (_) {}

                    // ── 23. WebRTC Local Host IP Leak Protection ────────────────────────
                    try {
                        var protectWebRtc = false;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isWebRtcProtected === 'function') {
                            protectWebRtc = window.OnyxShieldBridge.isWebRtcProtected(host);
                        }
                        if (protectWebRtc && window.RTCPeerConnection) {
                            var origAddIceCandidate = window.RTCPeerConnection.prototype.addIceCandidate;
                            if (typeof origAddIceCandidate === 'function') {
                                window.RTCPeerConnection.prototype.addIceCandidate = makeNative(function(candidate) {
                                    if (candidate && candidate.candidate && candidate.candidate.indexOf(' typ host') !== -1) {
                                        return Promise.resolve();
                                    }
                                    return origAddIceCandidate.apply(this, arguments);
                                }, 'addIceCandidate');
                            }

                            var origSetLocalDesc = window.RTCPeerConnection.prototype.setLocalDescription;
                            if (typeof origSetLocalDesc === 'function') {
                                window.RTCPeerConnection.prototype.setLocalDescription = makeNative(function(description) {
                                    if (description && description.sdp) {
                                        var lines = description.sdp.split('\r\n');
                                        var filtered = lines.filter(function(line) {
                                            return !(line.indexOf('a=candidate:') === 0 && line.indexOf(' typ host') !== -1);
                                        });
                                        description = new RTCSessionDescription({
                                            type: description.type,
                                            sdp: filtered.join('\r\n')
                                        });
                                    }
                                    return origSetLocalDesc.call(this, description);
                                }, 'setLocalDescription');
                            }
                        }
                    } catch (_) {}

                    // ── 9. Language Fingerprint Protection (Document-Start) ─────────────
                    try {
                        var protectLang = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isFingerprintLangActive === 'function') {
                            protectLang = window.OnyxShieldBridge.isFingerprintLangActive(host);
                        }
                        if (protectLang) {
                            Object.defineProperty(navigator, 'language', {
                                get: makeNative(function() { return 'en-US'; }, 'get language'),
                                configurable: true
                            });
                            Object.defineProperty(navigator, 'languages', {
                                get: makeNative(function() { return ['en-US', 'en']; }, 'get languages'),
                                configurable: true
                            });
                        }
                    } catch (_) {}

                    // ── 10. Brave-Style Session Fingerprint Farbling (Canvas, WebGL, Audio, Plugins, Hardware) ──
                    try {
                        var protectFp = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isFingerprintProtectionActive === 'function') {
                            protectFp = window.OnyxShieldBridge.isFingerprintProtectionActive(host);
                        }
                        if (protectFp && !window.__onyxFpInstalled) {
                            window.__onyxFpInstalled = true;

                            // Per-page-load seed: deterministic within the same document load,
                            // newly randomized on every page reload and across first-party domains.
                            var fpSeed = ((Math.random() * 0x7FFFFFFE) | 0) + 1;
                            var hostHash = 2166136261;
                            for (var hi = 0; hi < host.length; hi++) {
                                hostHash ^= host.charCodeAt(hi);
                                hostHash = Math.imul(hostHash, 16777619) >>> 0;
                            }
                            fpSeed = ((fpSeed ^ hostHash) >>> 0) || 1;

                            function nextFpRand(state) {
                                var x = (state | 0) || fpSeed;
                                x ^= x << 13;
                                x ^= x >>> 17;
                                x ^= x << 5;
                                return (x >>> 0);
                            }

                            // 10a. Hardware Concurrency & Device Memory Farbling
                            var hwPool = [4, 6, 8, 12];
                            var farbledCores = hwPool[(hostHash + (fpSeed & 0xFF)) % hwPool.length];
                            var farbledMem = hwPool[((hostHash >>> 4) + (fpSeed & 0xFF)) % hwPool.length];
                            try {
                                Object.defineProperty(navigator, 'hardwareConcurrency', {
                                    get: makeNative(function() { return farbledCores; }, 'get hardwareConcurrency'),
                                    configurable: true
                                });
                            } catch (_) {}
                            if ('deviceMemory' in navigator) {
                                try {
                                    Object.defineProperty(navigator, 'deviceMemory', {
                                        get: makeNative(function() { return farbledMem; }, 'get deviceMemory'),
                                        configurable: true
                                    });
                                } catch (_) {}
                            }

                            // 10b. Plugins Farbling (Brave standard plugin farbling)
                            try {
                                var suffix = (fpSeed & 0xFFFF).toString(16);
                                var mimeEntry = {
                                    type: 'application/pdf',
                                    suffixes: 'pdf',
                                    description: 'Portable Document Format'
                                };
                                var p0 = {
                                    0: mimeEntry,
                                    name: 'PDF Viewer',
                                    description: 'Portable Document Format',
                                    filename: 'internal-pdf-viewer-' + suffix,
                                    length: 1,
                                    item: makeNative(function(idx) { return idx === 0 ? mimeEntry : null; }, 'item'),
                                    namedItem: makeNative(function(name) { return name === 'application/pdf' ? mimeEntry : null; }, 'namedItem')
                                };
                                var p1 = {
                                    0: mimeEntry,
                                    name: 'Chrome PDF Viewer',
                                    description: 'Portable Document Format',
                                    filename: 'internal-chrome-pdf-' + suffix,
                                    length: 1,
                                    item: makeNative(function(idx) { return idx === 0 ? mimeEntry : null; }, 'item'),
                                    namedItem: makeNative(function(name) { return name === 'application/pdf' ? mimeEntry : null; }, 'namedItem')
                                };
                                var fakePlugins = [p0, p1];
                                fakePlugins.item = makeNative(function(idx) { return this[idx] || null; }, 'item');
                                fakePlugins.namedItem = makeNative(function(name) {
                                    for (var i = 0; i < this.length; i++) {
                                        if (this[i].name === name) return this[i];
                                    }
                                    return null;
                                }, 'namedItem');
                                fakePlugins.refresh = makeNative(function() {}, 'refresh');
                                Object.defineProperty(navigator, 'plugins', {
                                    get: makeNative(function() { return fakePlugins; }, 'get plugins'),
                                    configurable: true
                                });
                            } catch (_) {}

                            // 10c. Canvas 2D & WebGL Pixel Farbling (getImageData, toDataURL, toBlob, readPixels)
                            var origGetImageData = window.CanvasRenderingContext2D && CanvasRenderingContext2D.prototype.getImageData;
                            var origPutImageData = window.CanvasRenderingContext2D && CanvasRenderingContext2D.prototype.putImageData;
                            var origToDataURL = window.HTMLCanvasElement && HTMLCanvasElement.prototype.toDataURL;
                            var origToBlob = window.HTMLCanvasElement && HTMLCanvasElement.prototype.toBlob;

                            function farblePixelArray(data, seedOffset) {
                                if (!data || data.length < 16) return;
                                var totalPixels = (data.length >> 2);
                                if (totalPixels <= 0) return;
                                var count = Math.min(32, totalPixels);
                                var s = (fpSeed ^ totalPixels ^ (seedOffset || 0)) >>> 0;
                                for (var i = 0; i < count; i++) {
                                    s = nextFpRand(s + Math.imul(i + 1, 2654435761));
                                    var pxIdx = (s % totalPixels) << 2;
                                    var ch = (s >>> 8) % 3;
                                    data[pxIdx + ch] = data[pxIdx + ch] ^ 1;
                                }
                            }

                            if (origGetImageData) {
                                CanvasRenderingContext2D.prototype.getImageData = makeNative(function(sx, sy, sw, sh) {
                                    var imgData = origGetImageData.apply(this, arguments);
                                    try {
                                        if (imgData && imgData.data) {
                                            farblePixelArray(imgData.data, (sw | 0) * 31 + (sh | 0));
                                        }
                                    } catch (_) {}
                                    return imgData;
                                }, 'getImageData');
                            }

                            function createFarbledCanvasClone(srcCanvas) {
                                try {
                                    if (!origGetImageData || !origPutImageData) return null;
                                    var w = srcCanvas.width;
                                    var h = srcCanvas.height;
                                    if (!w || !h || w <= 0 || h <= 0 || w > 4096 || h > 4096) return null;
                                    var clone = document.createElement('canvas');
                                    clone.width = w;
                                    clone.height = h;
                                    var ctx = clone.getContext('2d');
                                    if (!ctx) return null;
                                    ctx.drawImage(srcCanvas, 0, 0);
                                    var imgData = origGetImageData.call(ctx, 0, 0, w, h);
                                    if (imgData && imgData.data) {
                                        farblePixelArray(imgData.data, w * 31 + h);
                                        origPutImageData.call(ctx, imgData, 0, 0);
                                    }
                                    return clone;
                                } catch (_) {
                                    return null;
                                }
                            }

                            if (origToDataURL) {
                                HTMLCanvasElement.prototype.toDataURL = makeNative(function() {
                                    var clone = createFarbledCanvasClone(this);
                                    if (clone) {
                                        return origToDataURL.apply(clone, arguments);
                                    }
                                    return origToDataURL.apply(this, arguments);
                                }, 'toDataURL');
                            }

                            if (origToBlob) {
                                HTMLCanvasElement.prototype.toBlob = makeNative(function() {
                                    var clone = createFarbledCanvasClone(this);
                                    if (clone) {
                                        return origToBlob.apply(clone, arguments);
                                    }
                                    return origToBlob.apply(this, arguments);
                                }, 'toBlob');
                            }

                            function hookWebGLContext(proto) {
                                if (!proto) return;
                                try {
                                    var origReadPixels = proto.readPixels;
                                    if (typeof origReadPixels === 'function') {
                                        proto.readPixels = makeNative(function(x, y, width, height, format, type, pixels) {
                                            origReadPixels.apply(this, arguments);
                                            try {
                                                if (pixels && typeof pixels.length === 'number' && pixels.length >= 16) {
                                                    farblePixelArray(pixels, (width | 0) * 37 + (height | 0));
                                                }
                                            } catch (_) {}
                                        }, 'readPixels');
                                    }
                                    var origGetParameter = proto.getParameter;
                                    if (typeof origGetParameter === 'function') {
                                        var jitter = ((fpSeed % 7) + 1) * 1e-5;
                                        proto.getParameter = makeNative(function(pname) {
                                            var res = origGetParameter.apply(this, arguments);
                                            try {
                                                // ALIASED_LINE_WIDTH_RANGE (0x846E) or ALIASED_POINT_SIZE_RANGE (0x846D)
                                                if ((pname === 0x846E || pname === 0x846D) && res && res.length === 2) {
                                                    var copy = new Float32Array(2);
                                                    copy[0] = res[0];
                                                    copy[1] = res[1] + jitter;
                                                    return copy;
                                                }
                                            } catch (_) {}
                                            return res;
                                        }, 'getParameter');
                                    }
                                } catch (_) {}
                            }

                            if (window.WebGLRenderingContext) hookWebGLContext(WebGLRenderingContext.prototype);
                            if (window.WebGL2RenderingContext) hookWebGLContext(WebGL2RenderingContext.prototype);

                            // 10d. AudioContext / OfflineAudioContext Sample Farbling
                            if (window.AudioBuffer && AudioBuffer.prototype.getChannelData) {
                                var origGetChannelData = AudioBuffer.prototype.getChannelData;
                                var farbledAudioBuffers = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
                                var audioNoise = ((fpSeed % 89) + 11) * 1e-6;
                                AudioBuffer.prototype.getChannelData = makeNative(function() {
                                    var data = origGetChannelData.apply(this, arguments);
                                    try {
                                        if (data && data.length > 0 && (!farbledAudioBuffers || !farbledAudioBuffers.has(data))) {
                                            if (farbledAudioBuffers) farbledAudioBuffers.add(data);
                                            for (var i = 0; i < data.length; i += 7) {
                                                data[i] = data[i] + audioNoise;
                                            }
                                        }
                                    } catch (_) {}
                                    return data;
                                }, 'getChannelData');
                            }
                        }
                    } catch (_) {}
                }
            } catch (_) {}
        })();
    """.trimIndent()
}
