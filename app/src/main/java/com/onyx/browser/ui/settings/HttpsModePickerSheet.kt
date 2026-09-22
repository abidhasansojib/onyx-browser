package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.BottomSheetHttpsModePickerBinding

class HttpsModePickerSheet(
    private val currentMode: Int,
    private val onSelected: (Int) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetHttpsModePickerBinding? = null
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
        _binding = BottomSheetHttpsModePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateSelection(currentMode)

        binding.optionDisabled.setOnClickListener {
            onSelected(BrowserPreferences.HTTPS_MODE_DISABLED)
            dismiss()
        }

        binding.optionWhenPossible.setOnClickListener {
            onSelected(BrowserPreferences.HTTPS_MODE_WHEN_POSSIBLE)
            dismiss()
        }

        binding.optionStrict.setOnClickListener {
            onSelected(BrowserPreferences.HTTPS_MODE_STRICT)
            dismiss()
        }

        binding.btnDismiss.setOnClickListener {
            dismiss()
        }
    }

    private fun updateSelection(selectedMode: Int) {
        val colorPrimary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val colorOutline = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)

        val isDisabled = selectedMode == BrowserPreferences.HTTPS_MODE_DISABLED
        binding.ivCheckDisabled.visibility = if (isDisabled) View.VISIBLE else View.INVISIBLE
        binding.optionDisabled.strokeColor = if (isDisabled) colorPrimary else colorOutline
        binding.optionDisabled.strokeWidth = if (isDisabled) dpToPx(2) else dpToPx(1)

        val isWhenPossible = selectedMode == BrowserPreferences.HTTPS_MODE_WHEN_POSSIBLE
        binding.ivCheckWhenPossible.visibility = if (isWhenPossible) View.VISIBLE else View.INVISIBLE
        binding.optionWhenPossible.strokeColor = if (isWhenPossible) colorPrimary else colorOutline
        binding.optionWhenPossible.strokeWidth = if (isWhenPossible) dpToPx(2) else dpToPx(1)

        val isStrict = selectedMode == BrowserPreferences.HTTPS_MODE_STRICT
        binding.ivCheckStrict.visibility = if (isStrict) View.VISIBLE else View.INVISIBLE
        binding.optionStrict.strokeColor = if (isStrict) colorPrimary else colorOutline
        binding.optionStrict.strokeWidth = if (isStrict) dpToPx(2) else dpToPx(1)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "HttpsModePickerSheet"
    }
}
