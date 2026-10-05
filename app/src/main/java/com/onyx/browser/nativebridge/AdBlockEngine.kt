package com.onyx.browser.nativebridge

import android.content.Context
import android.util.Log
import com.onyx.browser.data.preferences.BrowserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object AdBlockEngine {
    private const val TAG = "AdBlockEngine"
    private const val BINARY_CACHE_FILE = "onyx_filters.bin"
    private const val DEFAULT_ASSET_RULES = "easylist_rules.txt"
    private const val BRAVE_RESOURCES_ASSET = "brave-resources.json"

    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("adblock_bridge")
            isNativeLoaded = true
            Log.i(TAG, "Successfully loaded native library libadblock_bridge.so")
        } catch (t: Throwable) {
            Log.e(TAG, "Native library libadblock_bridge.so not found or could not be loaded", t)
            isNativeLoaded = false
        }
    }

    const val RESOURCE_TYPE_OTHER = 0
    const val RESOURCE_TYPE_SCRIPT = 1
    const val RESOURCE_TYPE_IMAGE = 2
    const val RESOURCE_TYPE_STYLESHEET = 3
    const val RESOURCE_TYPE_SUB_FRAME = 4
    const val RESOURCE_TYPE_XHR = 5
    const val RESOURCE_TYPE_MEDIA = 6
    const val RESOURCE_TYPE_MAIN_FRAME = 7

    data class RequestResult(
        val shouldBlock: Boolean,
        val redirectData: String? = null,
        val rewrittenUrl: String? = null
    )

    external fun initEngine(data: ByteArray): Boolean
    external fun initFromRules(rules: String): ByteArray?
    external fun loadResources(resourcesJson: String): Boolean
    external fun checkUrl(url: String, sourceUrl: String, resourceType: String): Boolean
    external fun checkRequestNative(url: String, sourceUrl: String, resourceType: Int, method: String): String?
    external fun getCspDirectivesNative(url: String, sourceUrl: String, resourceType: Int, method: String): String?
    external fun getCosmeticResources(url: String): String?
    external fun getHiddenClassIdSelectors(classesJson: String, idsJson: String, exceptionsJson: String): String?
    external fun serializeEngine(): ByteArray?
    external fun isEngineInitialized(): Boolean

    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        if (!isNativeLoaded) {
            Log.w(TAG, "Skipping initialization: native library not loaded")
            return@withContext
        }

        try {
            val cacheFile = File(context.filesDir, BINARY_CACHE_FILE)
            if (cacheFile.exists() && cacheFile.length() > 0) {
                val bytes = cacheFile.readBytes()
                val success = initEngine(bytes)
                if (success) {
                    Log.i(TAG, "AdBlockEngine initialized from serialized binary cache (${bytes.size} bytes)")
                    // Always reload scriptlet resources on top of cached engine
                    loadBraveResources(context)
                    return@withContext
                }
            }

            // Fallback to bundled asset rules
            Log.i(TAG, "Initializing AdBlockEngine from bundled rules asset...")
            val assetStream: InputStream = context.assets.open(DEFAULT_ASSET_RULES)
            val rulesText = assetStream.bufferedReader().use { it.readText() }
            val compiledBytes = initFromRules(rulesText)
            if (compiledBytes != null && compiledBytes.isNotEmpty()) {
                FileOutputStream(cacheFile).use { it.write(compiledBytes) }
                Log.i(TAG, "Compiled and cached filter database (${compiledBytes.size} bytes)")
            }

            // Load scriptlet resources (brave-resources.json) so +js() rules resolve to real JS
            loadBraveResources(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AdBlockEngine", e)
        }
    }

    /**
     * Loads brave-resources.json into the engine so that +js() scriptlet rules are resolved
     * into actual JS code returned by getCosmeticResources().
     * Without this, injected_script will always be empty and scriptlet-based ad blocking won't work.
     */
    fun loadBraveResources(context: Context) {
        try {
            val json = context.assets.open(BRAVE_RESOURCES_ASSET).bufferedReader().use { it.readText() }
            val success = loadResources(json)
            if (success) {
                Log.i(TAG, "Scriptlet resources (brave-resources.json) loaded successfully")
            } else {
                Log.w(TAG, "Failed to load scriptlet resources from brave-resources.json")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading brave-resources.json", e)
        }
    }

    suspend fun updateRulesFromNetwork(context: Context, rulesUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (!isNativeLoaded) return@withContext false

        return@withContext try {
            val url = URL(rulesUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val rulesText = connection.inputStream.bufferedReader().use { it.readText() }
                val compiledBytes = initFromRules(rulesText)
                if (compiledBytes != null && compiledBytes.isNotEmpty()) {
                    val cacheFile = File(context.filesDir, BINARY_CACHE_FILE)
                    FileOutputStream(cacheFile).use { it.write(compiledBytes) }
                    BrowserPreferences.getInstance(context).filterLastUpdatedTime = System.currentTimeMillis()
                    Log.i(TAG, "Successfully updated and serialized filter lists (${compiledBytes.size} bytes)")
                    // Reload scriptlet resources after filter update
                    loadBraveResources(context)
                    true
                } else {
                    false
                }
            } else {
                Log.w(TAG, "Failed to download filter rules: HTTP ${connection.responseCode}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception downloading filter rules", e)
            false
        }
    }

    fun checkRequest(url: String, sourceUrl: String, resourceType: Int, method: String = "GET"): RequestResult {
        if (!isNativeLoaded || url.isBlank()) return RequestResult(shouldBlock = false)
        return try {
            val effectiveMethod = if (method.isBlank()) "GET" else method.uppercase()
            val result = checkRequestNative(url, sourceUrl, resourceType, effectiveMethod)
            when {
                result == null -> RequestResult(shouldBlock = false)
                result.startsWith("redirect:") -> RequestResult(
                    shouldBlock = true,
                    redirectData = result.removePrefix("redirect:")
                )
                result.startsWith("rewrite:") -> RequestResult(
                    shouldBlock = false,
                    rewrittenUrl = result.removePrefix("rewrite:")
                )
                result == "blocked" -> RequestResult(shouldBlock = true)
                else -> RequestResult(shouldBlock = true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in checkRequestNative", t)
            RequestResult(shouldBlock = false)
        }
    }

    /**
     * Checks if any Content Security Policy (CSP) directives apply to this document/subframe request.
     * Returns null if no directives apply.
     */
    fun getCspDirectives(url: String, sourceUrl: String, resourceType: Int, method: String = "GET"): String? {
        if (!isNativeLoaded || url.isBlank()) return null
        return try {
            val effectiveMethod = if (method.isBlank()) "GET" else method.uppercase()
            getCspDirectivesNative(url, sourceUrl, resourceType, effectiveMethod)?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in getCspDirectivesNative", t)
            null
        }
    }

    fun shouldBlock(url: String, sourceUrl: String, resourceType: Int, method: String = "GET"): Boolean {
        return checkRequest(url, sourceUrl, resourceType, method).shouldBlock
    }

    fun shouldBlock(url: String, sourceUrl: String, resourceType: String, method: String = "GET"): Boolean {
        if (!isNativeLoaded) return false
        val typeInt = when (resourceType.lowercase()) {
            "script" -> RESOURCE_TYPE_SCRIPT
            "image" -> RESOURCE_TYPE_IMAGE
            "stylesheet" -> RESOURCE_TYPE_STYLESHEET
            "sub_frame" -> RESOURCE_TYPE_SUB_FRAME
            "xhr" -> RESOURCE_TYPE_XHR
            "media" -> RESOURCE_TYPE_MEDIA
            "main_frame" -> RESOURCE_TYPE_MAIN_FRAME
            else -> RESOURCE_TYPE_OTHER
        }
        return shouldBlock(url, sourceUrl, typeInt, method)
    }

    /**
     * Returns procedural filter rules (e.g. :has(), :has-text(), actions) for the given URL.
     */
    fun getProceduralRules(url: String): List<String> {
        if (!isNativeLoaded || url.isBlank()) return emptyList()
        return try {
            val json = getCosmeticResources(url) ?: return emptyList()
            if (json.isEmpty()) return emptyList()
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("procedural") ?: return emptyList()
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optString(i)
                if (!item.isNullOrBlank()) {
                    list.add(item)
                }
            }
            list
        } catch (t: Throwable) {
            Log.e(TAG, "Error parsing procedural rules from getCosmeticResources", t)
            emptyList()
        }
    }

    /**
     * Returns cosmetic CSS (hide_selectors formatted as display:none rules) for injection.
     * Returns empty string if none.
     */
    fun getCosmeticCss(url: String): String {
        if (!isNativeLoaded) return ""
        return try {
            val json = getCosmeticResources(url) ?: return ""
            if (json.isEmpty()) return ""
            val obj = JSONObject(json)
            obj.optString("css", "")
        } catch (t: Throwable) {
            Log.e(TAG, "Error parsing cosmetic CSS from getCosmeticResources", t)
            ""
        }
    }

    /**
     * Returns scriptlet JS code resolved from +js() cosmetic filter rules for the given URL.
     * This must be injected at document_start before any page scripts run.
     * Returns empty string if no scriptlets apply to this URL.
     */
    fun getScriptletJs(url: String): String {
        if (!isNativeLoaded) return ""
        return try {
            val json = getCosmeticResources(url) ?: return ""
            if (json.isEmpty()) return ""
            val obj = JSONObject(json)
            obj.optString("script", "")
        } catch (t: Throwable) {
            Log.e(TAG, "Error parsing scriptlet JS from getCosmeticResources", t)
            ""
        }
    }

    private val exceptionsCache = android.util.LruCache<String, String>(64)
    private val genericHideCache = android.util.LruCache<String, Boolean>(64)

    /**
     * Retrieves exceptions JSON array string for the given URL, caching it.
     */
    fun getExceptionsJson(url: String): String {
        if (!isNativeLoaded || url.isBlank()) return "[]"
        val cached = exceptionsCache.get(url)
        if (cached != null) return cached

        return try {
            val json = getCosmeticResources(url) ?: return "[]"
            if (json.isEmpty()) return "[]"
            val obj = JSONObject(json)
            val exceptionsArr = obj.optJSONArray("exceptions")
            val result = exceptionsArr?.toString() ?: "[]"
            exceptionsCache.put(url, result)
            if (obj.has("generichide")) {
                genericHideCache.put(url, obj.optBoolean("generichide", false))
            }
            result
        } catch (t: Throwable) {
            "[]"
        }
    }

    /**
     * Returns true if generic cosmetic filtering should be suppressed for this URL.
     * Corresponds to the $generichide network filter option.
     */
    fun isGenericHide(url: String): Boolean {
        if (!isNativeLoaded || url.isBlank()) return false
        val cached = genericHideCache.get(url)
        if (cached != null) return cached

        return try {
            val json = getCosmeticResources(url) ?: return false
            if (json.isEmpty()) return false
            val obj = JSONObject(json)
            val result = obj.optBoolean("generichide", false)
            genericHideCache.put(url, result)
            val exceptionsArr = obj.optJSONArray("exceptions")
            if (exceptionsArr != null) {
                exceptionsCache.put(url, exceptionsArr.toString())
            }
            result
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Returns matching generic hide CSS selectors for the given classes and IDs, respecting exceptions.
     * - classesJson: JSON array of string class names, e.g. ["ad-box", "banner"]
     * - idsJson: JSON array of string IDs, e.g. ["ad-unit"]
     * - url: Active page URL for retrieving site exceptions and checking $generichide
     * Returns JSON array string of matching CSS selectors, e.g. [".ad-box"]
     */
    fun getHiddenSelectors(classesJson: String, idsJson: String, url: String): String {
        if (!isNativeLoaded) return "[]"
        if (isGenericHide(url)) return "[]"
        return try {
            val exceptionsJson = getExceptionsJson(url)
            getHiddenClassIdSelectors(classesJson, idsJson, exceptionsJson) ?: "[]"
        } catch (t: Throwable) {
            Log.e(TAG, "Error in getHiddenClassIdSelectors", t)
            "[]"
        }
    }
}
