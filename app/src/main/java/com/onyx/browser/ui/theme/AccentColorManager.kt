package com.onyx.browser.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import kotlin.math.pow
import kotlin.math.sqrt

data class AccentPreset(
    val id: String,
    val name: String,
    val hex: String,
    @StyleRes val overlayResId: Int
)

object AccentColorManager {

    val PRESETS = listOf(
        AccentPreset(BrowserPreferences.ACCENT_PRESET_CRIMSON, "Crimson", "#DC4B64", R.style.ThemeOverlay_Onyx_Accent_Crimson),
        AccentPreset(BrowserPreferences.ACCENT_PRESET_ORANGE, "Orange", "#FB542B", R.style.ThemeOverlay_Onyx_Accent_Orange),
        AccentPreset(BrowserPreferences.ACCENT_PRESET_BLUE, "Blue", "#1A73E8", R.style.ThemeOverlay_Onyx_Accent_Blue),
        AccentPreset(BrowserPreferences.ACCENT_PRESET_GREEN, "Green", "#10B981", R.style.ThemeOverlay_Onyx_Accent_Green),
        AccentPreset(BrowserPreferences.ACCENT_PRESET_VIOLET, "Violet", "#8B5CF6", R.style.ThemeOverlay_Onyx_Accent_Violet),
        AccentPreset(BrowserPreferences.ACCENT_PRESET_AMBER, "Amber", "#F59E0B", R.style.ThemeOverlay_Onyx_Accent_Amber),
        AccentPreset(BrowserPreferences.ACCENT_PRESET_PINK, "Pink", "#EC4899", R.style.ThemeOverlay_Onyx_Accent_Pink),
        AccentPreset(BrowserPreferences.ACCENT_PRESET_SLATE, "Slate", "#64748B", R.style.ThemeOverlay_Onyx_Accent_Slate)
    )

    fun applyThemeOverlay(activity: Activity) {
        val prefs = BrowserPreferences.getInstance(activity)
        val overlayResId = getThemeOverlayRes(prefs)
        activity.theme.applyStyle(overlayResId, true)
    }

    @StyleRes
    fun getThemeOverlayRes(prefs: BrowserPreferences): Int {
        val presetId = prefs.accentColorPreset
        if (presetId != BrowserPreferences.ACCENT_PRESET_CUSTOM) {
            val preset = PRESETS.find { it.id.equals(presetId, ignoreCase = true) }
            if (preset != null) return preset.overlayResId
        }
        val customColor = getAccentColor(prefs)
        return findClosestPreset(customColor).overlayResId
    }

    @ColorInt
    fun getAccentColor(context: Context): Int {
        val prefs = BrowserPreferences.getInstance(context)
        return getAccentColor(prefs)
    }

    @ColorInt
    fun getAccentColor(prefs: BrowserPreferences): Int {
        val hex = prefs.accentColorHex
        return parseColorOrFallback(hex, BrowserPreferences.DEFAULT_ACCENT_HEX)
    }

    fun parseColorOrFallback(hex: String, fallback: String = BrowserPreferences.DEFAULT_ACCENT_HEX): Int {
        return try {
            val formatted = if (hex.startsWith("#")) hex else "#$hex"
            Color.parseColor(formatted)
        } catch (_: Exception) {
            Color.parseColor(fallback)
        }
    }

    fun getAccentColorStateList(context: Context): ColorStateList {
        return ColorStateList.valueOf(getAccentColor(context))
    }

    fun isValidHex(hex: String): Boolean {
        val cleaned = hex.trim().removePrefix("#")
        return cleaned.length == 6 && cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    fun getAccentName(prefs: BrowserPreferences): String {
        val presetId = prefs.accentColorPreset
        if (presetId == BrowserPreferences.ACCENT_PRESET_CUSTOM) {
            return prefs.accentColorHex
        }
        val preset = PRESETS.find { it.id.equals(presetId, ignoreCase = true) }
        return preset?.name ?: prefs.accentColorHex
    }

    private fun findClosestPreset(@ColorInt color: Int): AccentPreset {
        var minDistance = Double.MAX_VALUE
        var closest = PRESETS[0]

        val r1 = Color.red(color)
        val g1 = Color.green(color)
        val b1 = Color.blue(color)

        for (preset in PRESETS) {
            val presetColor = Color.parseColor(preset.hex)
            val r2 = Color.red(presetColor)
            val g2 = Color.green(presetColor)
            val b2 = Color.blue(presetColor)

            val distance = sqrt(
                (r1 - r2).toDouble().pow(2) +
                (g1 - g2).toDouble().pow(2) +
                (b1 - b2).toDouble().pow(2)
            )

            if (distance < minDistance) {
                minDistance = distance
                closest = preset
            }
        }
        return closest
    }
}
