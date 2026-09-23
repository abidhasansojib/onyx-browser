package com.onyx.browser.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.BottomSheetUserAgentPickerBinding
import com.onyx.browser.databinding.ItemUserAgentTemplateBinding
import com.onyx.browser.web.UserAgentManager
import com.onyx.browser.web.UserAgentTemplate

class UserAgentPickerSheet(
    private val onUserAgentChanged: (String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetUserAgentPickerBinding? = null
    private val binding get() = _binding!!
    private lateinit var preferences: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_OnyxBrowser_BottomSheetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetUserAgentPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferences = BrowserPreferences.getInstance(requireContext())

        setupCustomCard()
        setupTemplatesList()

        binding.btnDismiss.setOnClickListener {
            dismiss()
        }
    }

    private fun setupCustomCard() {
        val isCustomActive = preferences.userAgentSpoofTemplate == UserAgentManager.KEY_CUSTOM
        val colorPrimary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val colorOutline = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)

        binding.tvCustomActiveBadge.visibility = if (isCustomActive) View.VISIBLE else View.GONE
        binding.cardCustomUa.strokeColor = if (isCustomActive) colorPrimary else colorOutline
        binding.cardCustomUa.strokeWidth = dpToPx(if (isCustomActive) 2 else 1)

        val savedCustomUa = preferences.customUserAgent
        if (savedCustomUa.isNotBlank()) {
            binding.etCustomUa.setText(savedCustomUa)
        }

        binding.etCustomUa.addTextChangedListener {
            binding.tilCustomUa.error = null
        }

        binding.btnPasteUa.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pasted = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                if (pasted.isNotBlank()) {
                    binding.etCustomUa.setText(pasted)
                    binding.etCustomUa.setSelection(pasted.length)
                    Toast.makeText(requireContext(), "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnApplyCustomUa.setOnClickListener {
            val customText = binding.etCustomUa.text?.toString()?.trim() ?: ""
            if (customText.isBlank()) {
                binding.tilCustomUa.error = "Please enter a valid User-Agent string"
                return@setOnClickListener
            }

            preferences.customUserAgent = customText
            preferences.userAgentSpoofTemplate = UserAgentManager.KEY_CUSTOM
            onUserAgentChanged(UserAgentManager.KEY_CUSTOM)
            Toast.makeText(requireContext(), "Custom User-Agent applied", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun setupTemplatesList() {
        val templates = UserAgentManager.getTemplates().filter { !it.isCustom }
        binding.tvTemplateCount.text = "${templates.size} available"

        val adapter = UserAgentAdapter(templates, preferences.userAgentSpoofTemplate) { selectedTemplate ->
            preferences.userAgentSpoofTemplate = selectedTemplate.key
            onUserAgentChanged(selectedTemplate.key)
            Toast.makeText(requireContext(), "${selectedTemplate.title} applied", Toast.LENGTH_SHORT).show()
            dismiss()
        }
        binding.rvTemplates.adapter = adapter
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class UserAgentAdapter(
        private val items: List<UserAgentTemplate>,
        private val currentSelectedKey: String,
        private val onSelect: (UserAgentTemplate) -> Unit
    ) : RecyclerView.Adapter<UserAgentAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemUserAgentTemplateBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemUserAgentTemplateBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val template = items[position]
            val binding = holder.binding
            val context = binding.root.context
            val isSelected = template.key == currentSelectedKey

            val colorPrimary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
            val colorOutline = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)

            binding.tvTemplateTitle.text = template.title
            binding.tvCategoryBadge.text = template.category.uppercase()
            binding.tvTemplateUaPreview.text = template.userAgentString
            binding.ivTemplateIcon.setImageResource(template.iconResId)

            binding.ivSelectedCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.cardTemplate.strokeColor = if (isSelected) colorPrimary else colorOutline
            binding.cardTemplate.strokeWidth = ((if (isSelected) 2 else 1) * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

            binding.cardTemplate.setOnClickListener {
                onSelect(template)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
