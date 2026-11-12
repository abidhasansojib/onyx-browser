package com.onyx.browser.data

import com.onyx.browser.data.model.SitePermissionEntity
import com.onyx.browser.data.model.SitePermissionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePermissionEntityTest {

    @Test
    fun `SitePermissionType enum includes all core browser and shield permission categories`() {
        val types = SitePermissionType.values().map { it.name }.toSet()

        val expectedCategories = setOf(
            "CAMERA",
            "MICROPHONE",
            "LOCATION",
            "NOTIFICATIONS",
            "JAVASCRIPT",
            "POPUPS",
            "ADS",
            "CLIPBOARD",
            "AUTOPLAY",
            "SOUND",
            "WEBRTC",
            "IMAGES",
            "ZOOM",
            "MIXED_CONTENT",
            "GOOGLE_SIGNIN",
            "AUTO_SHRED",
            "DESKTOP_MODE"
        )

        assertTrue(types.containsAll(expectedCategories))
        for (name in expectedCategories) {
            assertEquals(name, SitePermissionType.valueOf(name).name)
        }
    }

    @Test
    fun `SitePermissionEntity construction, copy, and equality behave deterministically`() {
        val timestamp = 1_700_000_000_000L
        val entity1 = SitePermissionEntity(
            domain = "example.com",
            permissionType = SitePermissionType.JAVASCRIPT.name,
            state = "BLOCKED",
            updatedAt = timestamp
        )
        val entity2 = SitePermissionEntity(
            domain = "example.com",
            permissionType = "JAVASCRIPT",
            state = "BLOCKED",
            updatedAt = timestamp
        )

        assertEquals(entity1, entity2)
        assertEquals(entity1.hashCode(), entity2.hashCode())
        assertEquals("example.com", entity1.domain)
        assertEquals("JAVASCRIPT", entity1.permissionType)
        assertEquals("BLOCKED", entity1.state)
        assertEquals(timestamp, entity1.updatedAt)

        val updated = entity1.copy(state = "ALLOWED", updatedAt = timestamp + 5000L)
        assertNotEquals(entity1, updated)
        assertEquals("ALLOWED", updated.state)
    }
}
