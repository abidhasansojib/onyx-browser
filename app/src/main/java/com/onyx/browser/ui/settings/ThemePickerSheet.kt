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
    private val currentTheme: Int,
    private val onThemeSelected: (Int) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetThemePickerBinding? = null
    private val binding get() = _binding!!

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

        updateSelection(currentTheme)

        binding.optionSystem.setOnClickListener {
            onThemeSelected(BrowserPreferences.THEME_SYSTEM)
            dismiss()
        }

        binding.optionDark.setOnClickListener {
            onThemeSelected(BrowserPreferences.THEME_DARK)
            dismiss()
        }

        binding.optionLight.setOnClickListener {
            onThemeSelected(BrowserPreferences.THEME_LIGHT)
            dismiss()
        }

        binding.btnDismiss.setOnClickListener {
            dismiss()
        }
    }

    private fun updateSelection(selectedTheme: Int) {
        val colorActive = ContextCompat.getColor(requireContext(), R.color.google_blue)
        val colorDefaultStroke = ContextCompat.getColor(requireContext(), R.color.settings_card_stroke)
        val colorDefaultIcon = ContextCompat.getColor(requireContext(), R.color.settings_title_text)

        val activeStrokeWidth = dpToPx(2)
        val defaultStrokeWidth = dpToPx(1)

        // System
        val isSystem = selectedTheme == BrowserPreferences.THEME_SYSTEM
        binding.ivCheckSystem.visibility = if (isSystem) View.VISIBLE else View.INVISIBLE
        binding.optionSystem.strokeColor = if (isSystem) colorActive else colorDefaultStroke
        binding.optionSystem.strokeWidth = if (isSystem) activeStrokeWidth else defaultStrokeWidth
        binding.ivIconSystem.setColorFilter(if (isSystem) colorActive else colorDefaultIcon)

        // Dark
        val isDark = selectedTheme == BrowserPreferences.THEME_DARK
        binding.ivCheckDark.visibility = if (isDark) View.VISIBLE else View.INVISIBLE
        binding.optionDark.strokeColor = if (isDark) colorActive else colorDefaultStroke
        binding.optionDark.strokeWidth = if (isDark) activeStrokeWidth else defaultStrokeWidth
        binding.ivIconDark.setColorFilter(if (isDark) colorActive else colorDefaultIcon)

        // Light
        val isLight = selectedTheme == BrowserPreferences.THEME_LIGHT
        binding.ivCheckLight.visibility = if (isLight) View.VISIBLE else View.INVISIBLE
        binding.optionLight.strokeColor = if (isLight) colorActive else colorDefaultStroke
        binding.optionLight.strokeWidth = if (isLight) activeStrokeWidth else defaultStrokeWidth
        binding.ivIconLight.setColorFilter(if (isLight) colorActive else colorDefaultIcon)
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
