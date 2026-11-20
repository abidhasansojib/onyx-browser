package com.onyx.browser.userscript

import com.onyx.browser.ui.settings.userscript.UserScriptSyntaxHighlighter
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UserScriptSyntaxHighlighterTest {

    @Test
    fun `theme colors provide distinct palette for dark and light modes`() {
        val dark = UserScriptSyntaxHighlighter.getThemeColors(isDark = true)
        val light = UserScriptSyntaxHighlighter.getThemeColors(isDark = false)

        assertNotNull(dark)
        assertNotNull(light)

        // Verify distinct syntax categories
        assertNotEquals(dark.keyword, dark.string)
        assertNotEquals(dark.comment, dark.metadata)
        assertNotEquals(dark.gmApi, dark.number)

        assertNotEquals(light.keyword, light.string)
        assertNotEquals(light.comment, light.metadata)
        assertNotEquals(light.gmApi, light.number)

        // Verify dark and light themes have different contrast colors
        assertNotEquals(dark.keyword, light.keyword)
        assertNotEquals(dark.string, light.string)
        assertNotEquals(dark.metadata, light.metadata)
    }
}
