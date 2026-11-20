package com.onyx.browser.data

import com.onyx.browser.data.model.TabGroupItem
import com.onyx.browser.data.model.TabItem
import com.onyx.browser.data.preferences.BrowserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabGroupAndArchiveModelsTest {

    @Test
    fun `TabItem defaults to ungrouped and unarchived`() {
        val tab = TabItem(url = "https://onyx.dev", title = "Onyx")
        assertNull(tab.groupId)
        assertFalse(tab.isArchived)
        assertEquals(0L, tab.archivedAt)
    }

    @Test
    fun `TabItem supports group assignment and archive state transitions`() {
        val now = 1_700_000_000_000L
        val tab = TabItem(
            id = "tab-1",
            url = "https://example.com",
            title = "Example",
            groupId = "group-reading",
            isArchived = true,
            archivedAt = now
        )
        assertEquals("group-reading", tab.groupId)
        assertTrue(tab.isArchived)
        assertEquals(now, tab.archivedAt)

        val restored = tab.copy(isArchived = false, archivedAt = 0L)
        assertFalse(restored.isArchived)
        assertEquals(0L, restored.archivedAt)
        assertEquals("group-reading", restored.groupId)
    }

    @Test
    fun `TabGroupItem provides standard palette and defaults`() {
        val group = TabGroupItem(title = "Research")
        assertEquals("Research", group.title)
        assertEquals(TabGroupItem.DEFAULT_COLOR_ORANGE, group.colorHex)
        assertFalse(group.isCollapsed)
        assertEquals(8, TabGroupItem.GROUP_COLORS.size)
        assertTrue(TabGroupItem.GROUP_COLORS.contains(TabGroupItem.COLOR_BLUE))
        assertTrue(TabGroupItem.GROUP_COLORS.contains(TabGroupItem.COLOR_GREEN))
    }

    @Test
    fun `Auto-archive threshold constants correspond to expected hours`() {
        assertEquals(0, BrowserPreferences.TAB_ARCHIVE_NEVER)
        assertEquals(24, BrowserPreferences.TAB_ARCHIVE_1_DAY)
        assertEquals(72, BrowserPreferences.TAB_ARCHIVE_3_DAYS)
        assertEquals(168, BrowserPreferences.TAB_ARCHIVE_7_DAYS)
        assertEquals(336, BrowserPreferences.TAB_ARCHIVE_14_DAYS)
        assertEquals(720, BrowserPreferences.TAB_ARCHIVE_30_DAYS)
    }
}
