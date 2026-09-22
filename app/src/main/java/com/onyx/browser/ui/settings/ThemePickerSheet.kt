package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
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
        val colorPrimary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val colorOutline = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)

        // System
        val isSystem = selectedTheme == BrowserPreferences.THEME_SYSTEM
        binding.ivCheckSystem.visibility = if (isSystem) View.VISIBLE else View.INVISIBLE
        binding.optionSystem.strokeColor = if (isSystem) colorPrimary else colorOutline
        binding.optionSystem.strokeWidth = if (isSystem) dpToPx(2) else dpToPx(1)

        // Dark
        val isDark = selectedTheme == BrowserPreferences.THEME_DARK
        binding.ivCheckDark.visibility = if (isDark) View.VISIBLE else View.INVISIBLE
        binding.optionDark.strokeColor = if (isDark) colorPrimary else colorOutline
        binding.optionDark.strokeWidth = if (isDark) dpToPx(2) else dpToPx(1)

        // Light
        val isLight = selectedTheme == BrowserPreferences.THEME_LIGHT
        binding.ivCheckLight.visibility = if (isLight) View.VISIBLE else View.INVISIBLE
        binding.optionLight.strokeColor = if (isLight) colorPrimary else colorOutline
        binding.optionLight.strokeWidth = if (isLight) dpToPx(2) else dpToPx(1)
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
