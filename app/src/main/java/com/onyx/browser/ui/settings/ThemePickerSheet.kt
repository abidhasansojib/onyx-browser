package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.BottomSheetThemePickerBinding

class ThemePickerSheet(
    private val onAppearanceChanged: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    // Secondary constructor for compatibility
    constructor(currentTheme: Int, onThemeSelected: (Int) -> Unit) : this({
        onThemeSelected(currentTheme)
    })

    private var _binding: BottomSheetThemePickerBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: BrowserPreferences
    private var selectedThemeMode: Int = BrowserPreferences.THEME_SYSTEM
    private var initialThemeMode: Int = BrowserPreferences.THEME_SYSTEM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_OnyxBrowser_BottomSheetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetThemePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferences = BrowserPreferences.getInstance(requireContext())

        selectedThemeMode = preferences.themeMode
        initialThemeMode = selectedThemeMode

        updateThemeSelection(selectedThemeMode)

        // Theme Mode Clicks — automatically confirm, apply, and dismiss
        binding.optionSystem.setOnClickListener {
            selectThemeMode(BrowserPreferences.THEME_SYSTEM)
        }

        binding.optionDark.setOnClickListener {
            selectThemeMode(BrowserPreferences.THEME_DARK)
        }

        binding.optionLight.setOnClickListener {
            selectThemeMode(BrowserPreferences.THEME_LIGHT)
        }

        binding.btnDismiss.setOnClickListener {
            dismiss()
        }
    }

    private fun selectThemeMode(mode: Int) {
        selectedThemeMode = mode
        updateThemeSelection(mode)
        if (mode != initialThemeMode) {
            preferences.themeMode = mode
            preferences.applyTheme()
            onAppearanceChanged?.invoke()
        }
        dismiss()
    }

    private fun updateThemeSelection(selectedTheme: Int) {
        val defaultStroke = ContextCompat.getColor(requireContext(), R.color.settings_card_stroke)
        val defaultIcon = ContextCompat.getColor(requireContext(), R.color.settings_title_text)
        val activeColor = ContextCompat.getColor(requireContext(), R.color.primary)

        val activeStrokeWidth = dpToPx(2f)
        val defaultStrokeWidth = dpToPx(1f)

        // System
        val isSystem = selectedTheme == BrowserPreferences.THEME_SYSTEM
        binding.ivCheckSystem.visibility = if (isSystem) View.VISIBLE else View.INVISIBLE
        binding.ivCheckSystem.setColorFilter(activeColor)
        binding.optionSystem.strokeColor = if (isSystem) activeColor else defaultStroke
        binding.optionSystem.strokeWidth = if (isSystem) activeStrokeWidth else defaultStrokeWidth
        binding.ivIconSystem.setColorFilter(if (isSystem) activeColor else defaultIcon)

        // Dark
        val isDark = selectedTheme == BrowserPreferences.THEME_DARK
        binding.ivCheckDark.visibility = if (isDark) View.VISIBLE else View.INVISIBLE
        binding.ivCheckDark.setColorFilter(activeColor)
        binding.optionDark.strokeColor = if (isDark) activeColor else defaultStroke
        binding.optionDark.strokeWidth = if (isDark) activeStrokeWidth else defaultStrokeWidth
        binding.ivIconDark.setColorFilter(if (isDark) activeColor else defaultIcon)

        // Light
        val isLight = selectedTheme == BrowserPreferences.THEME_LIGHT
        binding.ivCheckLight.visibility = if (isLight) View.VISIBLE else View.INVISIBLE
        binding.ivCheckLight.setColorFilter(activeColor)
        binding.optionLight.strokeColor = if (isLight) activeColor else defaultStroke
        binding.optionLight.strokeWidth = if (isLight) activeStrokeWidth else defaultStrokeWidth
        binding.ivIconLight.setColorFilter(if (isLight) activeColor else defaultIcon)
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ThemePickerSheet"
    }
}
