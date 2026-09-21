package com.onyx.browser.web

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.onyx.browser.data.preferences.BrowserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest

class PasskeyWebAuthnBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val coroutineScope: CoroutineScope
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val credentialManager by lazy { CredentialManager.create(activity) }
    private val preferences by lazy { BrowserPreferences.getInstance(activity) }

    @JavascriptInterface
    fun isPasskeySupported(): Boolean {
        return preferences.isPasskeysEnabled
    }

    @JavascriptInterface
    fun createPasskey(requestJson: String, callbackId: String) {
        val origin = getEffectiveOrigin("")
        createPasskeyInternal(requestJson, origin, callbackId)
    }

    @JavascriptInterface
    fun createPasskey(requestJson: String, origin: String, callbackId: String) {
        val effectiveOrigin = getEffectiveOrigin(origin)
        createPasskeyInternal(requestJson, effectiveOrigin, callbackId)
    }

    private fun createPasskeyInternal(requestJson: String, origin: String, callbackId: String) {
        if (!preferences.isPasskeysEnabled) {
            sendError(callbackId, "NotAllowedError", "Passkeys are disabled in browser settings")
            return
        }

        mainHandler.post {
            try {
                val reqObj = JSONObject(requestJson)
                val challenge = reqObj.optString("challenge", "")
                val (clientDataBytes, clientDataHash) = computeClientData(
                    type = "webauthn.create",
                    challenge = challenge,
                    origin = origin
                )

                var usedClientDataHash = true
                val createRequest = try {
                    CreatePublicKeyCredentialRequest(
                        requestJson = requestJson,
                        clientDataHash = clientDataHash,
                        origin = origin.ifBlank { null },
                        preferImmediatelyAvailableCredentials = false
                    )
                } catch (_: Throwable) {
                    usedClientDataHash = false
                    CreatePublicKeyCredentialRequest(
                        requestJson = requestJson,
                        preferImmediatelyAvailableCredentials = false
                    )
                }

                coroutineScope.launch(Dispatchers.Main) {
                    try {
                        val response = try {
                            credentialManager.createCredential(activity, createRequest)
                        } catch (e: SecurityException) {
                            // If origin setting fails due to system security check, retry without origin
                            usedClientDataHash = false
                            val fallbackRequest = CreatePublicKeyCredentialRequest(
                                requestJson = requestJson,
                                preferImmediatelyAvailableCredentials = false
                            )
                            credentialManager.createCredential(activity, fallbackRequest)
                        }

                        if (response is CreatePublicKeyCredentialResponse) {
                            val finalResponse = if (usedClientDataHash) {
                                enrichRegistrationResponse(
                                    response.registrationResponseJson,
                                    clientDataBytes
                                )
                            } else {
                                response.registrationResponseJson
                            }
                            sendSuccess(callbackId, finalResponse)
                        } else {
                            sendError(callbackId, "UnknownError", "Unexpected credential response")
                        }
                    } catch (_: CreateCredentialCancellationException) {
                        sendError(callbackId, "NotAllowedError", "User cancelled passkey creation")
                    } catch (e: CreateCredentialException) {
                        sendError(callbackId, "InvalidStateError", e.message ?: "Passkey creation failed")
                    } catch (t: Throwable) {
                        sendError(callbackId, "UnknownError", t.message ?: "Passkey creation failed")
                    }
                }
            } catch (t: Throwable) {
                sendError(callbackId, "SyntaxError", "Invalid passkey request: ${t.message}")
            }
        }
    }

    @JavascriptInterface
    fun getPasskey(requestJson: String, callbackId: String) {
        val origin = getEffectiveOrigin("")
        getPasskeyInternal(requestJson, origin, callbackId)
    }

    @JavascriptInterface
    fun getPasskey(requestJson: String, origin: String, callbackId: String) {
        val effectiveOrigin = getEffectiveOrigin(origin)
        getPasskeyInternal(requestJson, effectiveOrigin, callbackId)
    }

    private fun getPasskeyInternal(requestJson: String, origin: String, callbackId: String) {
        if (!preferences.isPasskeysEnabled) {
            sendError(callbackId, "NotAllowedError", "Passkeys are disabled in browser settings")
            return
        }

        mainHandler.post {
            try {
                val reqObj = JSONObject(requestJson)
                val challenge = reqObj.optString("challenge", "")
                val (clientDataBytes, clientDataHash) = computeClientData(
                    type = "webauthn.get",
                    challenge = challenge,
                    origin = origin
                )

                var usedClientDataHash = true
                val getOption = try {
                    GetPublicKeyCredentialOption(
                        requestJson = requestJson,
                        clientDataHash = clientDataHash
                    )
                } catch (_: Throwable) {
                    usedClientDataHash = false
                    GetPublicKeyCredentialOption(requestJson = requestJson)
                }

                val getRequest = try {
                    GetCredentialRequest.Builder()
                        .addCredentialOption(getOption)
                        .apply {
                            if (origin.isNotBlank()) {
                                setOrigin(origin)
                            }
                        }
                        .build()
                } catch (_: Throwable) {
                    usedClientDataHash = false
                    GetCredentialRequest.Builder()
                        .addCredentialOption(getOption)
                        .build()
                }

                coroutineScope.launch(Dispatchers.Main) {
                    try {
                        val response = try {
                            credentialManager.getCredential(activity, getRequest)
                        } catch (e: SecurityException) {
                            usedClientDataHash = false
                            val fallbackOption = GetPublicKeyCredentialOption(requestJson = requestJson)
                            val fallbackRequest = GetCredentialRequest.Builder()
                                .addCredentialOption(fallbackOption)
                                .build()
                            credentialManager.getCredential(activity, fallbackRequest)
                        }

                        val cred = response.credential
                        if (cred is PublicKeyCredential) {
                            val finalResponse = if (usedClientDataHash) {
                                enrichAuthenticationResponse(
                                    cred.authenticationResponseJson,
                                    clientDataBytes
                                )
                            } else {
                                cred.authenticationResponseJson
                            }
                            sendSuccess(callbackId, finalResponse)
                        } else {
                            sendError(callbackId, "UnknownError", "Unexpected credential type")
                        }
                    } catch (_: GetCredentialCancellationException) {
                        sendError(callbackId, "NotAllowedError", "User cancelled passkey authentication")
                    } catch (e: GetCredentialException) {
                        sendError(callbackId, "InvalidStateError", e.message ?: "Passkey authentication failed")
                    } catch (t: Throwable) {
                        sendError(callbackId, "UnknownError", t.message ?: "Passkey authentication failed")
                    }
                }
            } catch (t: Throwable) {
                sendError(callbackId, "SyntaxError", "Invalid passkey request: ${t.message}")
            }
        }
    }

    private fun getEffectiveOrigin(origin: String): String {
        if (origin.isNotBlank() && origin != "null" && (origin.startsWith("http://") || origin.startsWith("https://"))) {
            return origin.trimEnd('/')
        }
        val webViewUrl = webView.url ?: ""
        if (webViewUrl.isNotBlank()) {
            try {
                val uri = Uri.parse(webViewUrl)
                val scheme = uri.scheme ?: "https"
                val host = uri.host
                val port = uri.port
                if (host != null) {
                    return if (port != -1 && port != 80 && port != 443) {
                        "$scheme://$host:$port"
                    } else {
                        "$scheme://$host"
                    }
                }
            } catch (_: Exception) {}
        }
        return origin.trimEnd('/')
    }

    private fun computeClientData(
        type: String,
        challenge: String,
        origin: String
    ): Pair<ByteArray, ByteArray> {
        val cleanOrigin = if (origin.startsWith("http://") || origin.startsWith("https://")) {
            origin.trimEnd('/')
        } else if (origin.isNotBlank()) {
            "https://$origin".trimEnd('/')
        } else {
            "https://localhost"
        }

        val json = JSONObject().apply {
            put("type", type)
            put("challenge", challenge)
            put("origin", cleanOrigin)
            put("crossOrigin", false)
        }.toString()

        val bytes = json.toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(bytes)
        return Pair(bytes, hash)
    }

    private fun enrichRegistrationResponse(rawJson: String, clientDataBytes: ByteArray): String {
        return try {
            val obj = JSONObject(rawJson)
            val resp = obj.optJSONObject("response") ?: JSONObject().also { obj.put("response", it) }
            val clientDataBase64Url = Base64.encodeToString(
                clientDataBytes,
                Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
            )
            resp.put("clientDataJSON", clientDataBase64Url)
            obj.toString()
        } catch (_: Exception) {
            rawJson
        }
    }

    private fun enrichAuthenticationResponse(rawJson: String, clientDataBytes: ByteArray): String {
        return try {
            val obj = JSONObject(rawJson)
            val resp = obj.optJSONObject("response") ?: JSONObject().also { obj.put("response", it) }
            val clientDataBase64Url = Base64.encodeToString(
                clientDataBytes,
                Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
            )
            resp.put("clientDataJSON", clientDataBase64Url)
            obj.toString()
        } catch (_: Exception) {
            rawJson
        }
    }

    private fun sendSuccess(callbackId: String, responseJson: String) {
        mainHandler.post {
            val safeCallbackId = JSONObject.quote(callbackId)
            val js = "if (window.__onyxPasskeyResolve) { window.__onyxPasskeyResolve($safeCallbackId, $responseJson); }"
            webView.evaluateJavascript(js, null)
        }
    }

    private fun sendError(callbackId: String, errorType: String, errorMessage: String) {
        mainHandler.post {
            val safeCallbackId = JSONObject.quote(callbackId)
            val safeType = JSONObject.quote(errorType)
            val safeMsg = JSONObject.quote(errorMessage)
            val js = "if (window.__onyxPasskeyReject) { window.__onyxPasskeyReject($safeCallbackId, $safeType, $safeMsg); }"
            webView.evaluateJavascript(js, null)
        }
    }

    companion object {
        const val JS_INTERFACE_NAME = "_OnyxPasskeyBridge"

        fun getWebAuthnPolyfillJs(): String {
            return """
                (function() {
                    if (window.__onyxPasskeyInstalled) return;
                    if (!window._OnyxPasskeyBridge || !window._OnyxPasskeyBridge.isPasskeySupported()) return;
                    window.__onyxPasskeyInstalled = true;

                    var pendingCallbacks = {};

                    window.__onyxPasskeyResolve = function(id, responseJson) {
                        var cb = pendingCallbacks[id];
                        if (cb) {
                            delete pendingCallbacks[id];
                            cb.resolve(responseJson);
                        }
                    };

                    window.__onyxPasskeyReject = function(id, type, msg) {
                        var cb = pendingCallbacks[id];
                        if (cb) {
                            delete pendingCallbacks[id];
                            var err = new DOMException(msg || 'WebAuthn failed', type || 'NotAllowedError');
                            cb.reject(err);
                        }
                    };

                    function bufferToBase64Url(buffer) {
                        if (!buffer) return '';
                        if (typeof buffer === 'string') {
                            return buffer.replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
                        }
                        try {
                            var view;
                            if (buffer instanceof Uint8Array) {
                                view = buffer;
                            } else if (buffer && buffer.buffer) {
                                view = new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength);
                            } else {
                                view = new Uint8Array(buffer);
                            }
                            var binary = '';
                            var len = view.byteLength;
                            for (var i = 0; i < len; i++) {
                                binary += String.fromCharCode(view[i]);
                            }
                            return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
                        } catch(e) {
                            return '';
                        }
                    }

                    function base64UrlToBuffer(base64url) {
                        if (!base64url || typeof base64url !== 'string') return new ArrayBuffer(0);
                        var base64 = base64url.replace(/-/g, '+').replace(/_/g, '/');
                        while (base64.length % 4) {
                            base64 += '=';
                        }
                        var binary = atob(base64);
                        var bytes = new Uint8Array(binary.length);
                        for (var i = 0; i < binary.length; i++) {
                            bytes[i] = binary.charCodeAt(i);
                        }
                        return bytes.buffer;
                    }

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
                        return Promise.resolve(true);
                    };
                    window.PublicKeyCredential.isConditionalMediationAvailable = function() {
                        return Promise.resolve(true);
                    };

                    var origCredentials = navigator.credentials;
                    var origCreate = origCredentials ? origCredentials.create.bind(origCredentials) : null;
                    var origGet = origCredentials ? origCredentials.get.bind(origCredentials) : null;

                    var customCredentials = {
                        create: function(options) {
                            if (!options || !options.publicKey) {
                                return origCreate ? origCreate(options) : Promise.reject(new DOMException('Not supported', 'NotSupportedError'));
                            }

                            return new Promise(function(resolve, reject) {
                                var pk = options.publicKey;
                                var jsonReq = {
                                    rp: pk.rp ? Object.assign({}, pk.rp) : {},
                                    user: {
                                        id: bufferToBase64Url(pk.user.id),
                                        name: pk.user.name || '',
                                        displayName: pk.user.displayName || pk.user.name || ''
                                    },
                                    challenge: bufferToBase64Url(pk.challenge),
                                    pubKeyCredParams: pk.pubKeyCredParams || [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
                                    timeout: pk.timeout,
                                    attestation: pk.attestation || 'none',
                                    authenticatorSelection: pk.authenticatorSelection || {}
                                };

                                if (!jsonReq.rp.id) {
                                    jsonReq.rp.id = window.location.hostname;
                                }
                                if (!jsonReq.rp.name) {
                                    jsonReq.rp.name = window.location.hostname;
                                }
                                if (pk.extensions) {
                                    jsonReq.extensions = pk.extensions;
                                }

                                if (pk.excludeCredentials && pk.excludeCredentials.length) {
                                    jsonReq.excludeCredentials = pk.excludeCredentials.map(function(item) {
                                        return {
                                            id: bufferToBase64Url(item.id),
                                            type: item.type || 'public-key',
                                            transports: item.transports || []
                                        };
                                    });
                                }

                                var cbId = 'reg_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
                                pendingCallbacks[cbId] = {
                                    resolve: function(resp) {
                                        var rawId = base64UrlToBuffer(resp.id || resp.rawId);
                                        var clientData = base64UrlToBuffer(resp.response && resp.response.clientDataJSON);
                                        var attestationObj = base64UrlToBuffer(resp.response && resp.response.attestationObject);
                                        var authData = base64UrlToBuffer(resp.response && resp.response.authenticatorData);

                                        var attestationResponse = {
                                            clientDataJSON: clientData,
                                            attestationObject: attestationObj,
                                            getTransports: function() { return (resp.response && resp.response.transports) || ['internal', 'hybrid']; },
                                            getAuthenticatorData: function() { return authData.byteLength > 0 ? authData : new ArrayBuffer(0); },
                                            getPublicKey: function() { return null; },
                                            getPublicKeyAlgorithm: function() { return -7; },
                                            toJSON: function() { return resp.response || {}; }
                                        };
                                        if (window.AuthenticatorAttestationResponse && window.AuthenticatorAttestationResponse.prototype) {
                                            Object.setPrototypeOf(attestationResponse, window.AuthenticatorAttestationResponse.prototype);
                                        }

                                        var result = {
                                            id: resp.id || resp.rawId,
                                            rawId: rawId,
                                            type: resp.type || 'public-key',
                                            authenticatorAttachment: resp.authenticatorAttachment || 'platform',
                                            response: attestationResponse,
                                            getClientExtensionResults: function() { return resp.clientExtensionResults || {}; },
                                            toJSON: function() { return resp; }
                                        };
                                        if (window.PublicKeyCredential && window.PublicKeyCredential.prototype) {
                                            Object.setPrototypeOf(result, window.PublicKeyCredential.prototype);
                                        }

                                        resolve(result);
                                    },
                                    reject: reject
                                };

                                if (options && options.signal) {
                                    if (options.signal.aborted) {
                                        delete pendingCallbacks[cbId];
                                        return reject(new DOMException('The operation was aborted', 'AbortError'));
                                    }
                                    options.signal.addEventListener('abort', function() {
                                        if (pendingCallbacks[cbId]) {
                                            delete pendingCallbacks[cbId];
                                            reject(new DOMException('The operation was aborted', 'AbortError'));
                                        }
                                    });
                                }

                                var origin = window.location.origin;
                                window._OnyxPasskeyBridge.createPasskey(JSON.stringify(jsonReq), origin, cbId);
                            });
                        },

                        get: function(options) {
                            if (!options || !options.publicKey) {
                                return origGet ? origGet(options) : Promise.reject(new DOMException('Not supported', 'NotSupportedError'));
                            }

                            return new Promise(function(resolve, reject) {
                                var pk = options.publicKey;
                                var jsonReq = {
                                    challenge: bufferToBase64Url(pk.challenge),
                                    timeout: pk.timeout,
                                    rpId: pk.rpId || window.location.hostname,
                                    userVerification: pk.userVerification || 'preferred'
                                };

                                if (pk.extensions) {
                                    jsonReq.extensions = pk.extensions;
                                }

                                if (pk.allowCredentials && pk.allowCredentials.length) {
                                    jsonReq.allowCredentials = pk.allowCredentials.map(function(item) {
                                        return {
                                            id: bufferToBase64Url(item.id),
                                            type: item.type || 'public-key',
                                            transports: item.transports || []
                                        };
                                    });
                                }

                                var cbId = 'auth_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
                                pendingCallbacks[cbId] = {
                                    resolve: function(resp) {
                                        var rawId = base64UrlToBuffer(resp.id || resp.rawId);
                                        var clientData = base64UrlToBuffer(resp.response && resp.response.clientDataJSON);
                                        var authData = base64UrlToBuffer(resp.response && resp.response.authenticatorData);
                                        var sig = base64UrlToBuffer(resp.response && resp.response.signature);
                                        var userHandle = resp.response && resp.response.userHandle ? base64UrlToBuffer(resp.response.userHandle) : null;

                                        var assertionResponse = {
                                            clientDataJSON: clientData,
                                            authenticatorData: authData,
                                            signature: sig,
                                            userHandle: userHandle,
                                            toJSON: function() { return resp.response || {}; }
                                        };
                                        if (window.AuthenticatorAssertionResponse && window.AuthenticatorAssertionResponse.prototype) {
                                            Object.setPrototypeOf(assertionResponse, window.AuthenticatorAssertionResponse.prototype);
                                        }

                                        var result = {
                                            id: resp.id || resp.rawId,
                                            rawId: rawId,
                                            type: resp.type || 'public-key',
                                            authenticatorAttachment: resp.authenticatorAttachment || 'platform',
                                            response: assertionResponse,
                                            getClientExtensionResults: function() { return resp.clientExtensionResults || {}; },
                                            toJSON: function() { return resp; }
                                        };
                                        if (window.PublicKeyCredential && window.PublicKeyCredential.prototype) {
                                            Object.setPrototypeOf(result, window.PublicKeyCredential.prototype);
                                        }

                                        resolve(result);
                                    },
                                    reject: reject
                                };

                                if (options && options.signal) {
                                    if (options.signal.aborted) {
                                        delete pendingCallbacks[cbId];
                                        return reject(new DOMException('The operation was aborted', 'AbortError'));
                                    }
                                    options.signal.addEventListener('abort', function() {
                                        if (pendingCallbacks[cbId]) {
                                            delete pendingCallbacks[cbId];
                                            reject(new DOMException('The operation was aborted', 'AbortError'));
                                        }
                                    });
                                }

                                var origin = window.location.origin;
                                window._OnyxPasskeyBridge.getPasskey(JSON.stringify(jsonReq), origin, cbId);
                            });
                        },

                        preventSilentAccess: function() {
                            return origCredentials && origCredentials.preventSilentAccess ? origCredentials.preventSilentAccess() : Promise.resolve();
                        }
                    };

                    try {
                        Object.defineProperty(navigator, 'credentials', {
                            get: function() { return customCredentials; },
                            configurable: true
                        });
                    } catch(e) {
                        navigator.credentials = customCredentials;
                    }
                })();
            """.trimIndent()
        }
    }
}
