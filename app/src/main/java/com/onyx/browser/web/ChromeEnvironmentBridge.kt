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
                        'OnyxNotificationBridge',
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

                    // ── 8. USB Devices Access Control ──────────────────────────────────
                    try {
                        var allowUsb = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isUsbAllowed === 'function') {
                            allowUsb = window.OnyxShieldBridge.isUsbAllowed(host);
                        }
                        if (!allowUsb && navigator.usb) {
                            if (typeof navigator.usb.requestDevice === 'function') {
                                navigator.usb.requestDevice = makeNative(function() {
                                    return Promise.reject(new DOMException('No device selected.', 'NotFoundError'));
                                }, 'requestDevice');
                            }
                            if (typeof navigator.usb.getDevices === 'function') {
                                navigator.usb.getDevices = makeNative(function() {
                                    return Promise.resolve([]);
                                }, 'getDevices');
                            }
                        }
                    } catch (_) {}

                    // ── 9. Serial Ports Access Control ─────────────────────────────────
                    try {
                        var allowSerial = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isSerialAllowed === 'function') {
                            allowSerial = window.OnyxShieldBridge.isSerialAllowed(host);
                        }
                        if (!allowSerial && navigator.serial) {
                            if (typeof navigator.serial.requestPort === 'function') {
                                navigator.serial.requestPort = makeNative(function() {
                                    return Promise.reject(new DOMException('No port selected.', 'NotFoundError'));
                                }, 'requestPort');
                            }
                            if (typeof navigator.serial.getPorts === 'function') {
                                navigator.serial.getPorts = makeNative(function() {
                                    return Promise.resolve([]);
                                }, 'getPorts');
                            }
                        }
                    } catch (_) {}

                    // ── 10. HID Devices Access Control ─────────────────────────────────
                    try {
                        var allowHid = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isHidAllowed === 'function') {
                            allowHid = window.OnyxShieldBridge.isHidAllowed(host);
                        }
                        if (!allowHid && navigator.hid) {
                            if (typeof navigator.hid.requestDevice === 'function') {
                                navigator.hid.requestDevice = makeNative(function() {
                                    return Promise.reject(new DOMException('No device selected.', 'NotFoundError'));
                                }, 'requestDevice');
                            }
                            if (typeof navigator.hid.getDevices === 'function') {
                                navigator.hid.getDevices = makeNative(function() {
                                    return Promise.resolve([]);
                                }, 'getDevices');
                            }
                        }
                    } catch (_) {}

                    // ── 11. MIDI Devices Access Control ────────────────────────────────
                    try {
                        var allowMidi = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isMidiAllowed === 'function') {
                            allowMidi = window.OnyxShieldBridge.isMidiAllowed(host);
                        }
                        if (!allowMidi && typeof navigator.requestMIDIAccess === 'function') {
                            navigator.requestMIDIAccess = makeNative(function() {
                                return Promise.reject(new DOMException('Web MIDI access denied.', 'SecurityError'));
                            }, 'requestMIDIAccess');
                        }
                    } catch (_) {}

                    // ── 12. Hand Tracking Access Control ───────────────────────────────
                    try {
                        var allowHandTracking = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isHandTrackingAllowed === 'function') {
                            allowHandTracking = window.OnyxShieldBridge.isHandTrackingAllowed(host);
                        }
                        if (!allowHandTracking) {
                            if (window.XRHand) {
                                window.XRHand = undefined;
                            }
                            if (window.XRInputSource && window.XRInputSource.prototype) {
                                try {
                                    Object.defineProperty(window.XRInputSource.prototype, 'hand', {
                                        get: function() { return null; },
                                        configurable: true
                                    });
                                } catch (_) {}
                            }
                        }
                    } catch (_) {}

                    // ── 13. Idle Detection Access Control ──────────────────────────────
                    try {
                        var allowIdle = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isIdleDetectionAllowed === 'function') {
                            allowIdle = window.OnyxShieldBridge.isIdleDetectionAllowed(host);
                        }
                        if (!allowIdle && window.IdleDetector) {
                            if (typeof window.IdleDetector.requestPermission === 'function') {
                                window.IdleDetector.requestPermission = makeNative(function() {
                                    return Promise.resolve('denied');
                                }, 'requestPermission');
                            }
                            if (window.IdleDetector.prototype && typeof window.IdleDetector.prototype.start === 'function') {
                                window.IdleDetector.prototype.start = makeNative(function() {
                                    return Promise.reject(new DOMException('Idle detection permission denied.', 'NotAllowedError'));
                                }, 'start');
                            }
                        }
                    } catch (_) {}

                    // ── 14. Local Fonts Access Control ─────────────────────────────────
                    try {
                        var allowFonts = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isLocalFontsAllowed === 'function') {
                            allowFonts = window.OnyxShieldBridge.isLocalFontsAllowed(host);
                        }
                        if (!allowFonts) {
                            if (typeof window.queryLocalFonts === 'function') {
                                window.queryLocalFonts = makeNative(function() {
                                    return Promise.reject(new DOMException('Local fonts permission denied.', 'SecurityError'));
                                }, 'queryLocalFonts');
                            }
                            if (navigator.fonts && typeof navigator.fonts.queryLocalFonts === 'function') {
                                navigator.fonts.queryLocalFonts = makeNative(function() {
                                    return Promise.reject(new DOMException('Local fonts permission denied.', 'SecurityError'));
                                }, 'queryLocalFonts');
                            }
                        }
                    } catch (_) {}

                    // ── 15. Bluetooth Devices Access Control ───────────────────────────
                    try {
                        var allowBluetooth = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isBluetoothAllowed === 'function') {
                            allowBluetooth = window.OnyxShieldBridge.isBluetoothAllowed(host);
                        }
                        if (!allowBluetooth && navigator.bluetooth) {
                            if (typeof navigator.bluetooth.requestDevice === 'function') {
                                navigator.bluetooth.requestDevice = makeNative(function() {
                                    return Promise.reject(new DOMException('User cancelled the requestDevice() chooser.', 'NotFoundError'));
                                }, 'requestDevice');
                            }
                            if (typeof navigator.bluetooth.getDevices === 'function') {
                                navigator.bluetooth.getDevices = makeNative(function() {
                                    return Promise.resolve([]);
                                }, 'getDevices');
                            }
                        }
                    } catch (_) {}

                    // ── 16. Screen Wake Lock Access Control ────────────────────────────
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

                    // ── 17. Payment Request Access Control ─────────────────────────────
                    try {
                        var allowPayment = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isPaymentHandlerAllowed === 'function') {
                            allowPayment = window.OnyxShieldBridge.isPaymentHandlerAllowed(host);
                        }
                        if (!allowPayment && window.PaymentRequest) {
                            if (window.PaymentRequest.prototype && typeof window.PaymentRequest.prototype.canMakePayment === 'function') {
                                window.PaymentRequest.prototype.canMakePayment = makeNative(function() {
                                    return Promise.resolve(false);
                                }, 'canMakePayment');
                            }
                            if (window.PaymentRequest.prototype && typeof window.PaymentRequest.prototype.show === 'function') {
                                window.PaymentRequest.prototype.show = makeNative(function() {
                                    return Promise.reject(new DOMException('Payment Request is disabled by user settings.', 'NotAllowedError'));
                                }, 'show');
                            }
                        }
                    } catch (_) {}

                    // ── 18. File Editing / System Access Control ─────────────────────────
                    try {
                        var allowFileEditing = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isFileEditingAllowed === 'function') {
                            allowFileEditing = window.OnyxShieldBridge.isFileEditingAllowed(host);
                        }
                        if (!allowFileEditing) {
                            if (typeof window.showOpenFilePicker === 'function') {
                                window.showOpenFilePicker = makeNative(function() {
                                    return Promise.reject(new DOMException('File editing access denied.', 'SecurityError'));
                                }, 'showOpenFilePicker');
                            }
                            if (typeof window.showSaveFilePicker === 'function') {
                                window.showSaveFilePicker = makeNative(function() {
                                    return Promise.reject(new DOMException('File editing access denied.', 'SecurityError'));
                                }, 'showSaveFilePicker');
                            }
                            if (typeof window.showDirectoryPicker === 'function') {
                                window.showDirectoryPicker = makeNative(function() {
                                    return Promise.reject(new DOMException('File editing access denied.', 'SecurityError'));
                                }, 'showDirectoryPicker');
                            }
                        }
                    } catch (_) {}

                    // ── 19. Virtual Reality & WebXR Device Access Control ────────────────
                    try {
                        var allowVr = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isVrAllowed === 'function') {
                            allowVr = window.OnyxShieldBridge.isVrAllowed(host);
                        }
                        if (!allowVr && navigator.xr) {
                            if (typeof navigator.xr.isSessionSupported === 'function') {
                                navigator.xr.isSessionSupported = makeNative(function() {
                                    return Promise.resolve(false);
                                }, 'isSessionSupported');
                            }
                            if (typeof navigator.xr.requestSession === 'function') {
                                navigator.xr.requestSession = makeNative(function() {
                                    return Promise.reject(new DOMException('WebXR session access denied by user settings.', 'NotAllowedError'));
                                }, 'requestSession');
                            }
                        }
                    } catch (_) {}

                    // ── 20. Web NFC Access Control ──────────────────────────────────────
                    try {
                        var allowNfc = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isNfcAllowed === 'function') {
                            allowNfc = window.OnyxShieldBridge.isNfcAllowed(host);
                        }
                        if (!allowNfc && window.NDEFReader) {
                            if (window.NDEFReader.prototype) {
                                if (typeof window.NDEFReader.prototype.scan === 'function') {
                                    window.NDEFReader.prototype.scan = makeNative(function() {
                                        return Promise.reject(new DOMException('NFC scan permission denied.', 'NotAllowedError'));
                                    }, 'scan');
                                }
                                if (typeof window.NDEFReader.prototype.write === 'function') {
                                    window.NDEFReader.prototype.write = makeNative(function() {
                                        return Promise.reject(new DOMException('NFC write permission denied.', 'NotAllowedError'));
                                    }, 'write');
                                }
                                if (typeof window.NDEFReader.prototype.makeReadOnly === 'function') {
                                    window.NDEFReader.prototype.makeReadOnly = makeNative(function() {
                                        return Promise.reject(new DOMException('NFC makeReadOnly permission denied.', 'NotAllowedError'));
                                    }, 'makeReadOnly');
                                }
                            }
                        }
                    } catch (_) {}

                    // ── 21. Window Management & Multi-Screen Access Control ──────────────
                    try {
                        var allowWindowMgmt = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isWindowManagementAllowed === 'function') {
                            allowWindowMgmt = window.OnyxShieldBridge.isWindowManagementAllowed(host);
                        }
                        if (!allowWindowMgmt) {
                            if (typeof window.getScreenDetails === 'function') {
                                window.getScreenDetails = makeNative(function() {
                                    return Promise.reject(new DOMException('Window management permission denied.', 'NotAllowedError'));
                                }, 'getScreenDetails');
                            }
                            if (typeof window.openWindow === 'function') {
                                window.openWindow = makeNative(function() {
                                    return Promise.reject(new DOMException('Window management permission denied.', 'NotAllowedError'));
                                }, 'openWindow');
                            }
                        }
                    } catch (_) {}

                    // ── 22. Storage Access API Control ──────────────────────────────────
                    try {
                        var allowStorageAccess = true;
                        if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.isStorageAccessAllowed === 'function') {
                            allowStorageAccess = window.OnyxShieldBridge.isStorageAccessAllowed(host);
                        }
                        if (!allowStorageAccess && document) {
                            if (typeof document.requestStorageAccess === 'function') {
                                document.requestStorageAccess = makeNative(function() {
                                    return Promise.reject(new DOMException('Storage access permission denied.', 'NotAllowedError'));
                                }, 'requestStorageAccess');
                            }
                            if (typeof document.hasStorageAccess === 'function') {
                                document.hasStorageAccess = makeNative(function() {
                                    return Promise.resolve(false);
                                }, 'hasStorageAccess');
                            }
                            if (typeof document.requestStorageAccessFor === 'function') {
                                document.requestStorageAccessFor = makeNative(function() {
                                    return Promise.reject(new DOMException('Storage access permission denied.', 'NotAllowedError'));
                                }, 'requestStorageAccessFor');
                            }
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
                }
            } catch (_) {}
        })();
    """.trimIndent()
}
