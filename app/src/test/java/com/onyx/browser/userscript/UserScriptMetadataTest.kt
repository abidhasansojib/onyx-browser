package com.onyx.browser.userscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserScriptMetadataTest {

    @Test
    fun `parse extracts complete userscript header fields`() {
        val script = """
            // ==UserScript==
            // @name         Onyx Enhancer
            // @namespace    https://onyx.browser
            // @version      1.4.2
            // @description  Enhances page behavior
            // @author       Onyx Team
            // @match        https://*.example.com/*
            // @match        https://example.org/*
            // @include      http://legacy.example.com/*
            // @exclude      https://*.example.com/logout*
            // @exclude-match https://*.example.com/admin/*
            // @run-at       document-start
            // @grant        GM_getValue
            // @grant        GM.xmlHttpRequest
            // @connect      api.example.com
            // @require      https://cdn.example.com/lib.js
            // @updateURL    https://example.com/script.meta.js
            // @downloadURL  https://example.com/script.user.js
            // @noframes
            // ==/UserScript==
            (function() { console.log('hello'); })();
        """.trimIndent()

        val meta = UserScriptMetadata.parse(script)
        assertEquals("Onyx Enhancer", meta.name)
        assertEquals("https://onyx.browser", meta.namespace)
        assertEquals("1.4.2", meta.version)
        assertEquals("Enhances page behavior", meta.description)
        assertEquals("Onyx Team", meta.author)
        assertEquals(listOf("https://*.example.com/*", "https://example.org/*"), meta.matches)
        assertEquals(listOf("http://legacy.example.com/*"), meta.includes)
        assertEquals(
            listOf("https://*.example.com/logout*", "https://*.example.com/admin/*"),
            meta.excludes
        )
        assertEquals(RunAt.DOCUMENT_START, meta.runAt)
        assertEquals(listOf("GM_getValue", "GM.xmlHttpRequest"), meta.grants)
        assertEquals(listOf("api.example.com"), meta.connects)
        assertEquals(listOf("https://cdn.example.com/lib.js"), meta.requires)
        assertEquals("https://example.com/script.meta.js", meta.updateUrl)
        assertEquals("https://example.com/script.user.js", meta.downloadUrl)
        assertTrue(meta.noFrames)
        assertTrue(meta.allowsApi("getValue"))
        assertTrue(meta.allowsApi("xmlhttpRequest"))
        assertFalse(meta.allowsApi("setValue"))
        assertTrue(meta.requiresNativeBridge)
    }

    @Test
    fun `parse maps run-at variations accurately`() {
        val docStart = """
            // ==UserScript==
            // @name StartScript
            // @run-at document-start
            // ==/UserScript==
        """.trimIndent()
        val docBody = """
            // ==UserScript==
            // @name BodyScript
            // @run-at document-body
            // ==/UserScript==
        """.trimIndent()
        val docEnd = """
            // ==UserScript==
            // @name EndScript
            // @run-at document-end
            // ==/UserScript==
        """.trimIndent()
        val docIdle = """
            // ==UserScript==
            // @name IdleScript
            // @run-at document-idle
            // ==/UserScript==
        """.trimIndent()

        assertEquals(RunAt.DOCUMENT_START, UserScriptMetadata.parse(docStart).runAt)
        assertEquals(RunAt.DOCUMENT_START, UserScriptMetadata.parse(docBody).runAt)
        assertEquals(RunAt.DOCUMENT_END, UserScriptMetadata.parse(docEnd).runAt)
        assertEquals(RunAt.DOCUMENT_END, UserScriptMetadata.parse(docIdle).runAt)
    }

    @Test
    fun `parse handles missing metadata block with defaults`() {
        val rawJs = "console.log('no header');"
        val meta = UserScriptMetadata.parse(rawJs)

        assertEquals(UserScriptMetadata.DEFAULT_NAME, meta.name)
        assertEquals("", meta.namespace)
        assertEquals("", meta.version)
        assertEquals("", meta.description)
        assertEquals("", meta.author)
        assertTrue(meta.matches.isEmpty())
        assertTrue(meta.includes.isEmpty())
        assertTrue(meta.excludes.isEmpty())
        assertTrue(meta.grants.isEmpty())
        assertTrue(meta.connects.isEmpty())
        assertTrue(meta.requires.isEmpty())
        assertEquals(RunAt.DOCUMENT_END, meta.runAt)
        assertFalse(meta.noFrames)
        assertEquals("", meta.updateUrl)
        assertEquals("", meta.downloadUrl)
        assertFalse(meta.requiresNativeBridge)
    }

    @Test
    fun `grant none disables native bridge capabilities`() {
        val script = """
            // ==UserScript==
            // @name NoneGrant
            // @grant none
            // @grant GM_getValue
            // ==/UserScript==
        """.trimIndent()

        val meta = UserScriptMetadata.parse(script)
        assertFalse(meta.allowsApi("getValue"))
        assertFalse(meta.requiresNativeBridge)
    }

    @Test
    fun `isNewer compares semantic versions accurately`() {
        assertTrue(UserScriptMetadata.isNewer("1.2.0", "1.1.9"))
        assertTrue(UserScriptMetadata.isNewer("2.0", "1.9.9"))
        assertTrue(UserScriptMetadata.isNewer("1.0.0", ""))
        assertFalse(UserScriptMetadata.isNewer("", "1.0.0"))
        assertFalse(UserScriptMetadata.isNewer("1.0.0", "1.0.0"))
        assertFalse(UserScriptMetadata.isNewer("1.0.0-beta", "1.0.0"))
    }
}
