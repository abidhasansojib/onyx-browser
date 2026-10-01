package com.onyx.browser.web.translate

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.onyx.browser.MainActivity
import com.onyx.browser.web.OnyxWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * JavaScript interface bridge for Native In-Page DOM Translation.
 * Receives batches of XML-tagged DOM text nodes from privileged WebView JavaScript,
 * executes asynchronous HTTPS POST requests to Google's translation API via OkHttp on Dispatchers.IO
 * (completely immune to webpage Content Security Policy restrictions),
 * and dispatches translated strings back to the WebView to update DOM text nodes in real time.
 */
class OnyxTranslateBridge(
    private val webView: OnyxWebView,
    private val coroutineScope: CoroutineScope
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeBatchCount = AtomicInteger(0)
    private var lastToastTime = 0L

    companion object {
        const val INTERFACE_NAME = "OnyxTranslateBridge"

        private val HTTP_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private fun findMainActivity(ctx: Context?): MainActivity? {
            var current: Context? = ctx
            while (current is ContextWrapper) {
                if (current is MainActivity) return current
                current = current.baseContext
            }
            return null
        }
    }

    @JavascriptInterface
    fun translateBatch(batchId: Int, xmlPayload: String, targetLang: String, isInitialBatch: Boolean) {
        if (xmlPayload.isBlank()) {
            return
        }

        activeBatchCount.incrementAndGet()
        updateLoadingState(true)

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val normalizedLang = PageTranslateManager.normalizeLang(targetLang)
                val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$normalizedLang&dt=t"
                val formBody = FormBody.Builder()
                    .add("q", xmlPayload)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                    .header("Accept", "*/*")
                    .post(formBody)
                    .build()

                val response = HTTP_CLIENT.newCall(request).execute()
                if (!response.isSuccessful) {
                    onBatchFailed(batchId, "HTTP ${response.code}")
                    return@launch
                }

                val bodyString = response.body?.string().orEmpty()
                val translationsMap = parseGoogleTranslateResponse(bodyString)

                withContext(Dispatchers.Main) {
                    applyBatchToWebView(batchId, translationsMap)
                    val remaining = activeBatchCount.decrementAndGet()
                    if (remaining <= 0) {
                        updateLoadingState(false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onBatchFailed(batchId, e.message ?: "Network error")
                }
            }
        }
    }

    @JavascriptInterface
    fun onTranslationFinished() {
        mainHandler.post {
            activeBatchCount.set(0)
            updateLoadingState(false)
        }
    }

    @JavascriptInterface
    fun onTranslationError(error: String) {
        mainHandler.post {
            activeBatchCount.set(0)
            updateLoadingState(false)
            showErrorToast("Translation error: $error")
        }
    }

    private fun applyBatchToWebView(batchId: Int, translations: Map<Int, String>) {
        if (translations.isEmpty()) return
        try {
            val jsonObj = JSONObject()
            for ((id, text) in translations) {
                jsonObj.put(id.toString(), text)
            }
            val jsonString = jsonObj.toString()
            webView.evaluateJavascript("if (window.__onyx_apply_batch) { window.__onyx_apply_batch($batchId, $jsonString); }", null)
        } catch (_: Exception) {}
    }

    private fun onBatchFailed(batchId: Int, error: String) {
        val remaining = activeBatchCount.decrementAndGet()
        if (remaining <= 0) {
            updateLoadingState(false)
        }
        showErrorToast("Translation failed: $error")
    }

    private fun updateLoadingState(isLoading: Boolean) {
        mainHandler.post {
            findMainActivity(webView.context)?.setTranslateLoading(isLoading)
        }
    }

    private fun showErrorToast(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime > 3000) {
            lastToastTime = now
            Toast.makeText(webView.context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseGoogleTranslateResponse(jsonString: String): Map<Int, String> {
        val results = mutableMapOf<Int, String>()
        try {
            val root = JSONArray(jsonString)
            val sentences = root.optJSONArray(0) ?: return results
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val sentence = sentences.optJSONArray(i) ?: continue
                val part = sentence.optString(0)
                sb.append(part)
            }
            val fullTranslatedXml = sb.toString()

            val pattern = Pattern.compile("<t\\s+id=[\"']?(\\d+)[\"']?>(.*?)</t>", Pattern.DOTALL)
            val matcher = pattern.matcher(fullTranslatedXml)
            while (matcher.find()) {
                val id = matcher.group(1)?.toIntOrNull() ?: continue
                val rawText = matcher.group(2) ?: ""
                val unescaped = unescapeXmlEntities(rawText)
                results[id] = unescaped
            }
        } catch (_: Exception) {}
        return results
    }

    private fun unescapeXmlEntities(text: String): String {
        return text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }
}
