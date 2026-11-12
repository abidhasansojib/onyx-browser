(function() {
    if (window.__onyxPasskeyInstalled) return;
    if (!window._OnyxPasskeyBridge) return;
    var passkeyEnabled = false;
    var passwordCredEnabled = false;
    try { passkeyEnabled = !!window._OnyxPasskeyBridge.isPasskeySupported(); } catch(_) {}
    try { passwordCredEnabled = !!window._OnyxPasskeyBridge.isPasswordCredentialSupported(); } catch(_) {}
    if (!passkeyEnabled && !passwordCredEnabled) return;

    var host = (location.hostname || '').toLowerCase();
    var href = (location.href || '').toLowerCase();
    var path = (location.pathname || '').toLowerCase();

    // Never tamper with navigator.credentials on Cloudflare challenges, Turnstile, or CAPTCHA pages
    var isSecurityOrChallenge =
        typeof window.__cf_chl_opt !== 'undefined' ||
        typeof window.__cf_chl_ctx !== 'undefined' ||
        host === 'challenges.cloudflare.com' ||
        host.endsWith('.challenges.cloudflare.com') ||
        path.indexOf('/cdn-cgi/') !== -1 ||
        href.indexOf('__cf_chl') !== -1 ||
        /recaptcha|hcaptcha|arkose|turnstile|funcaptcha|datadome|perimeterx|kasada|geetest/i.test(host + href);

    if (isSecurityOrChallenge) return;

    // Never replace navigator.credentials on Meta/Facebook auth domains.
    // Facebook's login confirmation, checkpoint, and 2FA flows call navigator.credentials
    // internally for device-key verification.
    var ref = (document.referrer || '').toLowerCase();
    if (/facebook\.com|meta\.com|fb\.com|fb\.me|instagram\.com|messenger\.com|arkose|funcaptcha/i.test(ref)) return;

    var isMetaAuthDomain =
        host === 'facebook.com' || host.endsWith('.facebook.com') ||
        host === 'meta.com' || host.endsWith('.meta.com') ||
        host === 'fb.com' || host.endsWith('.fb.com') ||
        host === 'fb.me' || host.endsWith('.fb.me') ||
        host === 'messenger.com' || host.endsWith('.messenger.com') ||
        host === 'instagram.com' || host.endsWith('.instagram.com') ||
        host === 'cdninstagram.com' || host.endsWith('.cdninstagram.com') ||
        host === 'threads.net' || host.endsWith('.threads.net') ||
        host === 'fbcdn.net' || host.endsWith('.fbcdn.net') ||
        host === 'facebook.net' || host.endsWith('.facebook.net') ||
        host === 'fbsbx.com' || host.endsWith('.fbsbx.com') ||
        host.indexOf('arkose') !== -1 || host.indexOf('funcaptcha') !== -1;

    if (isMetaAuthDomain) return;

    window.__onyxPasskeyInstalled = true;

    var pendingCallbacks = {};

    window.__onyxPasskeyResolve = function(id, payload) {
        var cb = pendingCallbacks[id];
        if (cb) {
            delete pendingCallbacks[id];
            try {
                var parsed = (typeof payload === 'string') ? JSON.parse(payload) : payload;
                cb.resolve(parsed);
            } catch (e) {
                cb.reject(new DOMException('Malformed credential response', 'UnknownError'));
            }
        }
    };

    window.__onyxPasskeyReject = function(id, type, msg) {
        var cb = pendingCallbacks[id];
        if (cb) {
            delete pendingCallbacks[id];
            var err;
            try {
                if (type === 'TypeError') {
                    err = new TypeError(msg || 'Invalid WebAuthn options');
                } else {
                    err = new DOMException(msg || 'WebAuthn failed', type || 'NotAllowedError');
                }
            } catch (_) {
                err = new Error(msg || 'WebAuthn failed');
                err.name = type || 'NotAllowedError';
            }
            cb.reject(err);
        }
    };

    function isBinaryBuffer(val) {
        return val && (
            val instanceof ArrayBuffer ||
            ArrayBuffer.isView(val) ||
            Object.prototype.toString.call(val) === '[object ArrayBuffer]'
        );
    }

    function bufferToBase64Url(buffer) {
        if (!buffer) return '';
        if (typeof buffer === 'string') {
            return buffer.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
        }
        try {
            var view;
            if (buffer instanceof Uint8Array) {
                view = buffer;
            } else if (ArrayBuffer.isView(buffer)) {
                view = new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength);
            } else {
                view = new Uint8Array(buffer);
            }
            var chunks = [];
            var chunkSize = 8192;
            for (var i = 0; i < view.byteLength; i += chunkSize) {
                var slice = view.subarray(i, Math.min(i + chunkSize, view.byteLength));
                chunks.push(String.fromCharCode.apply(null, slice));
            }
            return btoa(chunks.join('')).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
        } catch (e) {
            return '';
        }
    }

    function base64UrlToBuffer(base64url) {
        if (!base64url || typeof base64url !== 'string') return new ArrayBuffer(0);
        try {
            var base64 = base64url.trim().replace(/-/g, '+').replace(/_/g, '/');
            while (base64.length % 4) {
                base64 += '=';
            }
            var binary = atob(base64);
            var bytes = new Uint8Array(binary.length);
            for (var i = 0; i < binary.length; i++) {
                bytes[i] = binary.charCodeAt(i);
            }
            return bytes.buffer;
        } catch (e) {
            return new ArrayBuffer(0);
        }
    }

    function sanitizeTransports(transports) {
        if (!Array.isArray(transports)) return undefined;
        var valid = [];
        for (var i = 0; i < transports.length; i++) {
            var t = String(transports[i] || '').toLowerCase();
            if (t === 'cable') t = 'hybrid';
            if ((t === 'internal' || t === 'hybrid' || t === 'usb' || t === 'nfc' || t === 'ble') &&
                valid.indexOf(t) === -1) {
                valid.push(t);
            }
        }
        return valid.length > 0 ? valid : undefined;
    }

    function sanitizeExtensions(ext) {
        if (!ext || typeof ext !== 'object') return undefined;
        var out = {};
        var hasKeys = false;
        var keys = Object.keys(ext);
        for (var i = 0; i < keys.length; i++) {
            var k = keys[i];
            var v = ext[k];
            if (v === undefined || typeof v === 'function') continue;
            if (isBinaryBuffer(v)) {
                out[k] = bufferToBase64Url(v);
                hasKeys = true;
            } else if (Array.isArray(v)) {
                out[k] = v.map(function(item) {
                    return isBinaryBuffer(item) ? bufferToBase64Url(item) : item;
                });
                hasKeys = true;
            } else if (v !== null && typeof v === 'object') {
                var sub = sanitizeExtensions(v);
                if (sub !== undefined) {
                    out[k] = sub;
                    hasKeys = true;
                }
            } else {
                out[k] = v;
                hasKeys = true;
            }
        }
        return hasKeys ? out : undefined;
    }

    function isTopWindow() {
        try { return window.self === window.top; } catch (_) { return false; }
    }

    function buildPasswordCredentialObject(id, password, name, iconURL) {
        var obj = {
            id: String(id || ''),
            password: String(password || ''),
            name: name ? String(name) : String(id || ''),
            iconURL: iconURL ? String(iconURL) : '',
            type: 'password'
        };
        if (typeof window.PasswordCredential === 'function' && window.PasswordCredential.prototype) {
            try { Object.setPrototypeOf(obj, window.PasswordCredential.prototype); } catch (_) {}
        }
        return obj;
    }

    if (typeof window.PasswordCredential !== 'function') {
        window.PasswordCredential = function PasswordCredential(init) {
            if (!init) throw new TypeError('PasswordCredential requires init data or HTMLFormElement');
            if (typeof HTMLFormElement !== 'undefined' && init instanceof HTMLFormElement) {
                var fd = new FormData(init);
                var uid = '';
                var pwd = '';
                fd.forEach(function(val, key) {
                    var lk = String(key).toLowerCase();
                    if (!pwd && lk.indexOf('pass') !== -1) pwd = String(val);
                    else if (!uid && (lk.indexOf('user') !== -1 || lk.indexOf('email') !== -1 || lk.indexOf('login') !== -1)) uid = String(val);
                });
                this.id = uid;
                this.password = pwd;
                this.name = uid;
                this.iconURL = '';
            } else {
                if (!init.id || !init.password) {
                    throw new TypeError('PasswordCredential requires non-empty id and password');
                }
                this.id = String(init.id);
                this.password = String(init.password);
                this.name = init.name ? String(init.name) : '';
                this.iconURL = init.iconURL ? String(init.iconURL) : '';
            }
            this.type = 'password';
        };
    }

    if (passkeyEnabled) {
        if (typeof window.PublicKeyCredential !== 'function') {
            window.PublicKeyCredential = function PublicKeyCredential() {};
        }
        if (typeof window.AuthenticatorResponse !== 'function') {
            window.AuthenticatorResponse = function AuthenticatorResponse() {};
        }
        if (typeof window.AuthenticatorAttestationResponse !== 'function') {
            window.AuthenticatorAttestationResponse = function AuthenticatorAttestationResponse() {};
            window.AuthenticatorAttestationResponse.prototype = Object.create(window.AuthenticatorResponse.prototype);
        }
        if (typeof window.AuthenticatorAssertionResponse !== 'function') {
            window.AuthenticatorAssertionResponse = function AuthenticatorAssertionResponse() {};
            window.AuthenticatorAssertionResponse.prototype = Object.create(window.AuthenticatorResponse.prototype);
        }

        window.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable = function() {
            var enabled = true;
            try { enabled = !!window._OnyxPasskeyBridge.isPasskeySupported(); } catch (_) {}
            return Promise.resolve(enabled);
        };
        // Conditional mediation (silent autofill-assisted passkey UI) must resolve false so sites
        // do not launch modal CredentialManager sheets automatically on page load before user click.
        window.PublicKeyCredential.isConditionalMediationAvailable = function() {
            return Promise.resolve(false);
        };
        window.PublicKeyCredential.getClientCapabilities = function() {
            var enabled = true;
            try { enabled = !!window._OnyxPasskeyBridge.isPasskeySupported(); } catch (_) {}
            return Promise.resolve({
                conditionalCreate: false,
                conditionalGet: false,
                hybridTransport: enabled,
                passkeyPlatformAuthenticator: enabled,
                userVerifyingPlatformAuthenticator: enabled,
                relatedOrigins: false
            });
        };
        if (typeof window.PublicKeyCredential.parseCreationOptionsFromJSON !== 'function') {
            window.PublicKeyCredential.parseCreationOptionsFromJSON = function(json) {
                if (!json) return json;
                var res = Object.assign({}, json);
                if (typeof res.challenge === 'string') {
                    res.challenge = base64UrlToBuffer(res.challenge);
                }
                if (res.user && typeof res.user.id === 'string') {
                    res.user = Object.assign({}, res.user, { id: base64UrlToBuffer(res.user.id) });
                }
                if (Array.isArray(res.excludeCredentials)) {
                    res.excludeCredentials = res.excludeCredentials.map(function(item) {
                        if (item && typeof item.id === 'string') {
                            return Object.assign({}, item, { id: base64UrlToBuffer(item.id) });
                        }
                        return item;
                    });
                }
                return res;
            };
        }
        if (typeof window.PublicKeyCredential.parseRequestOptionsFromJSON !== 'function') {
            window.PublicKeyCredential.parseRequestOptionsFromJSON = function(json) {
                if (!json) return json;
                var res = Object.assign({}, json);
                if (typeof res.challenge === 'string') {
                    res.challenge = base64UrlToBuffer(res.challenge);
                }
                if (Array.isArray(res.allowCredentials)) {
                    res.allowCredentials = res.allowCredentials.map(function(item) {
                        if (item && typeof item.id === 'string') {
                            return Object.assign({}, item, { id: base64UrlToBuffer(item.id) });
                        }
                        return item;
                    });
                }
                return res;
            };
        }
    }

    var origCredentials = navigator.credentials;
    var origCreate = origCredentials && origCredentials.create ? origCredentials.create.bind(origCredentials) : null;
    var origGet = origCredentials && origCredentials.get ? origCredentials.get.bind(origCredentials) : null;
    var origStore = origCredentials && origCredentials.store ? origCredentials.store.bind(origCredentials) : null;

    var customCredentials = {
        create: function(options) {
            if (options && options.signal && options.signal.aborted) {
                return Promise.reject(new DOMException('The operation was aborted', 'AbortError'));
            }

            if (options && options.password) {
                try {
                    var pwCred = (options.password instanceof window.PasswordCredential)
                        ? options.password
                        : new window.PasswordCredential(options.password);
                    return Promise.resolve(pwCred);
                } catch (e) {
                    return Promise.reject(e);
                }
            }

            if (!options || !options.publicKey) {
                return origCreate ? origCreate(options) : Promise.reject(new DOMException('Not supported', 'NotSupportedError'));
            }

            var isNowSupported = true;
            try { isNowSupported = !!window._OnyxPasskeyBridge.isPasskeySupported(); } catch (_) {}
            if (!isNowSupported) {
                return Promise.reject(new DOMException('Passkeys are disabled in browser settings', 'NotAllowedError'));
            }

            return new Promise(function(resolve, reject) {
                var pk = options.publicKey;
                if (!pk.user || !pk.user.id || !pk.challenge) {
                    return reject(new TypeError('Missing required PublicKeyCredentialCreationOptions fields'));
                }

                var credParams = [];
                if (Array.isArray(pk.pubKeyCredParams)) {
                    for (var i = 0; i < pk.pubKeyCredParams.length; i++) {
                        var p = pk.pubKeyCredParams[i];
                        if (p && (p.type === 'public-key' || !p.type) && typeof p.alg === 'number') {
                            credParams.push({ type: 'public-key', alg: Math.trunc(p.alg) });
                        }
                    }
                }
                if (credParams.length === 0) {
                    credParams = [
                        { type: 'public-key', alg: -7 },
                        { type: 'public-key', alg: -257 },
                        { type: 'public-key', alg: -8 }
                    ];
                }

                var rpObj = {
                    id: (pk.rp && pk.rp.id) ? String(pk.rp.id) : window.location.hostname,
                    name: (pk.rp && pk.rp.name) ? String(pk.rp.name) : (document.title || window.location.hostname)
                };

                var authSel = {};
                if (pk.authenticatorSelection && typeof pk.authenticatorSelection === 'object') {
                    var as = pk.authenticatorSelection;
                    if (as.authenticatorAttachment === 'platform' || as.authenticatorAttachment === 'cross-platform') {
                        authSel.authenticatorAttachment = as.authenticatorAttachment;
                    }
                    if (as.residentKey === 'discouraged' || as.residentKey === 'preferred' || as.residentKey === 'required') {
                        authSel.residentKey = as.residentKey;
                    }
                    if (typeof as.requireResidentKey === 'boolean') {
                        authSel.requireResidentKey = as.requireResidentKey;
                    } else if (authSel.residentKey === 'required') {
                        authSel.requireResidentKey = true;
                    }
                    if (as.userVerification === 'required' || as.userVerification === 'preferred' || as.userVerification === 'discouraged') {
                        authSel.userVerification = as.userVerification;
                    }
                }
                if (!authSel.residentKey) authSel.residentKey = 'preferred';
                if (!authSel.userVerification) authSel.userVerification = 'preferred';

                var att = pk.attestation;
                if (att !== 'none' && att !== 'indirect' && att !== 'direct' && att !== 'enterprise') {
                    att = 'none';
                }

                var jsonReq = {
                    rp: rpObj,
                    user: {
                        id: bufferToBase64Url(pk.user.id),
                        name: String(pk.user.name || ''),
                        displayName: String(pk.user.displayName || pk.user.name || '')
                    },
                    challenge: bufferToBase64Url(pk.challenge),
                    pubKeyCredParams: credParams,
                    timeout: (typeof pk.timeout === 'number' && pk.timeout > 0) ? Math.floor(pk.timeout) : 180000,
                    attestation: att,
                    authenticatorSelection: authSel
                };

                var cleanExt = sanitizeExtensions(pk.extensions);
                if (cleanExt) {
                    jsonReq.extensions = cleanExt;
                }

                if (Array.isArray(pk.excludeCredentials) && pk.excludeCredentials.length > 0) {
                    var excl = [];
                    for (var j = 0; j < pk.excludeCredentials.length; j++) {
                        var item = pk.excludeCredentials[j];
                        if (!item || !item.id) continue;
                        var entry = {
                            id: bufferToBase64Url(item.id),
                            type: 'public-key'
                        };
                        var tr = sanitizeTransports(item.transports);
                        if (tr) entry.transports = tr;
                        excl.push(entry);
                    }
                    if (excl.length > 0) {
                        jsonReq.excludeCredentials = excl;
                    }
                }

                var cbId = 'reg_' + Date.now() + '_' + Math.random().toString(36).slice(2, 11);
                pendingCallbacks[cbId] = {
                    resolve: function(resp) {
                        var credIdStr = resp.id || resp.rawId || '';
                        var rawIdStr = resp.rawId || resp.id || '';
                        var rawId = base64UrlToBuffer(rawIdStr);
                        var respInner = resp.response || {};
                        var clientDataB64 = respInner.clientDataJSON || '';
                        var attestationB64 = respInner.attestationObject || '';
                        var authDataB64 = respInner.authenticatorData || '';
                        var pubKeyB64 = respInner.publicKey || '';
                        var pubKeyAlg = (typeof respInner.publicKeyAlgorithm === 'number') ? respInner.publicKeyAlgorithm : -7;
                        var transportsList = Array.isArray(respInner.transports) && respInner.transports.length > 0
                            ? respInner.transports
                            : ['internal', 'hybrid'];

                        var clientData = base64UrlToBuffer(clientDataB64);
                        var attestationObj = base64UrlToBuffer(attestationB64);
                        var authData = authDataB64 ? base64UrlToBuffer(authDataB64) : new ArrayBuffer(0);
                        var pubKeyBuf = pubKeyB64 ? base64UrlToBuffer(pubKeyB64) : null;
                        var attachment = resp.authenticatorAttachment || 'platform';
                        var extResults = resp.clientExtensionResults || { credProps: { rk: true } };

                        var attestationResponse = {
                            clientDataJSON: clientData,
                            attestationObject: attestationObj,
                            getTransports: function() { return transportsList; },
                            getAuthenticatorData: function() { return authData; },
                            getPublicKey: function() { return pubKeyBuf && pubKeyBuf.byteLength > 0 ? pubKeyBuf : null; },
                            getPublicKeyAlgorithm: function() { return pubKeyAlg; },
                            toJSON: function() {
                                var rj = {
                                    clientDataJSON: clientDataB64,
                                    attestationObject: attestationB64,
                                    transports: transportsList,
                                    publicKeyAlgorithm: pubKeyAlg
                                };
                                if (authDataB64) rj.authenticatorData = authDataB64;
                                if (pubKeyB64) rj.publicKey = pubKeyB64;
                                return rj;
                            }
                        };
                        if (window.AuthenticatorAttestationResponse && window.AuthenticatorAttestationResponse.prototype) {
                            try { Object.setPrototypeOf(attestationResponse, window.AuthenticatorAttestationResponse.prototype); } catch (_) {}
                        }

                        var result = {
                            id: credIdStr,
                            rawId: rawId,
                            type: resp.type || 'public-key',
                            authenticatorAttachment: attachment,
                            response: attestationResponse,
                            getClientExtensionResults: function() { return extResults; },
                            toJSON: function() {
                                return {
                                    id: credIdStr,
                                    rawId: rawIdStr,
                                    type: resp.type || 'public-key',
                                    authenticatorAttachment: attachment,
                                    response: attestationResponse.toJSON(),
                                    clientExtensionResults: extResults
                                };
                            }
                        };
                        if (window.PublicKeyCredential && window.PublicKeyCredential.prototype) {
                            try { Object.setPrototypeOf(result, window.PublicKeyCredential.prototype); } catch (_) {}
                        }

                        resolve(result);
                    },
                    reject: reject
                };

                if (options && options.signal) {
                    options.signal.addEventListener('abort', function() {
                        if (pendingCallbacks[cbId]) {
                            delete pendingCallbacks[cbId];
                            reject(new DOMException('The operation was aborted', 'AbortError'));
                        }
                    });
                }

                window._OnyxPasskeyBridge.createPasskey(
                    JSON.stringify(jsonReq),
                    window.location.origin,
                    isTopWindow(),
                    cbId
                );
            });
        },

        get: function(options) {
            if (options && options.signal && options.signal.aborted) {
                return Promise.reject(new DOMException('The operation was aborted', 'AbortError'));
            }

            // Hold conditional mediation requests passively until aborted so login pages
            // never auto-pop modal CredentialManager dialogs on page load.
            if (options && options.mediation === 'conditional') {
                return new Promise(function(_, reject) {
                    if (options.signal) {
                        options.signal.addEventListener('abort', function() {
                            reject(new DOMException('Conditional mediation aborted', 'AbortError'));
                        });
                    }
                });
            }

            // Password-only Credential Management API request
            if (options && options.password && !options.publicKey) {
                var pwAllowed = false;
                try { pwAllowed = !!window._OnyxPasskeyBridge.isPasswordCredentialSupported(); } catch (_) {}
                if (!pwAllowed) return Promise.resolve(null);

                return new Promise(function(resolve, reject) {
                    var cbId = 'pwd_' + Date.now() + '_' + Math.random().toString(36).slice(2, 11);
                    pendingCallbacks[cbId] = {
                        resolve: function(resp) {
                            if (resp && resp.__onyxCredentialType === 'password' && resp.id) {
                                resolve(buildPasswordCredentialObject(resp.id, resp.password));
                            } else {
                                resolve(null);
                            }
                        },
                        reject: function() { resolve(null); }
                    };
                    if (options.signal) {
                        options.signal.addEventListener('abort', function() {
                            if (pendingCallbacks[cbId]) {
                                delete pendingCallbacks[cbId];
                                reject(new DOMException('The operation was aborted', 'AbortError'));
                            }
                        });
                    }
                    window._OnyxPasskeyBridge.getPassword(
                        window.location.origin,
                        isTopWindow(),
                        options.mediation === 'silent',
                        cbId
                    );
                });
            }

            if (!options || !options.publicKey) {
                return origGet ? origGet(options) : Promise.reject(new DOMException('Not supported', 'NotSupportedError'));
            }

            if (options.mediation === 'silent') {
                return Promise.resolve(null);
            }

            var isNowSupported = true;
            try { isNowSupported = !!window._OnyxPasskeyBridge.isPasskeySupported(); } catch (_) {}
            if (!isNowSupported) {
                return Promise.reject(new DOMException('Passkeys are disabled in browser settings', 'NotAllowedError'));
            }

            return new Promise(function(resolve, reject) {
                var pk = options.publicKey;
                if (!pk.challenge) {
                    return reject(new TypeError('Missing required PublicKeyCredentialRequestOptions challenge'));
                }

                var uv = pk.userVerification;
                if (uv !== 'required' && uv !== 'preferred' && uv !== 'discouraged') {
                    uv = 'preferred';
                }

                var jsonReq = {
                    challenge: bufferToBase64Url(pk.challenge),
                    timeout: (typeof pk.timeout === 'number' && pk.timeout > 0) ? Math.floor(pk.timeout) : 180000,
                    rpId: pk.rpId ? String(pk.rpId) : window.location.hostname,
                    userVerification: uv
                };

                var cleanExt = sanitizeExtensions(pk.extensions);
                if (cleanExt) {
                    jsonReq.extensions = cleanExt;
                }

                if (Array.isArray(pk.allowCredentials) && pk.allowCredentials.length > 0) {
                    var allow = [];
                    for (var i = 0; i < pk.allowCredentials.length; i++) {
                        var item = pk.allowCredentials[i];
                        if (!item || !item.id) continue;
                        var entry = {
                            id: bufferToBase64Url(item.id),
                            type: 'public-key'
                        };
                        var tr = sanitizeTransports(item.transports);
                        if (tr) entry.transports = tr;
                        allow.push(entry);
                    }
                    if (allow.length > 0) {
                        jsonReq.allowCredentials = allow;
                    }
                }

                var cbId = 'auth_' + Date.now() + '_' + Math.random().toString(36).slice(2, 11);
                pendingCallbacks[cbId] = {
                    resolve: function(resp) {
                        if (resp && resp.__onyxCredentialType === 'password') {
                            resolve(buildPasswordCredentialObject(resp.id, resp.password));
                            return;
                        }

                        var credIdStr = resp.id || resp.rawId || '';
                        var rawIdStr = resp.rawId || resp.id || '';
                        var rawId = base64UrlToBuffer(rawIdStr);
                        var respInner = resp.response || {};
                        var clientDataB64 = respInner.clientDataJSON || '';
                        var authDataB64 = respInner.authenticatorData || '';
                        var sigB64 = respInner.signature || '';
                        var userHandleB64 = (respInner.userHandle && respInner.userHandle !== 'null') ? respInner.userHandle : null;

                        var clientData = base64UrlToBuffer(clientDataB64);
                        var authData = base64UrlToBuffer(authDataB64);
                        var sig = base64UrlToBuffer(sigB64);
                        var userHandle = userHandleB64 ? base64UrlToBuffer(userHandleB64) : null;
                        var attachment = resp.authenticatorAttachment || 'platform';
                        var extResults = resp.clientExtensionResults || {};

                        var assertionResponse = {
                            clientDataJSON: clientData,
                            authenticatorData: authData,
                            signature: sig,
                            userHandle: userHandle,
                            toJSON: function() {
                                var rj = {
                                    clientDataJSON: clientDataB64,
                                    authenticatorData: authDataB64,
                                    signature: sigB64
                                };
                                if (userHandleB64) rj.userHandle = userHandleB64;
                                return rj;
                            }
                        };
                        if (window.AuthenticatorAssertionResponse && window.AuthenticatorAssertionResponse.prototype) {
                            try { Object.setPrototypeOf(assertionResponse, window.AuthenticatorAssertionResponse.prototype); } catch (_) {}
                        }

                        var result = {
                            id: credIdStr,
                            rawId: rawId,
                            type: resp.type || 'public-key',
                            authenticatorAttachment: attachment,
                            response: assertionResponse,
                            getClientExtensionResults: function() { return extResults; },
                            toJSON: function() {
                                return {
                                    id: credIdStr,
                                    rawId: rawIdStr,
                                    type: resp.type || 'public-key',
                                    authenticatorAttachment: attachment,
                                    response: assertionResponse.toJSON(),
                                    clientExtensionResults: extResults
                                };
                            }
                        };
                        if (window.PublicKeyCredential && window.PublicKeyCredential.prototype) {
                            try { Object.setPrototypeOf(result, window.PublicKeyCredential.prototype); } catch (_) {}
                        }

                        resolve(result);
                    },
                    reject: reject
                };

                if (options && options.signal) {
                    options.signal.addEventListener('abort', function() {
                        if (pendingCallbacks[cbId]) {
                            delete pendingCallbacks[cbId];
                            reject(new DOMException('The operation was aborted', 'AbortError'));
                        }
                    });
                }

                window._OnyxPasskeyBridge.getPasskey(
                    JSON.stringify(jsonReq),
                    window.location.origin,
                    isTopWindow(),
                    !!options.password,
                    cbId
                );
            });
        },

        preventSilentAccess: function() {
            return origCredentials && origCredentials.preventSilentAccess ? origCredentials.preventSilentAccess() : Promise.resolve();
        },

        store: function(credential) {
            if (credential && credential.id && credential.password &&
                (credential.type === 'password' || typeof credential.password === 'string')) {
                var canStore = false;
                try { canStore = !!window._OnyxPasskeyBridge.isPasswordCredentialSupported(); } catch (_) {}
                if (canStore) {
                    return new Promise(function(resolve) {
                        var cbId = 'save_' + Date.now() + '_' + Math.random().toString(36).slice(2, 11);
                        pendingCallbacks[cbId] = {
                            resolve: function() { resolve(credential); },
                            reject: function() { resolve(credential); }
                        };
                        window._OnyxPasskeyBridge.storePassword(
                            String(credential.id),
                            String(credential.password),
                            window.location.origin,
                            isTopWindow(),
                            cbId
                        );
                    });
                }
            }
            return origStore ? origStore(credential) : Promise.resolve(credential);
        }
    };

    if (window.CredentialsContainer && window.CredentialsContainer.prototype) {
        try { Object.setPrototypeOf(customCredentials, window.CredentialsContainer.prototype); } catch (_) {}
    }

    try {
        Object.defineProperty(navigator, 'credentials', {
            get: function() { return customCredentials; },
            configurable: true
        });
    } catch (e) {
        navigator.credentials = customCredentials;
    }
})();
