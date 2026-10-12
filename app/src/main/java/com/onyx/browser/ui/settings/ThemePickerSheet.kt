package com.onyx.browser.ui.settings

import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.BottomSheetThemePickerBinding
import com.onyx.browser.ui.theme.AccentColorManager

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
    private var selectedPreset: String = BrowserPreferences.ACCENT_PRESET_CRIMSON
    private var selectedHex: String = BrowserPreferences.DEFAULT_ACCENT_HEX
    private var initialThemeMode: Int = BrowserPreferences.THEME_SYSTEM
    private var initialPreset: String = BrowserPreferences.ACCENT_PRESET_CRIMSON
    private var initialHex: String = BrowserPreferences.DEFAULT_ACCENT_HEX

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
        selectedPreset = preferences.accentColorPreset
        selectedHex = preferences.accentColorHex

        initialThemeMode = selectedThemeMode
        initialPreset = selectedPreset
        initialHex = selectedHex

        updateThemeSelection(selectedThemeMode)
        updateAccentSelection(selectedPreset, selectedHex)

        // Theme Mode Clicks
        binding.optionSystem.setOnClickListener {
            selectedThemeMode = BrowserPreferences.THEME_SYSTEM
            updateThemeSelection(selectedThemeMode)
        }

        binding.optionDark.setOnClickListener {
            selectedThemeMode = BrowserPreferences.THEME_DARK
            updateThemeSelection(selectedThemeMode)
        }

        binding.optionLight.setOnClickListener {
            selectedThemeMode = BrowserPreferences.THEME_LIGHT
            updateThemeSelection(selectedThemeMode)
        }

        // Accent Preset Clicks
        setupPresetClick(binding.btnSwatchCrimson, BrowserPreferences.ACCENT_PRESET_CRIMSON, "#DC4B64")
        setupPresetClick(binding.btnSwatchOrange, BrowserPreferences.ACCENT_PRESET_ORANGE, "#FB542B")
        setupPresetClick(binding.btnSwatchBlue, BrowserPreferences.ACCENT_PRESET_BLUE, "#1A73E8")
        setupPresetClick(binding.btnSwatchGreen, BrowserPreferences.ACCENT_PRESET_GREEN, "#10B981")
        setupPresetClick(binding.btnSwatchViolet, BrowserPreferences.ACCENT_PRESET_VIOLET, "#8B5CF6")
        setupPresetClick(binding.btnSwatchAmber, BrowserPreferences.ACCENT_PRESET_AMBER, "#F59E0B")
        setupPresetClick(binding.btnSwatchPink, BrowserPreferences.ACCENT_PRESET_PINK, "#EC4899")
        setupPresetClick(binding.btnSwatchSlate, BrowserPreferences.ACCENT_PRESET_SLATE, "#64748B")

        // Custom Hex Input
        val currentHexClean = selectedHex.removePrefix("#")
        binding.etCustomHex.setText(currentHexClean)
        updatePreviewColor(selectedHex)

        binding.etCustomHex.doAfterTextChanged { text ->
            binding.tvHexError.visibility = View.GONE
            val hexStr = text?.toString()?.trim() ?: ""
            if (AccentColorManager.isValidHex(hexStr)) {
                val hexFormatted = if (hexStr.startsWith("#")) hexStr else "#$hexStr"
                updatePreviewColor(hexFormatted)
            }
        }

        binding.btnApplyHex.setOnClickListener {
            val hexStr = binding.etCustomHex.text?.toString()?.trim() ?: ""
            if (AccentColorManager.isValidHex(hexStr)) {
                binding.tvHexError.visibility = View.GONE
                val hexFormatted = (if (hexStr.startsWith("#")) hexStr else "#$hexStr").uppercase()
                selectedPreset = BrowserPreferences.ACCENT_PRESET_CUSTOM
                selectedHex = hexFormatted
                updateAccentSelection(selectedPreset, selectedHex)
            } else {
                binding.tvHexError.visibility = View.VISIBLE
            }
        }

        binding.btnDone.setOnClickListener {
            saveChangesAndDismiss()
        }

        binding.btnDismiss.setOnClickListener {
            saveChangesAndDismiss()
        }
    }

    private fun setupPresetClick(view: View, presetId: String, hex: String) {
        view.setOnClickListener {
            selectedPreset = presetId
            selectedHex = hex
            binding.tvHexError.visibility = View.GONE
            updateAccentSelection(selectedPreset, selectedHex)
        }
    }

    private fun updateThemeSelection(selectedTheme: Int) {
        val activeColor = AccentColorManager.getAccentColor(requireContext())
        val defaultStroke = ContextCompat.getColor(requireContext(), R.color.settings_card_stroke)
        val defaultIcon = ContextCompat.getColor(requireContext(), R.color.settings_title_text)

        val activeStrokeWidth = dpToPx(2)
        val defaultStrokeWidth = dpToPx(1)

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

    private fun updateAccentSelection(preset: String, hex: String) {
        val isCustom = preset == BrowserPreferences.ACCENT_PRESET_CUSTOM

        // Swatch Checks
        binding.ivCheckCrimson.visibility = if (preset == BrowserPreferences.ACCENT_PRESET_CRIMSON) View.VISIBLE else View.INVISIBLE
        binding.ivCheckOrange.visibility = if (preset == BrowserPreferences.ACCENT_PRESET_ORANGE) View.VISIBLE else View.INVISIBLE
        binding.ivCheckBlue.visibility = if (preset == BrowserPreferences.ACCENT_PRESET_BLUE) View.VISIBLE else View.INVISIBLE
        binding.ivCheckGreen.visibility = if (preset == BrowserPreferences.ACCENT_PRESET_GREEN) View.VISIBLE else View.INVISIBLE
        binding.ivCheckViolet.visibility = if (preset == BrowserPreferences.ACCENT_PRESET_VIOLET) View.VISIBLE else View.INVISIBLE
        binding.ivCheckAmber.visibility = if (preset == BrowserPreferences.ACCENT_PRESET_AMBER) View.VISIBLE else View.INVISIBLE
        binding.ivCheckPink.visibility = if (preset == BrowserPreferences.ACCENT_PRESET_PINK) View.VISIBLE else View.INVISIBLE
        binding.ivCheckSlate.visibility = if (preset == BrowserPreferences.ACCENT_PRESET_SLATE) View.VISIBLE else View.INVISIBLE

        // Custom Card Highlight
        val defaultStroke = ContextCompat.getColor(requireContext(), R.color.settings_card_stroke)
        val activeColor = AccentColorManager.parseColorOrFallback(hex)
        binding.cardCustomHex.strokeColor = if (isCustom) activeColor else defaultStroke
        binding.cardCustomHex.strokeWidth = if (isCustom) dpToPx(2) else dpToPx(1)

        updatePreviewColor(hex)
        updateThemeSelection(selectedThemeMode)
    }

    private fun updatePreviewColor(hex: String) {
        val color = AccentColorManager.parseColorOrFallback(hex)
        binding.previewCustomColor.setCardBackgroundColor(color)
        binding.btnDone.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        binding.btnApplyHex.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun saveChangesAndDismiss() {
        val hasChanged = selectedThemeMode != initialThemeMode ||
                selectedPreset != initialPreset ||
                !selectedHex.equals(initialHex, ignoreCase = true)

        if (hasChanged) {
            preferences.themeMode = selectedThemeMode
            preferences.accentColorPreset = selectedPreset
            preferences.accentColorHex = selectedHex
            preferences.applyTheme()
            onAppearanceChanged?.invoke()
        }
        dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        val hasChanged = selectedThemeMode != initialThemeMode ||
                selectedPreset != initialPreset ||
                !selectedHex.equals(initialHex, ignoreCase = true)

        if (hasChanged) {
            preferences.themeMode = selectedThemeMode
            preferences.accentColorPreset = selectedPreset
            preferences.accentColorHex = selectedHex
            preferences.applyTheme()
            onAppearanceChanged?.invoke()
        }
    }

    private fun dpToPx(dp: Int): Int {
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
