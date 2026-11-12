package com.onyx.browser.ui.settings

import android.content.Context
import android.content.Intent

enum class SettingsDestination {
    PRIVACY_AND_SECURITY,
    SHIELDS,
    SITE_SETTINGS,
    APPEARANCE,
    ADDRESS_BAR,
    AUTOFILL,
    ACCESSIBILITY,
    DOWNLOADS,
    CONTENT_FILTERS,
    SEARCH_ENGINE,
    USER_SCRIPTS,
    VIDEO_PLAYBACK,
    ABOUT
}

object SettingsNavigator {

    fun navigate(context: Context, destination: SettingsDestination) {
        val targetClass = when (destination) {
            SettingsDestination.PRIVACY_AND_SECURITY,
            SettingsDestination.SHIELDS -> ShieldsActivity::class.java
            SettingsDestination.SITE_SETTINGS -> SiteSettingsActivity::class.java
            SettingsDestination.APPEARANCE -> AppearanceActivity::class.java
            SettingsDestination.ADDRESS_BAR -> AddressBarSettingsActivity::class.java
            SettingsDestination.AUTOFILL -> AutofillSettingsActivity::class.java
            SettingsDestination.ACCESSIBILITY -> AccessibilitySettingsActivity::class.java
            SettingsDestination.DOWNLOADS -> DownloadSettingsActivity::class.java
            SettingsDestination.CONTENT_FILTERS -> ContentFiltersActivity::class.java
            SettingsDestination.SEARCH_ENGINE -> SearchEngineSettingsActivity::class.java
            SettingsDestination.USER_SCRIPTS -> UserScriptsActivity::class.java
            SettingsDestination.VIDEO_PLAYBACK -> VideoSettingsActivity::class.java
            SettingsDestination.ABOUT -> AboutActivity::class.java
        }
        context.startActivity(Intent(context, targetClass))
    }
}
