package com.onyx.browser.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.databinding.DialogImportScriptUrlBinding

class ImportScriptUrlBottomSheet(
    private val onUrlEntered: (String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogImportScriptUrlBinding? = null
    private val binding get() = _binding!!

    override fun getTheme(): Int = R.style.Theme_OnyxBrowser_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogImportScriptUrlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Pre-fill from clipboard if it looks like a script URL
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (!clip.isNullOrBlank() && (clip.startsWith("http://") || clip.startsWith("https://"))) {
            binding.etScriptUrl.setText(clip)
            binding.etScriptUrl.setSelection(clip.length)
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnInstall.setOnClickListener {
            val url = binding.etScriptUrl.text?.toString()?.trim().orEmpty()
            if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                Toast.makeText(requireContext(), "Please enter a valid HTTP or HTTPS URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dismiss()
            onUrlEntered(url)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ImportScriptUrlBottomSheet"
    }
}
