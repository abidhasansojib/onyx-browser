package com.onyx.browser.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun newerPatchMinorMajorAreDetected() {
        assertTrue(AppUpdateManager.isUpdateAvailable("1.0.5", "v1.0.6"))
        assertTrue(AppUpdateManager.isUpdateAvailable("1.0.99", "v1.1.0"))
        assertTrue(AppUpdateManager.isUpdateAvailable("1.9.99", "v2.0.0"))
    }

    @Test
    fun numericComparisonIsNotLexicographic() {
        assertTrue(AppUpdateManager.isUpdateAvailable("1.2.0", "v1.10.0"))
        assertFalse(AppUpdateManager.isUpdateAvailable("1.0.10", "v1.0.9"))
    }

    @Test
    fun sameOrOlderVersionIsNotAnUpdate() {
        assertFalse(AppUpdateManager.isUpdateAvailable("1.0.6", "v1.0.6"))
        assertFalse(AppUpdateManager.isUpdateAvailable("1.0.7", "v1.0.6"))
        assertFalse(AppUpdateManager.isUpdateAvailable("v1.0.6", "1.0.6"))
    }

    @Test
    fun stableBeatsPrereleaseOfSameBaseVersion() {
        assertTrue(AppUpdateManager.isUpdateAvailable("1.0.5-prerelease", "v1.0.5"))
        assertFalse(AppUpdateManager.isUpdateAvailable("1.0.5", "v1.0.5-prerelease"))
    }

    @Test
    fun prereleaseChannelComparesBaseVersions() {
        assertTrue(AppUpdateManager.isUpdateAvailable("1.0.5-prerelease", "v1.0.6-prerelease"))
        assertFalse(AppUpdateManager.isUpdateAvailable("1.0.6-prerelease", "v1.0.5-prerelease"))
        assertFalse(AppUpdateManager.isUpdateAvailable("1.0.5-prerelease", "v1.0.5-prerelease"))
    }

    @Test
    fun prereleaseTagDetection() {
        assertTrue(AppUpdateManager.isPrereleaseTag("v1.0.5-prerelease"))
        assertTrue(AppUpdateManager.isPrereleaseTag("v1.0.5-beta"))
        assertTrue(AppUpdateManager.isPrereleaseTag("v1.0.5-rc1"))
        assertFalse(AppUpdateManager.isPrereleaseTag("v1.0.5"))
    }

    @Test
    fun defaultAssetNamesFollowCiNaming() {
        val stable = AppUpdateManager.buildDefaultAssetsForTag("v1.0.5", isPrerelease = false)
        assertTrue(stable.any { it.name == "Onyx-Browser-v1.0.5-arm64-v8a-release.apk" })
        val pre = AppUpdateManager.buildDefaultAssetsForTag("v1.0.5-prerelease", isPrerelease = true)
        assertTrue(pre.all { it.name.endsWith("-prerelease.apk") })
        assertTrue(pre.all { it.downloadUrl.startsWith("https://github.com/") })
    }

    @Test
    fun atomFeedEntriesAreParsedInOrder() {
        val xml = """
            <feed>
              <entry>
                <link href="https://github.com/o/r/releases/tag/v1.2.3"/>
                <title>Onyx v1.2.3</title>
                <content type="html">&lt;p&gt;Hi&lt;/p&gt;</content>
              </entry>
              <entry>
                <link href="https://github.com/o/r/releases/tag/v1.2.2-prerelease"/>
                <title>Onyx v1.2.2-prerelease</title>
                <content type="html">notes</content>
              </entry>
            </feed>
        """.trimIndent()
        val entries = ReleaseFeedParser.parseAtomEntries(xml)
        assertEquals(listOf("v1.2.3", "v1.2.2-prerelease"), entries.map { it.tag })
        assertEquals("Onyx v1.2.3", entries[0].title)
        assertEquals("<p>Hi</p>", entries[0].contentHtml)
    }
}
