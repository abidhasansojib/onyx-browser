package com.onyx.browser.userscript

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserScriptUrlMatcherTest {

    @Test
    fun `matches returns false when both match and include patterns are empty`() {
        assertFalse(
            UserScriptUrlMatcher.matches(
                url = "https://example.com/page",
                matchPatterns = emptyList(),
                includePatterns = emptyList()
            )
        )
    }

    @Test
    fun `match pattern wildcard all schemes and paths`() {
        val patterns = listOf("*://*/*")
        assertTrue(UserScriptUrlMatcher.matches("https://example.com/", patterns, emptyList()))
        assertTrue(UserScriptUrlMatcher.matches("http://sub.example.com/a/b/c", patterns, emptyList()))
        assertFalse(UserScriptUrlMatcher.matches("file:///sdcard/test.html", patterns, emptyList()))
    }

    @Test
    fun `match pattern all_urls and single asterisk match http and https`() {
        assertTrue(UserScriptUrlMatcher.matches("https://example.com/test", listOf("<all_urls>"), emptyList()))
        assertTrue(UserScriptUrlMatcher.matches("http://example.com/test", listOf("*"), emptyList()))
        assertFalse(UserScriptUrlMatcher.matches("about:blank", listOf("<all_urls>"), emptyList()))
    }

    @Test
    fun `match pattern subdomain wildcard matches root and subdomains`() {
        val patterns = listOf("https://*.example.com/path/*")
        assertTrue(UserScriptUrlMatcher.matches("https://example.com/path/index.html", patterns, emptyList()))
        assertTrue(UserScriptUrlMatcher.matches("https://sub.example.com/path/to/page", patterns, emptyList()))
        assertTrue(UserScriptUrlMatcher.matches("https://a.b.example.com/path/", patterns, emptyList()))
        assertFalse(UserScriptUrlMatcher.matches("http://sub.example.com/path/page", patterns, emptyList()))
        assertFalse(UserScriptUrlMatcher.matches("https://sub.example.com/other/page", patterns, emptyList()))
        assertFalse(UserScriptUrlMatcher.matches("https://notexample.com/path/page", patterns, emptyList()))
    }

    @Test
    fun `match pattern exact host and port support`() {
        val patterns = listOf("https://example.com/dashboard*")
        assertTrue(UserScriptUrlMatcher.matches("https://example.com/dashboard", patterns, emptyList()))
        assertTrue(UserScriptUrlMatcher.matches("https://example.com:8443/dashboard/settings", patterns, emptyList()))
        assertFalse(UserScriptUrlMatcher.matches("https://www.example.com/dashboard", patterns, emptyList()))
    }

    @Test
    fun `invalid match patterns return false gracefully`() {
        val invalidPatterns = listOf(
            "not-a-valid-pattern",
            "https://example.com", // missing path slash
            "://missing-scheme"
        )
        assertFalse(UserScriptUrlMatcher.matches("https://example.com/", invalidPatterns, emptyList()))
    }

    @Test
    fun `include pattern supports globs and regexes`() {
        val globIncludes = listOf("https://*.google.*/search*")
        assertTrue(UserScriptUrlMatcher.matches("https://www.google.com/search?q=onyx", emptyList(), globIncludes))
        assertFalse(UserScriptUrlMatcher.matches("https://www.google.com/maps", emptyList(), globIncludes))

        val regexIncludes = listOf("""/https:\/\/example\.com\/items\/\d+/""")
        assertTrue(UserScriptUrlMatcher.matches("https://example.com/items/42", emptyList(), regexIncludes))
        assertFalse(UserScriptUrlMatcher.matches("https://example.com/items/abc", emptyList(), regexIncludes))

        val brokenRegex = listOf("/[unclosed group/")
        assertFalse(UserScriptUrlMatcher.matches("https://example.com/", emptyList(), brokenRegex))
    }

    @Test
    fun `isExcluded evaluates both match and include regex or glob rules`() {
        val excludes = listOf(
            "https://*.example.com/admin/*",
            "*://login.example.com/*",
            """/https:\/\/example\.com\/private\/.*/"""
        )

        assertTrue(UserScriptUrlMatcher.isExcluded("https://sub.example.com/admin/users", excludes))
        assertTrue(UserScriptUrlMatcher.isExcluded("https://login.example.com/auth", excludes))
        assertTrue(UserScriptUrlMatcher.isExcluded("https://example.com/private/secret", excludes))
        assertFalse(UserScriptUrlMatcher.isExcluded("https://example.com/public/index.html", excludes))
    }
}
