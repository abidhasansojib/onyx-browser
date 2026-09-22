package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.BottomSheetCookieModePickerBinding

class CookieModePickerSheet(
    private val currentMode: Int,
    private val onSelected: (Int) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCookieModePickerBinding? = null
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
        _binding = BottomSheetCookieModePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateSelection(currentMode)

        binding.optionAllowAll.setOnClickListener {
            onSelected(BrowserPreferences.COOKIE_BLOCK_NONE)
            dismiss()
        }

        binding.optionBlockThirdParty.setOnClickListener {
            onSelected(BrowserPreferences.COOKIE_BLOCK_THIRD_PARTY)
            dismiss()
        }

        binding.optionBlockAll.setOnClickListener {
            onSelected(BrowserPreferences.COOKIE_BLOCK_ALL)
            dismiss()
        }

        binding.btnDismiss.setOnClickListener {
            dismiss()
        }
    }

    private fun updateSelection(selectedMode: Int) {
        val colorPrimary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val colorOutline = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)

        val isAllowAll = selectedMode == BrowserPreferences.COOKIE_BLOCK_NONE
        binding.ivCheckAllowAll.visibility = if (isAllowAll) View.VISIBLE else View.INVISIBLE
        binding.optionAllowAll.strokeColor = if (isAllowAll) colorPrimary else colorOutline
        binding.optionAllowAll.strokeWidth = if (isAllowAll) dpToPx(2) else dpToPx(1)

        val isBlockThirdParty = selectedMode == BrowserPreferences.COOKIE_BLOCK_THIRD_PARTY
        binding.ivCheckBlockThirdParty.visibility = if (isBlockThirdParty) View.VISIBLE else View.INVISIBLE
        binding.optionBlockThirdParty.strokeColor = if (isBlockThirdParty) colorPrimary else colorOutline
        binding.optionBlockThirdParty.strokeWidth = if (isBlockThirdParty) dpToPx(2) else dpToPx(1)

        val isBlockAll = selectedMode == BrowserPreferences.COOKIE_BLOCK_ALL
        binding.ivCheckBlockAll.visibility = if (isBlockAll) View.VISIBLE else View.INVISIBLE
        binding.optionBlockAll.strokeColor = if (isBlockAll) colorPrimary else colorOutline
        binding.optionBlockAll.strokeWidth = if (isBlockAll) dpToPx(2) else dpToPx(1)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CookieModePickerSheet"
    }
}
