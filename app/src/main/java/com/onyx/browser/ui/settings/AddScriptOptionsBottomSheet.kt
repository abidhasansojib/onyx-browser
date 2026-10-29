package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.databinding.BottomSheetAddScriptOptionsBinding

class AddScriptOptionsBottomSheet(
    private val onNewScript: () -> Unit,
    private val onImportUrl: () -> Unit,
    private val onImportFile: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddScriptOptionsBinding? = null
    private val binding get() = _binding!!

    override fun getTheme(): Int = R.style.Theme_OnyxBrowser_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAddScriptOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.optionNewScript.setOnClickListener {
            dismiss()
            onNewScript()
        }

        binding.optionImportUrl.setOnClickListener {
            dismiss()
            onImportUrl()
        }

        binding.optionImportFile.setOnClickListener {
            dismiss()
            onImportFile()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddScriptOptionsBottomSheet"
    }
}
