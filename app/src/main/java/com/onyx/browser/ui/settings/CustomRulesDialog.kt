package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.BottomSheetCustomRulesBinding

class CustomRulesDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCustomRulesBinding? = null
    private val binding get() = _binding!!

    companion object {
        fun newInstance() = CustomRulesDialog()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCustomRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = BrowserPreferences.getInstance(requireContext())

        binding.etCustomRules.setText(prefs.customFilterRules)

        binding.btnSave.setOnClickListener {
            prefs.customFilterRules = binding.etCustomRules.text?.toString() ?: ""
            dismiss()
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
