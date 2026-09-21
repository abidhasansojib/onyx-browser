package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.databinding.BottomSheetDownloadManagerPickerBinding

class DownloadManagerPickerSheet(
    private val currentSelection: Int,
    private val onSelected: (Int) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDownloadManagerPickerBinding? = null
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
        _binding = BottomSheetDownloadManagerPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateCheckmarks(currentSelection)
        binding.optionAsk.setOnClickListener { onSelected(0); dismiss() }
        binding.optionInternal.setOnClickListener { onSelected(1); dismiss() }
        binding.optionExternal.setOnClickListener { onSelected(2); dismiss() }
        binding.btnDismiss.setOnClickListener { dismiss() }
    }

    private fun updateCheckmarks(sel: Int) {
        binding.ivCheckAsk.visibility = if (sel == 0) View.VISIBLE else View.INVISIBLE
        binding.ivCheckInternal.visibility = if (sel == 1) View.VISIBLE else View.INVISIBLE
        binding.ivCheckExternal.visibility = if (sel == 2) View.VISIBLE else View.INVISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DownloadManagerPickerSheet"
    }
}
