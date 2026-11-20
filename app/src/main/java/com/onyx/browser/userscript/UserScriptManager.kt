package com.onyx.browser.userscript

import com.onyx.browser.web.AssetScriptLoader
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

private const val WRAPPER_ASSET = "scripts/userscript_wrapper.js"
private val WRAPPER_PLACEHOLDER = Regex("""\{\{([A-Z_]+)\}\}""")

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

        val replacements = mapOf(
            "EXCLUDE_ARRAY" to excludeArray,
            "MATCH_ARRAY" to matchArray,
            "NO_FRAMES" to noFrames.toString(),
            "SCRIPT_ID" to scriptId.toString(),
            "SCRIPT_KEY" to JSONObject.quote(scriptKey),
            "SCRIPT_NAME" to scriptName,
            "RESOURCES" to resourcesObj,
            "NAMESPACE" to JSONObject.quote(meta.namespace),
            "VERSION" to JSONObject.quote(meta.version),
            "DESCRIPTION" to JSONObject.quote(meta.description),
            "AUTHOR" to JSONObject.quote(meta.author),
            "CONNECTS" to connectsArray,
            "GRANTS" to grantsArray,
            "RUN_AT" to JSONObject.quote(runAtStr),
            "UNWRAP" to unwrap.toString(),
            "IS_INCOGNITO" to isIncognito.toString(),
            "REQUIRE_BLOCK" to requireBlock,
            "CODE" to parsed.code
        )
        val template = AssetScriptLoader.loadScript(context, WRAPPER_ASSET)
        // Single pass so user code containing "{{...}}" is never re-substituted
        return WRAPPER_PLACEHOLDER.replace(template) { m -> replacements[m.groupValues[1]] ?: m.value }
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
        val results = coroutineScope {
            currentScripts.map { item ->
                async { item to checkAndUpdate(item.id) }
            }.awaitAll()
        }
        com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context).updates.userScriptLastUpdateCheckTime =
            System.currentTimeMillis()
        results
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
