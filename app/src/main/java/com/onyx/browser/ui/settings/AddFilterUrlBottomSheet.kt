package com.onyx.browser.ui.settings

import android.content.ClipboardManager
import android.content.Context
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
import com.onyx.browser.databinding.BottomSheetAddFilterUrlBinding
import kotlinx.coroutines.launch

class AddFilterUrlBottomSheet(
    private val onFilterAdded: (CustomFilterItem) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddFilterUrlBinding? = null
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
        _binding = BottomSheetAddFilterUrlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnDismiss.setOnClickListener { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }

        binding.etFilterName.addTextChangedListener { binding.tilFilterName.error = null }
        binding.etFilterUrl.addTextChangedListener { binding.tilFilterUrl.error = null }

        binding.btnPasteUrl.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pasted = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                if (pasted.startsWith("http://", ignoreCase = true) || pasted.startsWith("https://", ignoreCase = true)) {
                    binding.etFilterUrl.setText(pasted)
                    binding.etFilterUrl.setSelection(pasted.length)
                } else if (pasted.isNotBlank()) {
                    binding.etFilterUrl.setText(pasted)
                    binding.etFilterUrl.setSelection(pasted.length)
                }
            }
        }

        binding.btnAddFilter.setOnClickListener {
            val name = binding.etFilterName.text?.toString()?.trim() ?: ""
            val url = binding.etFilterUrl.text?.toString()?.trim() ?: ""

            if (name.isBlank()) {
                binding.tilFilterName.error = getString(R.string.filter_name_required)
                return@setOnClickListener
            }

            if (url.isBlank() || (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true))) {
                binding.tilFilterUrl.error = getString(R.string.filter_url_invalid)
                return@setOnClickListener
            }

            val item = CustomFilterItem(
                id = "custom_url_${System.currentTimeMillis()}",
                name = name,
                url = url,
                rules = "",
                isUrlType = true,
                isEnabled = true
            )

            binding.btnAddFilter.isEnabled = false
            binding.btnCancel.isEnabled = false
            binding.progressLoading.visibility = View.VISIBLE

            val context = requireContext().applicationContext
            lifecycleScope.launch {
                val downloaded = FilterListManager.downloadCustomFilter(context, item)
                val prefs = BrowserPreferences.getInstance(context)
                prefs.addCustomFilter(item)
                FilterListManager.recompileFilters(context)

                if (isAdded) {
                    binding.progressLoading.visibility = View.GONE
                    if (downloaded) {
                        Toast.makeText(context, getString(R.string.filter_added_success, name), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, getString(R.string.filter_added_download_pending, name), Toast.LENGTH_LONG).show()
                    }
                    onFilterAdded(item)
                    dismiss()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
