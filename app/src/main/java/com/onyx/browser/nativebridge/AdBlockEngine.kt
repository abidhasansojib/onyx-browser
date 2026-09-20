package com.onyx.browser.nativebridge

import android.content.Context
import android.util.Log
import com.onyx.browser.data.preferences.BrowserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object AdBlockEngine {
    private const val TAG = "AdBlockEngine"
    private const val BINARY_CACHE_FILE = "onyx_filters.bin"
    private const val DEFAULT_ASSET_RULES = "easylist_rules.txt"

    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("adblock_bridge")
            isNativeLoaded = true
            Log.i(TAG, "Successfully loaded native library libadblock_bridge.so")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library libadblock_bridge.so not found or could not be loaded", e)
            isNativeLoaded = false
        }
    }

    external fun initEngine(data: ByteArray): Boolean
    external fun initFromRules(rules: String): ByteArray?
    external fun checkUrl(url: String, sourceUrl: String, resourceType: String): Boolean
    external fun getCosmeticResources(url: String): String?
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
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AdBlockEngine", e)
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

    fun shouldBlock(url: String, sourceUrl: String, resourceType: String): Boolean {
        if (!isNativeLoaded) return false
        return try {
            checkUrl(url, sourceUrl, resourceType)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in checkUrl", t)
            false
        }
    }

    fun getCosmeticCss(url: String): String {
        if (!isNativeLoaded) return ""
        return try {
            getCosmeticResources(url) ?: ""
        } catch (t: Throwable) {
            Log.e(TAG, "Error in getCosmeticResources", t)
            ""
        }
    }
}
