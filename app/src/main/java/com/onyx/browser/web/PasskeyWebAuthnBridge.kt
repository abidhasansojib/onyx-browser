package com.onyx.browser.web

import android.app.Activity
import android.os.Handler
import android.os.Looper
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
        if (!preferences.isPasskeysEnabled) {
            sendError(callbackId, "NotAllowedError", "Passkeys are disabled in browser settings")
            return
        }

        mainHandler.post {
            try {
                val createRequest = CreatePublicKeyCredentialRequest(
                    requestJson = requestJson,
                    preferImmediatelyAvailableCredentials = false
                )

                coroutineScope.launch(Dispatchers.Main) {
                    try {
                        val response = credentialManager.createCredential(activity, createRequest)
                        if (response is CreatePublicKeyCredentialResponse) {
                            sendSuccess(callbackId, response.registrationResponseJson)
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
        if (!preferences.isPasskeysEnabled) {
            sendError(callbackId, "NotAllowedError", "Passkeys are disabled in browser settings")
            return
        }

        mainHandler.post {
            try {
                val getOption = GetPublicKeyCredentialOption(requestJson = requestJson)
                val getRequest = GetCredentialRequest.Builder()
                    .addCredentialOption(getOption)
                    .build()

                coroutineScope.launch(Dispatchers.Main) {
                    try {
                        val response = credentialManager.getCredential(activity, getRequest)
                        val cred = response.credential
                        if (cred is PublicKeyCredential) {
                            sendSuccess(callbackId, cred.authenticationResponseJson)
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
                        var bytes = new Uint8Array(buffer);
                        var binary = '';
                        for (var i = 0; i < bytes.byteLength; i++) {
                            binary += String.fromCharCode(bytes[i]);
                        }
                        return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
                    }

                    function base64UrlToBuffer(base64url) {
                        if (!base64url) return new ArrayBuffer(0);
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

                    window.PublicKeyCredential = window.PublicKeyCredential || function() {};
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
                                    rp: pk.rp || {},
                                    user: {
                                        id: bufferToBase64Url(pk.user.id),
                                        name: pk.user.name,
                                        displayName: pk.user.displayName || pk.user.name
                                    },
                                    challenge: bufferToBase64Url(pk.challenge),
                                    pubKeyCredParams: pk.pubKeyCredParams || [],
                                    timeout: pk.timeout,
                                    attestation: pk.attestation || 'none',
                                    authenticatorSelection: pk.authenticatorSelection || {}
                                };

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

                                        var result = {
                                            id: resp.id,
                                            rawId: rawId,
                                            type: resp.type || 'public-key',
                                            authenticatorAttachment: resp.authenticatorAttachment || 'platform',
                                            response: {
                                                clientDataJSON: clientData,
                                                attestationObject: attestationObj,
                                                getTransports: function() { return (resp.response && resp.response.transports) || ['internal']; },
                                                getAuthenticatorData: function() { return new ArrayBuffer(0); },
                                                getPublicKey: function() { return null; }
                                            },
                                            getClientExtensionResults: function() { return {}; }
                                        };
                                        resolve(result);
                                    },
                                    reject: reject
                                };

                                window._OnyxPasskeyBridge.createPasskey(JSON.stringify(jsonReq), cbId);
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

                                        var result = {
                                            id: resp.id,
                                            rawId: rawId,
                                            type: resp.type || 'public-key',
                                            authenticatorAttachment: resp.authenticatorAttachment || 'platform',
                                            response: {
                                                clientDataJSON: clientData,
                                                authenticatorData: authData,
                                                signature: sig,
                                                userHandle: userHandle
                                            },
                                            getClientExtensionResults: function() { return {}; }
                                        };
                                        resolve(result);
                                    },
                                    reject: reject
                                };

                                window._OnyxPasskeyBridge.getPasskey(JSON.stringify(jsonReq), cbId);
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
