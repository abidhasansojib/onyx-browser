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

        // Asynchronously resolve and cache any missing requires or resources on disk
        loaded.forEach { parsed ->
            if (parsed.metadata.requires.isNotEmpty() && parsed.requiresContent.isBlank()) {
                scope.launch {
                    resolveRequires(parsed)
                }
            }
            if (parsed.metadata.resources.isNotEmpty() && (parsed.resourcesContent.isBlank() || parsed.resourcesContent == "{}")) {
                scope.launch {
                    resolveResources(parsed)
                }
            }
        }
    }

    private fun fetchRequires(requires: List<String>): String {
        if (requires.isEmpty()) return ""
        val sb = StringBuilder()
        requires.forEach { url ->
            val cached = requireCache[url]
            if (cached != null) {
                sb.append(cached).append('\n')
                return@forEach
            }
            try {
                val resp = httpClient.newCall(Request.Builder().url(url).build()).execute()
                val body = resp.body?.string().orEmpty()
                resp.close()
                if (body.isNotBlank()) {
                    requireCache[url] = body
                    sb.append(body).append('\n')
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch @require $url", e)
            }
        }
        return sb.toString()
    }

    private fun resolveRequires(parsed: ParsedUserScript) {
        val result = fetchRequires(parsed.metadata.requires)
        parsed.requiresContent = result
        if (result.isNotBlank()) {
            writeRequiresFile(parsed.script.id, result)
        }
    }

    private fun resolveResources(parsed: ParsedUserScript) {
        if (parsed.metadata.resources.isEmpty()) return
        val resMap = JSONObject()
        parsed.metadata.resources.forEach { res ->
            try {
                val resp = httpClient.newCall(Request.Builder().url(res.url).build()).execute()
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
        val json = resMap.toString()
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

    fun buildInjectionJs(parsed: ParsedUserScript, token: String): String {
        val isIsolated = parsed.metadata.isIsolatedWorld

        return if (isIsolated) {
            val shimTemplate = loadShimAsset()
                .replace("__SCRIPT_ID__", parsed.script.id.toString())
                .replace("__GM_TOKEN__", JSONObject.quote(token))
                .replace("__GM_INFO__", buildGmInfo(parsed))
                .replace("__GM_RESOURCES__", if (parsed.resourcesContent.isNotBlank()) parsed.resourcesContent else "{}")

            buildString {
                append("(function(){\n")
                if (parsed.metadata.noFrames) {
                    append("if (window.self !== window.top) return;\n")
                }
                append("var unsafeWindow = window;\n")
                append("var {")
                append("GM, GM_info, GM_getValue, GM_setValue, GM_deleteValue, ")
                append("GM_listValues, GM_addStyle, GM_addElement, GM_xmlhttpRequest, ")
                append("GM_registerMenuCommand, GM_unregisterMenuCommand, GM_openInTab, ")
                append("GM_setClipboard, GM_log, GM_notification, GM_download, ")
                append("GM_getResourceText, GM_getResourceURL, GM_addValueChangeListener, GM_removeValueChangeListener")
                append("} = ").append(shimTemplate).append(";\n")
                append("var _scope = Object.create(unsafeWindow);\n")
                append("_scope.unsafeWindow = unsafeWindow;\n")
                append("_scope.window = _scope;\n")
                append("_scope.self = _scope;\n")
                append("_scope.globalThis = _scope;\n")
                append("_scope.GM = GM;\n")
                append("_scope.GM_info = GM_info;\n")
                append("_scope.GM_getValue = GM_getValue;\n")
                append("_scope.GM_setValue = GM_setValue;\n")
                append("_scope.GM_deleteValue = GM_deleteValue;\n")
                append("_scope.GM_listValues = GM_listValues;\n")
                append("_scope.GM_addStyle = GM_addStyle;\n")
                append("_scope.GM_addElement = GM_addElement;\n")
                append("_scope.GM_xmlhttpRequest = GM_xmlhttpRequest;\n")
                append("_scope.GM_download = GM_download;\n")
                append("_scope.GM_registerMenuCommand = GM_registerMenuCommand;\n")
                append("_scope.GM_unregisterMenuCommand = GM_unregisterMenuCommand;\n")
                append("_scope.GM_openInTab = GM_openInTab;\n")
                append("_scope.GM_setClipboard = GM_setClipboard;\n")
                append("_scope.GM_log = GM_log;\n")
                append("_scope.GM_notification = GM_notification;\n")
                append("_scope.GM_getResourceText = GM_getResourceText;\n")
                append("_scope.GM_getResourceURL = GM_getResourceURL;\n")
                append("_scope.GM_addValueChangeListener = GM_addValueChangeListener;\n")
                append("_scope.GM_removeValueChangeListener = GM_removeValueChangeListener;\n")
                append("(function(GM, GM_info, unsafeWindow, GM_getValue, GM_setValue, GM_deleteValue, GM_listValues, GM_addStyle, GM_addElement, GM_xmlhttpRequest, GM_registerMenuCommand, GM_unregisterMenuCommand, GM_openInTab, GM_setClipboard, GM_log, GM_notification, GM_download, GM_getResourceText, GM_getResourceURL, GM_addValueChangeListener, GM_removeValueChangeListener){\n")
                if (parsed.requiresContent.isNotEmpty()) append(parsed.requiresContent).append('\n')
                append("""
                try {
                    for (var __k in _scope) {
                        if (Object.prototype.hasOwnProperty.call(_scope, __k)) {
                            try { if (!(__k in unsafeWindow)) unsafeWindow[__k] = _scope[__k]; } catch (_) {}
                            try { if (!(__k in window)) window[__k] = _scope[__k]; } catch (_) {}
                        }
                    }
                } catch (_) {}
                """.trimIndent()).append('\n')
                append("var _t0 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();\n")
                append(parsed.code).append("\n")
                append("var _t1 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();\n")
                append("if (_t1 - _t0 > 500) { console.warn('Onyx: Userscript took ' + Math.round(_t1 - _t0) + 'ms to execute'); }\n")
                append("}).call(_scope, GM, GM_info, unsafeWindow, GM_getValue, GM_setValue, GM_deleteValue, GM_listValues, GM_addStyle, GM_addElement, GM_xmlhttpRequest, GM_registerMenuCommand, GM_unregisterMenuCommand, GM_openInTab, GM_setClipboard, GM_log, GM_notification, GM_download, GM_getResourceText, GM_getResourceURL, GM_addValueChangeListener, GM_removeValueChangeListener);\n")
                append("})();\n")
                append("\n//# sourceURL=onyx-userscript-").append(parsed.script.id).append(".user.js\n")
            }
        } else {
            // Unprivileged / Page-World script (@grant none or @unwrap):
            // Runs directly in the host page's context for 100% compatibility with site internals (e.g. YouTube ytplayer/ytcfg).
            val gmInfoJson = buildGmInfo(parsed)
            buildString {
                append("(function(){\n")
                if (parsed.metadata.noFrames) {
                    append("if (window.self !== window.top) return;\n")
                }
                append("var unsafeWindow = window;\n")
                append("var GM_info = ").append(gmInfoJson).append(";\n")
                append("var GM = { info: GM_info };\n")
                append("(function(GM, GM_info, unsafeWindow){\n")
                if (parsed.requiresContent.isNotEmpty()) append(parsed.requiresContent).append('\n')
                append("var _t0 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();\n")
                append(parsed.code).append("\n")
                append("var _t1 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();\n")
                append("if (_t1 - _t0 > 500) { console.warn('Onyx: Userscript took ' + Math.round(_t1 - _t0) + 'ms to execute'); }\n")
                append("}).call(window, GM, GM_info, unsafeWindow);\n")
                append("})();\n")
                append("\n//# sourceURL=onyx-userscript-").append(parsed.script.id).append(".user.js\n")
            }
        }
    }

    private fun loadShimAsset(): String {
        return try {
            try {
                context.assets.open("userscript_gm_shim.js").bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                context.assets.open("userscript_gm_shim.js").bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load userscript_gm_shim.js asset", e)
            ""
        }
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

        val downloadUrl = meta.downloadUrl.ifEmpty { sourceUrl }.ifEmpty { checkUrl }
        val newCode = if (downloadUrl == checkUrl) {
            checkBody
        } else {
            try {
                httpGet(downloadUrl)
            } catch (e: Exception) {
                return@withContext UpdateResult.Failed(e.message ?: "Download error")
            } ?: return@withContext UpdateResult.Failed("Empty script downloaded")
        }

        updateScript(id, newCode, sourceUrl = downloadUrl)
        UpdateResult.Updated(from = meta.version, to = remoteMeta.version)
    }

    suspend fun checkAndUpdateAll(): List<Pair<UserScriptItem, UpdateResult>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<UserScriptItem, UpdateResult>>()
        val currentScripts = userScriptDao.getAll()
        for (item in currentScripts) {
            val res = checkAndUpdate(item.id)
            results.add(item to res)
        }
        results
    }

    private fun httpGet(url: String): String? {
        val request = Request.Builder().url(url).build()
        return httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }

    // endregion

    // region GM values
    fun gmGetValue(scriptId: Long, key: String): String? = valueDao.getValue(scriptId, key)

    fun gmSetValue(scriptId: Long, key: String, value: String) {
        val oldValue = valueDao.getValue(scriptId, key)
        valueDao.setValue(UserScriptValueItem(scriptId, key, value))
        if (oldValue != value) {
            valueChangeListeners.forEach { runCatching { it.onValueChanged(scriptId, key, oldValue, value) } }
        }
    }

    fun gmDeleteValue(scriptId: Long, key: String) {
        val oldValue = valueDao.getValue(scriptId, key)
        valueDao.deleteValue(scriptId, key)
        if (oldValue != null) {
            valueChangeListeners.forEach { runCatching { it.onValueChanged(scriptId, key, oldValue, null) } }
        }
    }

    fun gmListValues(scriptId: Long): List<String> = valueDao.listKeys(scriptId)
    // endregion

    companion object {
        private const val TAG = "UserScriptManager"
        private val requireCache = ConcurrentHashMap<String, String>()

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
