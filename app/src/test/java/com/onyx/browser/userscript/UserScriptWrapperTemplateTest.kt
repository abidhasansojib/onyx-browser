package com.onyx.browser.userscript

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the wrapper asset against drifting from the placeholders UserScriptManager fills in. */
class UserScriptWrapperTemplateTest {

    private val known = setOf(
        "EXCLUDE_ARRAY", "MATCH_ARRAY", "NO_FRAMES", "SCRIPT_ID", "SCRIPT_KEY", "SCRIPT_NAME",
        "RESOURCES", "NAMESPACE", "VERSION", "DESCRIPTION", "AUTHOR", "CONNECTS", "GRANTS",
        "RUN_AT", "UNWRAP", "INJECT_INTO", "SANDBOX", "SANDBOX_MODE", "IS_ISOLATED",
        "IS_INCOGNITO", "REQUIRE_BLOCK", "CODE"
    )

    private fun template(): String {
        val candidates = listOf(
            File("src/main/assets/scripts/userscript_wrapper.js"),
            File("app/src/main/assets/scripts/userscript_wrapper.js")
        )
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun onlyKnownPlaceholdersAreUsed() {
        val used = Regex("""\{\{([A-Z_]+)\}\}""").findAll(template()).map { it.groupValues[1] }.toSet()
        assertTrue("Unknown placeholders: ${used - known}", known.containsAll(used))
    }

    @Test
    fun userCodeAndRequiresAreInjected() {
        val t = template()
        assertTrue(t.contains("{{CODE}}"))
        assertTrue(t.contains("{{REQUIRE_BLOCK}}"))
    }

    @Test
    fun batchValueAndTabApisArePresent() {
        val t = template()
        listOf("GM_getValues", "GM_setValues", "GM_deleteValues", "GM_getTab", "GM_saveTab", "GM_getTabs")
            .forEach { assertTrue("Missing $it", t.contains("function $it")) }
    }
}
