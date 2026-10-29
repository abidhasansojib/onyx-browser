package com.onyx.browser.userscript

import android.content.Context
import android.util.Log
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.UserScriptItem
import com.onyx.browser.data.model.UserScriptValueItem
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
    val code: String
)

sealed class UpdateResult {
    data class Updated(val from: String, val to: String) : UpdateResult()
    object UpToDate : UpdateResult()
    object NoSource : UpdateResult()
    data class Failed(val message: String) : UpdateResult()
}

class UserScriptManager private constructor(private val context: Context) {

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
                ParsedUserScript(
                    script = row,
                    metadata = UserScriptMetadata.parse(code),
                    code = code
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse metadata for script ${row.name}", e)
                null
            }
        }

        loaded.forEach { resolveRequires(it) }
        scripts = loaded
        _hasNativeScripts.value = loaded.any { it.script.enabled && it.metadata.requiresNativeBridge }
    }

    private fun resolveRequires(parsed: ParsedUserScript) {
        if (parsed.metadata.requires.isEmpty()) return
        val sb = StringBuilder()
        parsed.metadata.requires.forEach { url ->
            val cached = requireCache[url]
            if (cached != null) {
                sb.append(cached).append('\n')
                return@forEach
            }
            try {
                val resp = httpClient.newCall(Request.Builder().url(url).build()).execute()
                val body = resp.body?.string().orEmpty()
                resp.close()
                requireCache[url] = body
                sb.append(body).append('\n')
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch @require $url", e)
            }
        }
        parsed.requiresContent = sb.toString()
    }

    fun getMatchingScripts(url: String, runAt: RunAt): List<ParsedUserScript> {
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
        val shimTemplate = loadShimAsset()
            .replace("__SCRIPT_ID__", parsed.script.id.toString())
            .replace("__GM_TOKEN__", JSONObject.quote(token))
            .replace("__GM_INFO__", buildGmInfo(parsed))

        return buildString {
            append("(function(){\nvar {")
            append("GM, GM_info, unsafeWindow, GM_getValue, GM_setValue, GM_deleteValue, ")
            append("GM_listValues, GM_addStyle, GM_addElement, GM_xmlhttpRequest, ")
            append("GM_registerMenuCommand, GM_unregisterMenuCommand, GM_openInTab, ")
            append("GM_setClipboard, GM_log, GM_notification")
            append("} = ").append(shimTemplate).append(";\n")
            if (parsed.requiresContent.isNotEmpty()) append(parsed.requiresContent).append('\n')
            append(parsed.code).append("\n}).call(window);\n")
            append("\n//# sourceURL=onyx-userscript-").append(parsed.script.id).append(".user.js\n")
        }
    }

    private fun loadShimAsset(): String {
        return try {
            context.assets.open("onyx_gm_shim.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load onyx_gm_shim.js asset", e)
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
            put("runAt", if (m.runAt == RunAt.DOCUMENT_START) "document-start" else "document-end")
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
            scripts.firstOrNull { it.script.name == metadata.name }?.script
        } else null

        if (existing != null) {
            val updated = existing.copy(
                name = metadata.name,
                sourceUrl = sourceUrl ?: existing.sourceUrl,
                version = metadata.version,
                description = metadata.description,
                author = metadata.author,
                matchSummary = matchSummary,
                runAt = if (metadata.runAt == RunAt.DOCUMENT_START) "document-start" else "document-end"
            )
            userScriptDao.update(updated)
            writeCodeFile(existing.id, code)
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
            runAt = if (metadata.runAt == RunAt.DOCUMENT_START) "document-start" else "document-end"
        )
        val id = userScriptDao.insert(newItem)
        writeCodeFile(id, code)
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
            runAt = if (metadata.runAt == RunAt.DOCUMENT_START) "document-start" else "document-end"
        )
        userScriptDao.update(updated)
        writeCodeFile(id, code)
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
        valueDao.setValue(UserScriptValueItem(scriptId, key, value))
    }

    fun gmDeleteValue(scriptId: Long, key: String) {
        valueDao.deleteValue(scriptId, key)
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
    }
}
