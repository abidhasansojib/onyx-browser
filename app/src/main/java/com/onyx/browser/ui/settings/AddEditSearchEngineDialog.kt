package com.onyx.browser.ui.settings

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.databinding.DialogAddSearchEngineBinding
import java.util.UUID

class AddEditSearchEngineDialog(
    private val existingEngine: SearchEngine? = null,
    private val onSave: (SearchEngine) -> Unit
) : DialogFragment() {

    private var _binding: DialogAddSearchEngineBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogAddSearchEngineBinding.inflate(LayoutInflater.from(requireContext()))

        val isEdit = existingEngine != null
        if (isEdit) {
            binding.tvDialogTitle.text = "Edit search engine"
            binding.etEngineName.setText(existingEngine?.displayName)
            binding.etEngineKeyword.setText(existingEngine?.keyword)
            binding.etEngineUrl.setText(existingEngine?.queryUrl)
            binding.btnSave.text = getString(R.string.save)
        } else {
            binding.tvDialogTitle.text = "Add search engine"
            binding.btnSave.text = getString(R.string.add)
        }

        binding.btnCancel.setOnClickListener { dismiss() }

        binding.btnSave.setOnClickListener {
            val name = binding.etEngineName.text?.toString()?.trim() ?: ""
            val keyword = binding.etEngineKeyword.text?.toString()?.trim() ?: ""
            var url = binding.etEngineUrl.text?.toString()?.trim() ?: ""

            if (name.isBlank()) {
                binding.tilEngineName.error = "Name cannot be empty"
                return@setOnClickListener
            }
            binding.tilEngineName.error = null

            if (url.isBlank()) {
                binding.tilEngineUrl.error = "URL cannot be empty"
                return@setOnClickListener
            }

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }

            // Auto-append %s if not present
            if (!url.contains("%s")) {
                url = if (url.contains("?")) {
                    "$url&q=%s"
                } else {
                    "$url?q=%s"
                }
            }

            val homeUrl = try {
                val uri = android.net.Uri.parse(url)
                "${uri.scheme}://${uri.host}"
            } catch (_: Exception) {
                ""
            }

            val engine = SearchEngine(
                id = existingEngine?.id ?: "custom_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
                displayName = name,
                queryUrl = url,
                homeUrl = homeUrl,
                iconResId = R.drawable.ic_web,
                keyword = keyword,
                isCustom = true
            )

            onSave(engine)
            dismiss()
        }

        return AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .create().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddEditSearchEngineDialog"
    }
}
