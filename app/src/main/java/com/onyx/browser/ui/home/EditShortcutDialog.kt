package com.onyx.browser.ui.home

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.databinding.DialogEditShortcutBinding

class EditShortcutDialog(
    private val shortcut: ShortcutItem,
    private val onShortcutSaved: (ShortcutItem) -> Unit
) : DialogFragment() {

    private var _binding: DialogEditShortcutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }
        _binding = DialogEditShortcutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.etEditTitle.setText(shortcut.title)
        binding.etEditUrl.setText(shortcut.url)

        binding.btnCancelEdit.setOnClickListener {
            dismiss()
        }

        binding.btnSaveEdit.setOnClickListener {
            val newTitle = binding.etEditTitle.text?.toString()?.trim() ?: ""
            var newUrl = binding.etEditUrl.text?.toString()?.trim() ?: ""

            if (newUrl.isBlank()) {
                Toast.makeText(requireContext(), R.string.shortcut_url_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                newUrl = "https://$newUrl"
            }

            val iconType = ShortcutItem.inferIconType(newUrl)
            val updated = shortcut.copy(
                title = if (newTitle.isNotBlank()) newTitle else newUrl,
                url = newUrl,
                iconType = iconType
            )

            onShortcutSaved(updated)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "EditShortcutDialog"
    }
}
