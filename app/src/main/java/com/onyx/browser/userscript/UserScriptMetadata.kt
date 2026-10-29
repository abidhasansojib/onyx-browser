package com.onyx.browser.userscript

enum class RunAt {
    DOCUMENT_START,
    DOCUMENT_END
}

/**
 * Parsed `// ==UserScript== ... // ==/UserScript==` metadata block for Tampermonkey/Violentmonkey scripts.
 */
data class UserScriptMetadata(
    val name: String,
    val namespace: String,
    val version: String,
    val description: String,
    val author: String,
    val matches: List<String>,
    val includes: List<String>,
    val excludes: List<String>,
    val grants: List<String>,
    val requires: List<String>,
    val connects: List<String>,
    val runAt: RunAt,
    val noFrames: Boolean,
    val updateUrl: String,
    val downloadUrl: String
) {
    /** Explicit grants only; `none` (or omitted grants) gives no native capability. */
    fun allowsApi(api: String): Boolean = "none" !in grants && grants.any {
        it == "GM_$api" || it == "GM.$api" ||
                (api == "xmlhttpRequest" && (it == "GM.xmlHttpRequest" || it == "GM_xmlhttpRequest"))
    }

    val requiresNativeBridge: Boolean
        get() = NATIVE_APIS.any(::allowsApi)

    companion object {
        private val NATIVE_APIS = listOf(
            "getValue", "setValue", "deleteValue", "listValues", "xmlhttpRequest",
            "registerMenuCommand", "unregisterMenuCommand", "openInTab", "setClipboard",
            "notification", "log"
        )

        const val DEFAULT_NAME = "Unnamed script"

        private val BLOCK_REGEX =
            Regex("""//\s*==UserScript==\s*\n(.*?)//\s*==/UserScript==""", RegexOption.DOT_MATCHES_ALL)
        private val LINE_REGEX = Regex("""//\s*@([\w-]+)\s+(.*)""")

        fun parse(code: String): UserScriptMetadata {
            val block = BLOCK_REGEX.find(code)?.groupValues?.get(1).orEmpty()
            val tags = mutableMapOf<String, MutableList<String>>()
            block.lineSequence().forEach { line ->
                val m = LINE_REGEX.find(line) ?: return@forEach
                val key = m.groupValues[1].lowercase()
                val value = m.groupValues[2].trim()
                if (value.isNotEmpty()) tags.getOrPut(key) { mutableListOf() }.add(value)
            }

            fun first(vararg keys: String): String =
                keys.firstNotNullOfOrNull { tags[it]?.firstOrNull() }.orEmpty()

            fun all(vararg keys: String): List<String> =
                keys.flatMap { tags[it].orEmpty() }

            val runAt = when (first("run-at").lowercase()) {
                "document-start" -> RunAt.DOCUMENT_START
                "document-body" -> RunAt.DOCUMENT_START
                "document-idle" -> RunAt.DOCUMENT_END
                else -> RunAt.DOCUMENT_END
            }

            return UserScriptMetadata(
                name = first("name").ifEmpty { DEFAULT_NAME },
                namespace = first("namespace"),
                version = first("version"),
                description = first("description"),
                author = first("author"),
                matches = all("match"),
                includes = all("include"),
                excludes = all("exclude", "exclude-match"),
                grants = all("grant"),
                requires = all("require"),
                connects = all("connect"),
                runAt = runAt,
                noFrames = tags.containsKey("noframes"),
                updateUrl = first("updateurl"),
                downloadUrl = first("downloadurl")
            )
        }

        fun isNewer(remote: String, installed: String): Boolean {
            if (remote.isBlank()) return false
            if (installed.isBlank()) return true
            return compareVersions(remote, installed) > 0
        }

        private fun compareVersions(a: String, b: String): Int {
            val pa = a.trim().split('.')
            val pb = b.trim().split('.')
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val sa = pa.getOrElse(i) { "0" }
                val sb = pb.getOrElse(i) { "0" }
                val na = sa.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L
                val nb = sb.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L
                if (na != nb) return na.compareTo(nb)
                val ra = sa.dropWhile { it.isDigit() }
                val rb = sb.dropWhile { it.isDigit() }
                if (ra != rb) {
                    if (ra.isEmpty()) return 1
                    if (rb.isEmpty()) return -1
                    return ra.compareTo(rb)
                }
            }
            return 0
        }
    }
}
