package com.onyx.browser.userscript

import android.content.Context
import android.net.Uri
import android.util.Log
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.UserScriptItem
import com.onyx.browser.data.model.UserScriptValueItem
import com.onyx.browser.data.preferences.BrowserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ParsedUserScript(
    val script: UserScriptItem,
    val metadata: UserScriptMetadata,
    var requiresContent: String = "",
    var resourcesContent: String = "{}",
    val code: String
)

sealed class UpdateResult {
    data class Updated(val from: String, val to: String) : UpdateResult()
    object UpToDate : UpdateResult()
    object NoSource : UpdateResult()
    data class Failed(val message: String) : UpdateResult()
}

class UserScriptManager private constructor(private val context: Context) {

    interface ValueChangeListener {
        fun onValueChanged(scriptId: Long, key: String, oldValue: String?, newValue: String?)
    }

    private val valueChangeListeners = java.util.concurrent.CopyOnWriteArrayList<ValueChangeListener>()

    fun addValueChangeListener(listener: ValueChangeListener) {
        valueChangeListeners.add(listener)
    }

    fun removeValueChangeListener(listener: ValueChangeListener) {
        valueChangeListeners.remove(listener)
    }

    val onScriptsChangedListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addOnScriptsChangedListener(listener: () -> Unit) {
        onScriptsChangedListeners.add(listener)
    }

    fun removeOnScriptsChangedListener(listener: () -> Unit) {
        onScriptsChangedListeners.remove(listener)
    }

    private fun notifyScriptsChanged() {
        onScriptsChangedListeners.forEach { runCatching { it.invoke() } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val database: AppDatabase
        get() = AppDatabase.getInstance(context)

    private val userScriptDao get() = database.userScriptDao()
    private val valueDao get() = database.userScriptValueDao()

    private val scriptDir: File by lazy {
        File(context.filesDir, "userscripts").apply { mkdirs() }
    }

    private fun codeFile(id: Long) = File(scriptDir, "$id.user.js")
    private fun requiresFile(id: Long) = File(scriptDir, "$id.requires.js")
    private fun resourcesFile(id: Long) = File(scriptDir, "$id.resources.json")

    fun readCodeFile(id: Long): String {
        val f = codeFile(id)
        return if (f.exists()) f.readText() else ""
    }

    private fun writeCodeFile(id: Long, code: String) {
        codeFile(id).writeText(code)
    }

    private fun deleteCodeFile(id: Long) {
        codeFile(id).delete()
    }

    fun readRequiresFile(id: Long): String {
        val f = requiresFile(id)
        return if (f.exists()) f.readText() else ""
    }

    private fun writeRequiresFile(id: Long, content: String) {
        requiresFile(id).writeText(content)
    }

    private fun deleteRequiresFile(id: Long) {
        requiresFile(id).delete()
    }

    fun readResourcesFile(id: Long): String {
        val f = resourcesFile(id)
        return if (f.exists()) f.readText() else "{}"
    }

    private fun writeResourcesFile(id: Long, json: String) {
        resourcesFile(id).writeText(json)
    }

    private fun deleteResourcesFile(id: Long) {
        resourcesFile(id).delete()
    }

    @Volatile
    var scripts: List<ParsedUserScript> = emptyList()
        private set

    private val _hasNativeScripts = MutableStateFlow(false)
    val hasNativeScripts = _hasNativeScripts.asStateFlow()

    init {
        scope.launch { reload() }
    }

    suspend fun reload() = withContext(Dispatchers.IO) {
        val rows = try {
            userScriptDao.getAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load userscripts from database", e)
            emptyList()
        }

        val loaded = rows.mapNotNull { row ->
            val code = readCodeFile(row.id)
            if (code.isBlank()) return@mapNotNull null
            try {
                val meta = UserScriptMetadata.parse(code)
                val reqContent = readRequiresFile(row.id)
                val resContent = readResourcesFile(row.id)
                ParsedUserScript(
                    script = row,
                    metadata = meta,
                    requiresContent = reqContent,
                    resourcesContent = resContent,
                    code = code
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse metadata for script ${row.name}", e)
                null
            }
        }

        scripts = loaded
        _hasNativeScripts.value = loaded.any { it.script.enabled && it.metadata.requiresNativeBridge }
        notifyScriptsChanged()

        // Asynchronously resolve and cache any missing requires or resources on disk
        loaded.forEach { parsed ->
            if (parsed.metadata.requires.isNotEmpty() && parsed.requiresContent.isBlank()) {
                scope.launch {
                    resolveRequires(parsed)
                    notifyScriptsChanged()
                }
            }
            if (parsed.metadata.resources.isNotEmpty() && (parsed.resourcesContent.isBlank() || parsed.resourcesContent == "{}")) {
                scope.launch {
                    resolveResources(parsed)
                    notifyScriptsChanged()
                }
            }
        }
    }

    fun parseRequireSources(cache: String): List<String> {
        if (cache.isBlank()) return emptyList()
        val trimmed = cache.trim()
        return if (trimmed.startsWith("[")) {
            try {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).mapNotNull { idx ->
                    val obj = arr.optJSONObject(idx)
                    obj?.optString("source") ?: arr.optString(idx).takeIf { it.isNotBlank() }
                }
            } catch (_: Exception) {
                listOf(cache)
            }
        } else {
            listOf(cache)
        }
    }

    private fun fetchRequires(requires: List<String>): String {
        if (requires.isEmpty()) return "[]"
        val arr = JSONArray()
        requires.forEach { url ->
            val cached = requireCache[url]
            if (cached != null) {
                arr.put(JSONObject().put("url", url).put("source", cached))
                return@forEach
            }
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "*/*")
                    .build()
                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                resp.close()
                if (body.isNotBlank()) {
                    // LRU-style cap: evict oldest entries when cache exceeds 50 items to prevent
                    // unbounded memory growth from large @require libraries (jQuery, lodash, etc.)
                    if (requireCache.size >= 50) {
                        requireCache.keys.take(20).forEach { requireCache.remove(it) }
                    }
                    requireCache[url] = body
                    arr.put(JSONObject().put("url", url).put("source", body))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch @require $url", e)
            }
        }
        return arr.toString()
    }

    private fun resolveRequires(parsed: ParsedUserScript) {
        val result = fetchRequires(parsed.metadata.requires)
        parsed.requiresContent = result
        if (result.isNotBlank()) {
            writeRequiresFile(parsed.script.id, result)
        }
    }

    private fun fetchResources(resources: List<UserScriptResource>): String {
        if (resources.isEmpty()) return "{}"
        val resMap = JSONObject()
        resources.forEach { res ->
            try {
                val req = Request.Builder()
                    .url(res.url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "*/*")
                    .build()
                val resp = httpClient.newCall(req).execute()
                val bytes = resp.body?.bytes()
                val contentType = resp.header("Content-Type") ?: "application/octet-stream"
                resp.close()
                if (bytes != null && bytes.isNotEmpty()) {
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    val item = JSONObject().apply {
                        put("mime", contentType)
                        put("base64", base64)
                        if (contentType.startsWith("text/") || contentType.contains("javascript") || contentType.contains("json")) {
                            put("text", String(bytes, Charsets.UTF_8))
                        }
                    }
                    resMap.put(res.name, item)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch @resource ${res.name} from ${res.url}", e)
            }
        }
        return resMap.toString()
    }

    private fun resolveResources(parsed: ParsedUserScript) {
        if (parsed.metadata.resources.isEmpty()) return
        val json = fetchResources(parsed.metadata.resources)
        parsed.resourcesContent = json
        if (json.isNotBlank() && json != "{}") {
            writeResourcesFile(parsed.script.id, json)
        }
    }

    fun getMatchingScripts(url: String, runAt: RunAt): List<ParsedUserScript> {
        val prefs = BrowserPreferences.getInstance(context)
        if (prefs.isGlobalScriptBlockingEnabled || !prefs.isJavaScriptEnabledGlobal) return emptyList()

        val domain = try {
            java.net.URI(url).host?.lowercase()?.removePrefix("www.") ?: ""
        } catch (_: Exception) { "" }

        if (domain.isNotEmpty() && (prefs.isScriptBlockingEnabledForDomain(domain) || !prefs.isJavaScriptEnabledForDomain(domain))) {
            return emptyList()
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) return emptyList()
        return scripts.filter { parsed ->
            parsed.script.enabled &&
                    parsed.metadata.runAt == runAt &&
                    UserScriptUrlMatcher.matches(url, parsed.metadata.matches, parsed.metadata.includes) &&
                    !UserScriptUrlMatcher.isExcluded(url, parsed.metadata.excludes)
        }.distinctBy { it.metadata.name.takeIf { n -> n != UserScriptMetadata.DEFAULT_NAME } ?: "id:${it.script.id}" }
    }

    fun getById(id: Long): ParsedUserScript? = scripts.firstOrNull { it.script.id == id }

    fun isApiAllowed(id: Long, url: String, api: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val parsed = getById(id) ?: return false
        if (!parsed.script.enabled || !parsed.metadata.allowsApi(api)) return false
        val m = parsed.metadata
        return UserScriptUrlMatcher.matches(url, m.matches, m.includes) &&
                !UserScriptUrlMatcher.isExcluded(url, m.excludes)
    }

    fun buildCombinedScript(isIncognito: Boolean = false): String? {
        val prefs = BrowserPreferences.getInstance(context)
        if (prefs.isGlobalScriptBlockingEnabled || !prefs.isJavaScriptEnabledGlobal) return null

        val active = scripts.filter { it.script.enabled }
        if (active.isEmpty()) return null

        val builder = StringBuilder()
        builder.append(RUNTIME_BOOTSTRAP).append('\n')
        for (parsed in active) {
            builder.append(buildScriptBlock(parsed, isIncognito)).append('\n')
        }
        return builder.toString()
    }

    fun buildInjectionJs(parsed: ParsedUserScript, token: String = "", isIncognito: Boolean = false): String {
        return buildScriptBlock(parsed, isIncognito)
    }

    private fun buildScriptBlock(parsed: ParsedUserScript, isIncognito: Boolean = false): String {
        val meta = parsed.metadata
        val excludePatterns = meta.excludes.mapNotNull { UserScriptUrlMatcher.globToRegexSource(it) }
        val matchPatterns = meta.matches.mapNotNull { UserScriptUrlMatcher.matchPatternToRegexSource(it) } +
                meta.includes.map { UserScriptUrlMatcher.globToRegexSource(it) }

        val excludeArray = excludePatterns.joinToString(",") { "new RegExp(${JSONObject.quote(it)})" }
        val matchArray = matchPatterns.joinToString(",") { "new RegExp(${JSONObject.quote(it)})" }

        val requireSources = parseRequireSources(parsed.requiresContent)
        val requireBlock = requireSources.joinToString("\n") {
            "try {\n$it\n} catch(__reqErr) { console.error('[UserScript] require failed in ' + __scriptName, __reqErr); }"
        }

        val resourcesObj = if (parsed.resourcesContent.isNotBlank() && parsed.resourcesContent != "{}") {
            parsed.resourcesContent
        } else {
            "{}"
        }

        val connectsArray = meta.connects.joinToString(",") { JSONObject.quote(it) }
        val grantsArray = meta.grants.joinToString(",") { JSONObject.quote(it) }

        val runAtStr = when (meta.runAt) {
            RunAt.DOCUMENT_START -> "document-start"
            RunAt.DOCUMENT_END -> "document-end"
            RunAt.DOCUMENT_IDLE -> "document-idle"
        }

        val scriptKey = "us_${parsed.script.id}"
        val scriptName = JSONObject.quote(meta.name)
        val scriptId = parsed.script.id
        val noFrames = meta.noFrames
        val unwrap = meta.unwrap

        return "(function(){\n" +
                "var __exclude = [$excludeArray];\n" +
                "var __match = [$matchArray];\n" +
                "function __matchesUrl(u) {\n" +
                "  for (var __i = 0; __i < __exclude.length; __i++) { if (__exclude[__i].test(u)) return false; }\n" +
                "  if (__match.length === 0) return true;\n" +
                "  for (var __j = 0; __j < __match.length; __j++) { if (__match[__j].test(u)) return true; }\n" +
                "  return false;\n" +
                "}\n" +
                "if ($noFrames && window.self !== window.top) return;\n" +
                "var __scriptId = $scriptId;\n" +
                "var __scriptKey = ${JSONObject.quote(scriptKey)};\n" +
                "var __scriptName = $scriptName;\n" +
                "var __resources = $resourcesObj;\n" +
                "var GM_info = {\n" +
                "  script: {\n" +
                "    name: $scriptName,\n" +
                "    namespace: ${JSONObject.quote(meta.namespace)},\n" +
                "    version: ${JSONObject.quote(meta.version)},\n" +
                "    description: ${JSONObject.quote(meta.description)},\n" +
                "    author: ${JSONObject.quote(meta.author)},\n" +
                "    matches: __match.map(function(r){ return r.source; }),\n" +
                "    includes: [],\n" +
                "    excludes: [],\n" +
                "    resources: __resources,\n" +
                "    connects: [$connectsArray],\n" +
                "    grant: [$grantsArray],\n" +
                "    runAt: ${JSONObject.quote(runAtStr)},\n" +
                "    noframes: $noFrames,\n" +
                "    unwrap: $unwrap\n" +
                "  },\n" +
                "  version: '1.0',\n" +
                "  scriptHandler: 'OnyxBrowser',\n" +
                "  scriptMetaStr: null,\n" +
                "  scriptWillUpdate: false,\n" +
                "  isIncognito: $isIncognito,\n" +
                "  downloadMode: 'native'\n" +
                "};\n" +
                "var unsafeWindow = window;\n" +
                "function GM_log() { try { console.log.apply(console, arguments); } catch(__e) {} }\n" +
                "function GM_addStyle(css) {\n" +
                "  var __s = document.createElement('style');\n" +
                "  __s.setAttribute('data-userscript', __scriptName);\n" +
                "  __s.textContent = css;\n" +
                "  var __t = document.head || document.documentElement;\n" +
                "  if (__t) { __t.appendChild(__s); return __s; }\n" +
                "  if (typeof MutationObserver !== 'undefined') {\n" +
                "    var __obs = new MutationObserver(function() {\n" +
                "      var __cur = document.head || document.documentElement;\n" +
                "      if (__cur) { __obs.disconnect(); __cur.appendChild(__s); }\n" +
                "    });\n" +
                "    __obs.observe(document, { childList: true, subtree: true });\n" +
                "  } else {\n" +
                "    document.addEventListener('DOMContentLoaded', function() {\n" +
                "      (document.head || document.documentElement).appendChild(__s);\n" +
                "    });\n" +
                "  }\n" +
                "  return __s;\n" +
                "}\n" +
                "function GM_addElement(a, b, c) {\n" +
                "  var __tag, __attrs, __parent;\n" +
                "  if (typeof a === 'string') { __tag = a; __attrs = b || {}; __parent = c || document.head || document.documentElement; }\n" +
                "  else { __parent = a || document.head || document.documentElement; __tag = b; __attrs = c || {}; }\n" +
                "  var __el = document.createElement(__tag);\n" +
                "  for (var __k in __attrs) {\n" +
                "    if (__attrs.hasOwnProperty(__k)) {\n" +
                "      if (__k === 'textContent') __el.textContent = __attrs[__k];\n" +
                "      else __el.setAttribute(__k, __attrs[__k]);\n" +
                "    }\n" +
                "  }\n" +
                "  if (__parent) __parent.appendChild(__el);\n" +
                "  return __el;\n" +
                "}\n" +
                "function GM_getValue(key, defaultValue) {\n" +
                "  try {\n" +
                "    if (window.onyxGM && window.onyxGM.getValue) {\n" +
                "      var raw = window.onyxGM.getValue(__scriptKey, String(key));\n" +
                "      if (raw === null || raw === undefined) return defaultValue;\n" +
                "      return JSON.parse(raw);\n" +
                "    }\n" +
                "  } catch(__e) {}\n" +
                "  return defaultValue;\n" +
                "}\n" +
                "function GM_setValue(key, value) {\n" +
                "  try {\n" +
                "    if (window.onyxGM && window.onyxGM.setValue) {\n" +
                "      window.onyxGM.setValue(__scriptKey, String(key), JSON.stringify(value));\n" +
                "    }\n" +
                "  } catch(__e) {}\n" +
                "}\n" +
                "function GM_deleteValue(key) {\n" +
                "  try {\n" +
                "    if (window.onyxGM && window.onyxGM.deleteValue) {\n" +
                "      window.onyxGM.deleteValue(__scriptKey, String(key));\n" +
                "    }\n" +
                "  } catch(__e) {}\n" +
                "}\n" +
                "function GM_listValues() {\n" +
                "  try {\n" +
                "    if (window.onyxGM && window.onyxGM.listValues) {\n" +
                "      var raw = window.onyxGM.listValues(__scriptKey);\n" +
                "      return raw ? JSON.parse(raw) : [];\n" +
                "    }\n" +
                "  } catch(__e) {}\n" +
                "  return [];\n" +
                "}\n" +
                "function GM_addValueChangeListener(name, callback) {\n" +
                "  if (!window.__usRuntime.valueListeners[__scriptKey]) { window.__usRuntime.valueListeners[__scriptKey] = {}; }\n" +
                "  var __id = 'vl_' + Math.random().toString(36).substring(2);\n" +
                "  window.__usRuntime.valueListeners[__scriptKey][__id] = function(k, ov, nv, r) {\n" +
                "    if (k === name) { try { callback(k, ov, nv, r); } catch(__e) {} }\n" +
                "  };\n" +
                "  return __id;\n" +
                "}\n" +
                "function GM_removeValueChangeListener(id) {\n" +
                "  if (window.__usRuntime.valueListeners[__scriptKey]) {\n" +
                "    delete window.__usRuntime.valueListeners[__scriptKey][id];\n" +
                "  }\n" +
                "}\n" +
                "function GM_openInTab(url, options) {\n" +
                "  var active = true;\n" +
                "  if (typeof options === 'boolean') active = !options;\n" +
                "  else if (options && typeof options === 'object') active = options.active !== false;\n" +
                "  try {\n" +
                "    if (window.onyxGM && window.onyxGM.openInTab) {\n" +
                "      window.onyxGM.openInTab(url, active);\n" +
                "    }\n" +
                "  } catch(__e) {}\n" +
                "  return { closed: false, close: function() {} };\n" +
                "}\n" +
                "function GM_setClipboard(text, type) {\n" +
                "  try {\n" +
                "    if (window.onyxGM && window.onyxGM.setClipboard) {\n" +
                "      window.onyxGM.setClipboard(String(text));\n" +
                "      return;\n" +
                "    }\n" +
                "  } catch(__e) {}\n" +
                "  try {\n" +
                "    if (navigator.clipboard && navigator.clipboard.writeText) {\n" +
                "      navigator.clipboard.writeText(String(text)).catch(function() {});\n" +
                "    }\n" +
                "  } catch(__e2) {}\n" +
                "}\n" +
                "function GM_notification(details, ondone) {\n" +
                "  try {\n" +
                "    var __opts = (typeof details === 'string') ? { text: details, title: ondone } : (details || {});\n" +
                "    var __title = (typeof __opts.title === 'string' && __opts.title) ? __opts.title : __scriptName;\n" +
                "    if (window.onyxGM && window.onyxGM.notify) {\n" +
                "      window.onyxGM.notify(__scriptName, __title, __opts.text || '', location.hostname || '');\n" +
                "    }\n" +
                "    if (typeof __opts.ondone === 'function') setTimeout(__opts.ondone, 0);\n" +
                "  } catch(__e) {}\n" +
                "}\n" +
                "var __xhrSeq = 0;\n" +
                "function GM_xmlhttpRequest(details) {\n" +
                "  details = details || {};\n" +
                "  try {\n" +
                "    var __id = 'x_' + __scriptId + '_' + (++__xhrSeq) + '_' + Date.now();\n" +
                "    var __payload = {\n" +
                "      url: details.url,\n" +
                "      method: (details.method || 'GET').toUpperCase(),\n" +
                "      headers: details.headers || {},\n" +
                "      timeout: details.timeout || 0,\n" +
                "      responseType: details.responseType || '',\n" +
                "      anonymous: !!details.anonymous\n" +
                "    };\n" +
                "    if (details.data !== undefined && details.data !== null) {\n" +
                "      __payload.data = (typeof details.data === 'string') ? details.data : JSON.stringify(details.data);\n" +
                "    }\n" +
                "    window.__usRuntime.xhrCallbacks[__id] = function(result, error) {\n" +
                "      if (error) {\n" +
                "        if (details.onerror) details.onerror({ error: error.error || 'error', finalUrl: details.url, readyState: 4, status: 0, context: details.context });\n" +
                "        return;\n" +
                "      }\n" +
                "      var __resp = result.responseText;\n" +
                "      var __respObj = __resp;\n" +
                "      if (details.responseType === 'json') {\n" +
                "        try { __respObj = JSON.parse(__resp); } catch(__pe) { __respObj = null; }\n" +
                "      } else if ((details.responseType === 'blob' || details.responseType === 'arraybuffer') && result.responseBase64) {\n" +
                "        try {\n" +
                "          var __binStr = atob(result.responseBase64);\n" +
                "          var __binLen = __binStr.length;\n" +
                "          var __u8 = new Uint8Array(__binLen);\n" +
                "          for (var __bi = 0; __bi < __binLen; __bi++) { __u8[__bi] = __binStr.charCodeAt(__bi); }\n" +
                "          if (details.responseType === 'blob') {\n" +
                "            var __mime = 'application/octet-stream';\n" +
                "            if (result.responseHeaders) {\n" +
                "              var __mMatch = result.responseHeaders.match(/content-type:\\s*([^\\r\\n;]+)/i);\n" +
                "              if (__mMatch) __mime = __mMatch[1].trim();\n" +
                "            }\n" +
                "            __respObj = new Blob([__u8], { type: __mime });\n" +
                "          } else {\n" +
                "            __respObj = __u8.buffer;\n" +
                "          }\n" +
                "        } catch(__be) { console.error('[UserScript] binary conversion error', __be); }\n" +
                "      }\n" +
                "      var __r = {\n" +
                "        status: result.status,\n" +
                "        statusText: result.statusText,\n" +
                "        responseText: __resp,\n" +
                "        response: __respObj,\n" +
                "        readyState: 4,\n" +
                "        finalUrl: result.finalUrl,\n" +
                "        responseHeaders: result.responseHeaders,\n" +
                "        context: details.context\n" +
                "      };\n" +
                "      if (details.onreadystatechange) try { details.onreadystatechange(__r); } catch(__e3) {}\n" +
                "      if (details.onload) details.onload(__r);\n" +
                "    };\n" +
                "    if (window.onyxGM && window.onyxGM.xhr) {\n" +
                "      window.onyxGM.xhr(__id, JSON.stringify(__payload));\n" +
                "    } else {\n" +
                "      delete window.__usRuntime.xhrCallbacks[__id];\n" +
                "      if (details.onerror) details.onerror({ error: 'Bridge unavailable', context: details.context });\n" +
                "    }\n" +
                "    return {\n" +
                "      abort: function() {\n" +
                "        try { if (window.onyxGM && window.onyxGM.abortXhr) window.onyxGM.abortXhr(__id); } catch(__ae) {}\n" +
                "        if (details.onabort) details.onabort();\n" +
                "      }\n" +
                "    };\n" +
                "  } catch(__e) {\n" +
                "    if (details.onerror) details.onerror({ error: String(__e), context: details.context });\n" +
                "    return { abort: function() {} };\n" +
                "  }\n" +
                "}\n" +
                "function GM_download(details, filename) {\n" +
                "  try {\n" +
                "    var __opts = (typeof details === 'string') ? { url: details, name: filename } : (details || {});\n" +
                "    var __u = __opts.url || '';\n" +
                "    if (__u.indexOf('blob:') === 0 || __u.indexOf('data:') === 0) {\n" +
                "      var __a = document.createElement('a');\n" +
                "      __a.href = __u;\n" +
                "      __a.download = __opts.name || 'download';\n" +
                "      __a.style.display = 'none';\n" +
                "      (document.body || document.documentElement).appendChild(__a);\n" +
                "      __a.click();\n" +
                "      setTimeout(function() {\n" +
                "        __a.remove();\n" +
                "        if (__opts.onload) __opts.onload();\n" +
                "      }, 100);\n" +
                "      return { abort: function() {} };\n" +
                "    }\n" +
                "    if (window.onyxGM && window.onyxGM.download) {\n" +
                "      window.onyxGM.download(__u, __opts.name || 'download');\n" +
                "    }\n" +
                "    if (__opts.onload) setTimeout(__opts.onload, 100);\n" +
                "  } catch(__e) {\n" +
                "    if (details && details.onerror) details.onerror(String(__e));\n" +
                "  }\n" +
                "}\n" +
                "function GM_getResourceText(name) {\n" +
                "  var __r = __resources[name];\n" +
                "  if (!__r) return undefined;\n" +
                "  if (__r.text !== undefined) return __r.text;\n" +
                "  if (__r.base64) {\n" +
                "    try { return decodeURIComponent(escape(atob(__r.base64))); }\n" +
                "    catch(__e) { try { return atob(__r.base64); } catch(__e2) { return undefined; } }\n" +
                "  }\n" +
                "  return undefined;\n" +
                "}\n" +
                "function GM_getResourceURL(name) {\n" +
                "  var __r = __resources[name];\n" +
                "  if (!__r) return undefined;\n" +
                "  return 'data:' + (__r.mime || 'application/octet-stream') + ';base64,' + (__r.base64 || '');\n" +
                "}\n" +
                "function GM_registerMenuCommand(caption, onClick, accessKey) {\n" +
                "  var __id = 'm_' + __scriptId + '_' + (++__xhrSeq);\n" +
                "  window.__usRuntime.menuCommands[__id] = { scriptName: __scriptName, caption: caption, onClick: onClick, accessKey: accessKey };\n" +
                "  __usRenderMenuButton();\n" +
                "  try { if (window.onyxGM && window.onyxGM.registerMenuCommand) window.onyxGM.registerMenuCommand(caption, __id); } catch(__e) {}\n" +
                "  return __id;\n" +
                "}\n" +
                "function GM_unregisterMenuCommand(id) {\n" +
                "  delete window.__usRuntime.menuCommands[id];\n" +
                "  __usRenderMenuButton();\n" +
                "  try { if (window.onyxGM && window.onyxGM.unregisterMenuCommand) window.onyxGM.unregisterMenuCommand(id); } catch(__e) {}\n" +
                "}\n" +
                "function __usRenderMenuButton() {\n" +
                "  try {\n" +
                "    var __ids = Object.keys(window.__usRuntime.menuCommands);\n" +
                "    var __btn = document.getElementById('__us_menu_btn');\n" +
                "    if (__ids.length === 0) { if (__btn) __btn.remove(); return; }\n" +
                "    if (!__btn) {\n" +
                "      __btn = document.createElement('div');\n" +
                "      __btn.id = '__us_menu_btn';\n" +
                "      __btn.style.cssText = 'position:fixed;right:14px;bottom:80px;z-index:2147483647;width:38px;height:38px;border-radius:19px;background:rgba(30,30,34,0.88);backdrop-filter:blur(10px);color:#fff;display:flex;align-items:center;justify-content:center;font:16px sans-serif;box-shadow:0 3px 10px rgba(0,0,0,0.45);cursor:pointer;user-select:none;border:1px solid rgba(255,255,255,0.12);';\n" +
                "      __btn.textContent = '\\u2699';\n" +
                "      var __menu = document.createElement('div');\n" +
                "      __menu.id = '__us_menu_list';\n" +
                "      __menu.style.cssText = 'position:fixed;right:14px;bottom:126px;z-index:2147483647;background:#202024;color:#fff;border-radius:12px;box-shadow:0 4px 16px rgba(0,0,0,0.55);display:none;min-width:180px;max-width:280px;font:13px sans-serif;overflow:hidden;border:1px solid rgba(255,255,255,0.1);';\n" +
                "      (document.body || document.documentElement).appendChild(__btn);\n" +
                "      (document.body || document.documentElement).appendChild(__menu);\n" +
                "      __btn.addEventListener('click', function(e) {\n" +
                "        e.stopPropagation();\n" +
                "        __menu.style.display = (__menu.style.display === 'none' ? 'block' : 'none');\n" +
                "      });\n" +
                "      document.addEventListener('click', function() {\n" +
                "        if (__menu) __menu.style.display = 'none';\n" +
                "      });\n" +
                "    }\n" +
                "    var __menuEl = document.getElementById('__us_menu_list');\n" +
                "    if (__menuEl) {\n" +
                "      __menuEl.innerHTML = '';\n" +
                "      __ids.forEach(function(__mid) {\n" +
                "        var __cmd = window.__usRuntime.menuCommands[__mid];\n" +
                "        var __item = document.createElement('div');\n" +
                "        __item.textContent = __cmd.caption;\n" +
                "        __item.style.cssText = 'padding:11px 16px;border-bottom:1px solid rgba(255,255,255,0.06);cursor:pointer;transition:background 0.15s;';\n" +
                "        __item.addEventListener('click', function(e) {\n" +
                "          e.stopPropagation();\n" +
                "          __menuEl.style.display = 'none';\n" +
                "          try { __cmd.onClick(); } catch(__e) {}\n" +
                "        });\n" +
                "        __menuEl.appendChild(__item);\n" +
                "      });\n" +
                "    }\n" +
                "  } catch(__e) {}\n" +
                "}\n" +
                "var GM = {\n" +
                "  info: GM_info,\n" +
                "  log: function() { GM_log.apply(null, arguments); return Promise.resolve(); },\n" +
                "  getValue: function(k, d) { return Promise.resolve(GM_getValue(k, d)); },\n" +
                "  setValue: function(k, v) { GM_setValue(k, v); return Promise.resolve(); },\n" +
                "  deleteValue: function(k) { GM_deleteValue(k); return Promise.resolve(); },\n" +
                "  listValues: function() { return Promise.resolve(GM_listValues()); },\n" +
                "  addStyle: GM_addStyle,\n" +
                "  addElement: GM_addElement,\n" +
                "  xmlHttpRequest: function(d) {\n" +
                "    return new Promise(function(res) {\n" +
                "      var __d = Object.assign({}, d);\n" +
                "      var __ol = __d.onload, __oe = __d.onerror;\n" +
                "      __d.onload = function(r) { if (__ol) __ol(r); res(r); };\n" +
                "      __d.onerror = function(r) { if (__oe) __oe(r); res(r); };\n" +
                "      GM_xmlhttpRequest(__d);\n" +
                "    });\n" +
                "  },\n" +
                "  download: function(d, f) { GM_download(d, f); return Promise.resolve(); },\n" +
                "  getResourceText: function(n) { return Promise.resolve(GM_getResourceText(n)); },\n" +
                "  getResourceUrl: function(n) { return Promise.resolve(GM_getResourceURL(n)); },\n" +
                "  getResourceURL: function(n) { return Promise.resolve(GM_getResourceURL(n)); },\n" +
                "  registerMenuCommand: function(c, cb, a) { return Promise.resolve(GM_registerMenuCommand(c, cb, a)); },\n" +
                "  unregisterMenuCommand: function(id) { return Promise.resolve(GM_unregisterMenuCommand(id)); },\n" +
                "  openInTab: function(u, o) { return Promise.resolve(GM_openInTab(u, o)); },\n" +
                "  setClipboard: function(t, ty) { GM_setClipboard(t, ty); return Promise.resolve(); },\n" +
                "  notification: function(d, od) { GM_notification(d, od); return Promise.resolve(); },\n" +
                "  addValueChangeListener: function(n, cb) { return Promise.resolve(GM_addValueChangeListener(n, cb)); },\n" +
                "  removeValueChangeListener: function(id) { return Promise.resolve(GM_removeValueChangeListener(id)); }\n" +
                "};\n" +
                "var GM_xmlHttpRequest = GM_xmlhttpRequest;\n" +
                requireBlock + "\n" +
                "var __hasExecuted = false;\n" +
                "function __runUserScript() {\n" +
                "  if (__hasExecuted) return;\n" +
                "  __hasExecuted = true;\n" +
                "  try {\n" +
                parsed.code + "\n" +
                "  } catch(__e) {\n" +
                "    console.error('[UserScript] ' + __scriptName + ' failed', __e);\n" +
                "  }\n" +
                "}\n" +
                "var __runAt = ${JSONObject.quote(runAtStr)};\n" +
                "function __attemptRun() {\n" +
                "  if (__hasExecuted) return;\n" +
                "  if (!__matchesUrl(location.href)) return;\n" +
                "  if (__runAt === 'document-start') {\n" +
                "    __runUserScript();\n" +
                "  } else if (__runAt === 'document-end') {\n" +
                "    if (document.readyState !== 'loading') { __runUserScript(); }\n" +
                "    else { document.addEventListener('DOMContentLoaded', __runUserScript); }\n" +
                "  } else {\n" +
                "    if (document.readyState === 'complete') { __runUserScript(); }\n" +
                "    else { window.addEventListener('load', __runUserScript); }\n" +
                "  }\n" +
                "}\n" +
                "__attemptRun();\n" +
                "window.addEventListener('onyx:urlchange', function() { __hasExecuted = false; __attemptRun(); });\n" +
                "window.addEventListener('yt-navigate-finish', function() { __hasExecuted = false; __attemptRun(); });\n" +
                "})();\n"
    }

    private fun buildGmInfo(parsed: ParsedUserScript): String {
        val m = parsed.metadata
        val scriptObj = JSONObject().apply {
            put("name", m.name)
            put("namespace", m.namespace)
            put("version", m.version)
            put("description", m.description)
            put("author", m.author)
            put("runAt", when (m.runAt) {
                RunAt.DOCUMENT_START -> "document-start"
                RunAt.DOCUMENT_END -> "document-end"
                RunAt.DOCUMENT_IDLE -> "document-idle"
            })
            put("matches", JSONArray(m.matches))
            put("includes", JSONArray(m.includes))
            put("excludes", JSONArray(m.excludes))
            put("grant", JSONArray(m.grants))
            put("connects", JSONArray(m.connects))
        }
        return JSONObject().apply {
            put("script", scriptObj)
            put("scriptHandler", "OnyxBrowser")
            put("version", parsed.script.id.toString())
            put("uuid", parsed.script.id.toString())
        }.toString()
    }

    // region CRUD

    suspend fun addScript(code: String, sourceUrl: String? = null): Long = withContext(Dispatchers.IO) {
        val metadata = UserScriptMetadata.parse(code)
        val matchSummary = when {
            metadata.matches.isNotEmpty() -> metadata.matches.take(2).joinToString(", ")
            metadata.includes.isNotEmpty() -> metadata.includes.take(2).joinToString(", ")
            else -> "All sites"
        }

        val existing = if (metadata.name != UserScriptMetadata.DEFAULT_NAME) {
            scripts.firstOrNull {
                it.script.name == metadata.name &&
                        (metadata.namespace.isBlank() || it.metadata.namespace == metadata.namespace)
            }?.script
        } else null

        if (existing != null) {
            val updated = existing.copy(
                name = metadata.name,
                sourceUrl = sourceUrl ?: existing.sourceUrl,
                version = metadata.version,
                description = metadata.description,
                author = metadata.author,
                matchSummary = matchSummary,
                runAt = when (metadata.runAt) {
                    RunAt.DOCUMENT_START -> "document-start"
                    RunAt.DOCUMENT_END -> "document-end"
                    RunAt.DOCUMENT_IDLE -> "document-idle"
                }
            )
            userScriptDao.update(updated)
            writeCodeFile(existing.id, code)
            if (metadata.requires.isNotEmpty()) {
                val reqContent = fetchRequires(metadata.requires)
                if (reqContent.isNotBlank()) {
                    writeRequiresFile(existing.id, reqContent)
                }
            }
            if (metadata.resources.isNotEmpty()) {
                val resContent = fetchResources(metadata.resources)
                if (resContent.isNotBlank() && resContent != "{}") {
                    writeResourcesFile(existing.id, resContent)
                }
            }
            reload()
            return@withContext existing.id
        }

        val maxOrder = scripts.maxOfOrNull { it.script.order } ?: 0
        val newItem = UserScriptItem(
            name = metadata.name,
            enabled = true,
            sourceUrl = sourceUrl,
            order = maxOrder + 1,
            version = metadata.version,
            description = metadata.description,
            author = metadata.author,
            matchSummary = matchSummary,
            runAt = when (metadata.runAt) {
                RunAt.DOCUMENT_START -> "document-start"
                RunAt.DOCUMENT_END -> "document-end"
                RunAt.DOCUMENT_IDLE -> "document-idle"
            }
        )
        val id = userScriptDao.insert(newItem)
        writeCodeFile(id, code)
        if (metadata.requires.isNotEmpty()) {
            val reqContent = fetchRequires(metadata.requires)
            if (reqContent.isNotBlank()) {
                writeRequiresFile(id, reqContent)
            }
        }
        if (metadata.resources.isNotEmpty()) {
            val resContent = fetchResources(metadata.resources)
            if (resContent.isNotBlank() && resContent != "{}") {
                writeResourcesFile(id, resContent)
            }
        }
        reload()
        id
    }

    suspend fun updateScript(id: Long, code: String, sourceUrl: String? = null) = withContext(Dispatchers.IO) {
        val existing = userScriptDao.getById(id) ?: return@withContext
        val metadata = UserScriptMetadata.parse(code)
        val matchSummary = when {
            metadata.matches.isNotEmpty() -> metadata.matches.take(2).joinToString(", ")
            metadata.includes.isNotEmpty() -> metadata.includes.take(2).joinToString(", ")
            else -> "All sites"
        }
        val updated = existing.copy(
            name = metadata.name,
            sourceUrl = sourceUrl ?: existing.sourceUrl,
            version = metadata.version,
            description = metadata.description,
            author = metadata.author,
            matchSummary = matchSummary,
            runAt = when (metadata.runAt) {
                RunAt.DOCUMENT_START -> "document-start"
                RunAt.DOCUMENT_END -> "document-end"
                RunAt.DOCUMENT_IDLE -> "document-idle"
            }
        )
        userScriptDao.update(updated)
        writeCodeFile(id, code)
        if (metadata.requires.isNotEmpty()) {
            val reqContent = fetchRequires(metadata.requires)
            if (reqContent.isNotBlank()) {
                writeRequiresFile(id, reqContent)
            }
        }
        if (metadata.resources.isNotEmpty()) {
            val resContent = fetchResources(metadata.resources)
            if (resContent.isNotBlank() && resContent != "{}") {
                writeResourcesFile(id, resContent)
            }
        }
        reload()
    }

    suspend fun toggleScript(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        userScriptDao.updateEnabled(id, enabled)
        reload()
    }

    suspend fun deleteScript(id: Long) = withContext(Dispatchers.IO) {
        valueDao.deleteAllForScript(id)
        userScriptDao.deleteById(id)
        deleteCodeFile(id)
        deleteRequiresFile(id)
        deleteResourcesFile(id)
        reload()
    }

    suspend fun checkAndUpdate(id: Long): UpdateResult = withContext(Dispatchers.IO) {
        val parsed = getById(id) ?: return@withContext UpdateResult.Failed("Script not found")
        val meta = parsed.metadata
        val sourceUrl = parsed.script.sourceUrl.orEmpty()
        val checkUrl = meta.updateUrl.ifEmpty { meta.downloadUrl }.ifEmpty { sourceUrl }
        if (checkUrl.isBlank()) return@withContext UpdateResult.NoSource

        val checkBody = try {
            httpGet(checkUrl)
        } catch (e: Exception) {
            return@withContext UpdateResult.Failed(e.message ?: "Network error")
        } ?: return@withContext UpdateResult.Failed("Empty response from update server")

        val remoteMeta = UserScriptMetadata.parse(checkBody)
        if (!UserScriptMetadata.isNewer(remoteMeta.version, meta.version)) {
            return@withContext UpdateResult.UpToDate
        }

        // Prefer @downloadURL, then sourceUrl. Never fall back to checkUrl (which may be a
        // metadata-only .meta.js URL) — writing a metadata-only body would corrupt the script.
        val downloadUrl = meta.downloadUrl.ifEmpty { sourceUrl }
        if (downloadUrl.isBlank()) return@withContext UpdateResult.Failed("No @downloadURL or source URL to fetch updated code")

        val newCode = if (downloadUrl == checkUrl) {
            // The @updateURL and @downloadURL both point to the same full script — reuse fetched body
            checkBody
        } else {
            try {
                httpGet(downloadUrl)
            } catch (e: Exception) {
                return@withContext UpdateResult.Failed(e.message ?: "Download error")
            } ?: return@withContext UpdateResult.Failed("Empty script downloaded")
        }

        // Sanity-check: refuse to overwrite with a metadata-only body (no executable JS)
        val hasExecutableCode = newCode.lines().any { line ->
            val trimmed = line.trim()
            trimmed.isNotBlank() && !trimmed.startsWith("//") && !trimmed.startsWith("/*") && !trimmed.startsWith("*")
        }
        if (!hasExecutableCode) return@withContext UpdateResult.Failed("Downloaded file contains no executable code (possible .meta.js URL)")

        updateScript(id, newCode, sourceUrl = downloadUrl)
        UpdateResult.Updated(from = meta.version, to = remoteMeta.version)
    }

    suspend fun checkAndUpdateAll(): List<Pair<UserScriptItem, UpdateResult>> = withContext(Dispatchers.IO) {
        val currentScripts = userScriptDao.getAll()
        // Run all update checks in parallel — each is an independent network round-trip
        coroutineScope {
            currentScripts.map { item ->
                async { item to checkAndUpdate(item.id) }
            }.awaitAll()
        }
    }

    private fun httpGet(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
            .header("Accept", "*/*")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }

    // endregion

    // region GM values
    fun gmGetValue(scriptId: Long, key: String): String? = valueDao.getValue(scriptId, key)
    fun gmGetValue(scriptKey: String, key: String): String? {
        val id = scriptKey.removePrefix("us_").toLongOrNull() ?: return null
        return gmGetValue(id, key)
    }

    fun gmSetValue(scriptId: Long, key: String, value: String) {
        val oldValue = valueDao.getValue(scriptId, key)
        valueDao.setValue(UserScriptValueItem(scriptId, key, value))
        if (oldValue != value) {
            valueChangeListeners.forEach { runCatching { it.onValueChanged(scriptId, key, oldValue, value) } }
        }
    }
    fun gmSetValue(scriptKey: String, key: String, value: String) {
        val id = scriptKey.removePrefix("us_").toLongOrNull() ?: return
        gmSetValue(id, key, value)
    }

    fun gmDeleteValue(scriptId: Long, key: String) {
        val oldValue = valueDao.getValue(scriptId, key)
        valueDao.deleteValue(scriptId, key)
        if (oldValue != null) {
            valueChangeListeners.forEach { runCatching { it.onValueChanged(scriptId, key, oldValue, null) } }
        }
    }
    fun gmDeleteValue(scriptKey: String, key: String) {
        val id = scriptKey.removePrefix("us_").toLongOrNull() ?: return
        gmDeleteValue(id, key)
    }

    fun gmListValues(scriptId: Long): List<String> = valueDao.listKeys(scriptId)
    fun gmListValues(scriptKey: String): List<String> {
        val id = scriptKey.removePrefix("us_").toLongOrNull() ?: return emptyList()
        return gmListValues(id)
    }
    // endregion

    companion object {
        private const val TAG = "UserScriptManager"
        private val requireCache = ConcurrentHashMap<String, String>()

        private const val RUNTIME_BOOTSTRAP =
            "if(!window.__usRuntime){" +
            "window.__usRuntime={xhrCallbacks:{},valueListeners:{},menuCommands:{}};" +
            "window.__usXhrCallback=function(id,result,error){var c=window.__usRuntime.xhrCallbacks[id];if(c){delete window.__usRuntime.xhrCallbacks[id];c(result,error);}};" +
            "window.__usValueChanged=function(scriptKey,key,jsonValueOrNull){var list=window.__usRuntime.valueListeners[scriptKey];if(!list)return;" +
            "var nv=(jsonValueOrNull===null||jsonValueOrNull===undefined)?undefined:JSON.parse(jsonValueOrNull);" +
            "for(var k in list){if(list.hasOwnProperty(k)){try{list[k](key,undefined,nv,false);}catch(e){}}}};" +
            "};" +
            "if(!window.__onyxSpaHookInstalled){" +
            "window.__onyxSpaHookInstalled=true;" +
            "var _lastUrl=location.href;" +
            "function _notifyUrlChange(){" +
            "if(location.href!==_lastUrl){" +
            "_lastUrl=location.href;" +
            "try{" +
            "window.dispatchEvent(new CustomEvent('onyx:urlchange',{detail:{url:location.href}}));" +
            "window.dispatchEvent(new Event('yt-navigate-finish'));" +
            "window.dispatchEvent(new Event('yt-page-data-updated'));" +
            "window.dispatchEvent(new Event('popstate'));" +
            "window.dispatchEvent(new Event('hashchange'));" +
            "}catch(_){}" +
            "}" +
            "};" +
            "var _ps=history.pushState;" +
            "if(_ps){history.pushState=function(){var r=_ps.apply(this,arguments);_notifyUrlChange();return r;};};" +
            "var _rs=history.replaceState;" +
            "if(_rs){history.replaceState=function(){var r=_rs.apply(this,arguments);_notifyUrlChange();return r;};};" +
            "window.addEventListener('popstate',_notifyUrlChange);" +
            "window.addEventListener('hashchange',_notifyUrlChange);" +
            "}"

        @Volatile
        private var INSTANCE: UserScriptManager? = null

        fun getInstance(context: Context): UserScriptManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserScriptManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun isUserScriptUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            val path = uri.path?.lowercase().orEmpty()
            if (path.endsWith(".user.js")) return true
            val query = uri.query?.lowercase().orEmpty()
            if (query.contains(".user.js")) return true
            return false
        }
    }
}
