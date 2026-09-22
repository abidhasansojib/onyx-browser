package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.BottomSheetBlockingLevelPickerBinding

class BlockingLevelPickerSheet(
    private val currentLevel: Int,
    private val onSelected: (Int) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBlockingLevelPickerBinding? = null
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
        _binding = BottomSheetBlockingLevelPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateSelection(currentLevel)

        binding.optionStandard.setOnClickListener {
            onSelected(BrowserPreferences.BLOCKING_STANDARD)
            dismiss()
        }

        binding.optionAggressive.setOnClickListener {
            onSelected(BrowserPreferences.BLOCKING_AGGRESSIVE)
            dismiss()
        }

        binding.btnDismiss.setOnClickListener {
            dismiss()
        }
    }

    private fun updateSelection(selectedLevel: Int) {
        val colorPrimary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val colorOutline = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)

        val isStandard = selectedLevel == BrowserPreferences.BLOCKING_STANDARD
        binding.ivCheckStandard.visibility = if (isStandard) View.VISIBLE else View.INVISIBLE
        binding.optionStandard.strokeColor = if (isStandard) colorPrimary else colorOutline
        binding.optionStandard.strokeWidth = if (isStandard) dpToPx(2) else dpToPx(1)

        val isAggressive = selectedLevel == BrowserPreferences.BLOCKING_AGGRESSIVE
        binding.ivCheckAggressive.visibility = if (isAggressive) View.VISIBLE else View.INVISIBLE
        binding.optionAggressive.strokeColor = if (isAggressive) colorPrimary else colorOutline
        binding.optionAggressive.strokeWidth = if (isAggressive) dpToPx(2) else dpToPx(1)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BlockingLevelPickerSheet"
    }
}
