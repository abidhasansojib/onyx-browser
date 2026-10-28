package com.onyx.browser.web

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.AbortError
import androidx.credentials.exceptions.domerrors.ConstraintError
import androidx.credentials.exceptions.domerrors.DataError
import androidx.credentials.exceptions.domerrors.DomError
import androidx.credentials.exceptions.domerrors.EncodingError
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NetworkError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.NotReadableError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.domerrors.OperationError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.domerrors.SyntaxError
import androidx.credentials.exceptions.domerrors.TimeoutError
import androidx.credentials.exceptions.domerrors.UnknownError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import com.onyx.browser.MainActivity
import com.onyx.browser.data.preferences.BrowserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.RSAPublicKeySpec

class PasskeyWebAuthnBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val coroutineScope: CoroutineScope
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences by lazy { BrowserPreferences.getInstance(activity.applicationContext) }

    private data class OriginContext(
        val effectiveOrigin: String,
        val crossOrigin: Boolean
    )

    private fun resolveActiveActivity(): Activity {
        if (!activity.isFinishing && !activity.isDestroyed) {
            return activity
        }
        val current = MainActivity.currentInstance?.get()
        if (current != null && !current.isFinishing && !current.isDestroyed) {
            return current
        }
        return activity
    }

    @JavascriptInterface
    fun isPasskeySupported(): Boolean {
        return preferences.isPasskeysEnabled
    }

    @JavascriptInterface
    fun isPasswordCredentialSupported(): Boolean {
        return preferences.isAutofillEnabled
    }

    @JavascriptInterface
    fun createPasskey(requestJson: String, callbackId: String) {
        createPasskey(requestJson, "", true, callbackId)
    }

    @JavascriptInterface
    fun createPasskey(requestJson: String, origin: String, callbackId: String) {
        createPasskey(requestJson, origin, true, callbackId)
    }

    @JavascriptInterface
    fun createPasskey(requestJson: String, origin: String, isTopFrame: Boolean, callbackId: String) {
        if (!preferences.isPasskeysEnabled) {
            sendError(callbackId, "NotAllowedError", "Passkeys are disabled in browser settings")
            return
        }
        // IMPORTANT: @JavascriptInterface runs on Chromium's background JavaBridge thread.
        // Never touch WebView methods (such as webView.url) outside mainHandler.post.
        mainHandler.post {
            createPasskeyOnMainThread(requestJson, origin, isTopFrame, callbackId)
        }
    }

    private fun createPasskeyOnMainThread(
        requestJson: String,
        callerOrigin: String,
        isTopFrame: Boolean,
        callbackId: String
    ) {
        try {
            val reqObj = JSONObject(requestJson)
            val rpObj = reqObj.optJSONObject("rp")
            val rpId = rpObj?.optString("id", "")?.takeIf { it.isNotBlank() }

            val originCtx = resolveVerifiedOrigin(callerOrigin, rpId, isTopFrame, callbackId) ?: return
            val challenge = reqObj.optString("challenge", "")
            if (challenge.isBlank()) {
                sendError(callbackId, "TypeError", "Missing WebAuthn challenge")
                return
            }

            // Ensure rp.id is always populated with a verified host
            if (rpObj != null && rpObj.optString("id").isNullOrBlank()) {
                val host = Uri.parse(originCtx.effectiveOrigin).host ?: ""
                if (host.isNotBlank()) rpObj.put("id", host)
            }
            val normalizedRequestJson = reqObj.toString()

            val (clientDataBytes, clientDataHash) = computeClientData(
                type = "webauthn.create",
                challenge = challenge,
                origin = originCtx.effectiveOrigin,
                crossOrigin = originCtx.crossOrigin
            )

            val createRequest = CreatePublicKeyCredentialRequest(
                requestJson = normalizedRequestJson,
                clientDataHash = clientDataHash,
                preferImmediatelyAvailableCredentials = false,
                origin = originCtx.effectiveOrigin
            )

            val hostActivity = resolveActiveActivity()
            val credManager = CredentialManager.create(hostActivity)
            val mainAct = hostActivity as? MainActivity

            coroutineScope.launch(Dispatchers.Main) {
                mainAct?.onCredentialManagerDialogStart()
                try {
                    val response = credManager.createCredential(hostActivity, createRequest)
                    if (response is CreatePublicKeyCredentialResponse) {
                        val finalResponse = enrichRegistrationResponse(
                            response.registrationResponseJson,
                            clientDataBytes
                        )
                        sendSuccess(callbackId, finalResponse)
                    } else {
                        sendError(callbackId, "UnknownError", "Unexpected credential response")
                    }
                } catch (_: CreateCredentialCancellationException) {
                    sendError(callbackId, "NotAllowedError", "Passkey creation was cancelled")
                } catch (e: CreatePublicKeyCredentialDomException) {
                    sendError(
                        callbackId,
                        mapDomErrorName(e.domError),
                        e.errorMessage?.toString() ?: e.message ?: "Passkey creation failed"
                    )
                } catch (e: SecurityException) {
                    sendError(
                        callbackId,
                        "SecurityError",
                        e.message ?: "Origin verification failed in credential provider"
                    )
                } catch (e: CreateCredentialException) {
                    val (errName, errMsg) = mapCreateCredentialException(e)
                    sendError(callbackId, errName, errMsg)
                } catch (t: Throwable) {
                    sendError(callbackId, "NotAllowedError", t.message ?: "Passkey creation failed")
                } finally {
                    mainAct?.onCredentialManagerDialogEnd()
                }
            }
        } catch (t: Throwable) {
            sendError(callbackId, "SyntaxError", "Invalid passkey creation options: ${t.message}")
        }
    }

    @JavascriptInterface
    fun getPasskey(requestJson: String, callbackId: String) {
        getPasskey(requestJson, "", true, false, callbackId)
    }

    @JavascriptInterface
    fun getPasskey(requestJson: String, origin: String, callbackId: String) {
        getPasskey(requestJson, origin, true, false, callbackId)
    }

    @JavascriptInterface
    fun getPasskey(
        requestJson: String,
        origin: String,
        isTopFrame: Boolean,
        includePasswords: Boolean,
        callbackId: String
    ) {
        if (!preferences.isPasskeysEnabled) {
            sendError(callbackId, "NotAllowedError", "Passkeys are disabled in browser settings")
            return
        }
        mainHandler.post {
            getPasskeyOnMainThread(requestJson, origin, isTopFrame, includePasswords, callbackId)
        }
    }

    private fun getPasskeyOnMainThread(
        requestJson: String,
        callerOrigin: String,
        isTopFrame: Boolean,
        includePasswords: Boolean,
        callbackId: String
    ) {
        try {
            val reqObj = JSONObject(requestJson)
            val rpId = reqObj.optString("rpId", "").takeIf { it.isNotBlank() }

            val originCtx = resolveVerifiedOrigin(callerOrigin, rpId, isTopFrame, callbackId) ?: return
            val challenge = reqObj.optString("challenge", "")
            if (challenge.isBlank()) {
                sendError(callbackId, "TypeError", "Missing WebAuthn challenge")
                return
            }

            if (reqObj.optString("rpId").isNullOrBlank()) {
                val host = Uri.parse(originCtx.effectiveOrigin).host ?: ""
                if (host.isNotBlank()) reqObj.put("rpId", host)
            }
            val normalizedRequestJson = reqObj.toString()

            val (clientDataBytes, clientDataHash) = computeClientData(
                type = "webauthn.get",
                challenge = challenge,
                origin = originCtx.effectiveOrigin,
                crossOrigin = originCtx.crossOrigin
            )

            val publicKeyOption = GetPublicKeyCredentialOption(
                requestJson = normalizedRequestJson,
                clientDataHash = clientDataHash
            )

            val builder = GetCredentialRequest.Builder()
                .addCredentialOption(publicKeyOption)
                .setOrigin(originCtx.effectiveOrigin)

            if (includePasswords && preferences.isAutofillEnabled) {
                builder.addCredentialOption(GetPasswordOption())
            }

            val getRequest = builder.build()
            val hostActivity = resolveActiveActivity()
            val credManager = CredentialManager.create(hostActivity)
            val mainAct = hostActivity as? MainActivity

            coroutineScope.launch(Dispatchers.Main) {
                mainAct?.onCredentialManagerDialogStart()
                try {
                    val response = credManager.getCredential(hostActivity, getRequest)
                    when (val cred = response.credential) {
                        is PublicKeyCredential -> {
                            val finalResponse = enrichAuthenticationResponse(
                                cred.authenticationResponseJson,
                                clientDataBytes
                            )
                            sendSuccess(callbackId, finalResponse)
                        }
                        is PasswordCredential -> {
                            val pwdObj = JSONObject().apply {
                                put("__onyxCredentialType", "password")
                                put("id", cred.id)
                                put("password", cred.password)
                            }
                            sendSuccess(callbackId, pwdObj.toString())
                        }
                        else -> {
                            sendError(callbackId, "UnknownError", "Unsupported credential type")
                        }
                    }
                } catch (_: GetCredentialCancellationException) {
                    sendError(callbackId, "NotAllowedError", "Passkey sign-in was cancelled")
                } catch (_: NoCredentialException) {
                    sendError(callbackId, "NotAllowedError", "No matching passkey found in your password manager")
                } catch (e: GetPublicKeyCredentialDomException) {
                    sendError(
                        callbackId,
                        mapDomErrorName(e.domError),
                        e.errorMessage?.toString() ?: e.message ?: "Passkey sign-in failed"
                    )
                } catch (e: SecurityException) {
                    sendError(
                        callbackId,
                        "SecurityError",
                        e.message ?: "Origin verification failed in credential provider"
                    )
                } catch (e: GetCredentialException) {
                    val (errName, errMsg) = mapGetCredentialException(e)
                    sendError(callbackId, errName, errMsg)
                } catch (t: Throwable) {
                    sendError(callbackId, "NotAllowedError", t.message ?: "Passkey sign-in failed")
                } finally {
                    mainAct?.onCredentialManagerDialogEnd()
                }
            }
        } catch (t: Throwable) {
            sendError(callbackId, "SyntaxError", "Invalid passkey request options: ${t.message}")
        }
    }

    @JavascriptInterface
    fun getPassword(
        origin: String,
        isTopFrame: Boolean,
        preferImmediatelyAvailable: Boolean,
        callbackId: String
    ) {
        if (!preferences.isAutofillEnabled) {
            sendSuccess(callbackId, "null")
            return
        }
        mainHandler.post {
            val originCtx = resolveVerifiedOrigin(origin, null, isTopFrame, callbackId) ?: return@post
            val hostActivity = resolveActiveActivity()
            val credManager = CredentialManager.create(hostActivity)
            val mainAct = hostActivity as? MainActivity

            val getRequest = try {
                GetCredentialRequest.Builder()
                    .addCredentialOption(GetPasswordOption())
                    .setOrigin(originCtx.effectiveOrigin)
                    .setPreferImmediatelyAvailableCredentials(preferImmediatelyAvailable)
                    .build()
            } catch (_: Throwable) {
                sendSuccess(callbackId, "null")
                return@post
            }

            coroutineScope.launch(Dispatchers.Main) {
                mainAct?.onCredentialManagerDialogStart()
                try {
                    val response = credManager.getCredential(hostActivity, getRequest)
                    val cred = response.credential
                    if (cred is PasswordCredential) {
                        val pwdObj = JSONObject().apply {
                            put("__onyxCredentialType", "password")
                            put("id", cred.id)
                            put("password", cred.password)
                        }
                        sendSuccess(callbackId, pwdObj.toString())
                    } else {
                        sendSuccess(callbackId, "null")
                    }
                } catch (_: Throwable) {
                    // Per W3C Credential Management spec, when no password credential is chosen or available, resolve null
                    sendSuccess(callbackId, "null")
                } finally {
                    mainAct?.onCredentialManagerDialogEnd()
                }
            }
        }
    }

    @JavascriptInterface
    fun storePassword(
        username: String,
        password: String,
        origin: String,
        isTopFrame: Boolean,
        callbackId: String
    ) {
        if (!preferences.isAutofillEnabled || !preferences.isSavePasswordsPromptEnabled ||
            username.isBlank() || password.isEmpty()
        ) {
            sendSuccess(callbackId, "{}")
            return
        }
        mainHandler.post {
            if ((webView as? OnyxWebView)?.isIncognito == true) {
                sendSuccess(callbackId, "{}")
                return@post
            }
            val originCtx = resolveVerifiedOrigin(origin, null, isTopFrame, null)
            if (originCtx == null) {
                sendSuccess(callbackId, "{}")
                return@post
            }

            val hostActivity = resolveActiveActivity()
            val credManager = CredentialManager.create(hostActivity)
            val mainAct = hostActivity as? MainActivity

            val saveRequest = try {
                CreatePasswordRequest(
                    id = username,
                    password = password,
                    origin = originCtx.effectiveOrigin,
                    preferImmediatelyAvailableCredentials = false,
                    isAutoSelectAllowed = false
                )
            } catch (_: Throwable) {
                sendSuccess(callbackId, "{}")
                return@post
            }

            coroutineScope.launch(Dispatchers.Main) {
                mainAct?.onCredentialManagerDialogStart()
                try {
                    credManager.createCredential(hostActivity, saveRequest)
                } catch (_: Throwable) {
                    // Saving a password via navigator.credentials.store should never break login flow on cancellation
                } finally {
                    mainAct?.onCredentialManagerDialogEnd()
                    sendSuccess(callbackId, "{}")
                }
            }
        }
    }

    /**
     * Must ONLY be called on the Main UI thread because it reads [webView.url].
     */
    private fun resolveVerifiedOrigin(
        callerOrigin: String,
        rpId: String?,
        isTopFrame: Boolean,
        callbackIdForError: String?
    ): OriginContext? {
        val rawTopUrl = webView.url?.takeIf { it.isNotBlank() }
            ?: (webView as? OnyxWebView)?.pendingMainFrameUrl
            ?: ""
        val topOrigin = normalizeSecureWebOrigin(rawTopUrl)
        val normalizedCaller = normalizeSecureWebOrigin(callerOrigin)

        val effectiveOrigin = if (isTopFrame) {
            topOrigin ?: normalizedCaller
        } else {
            normalizedCaller ?: topOrigin
        }

        if (effectiveOrigin.isNullOrBlank()) {
            if (callbackIdForError != null) {
                sendError(
                    callbackIdForError,
                    "SecurityError",
                    "Passkeys require a secure HTTPS (or localhost) web origin"
                )
            }
            return null
        }

        val originHost = Uri.parse(effectiveOrigin).host?.lowercase()?.trimEnd('.') ?: ""
        if (originHost.isBlank()) {
            if (callbackIdForError != null) {
                sendError(callbackIdForError, "SecurityError", "Invalid origin host")
            }
            return null
        }

        if (!rpId.isNullOrBlank()) {
            val cleanRpId = rpId.lowercase().trim().trimEnd('.')
            val isExactMatch = originHost == cleanRpId
            val isValidSubdomainMatch = cleanRpId.contains('.') && originHost.endsWith(".$cleanRpId")
            if (!isExactMatch && !isValidSubdomainMatch) {
                if (callbackIdForError != null) {
                    sendError(
                        callbackIdForError,
                        "SecurityError",
                        "Relying Party ID '$cleanRpId' does not match origin '$originHost'"
                    )
                }
                return null
            }
        }

        val isCrossOrigin = !isTopFrame &&
            topOrigin != null &&
            !effectiveOrigin.equals(topOrigin, ignoreCase = true)

        return OriginContext(
            effectiveOrigin = effectiveOrigin,
            crossOrigin = isCrossOrigin
        )
    }

    private fun normalizeSecureWebOrigin(rawUrlOrOrigin: String?): String? {
        if (rawUrlOrOrigin.isNullOrBlank() || rawUrlOrOrigin == "null") return null
        return try {
            val uri = Uri.parse(rawUrlOrOrigin.trim())
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (host.isBlank()) return null

            val isLocalhost = host == "localhost" ||
                host.endsWith(".localhost") ||
                host == "127.0.0.1" ||
                host == "::1" ||
                host == "[::1]"

            if (scheme != "https" && !(scheme == "http" && isLocalhost)) {
                return null
            }

            val port = uri.port
            val isDefaultPort = port == -1 ||
                (scheme == "https" && port == 443) ||
                (scheme == "http" && port == 80)

            if (isDefaultPort) {
                "$scheme://$host"
            } else {
                "$scheme://$host:$port"
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun computeClientData(
        type: String,
        challenge: String,
        origin: String,
        crossOrigin: Boolean
    ): Pair<ByteArray, ByteArray> {
        val cleanOrigin = origin.trimEnd('/')
        val json = JSONObject().apply {
            put("type", type)
            put("challenge", challenge)
            put("origin", cleanOrigin)
            put("crossOrigin", crossOrigin)
        }.toString()

        val bytes = json.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Pair(bytes, hash)
    }

    private fun hasValidClientDataJson(encoded: String?): Boolean {
        if (encoded.isNullOrBlank()) return false
        if (encoded.contains("placeholder", ignoreCase = true)) return false
        return try {
            val decoded = fromBase64Url(encoded)
            if (decoded.isEmpty()) return false
            val text = String(decoded, Charsets.UTF_8).trim()
            if (!text.startsWith("{") || text.contains("placeholder", ignoreCase = true)) return false
            val json = JSONObject(text)
            val type = json.optString("type", "")
            val challenge = json.optString("challenge", "")
            val origin = json.optString("origin", "")
            type.startsWith("webauthn.") && challenge.isNotBlank() && origin.isNotBlank()
        } catch (_: Throwable) {
            false
        }
    }

    private fun enrichRegistrationResponse(rawJson: String, clientDataBytes: ByteArray): String {
        return try {
            val obj = JSONObject(rawJson)
            val resp = obj.optJSONObject("response") ?: JSONObject().also { obj.put("response", it) }

            // Preserve provider's clientDataJSON if it already constructed and signed a valid JSON payload;
            // otherwise inject the browser's CollectedClientData matching clientDataHash.
            if (!hasValidClientDataJson(resp.optString("clientDataJSON", null))) {
                resp.put("clientDataJSON", toBase64Url(clientDataBytes))
            }

            // Ensure id and rawId are both populated
            val credId = obj.optString("id").ifBlank { obj.optString("rawId") }
            if (credId.isNotBlank()) {
                if (obj.optString("id").isBlank()) obj.put("id", credId)
                if (obj.optString("rawId").isBlank()) obj.put("rawId", credId)
            }
            if (obj.optString("type").isBlank()) {
                obj.put("type", "public-key")
            }

            // Populate authenticatorData, publicKey (DER SPKI), and publicKeyAlgorithm from CBOR attestationObject
            // when omitted by third-party providers (e.g., Bitwarden, KeePassDX, Proton Pass).
            val attestationB64 = resp.optString("attestationObject", "")
            if (attestationB64.isNotBlank()) {
                populateAttestationMetadata(resp, attestationB64)
            }

            obj.toString()
        } catch (_: Throwable) {
            rawJson
        }
    }

    private fun enrichAuthenticationResponse(rawJson: String, clientDataBytes: ByteArray): String {
        return try {
            val obj = JSONObject(rawJson)
            val resp = obj.optJSONObject("response") ?: JSONObject().also { obj.put("response", it) }

            if (!hasValidClientDataJson(resp.optString("clientDataJSON", null))) {
                resp.put("clientDataJSON", toBase64Url(clientDataBytes))
            }

            val credId = obj.optString("id").ifBlank { obj.optString("rawId") }
            if (credId.isNotBlank()) {
                if (obj.optString("id").isBlank()) obj.put("id", credId)
                if (obj.optString("rawId").isBlank()) obj.put("rawId", credId)
            }
            if (obj.optString("type").isBlank()) {
                obj.put("type", "public-key")
            }

            obj.toString()
        } catch (_: Throwable) {
            rawJson
        }
    }

    private fun populateAttestationMetadata(resp: JSONObject, attestationB64: String) {
        try {
            val attestationBytes = fromBase64Url(attestationB64)
            if (attestationBytes.isEmpty()) return

            val topMap = CborReader(attestationBytes).readValue() as? Map<*, *> ?: return
            val authData = topMap["authData"] as? ByteArray ?: return
            if (authData.isEmpty()) return

            if (resp.optString("authenticatorData").isBlank()) {
                resp.put("authenticatorData", toBase64Url(authData))
            }

            // Parse attestedCredentialData if AT flag (bit 6 = 0x40) is set
            if (authData.size >= 55 && (authData[32].toInt() and 0x40) != 0) {
                val credIdLen = ((authData[53].toInt() and 0xFF) shl 8) or (authData[54].toInt() and 0xFF)
                val coseKeyOffset = 55 + credIdLen
                if (coseKeyOffset < authData.size) {
                    val coseSlice = authData.copyOfRange(coseKeyOffset, authData.size)
                    val coseMap = CborReader(coseSlice).readValue() as? Map<*, *>
                    if (coseMap != null) {
                        val alg = (coseMap[3L] as? Long)?.toInt()
                        if (alg != null && !resp.has("publicKeyAlgorithm")) {
                            resp.put("publicKeyAlgorithm", alg)
                        }
                        if (resp.optString("publicKey").isBlank()) {
                            val spkiBytes = coseKeyToDerSpki(coseMap)
                            if (spkiBytes != null && spkiBytes.isNotEmpty()) {
                                resp.put("publicKey", toBase64Url(spkiBytes))
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Non-fatal: attestationObject itself remains intact
        }
    }

    private fun coseKeyToDerSpki(coseMap: Map<*, *>): ByteArray? {
        val kty = coseMap[1L] as? Long ?: return null
        return when (kty) {
            2L -> {
                // EC2 (P-256 / P-384)
                val crv = coseMap[-1L] as? Long ?: return null
                val x = coseMap[-2L] as? ByteArray ?: return null
                val y = coseMap[-3L] as? ByteArray ?: return null
                when {
                    crv == 1L && x.size == 32 && y.size == 32 -> {
                        val out = ByteArray(P256_SPKI_PREFIX.size + 64)
                        System.arraycopy(P256_SPKI_PREFIX, 0, out, 0, P256_SPKI_PREFIX.size)
                        System.arraycopy(x, 0, out, P256_SPKI_PREFIX.size, 32)
                        System.arraycopy(y, 0, out, P256_SPKI_PREFIX.size + 32, 32)
                        out
                    }
                    crv == 2L && x.size == 48 && y.size == 48 -> {
                        val out = ByteArray(P384_SPKI_PREFIX.size + 96)
                        System.arraycopy(P384_SPKI_PREFIX, 0, out, 0, P384_SPKI_PREFIX.size)
                        System.arraycopy(x, 0, out, P384_SPKI_PREFIX.size, 48)
                        System.arraycopy(y, 0, out, P384_SPKI_PREFIX.size + 48, 48)
                        out
                    }
                    else -> null
                }
            }
            1L -> {
                // OKP (Ed25519: crv == 6)
                val crv = coseMap[-1L] as? Long ?: return null
                val x = coseMap[-2L] as? ByteArray ?: return null
                if (crv == 6L && x.size == 32) {
                    val out = ByteArray(ED25519_SPKI_PREFIX.size + 32)
                    System.arraycopy(ED25519_SPKI_PREFIX, 0, out, 0, ED25519_SPKI_PREFIX.size)
                    System.arraycopy(x, 0, out, ED25519_SPKI_PREFIX.size, 32)
                    out
                } else null
            }
            3L -> {
                // RSA (RS256)
                val n = coseMap[-1L] as? ByteArray ?: return null
                val e = coseMap[-2L] as? ByteArray ?: return null
                if (n.isEmpty() || e.isEmpty()) return null
                try {
                    val spec = RSAPublicKeySpec(BigInteger(1, n), BigInteger(1, e))
                    KeyFactory.getInstance("RSA").generatePublic(spec).encoded
                } catch (_: Throwable) {
                    null
                }
            }
            else -> null
        }
    }

    /**
     * Minimal RFC 8949 CBOR reader solely for WebAuthn attestationObject & COSE_Key structures.
     */
    private class CborReader(private val data: ByteArray) {
        private var pos = 0

        fun readValue(): Any? {
            if (pos >= data.size) return null
            val initial = data[pos++].toInt() and 0xFF
            val major = initial ushr 5
            val info = initial and 0x1F

            return when (major) {
                0 -> readLength(info) // Unsigned integer
                1 -> -1L - readLength(info) // Negative integer
                2 -> { // Byte string
                    val len = readLength(info).toInt()
                    if (len < 0 || pos + len > data.size) return null
                    val bytes = data.copyOfRange(pos, pos + len)
                    pos += len
                    bytes
                }
                3 -> { // Text string
                    val len = readLength(info).toInt()
                    if (len < 0 || pos + len > data.size) return null
                    val str = String(data, pos, len, Charsets.UTF_8)
                    pos += len
                    str
                }
                4 -> { // Array
                    if (info == 31) {
                        val list = ArrayList<Any?>()
                        while (pos < data.size && (data[pos].toInt() and 0xFF) != 0xFF) {
                            list.add(readValue())
                        }
                        if (pos < data.size) pos++
                        list
                    } else {
                        val count = readLength(info).toInt()
                        if (count < 0 || count > 4096) return null
                        val list = ArrayList<Any?>(count)
                        for (i in 0 until count) {
                            if (pos >= data.size) break
                            list.add(readValue())
                        }
                        list
                    }
                }
                5 -> { // Map
                    if (info == 31) {
                        val map = LinkedHashMap<Any?, Any?>()
                        while (pos < data.size && (data[pos].toInt() and 0xFF) != 0xFF) {
                            val k = readValue()
                            val v = readValue()
                            map[k] = v
                        }
                        if (pos < data.size) pos++
                        map
                    } else {
                        val count = readLength(info).toInt()
                        if (count < 0 || count > 4096) return null
                        val map = LinkedHashMap<Any?, Any?>(count)
                        for (i in 0 until count) {
                            if (pos >= data.size) break
                            val k = readValue()
                            val v = readValue()
                            map[k] = v
                        }
                        map
                    }
                }
                6 -> { // Semantic tag — skip tag number and return inner value
                    readLength(info)
                    readValue()
                }
                7 -> {
                    when (info) {
                        20 -> false
                        21 -> true
                        22, 23 -> null
                        25 -> { pos = (pos + 2).coerceAtMost(data.size); null }
                        26 -> { pos = (pos + 4).coerceAtMost(data.size); null }
                        27 -> { pos = (pos + 8).coerceAtMost(data.size); null }
                        else -> null
                    }
                }
                else -> null
            }
        }

        private fun readLength(info: Int): Long {
            return when {
                info < 24 -> info.toLong()
                info == 24 -> {
                    if (pos >= data.size) return 0L
                    (data[pos++].toInt() and 0xFF).toLong()
                }
                info == 25 -> {
                    if (pos + 2 > data.size) return 0L
                    val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    pos += 2
                    v.toLong()
                }
                info == 26 -> {
                    if (pos + 4 > data.size) return 0L
                    val v = ((data[pos].toLong() and 0xFF) shl 24) or
                        ((data[pos + 1].toLong() and 0xFF) shl 16) or
                        ((data[pos + 2].toLong() and 0xFF) shl 8) or
                        (data[pos + 3].toLong() and 0xFF)
                    pos += 4
                    v
                }
                info == 27 -> {
                    if (pos + 8 > data.size) return 0L
                    var v = 0L
                    for (i in 0 until 8) {
                        v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
                    }
                    pos += 8
                    v
                }
                else -> 0L
            }
        }
    }

    private fun mapDomErrorName(domError: DomError): String {
        return when (domError) {
            is AbortError -> "AbortError"
            is ConstraintError -> "ConstraintError"
            is DataError -> "DataError"
            is EncodingError -> "EncodingError"
            is InvalidStateError -> "InvalidStateError"
            is NetworkError -> "NetworkError"
            is NotAllowedError -> "NotAllowedError"
            is NotReadableError -> "NotReadableError"
            is NotSupportedError -> "NotSupportedError"
            is OperationError -> "OperationError"
            is SecurityError -> "SecurityError"
            is SyntaxError -> "SyntaxError"
            is TimeoutError -> "TimeoutError"
            is UnknownError -> "UnknownError"
            else -> "NotAllowedError"
        }
    }

    private fun mapCreateCredentialException(e: CreateCredentialException): Pair<String, String> {
        val rawMsg = e.errorMessage?.toString() ?: e.message ?: "Passkey creation failed"
        return when (e) {
            is CreateCredentialNoCreateOptionException,
            is CreateCredentialProviderConfigurationException -> {
                "NotAllowedError" to "No passkey provider enabled. Enable your password manager (e.g. Bitwarden) in Android Passwords, passkeys & autofill settings."
            }
            is CreateCredentialUnsupportedException -> {
                "NotSupportedError" to "Passkeys require Android 14+ or an active Credential Manager provider."
            }
            else -> {
                if (rawMsg.contains("privileged", ignoreCase = true) ||
                    rawMsg.contains("allowlist", ignoreCase = true)
                ) {
                    "NotAllowedError" to "Browser not yet trusted by credential provider. Use Bitwarden or Proton Pass and tap Trust when prompted."
                } else {
                    "NotAllowedError" to rawMsg
                }
            }
        }
    }

    private fun mapGetCredentialException(e: GetCredentialException): Pair<String, String> {
        val rawMsg = e.errorMessage?.toString() ?: e.message ?: "Passkey sign-in failed"
        return when (e) {
            is NoCredentialException -> {
                "NotAllowedError" to "No matching passkey found in your password manager."
            }
            is GetCredentialProviderConfigurationException -> {
                "NotAllowedError" to "No passkey provider enabled. Enable your password manager (e.g. Bitwarden) in Android Passwords, passkeys & autofill settings."
            }
            is GetCredentialUnsupportedException -> {
                "NotSupportedError" to "Passkeys require Android 14+ or an active Credential Manager provider."
            }
            else -> {
                "NotAllowedError" to rawMsg
            }
        }
    }

    private fun toBase64Url(bytes: ByteArray): String {
        return Base64.encodeToString(
            bytes,
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
        )
    }

    private fun fromBase64Url(encoded: String): ByteArray {
        val normalized = encoded.trim()
            .replace('-', '+')
            .replace('_', '/')
        val padLen = (4 - (normalized.length % 4)) % 4
        val padded = normalized + "=".repeat(padLen)
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun sendSuccess(callbackId: String, responseJson: String) {
        mainHandler.post {
            try {
                webView.onResume()
                webView.resumeTimers()
            } catch (_: Throwable) {}
            val safeCallbackId = JSONObject.quote(callbackId)
            val safePayload = JSONObject.quote(responseJson)
            val js = "if (window.__onyxPasskeyResolve) { window.__onyxPasskeyResolve($safeCallbackId, $safePayload); }"
            webView.evaluateJavascript(js, null)
        }
    }

    private fun sendError(callbackId: String, errorType: String, errorMessage: String) {
        mainHandler.post {
            try {
                webView.onResume()
                webView.resumeTimers()
            } catch (_: Throwable) {}
            val safeCallbackId = JSONObject.quote(callbackId)
            val safeType = JSONObject.quote(errorType)
            val safeMsg = JSONObject.quote(errorMessage)
            val js = "if (window.__onyxPasskeyReject) { window.__onyxPasskeyReject($safeCallbackId, $safeType, $safeMsg); }"
            webView.evaluateJavascript(js, null)
        }
    }

    companion object {
        const val JS_INTERFACE_NAME = "_OnyxPasskeyBridge"

        // ASN.1 DER SubjectPublicKeyInfo prefixes
        private val P256_SPKI_PREFIX = byteArrayOf(
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(),
            0x3D, 0x02, 0x01, 0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D,
            0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04
        )
        private val P384_SPKI_PREFIX = byteArrayOf(
            0x30, 0x76, 0x30, 0x10, 0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(),
            0x3D, 0x02, 0x01, 0x06, 0x05, 0x2B, 0x81.toByte(), 0x04, 0x00, 0x22,
            0x03, 0x62, 0x00, 0x04
        )
        private val ED25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00
        )

        fun getWebAuthnPolyfillJs(): String {
            return """
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
                                        var userHandleB64 = respInner.userHandle || null;

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
            """.trimIndent()
        }
    }
}
