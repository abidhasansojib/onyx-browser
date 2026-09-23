package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.filter.CustomFilterItem
import com.onyx.browser.data.filter.FilterListManager
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.BottomSheetCreateCustomFilterBinding
import kotlinx.coroutines.launch

class CreateCustomFilterBottomSheet(
    private val existingFilter: CustomFilterItem? = null,
    private val onFilterSaved: (CustomFilterItem) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCreateCustomFilterBinding? = null
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
        _binding = BottomSheetCreateCustomFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnDismiss.setOnClickListener { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }

        binding.etCustomFilterName.addTextChangedListener { binding.tilCustomFilterName.error = null }
        binding.etCustomFilterRules.addTextChangedListener { binding.tilCustomFilterRules.error = null }

        if (existingFilter != null) {
            binding.tvSheetTitle.text = getString(R.string.edit_custom_filter_title)
            binding.etCustomFilterName.setText(existingFilter.name)
            binding.etCustomFilterRules.setText(existingFilter.rules)
            binding.btnSaveFilter.text = getString(R.string.update)
        }

        binding.btnSaveFilter.setOnClickListener {
            val name = binding.etCustomFilterName.text?.toString()?.trim() ?: ""
            val rules = binding.etCustomFilterRules.text?.toString()?.trim() ?: ""

            if (name.isBlank()) {
                binding.tilCustomFilterName.error = getString(R.string.filter_name_required)
                return@setOnClickListener
            }

            if (rules.isBlank()) {
                binding.tilCustomFilterRules.error = getString(R.string.filter_rules_required)
                return@setOnClickListener
            }

            val item = existingFilter?.copy(
                name = name,
                rules = rules
            ) ?: CustomFilterItem(
                id = "custom_rules_${System.currentTimeMillis()}",
                name = name,
                url = "",
                rules = rules,
                isUrlType = false,
                isEnabled = true
            )

            val context = requireContext().applicationContext
            val prefs = BrowserPreferences.getInstance(context)

            if (existingFilter != null) {
                prefs.updateCustomFilter(item)
            } else {
                prefs.addCustomFilter(item)
            }

            lifecycleScope.launch {
                FilterListManager.recompileFilters(context)
            }

            Toast.makeText(
                context,
                getString(R.string.custom_filter_saved, name),
                Toast.LENGTH_SHORT
            ).show()

            onFilterSaved(item)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
