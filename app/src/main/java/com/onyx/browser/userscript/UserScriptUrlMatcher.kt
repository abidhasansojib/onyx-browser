package com.onyx.browser.userscript

import java.util.concurrent.ConcurrentHashMap

/**
 * Converts Tampermonkey/Violentmonkey `@match` / `@include` patterns to regular expressions.
 */
object UserScriptUrlMatcher {

    private val matchCache = ConcurrentHashMap<String, Regex?>()
    private val includeCache = ConcurrentHashMap<String, Regex>()

    fun matches(url: String, matchPatterns: List<String>, includePatterns: List<String>): Boolean {
        if (matchPatterns.isEmpty() && includePatterns.isEmpty()) {
            return url.startsWith("http://") || url.startsWith("https://")
        }
        return matchPatterns.any { matchPattern(url, it) } ||
                includePatterns.any { includePattern(url, it) }
    }

    fun isExcluded(url: String, excludePatterns: List<String>): Boolean =
        excludePatterns.any { includePattern(url, it) || matchPattern(url, it) }

    private fun matchPattern(url: String, pattern: String): Boolean {
        if (pattern == "*" || pattern == "<all_urls>") return url.startsWith("http")
        val regex = matchCache.computeIfAbsent(pattern) { compileMatch(it) } ?: return false
        return regex.matches(url)
    }

    private fun includePattern(url: String, pattern: String): Boolean {
        if (pattern.length > 2 && pattern.startsWith("/") && pattern.endsWith("/")) {
            return try {
                Regex(pattern.substring(1, pattern.length - 1)).containsMatchIn(url)
            } catch (_: Exception) {
                false
            }
        }
        val regex = includeCache.computeIfAbsent(pattern) { globToRegex(it) }
        return regex.matches(url)
    }

    /** Chrome match pattern: <scheme>://<host><path> */
    private fun compileMatch(pattern: String): Regex? {
        val schemeSep = pattern.indexOf("://")
        if (schemeSep < 0) return null
        val scheme = pattern.substring(0, schemeSep)
        val rest = pattern.substring(schemeSep + 3)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val host = rest.substring(0, slash)
        val path = rest.substring(slash)

        val schemeRegex = when (scheme) {
            "*" -> "https?"
            else -> Regex.escape(scheme)
        }
        val hostRegex = when {
            host == "*" -> "[^/]+"
            host.startsWith("*.") -> "(?:[^/]+\\.)?" + Regex.escape(host.substring(2))
            else -> Regex.escape(host)
        }
        val pathRegex = globBody(path)
        return try {
            Regex("^$schemeRegex://$hostRegex(?::\\d+)?$pathRegex$")
        } catch (_: Exception) {
            null
        }
    }

    private fun globToRegex(pattern: String): Regex {
        val body = globBody(pattern)
        return try {
            Regex("^$body$")
        } catch (_: Exception) {
            Regex(Regex.escape(pattern))
        }
    }

    private fun globBody(glob: String): String =
        glob.split("*").joinToString(".*") { Regex.escape(it) }

    fun matchPatternToRegexSource(pattern: String): String? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == "<all_urls>" || trimmed == "*") return "^https?://.*$"
        val schemeSep = trimmed.indexOf("://")
        if (schemeSep < 0) return null
        val scheme = trimmed.substring(0, schemeSep)
        val rest = trimmed.substring(schemeSep + 3)
        val slash = rest.indexOf('/')
        val host = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "/*" else rest.substring(slash)

        val schemePattern = if (scheme == "*") "https?" else escapeJsRegex(scheme)
        val hostPattern = when {
            host == "*" -> "[^/]*"
            host.startsWith("*.") -> "(?:[^/]*\\.)?" + escapeJsRegex(host.removePrefix("*."))
            else -> escapeJsRegex(host)
        }
        val pathPattern = if (path.isEmpty()) "/.*" else path.split("*").joinToString(".*") { escapeJsRegex(it) }
        return "^$schemePattern://$hostPattern(?::\\d+)?$pathPattern$"
    }

    fun globToRegexSource(pattern: String): String {
        val trimmed = pattern.trim()
        if (trimmed.length >= 2 && trimmed.startsWith("/") && trimmed.endsWith("/")) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        return "^" + trimmed.split("*").joinToString(".*") { escapeJsRegex(it) } + "$"
    }

    private fun escapeJsRegex(s: String): String {
        val builder = StringBuilder()
        for (c in s) {
            if (c in ".+?^\${}()|[]/\\") {
                builder.append('\\')
            }
            builder.append(c)
        }
        return builder.toString()
    }
}
