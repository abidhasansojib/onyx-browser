package com.onyx.browser.ui.home

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import com.google.android.material.color.MaterialColors
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

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val maxDialogWidth = (420 * displayMetrics.density).toInt()
            val targetWidth = (displayMetrics.widthPixels * 0.90).toInt().coerceAtMost(maxDialogWidth)
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.etEditTitle.setText(shortcut.title)
        binding.etEditUrl.setText(shortcut.url)

        updateLivePreview(shortcut.title, shortcut.url)

        binding.etEditTitle.doAfterTextChanged {
            updateLivePreview(it?.toString().orEmpty(), binding.etEditUrl.text?.toString().orEmpty())
        }

        binding.etEditUrl.doAfterTextChanged {
            binding.tilUrl.error = null
            updateLivePreview(binding.etEditTitle.text?.toString().orEmpty(), it?.toString().orEmpty())
        }

        binding.btnCancelEdit.setOnClickListener {
            dismiss()
        }

        binding.btnSaveEdit.setOnClickListener {
            val newTitle = binding.etEditTitle.text?.toString()?.trim() ?: ""
            var newUrl = binding.etEditUrl.text?.toString()?.trim() ?: ""

            if (newUrl.isBlank()) {
                binding.tilUrl.error = getString(R.string.shortcut_url_hint)
                binding.etEditUrl.requestFocus()
                return@setOnClickListener
            }
            binding.tilUrl.error = null

            if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://") && !newUrl.startsWith("onyx://")) {
                newUrl = "https://$newUrl"
            }

            val iconType = ShortcutItem.inferIconType(newUrl)
            val updated = shortcut.copy(
                title = if (newTitle.isNotBlank()) newTitle else {
                    try {
                        java.net.URI(newUrl).host?.removePrefix("www.") ?: newUrl
                    } catch (_: Exception) {
                        newUrl
                    }
                },
                url = newUrl,
                iconType = iconType
            )

            onShortcutSaved(updated)
            dismiss()
        }
    }

    private fun updateLivePreview(title: String, url: String) {
        val inferUrl = if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("onyx://")) {
            "https://$url"
        } else {
            url
        }
        val iconType = ShortcutItem.inferIconType(inferUrl)

        binding.ivPreviewIcon.clearColorFilter()
        binding.ivPreviewIcon.imageTintList = null

        val textColorPrimary = MaterialColors.getColor(binding.root, android.R.attr.textColorPrimary, Color.WHITE)
        val textColorSecondary = MaterialColors.getColor(binding.root, android.R.attr.textColorSecondary, Color.LTGRAY)

        when (iconType) {
            ShortcutItem.ICON_GOOGLE -> {
                binding.ivPreviewIcon.visibility = View.VISIBLE
                binding.tvPreviewLetterBadge.visibility = View.GONE
                binding.ivPreviewIcon.setImageResource(R.drawable.ic_engine_google)
            }
            ShortcutItem.ICON_YOUTUBE -> {
                binding.ivPreviewIcon.visibility = View.VISIBLE
                binding.tvPreviewLetterBadge.visibility = View.GONE
                binding.ivPreviewIcon.setImageResource(R.drawable.ic_brand_youtube)
            }
            ShortcutItem.ICON_GITHUB -> {
                binding.ivPreviewIcon.visibility = View.VISIBLE
                binding.tvPreviewLetterBadge.visibility = View.GONE
                binding.ivPreviewIcon.setImageResource(R.drawable.ic_brand_github)
            }
            ShortcutItem.ICON_WIKIPEDIA -> {
                binding.ivPreviewIcon.visibility = View.VISIBLE
                binding.tvPreviewLetterBadge.visibility = View.GONE
                binding.ivPreviewIcon.setImageResource(R.drawable.ic_brand_wikipedia)
            }
            ShortcutItem.ICON_FACEBOOK -> {
                binding.ivPreviewIcon.visibility = View.VISIBLE
                binding.tvPreviewLetterBadge.visibility = View.GONE
                binding.ivPreviewIcon.setImageResource(R.drawable.ic_brand_facebook)
            }
            ShortcutItem.ICON_REDDIT -> {
                binding.ivPreviewIcon.visibility = View.VISIBLE
                binding.tvPreviewLetterBadge.visibility = View.GONE
                binding.ivPreviewIcon.setImageResource(R.drawable.ic_brand_reddit)
            }
            ShortcutItem.ICON_BOOKMARKS -> {
                binding.ivPreviewIcon.visibility = View.VISIBLE
                binding.tvPreviewLetterBadge.visibility = View.GONE
                binding.ivPreviewIcon.setImageResource(R.drawable.ic_bookmark)
                binding.ivPreviewIcon.imageTintList = ColorStateList.valueOf(textColorSecondary)
            }
            ShortcutItem.ICON_HISTORY -> {
                binding.ivPreviewIcon.visibility = View.VISIBLE
                binding.tvPreviewLetterBadge.visibility = View.GONE
                binding.ivPreviewIcon.setImageResource(R.drawable.ic_history)
                binding.ivPreviewIcon.imageTintList = ColorStateList.valueOf(textColorSecondary)
            }
            ShortcutItem.ICON_DOWNLOADS -> {
                binding.ivPreviewIcon.visibility = View.VISIBLE
                binding.tvPreviewLetterBadge.visibility = View.GONE
                binding.ivPreviewIcon.setImageResource(R.drawable.ic_download)
                binding.ivPreviewIcon.imageTintList = ColorStateList.valueOf(textColorSecondary)
            }
            else -> {
                val letter = title.trim().firstOrNull()?.uppercase()
                    ?: inferUrl.removePrefix("https://").removePrefix("http://").removePrefix("www.").firstOrNull()?.uppercase()
                if (letter != null && letter.matches(Regex("[A-Za-z0-9]"))) {
                    binding.ivPreviewIcon.visibility = View.GONE
                    binding.tvPreviewLetterBadge.visibility = View.VISIBLE
                    binding.tvPreviewLetterBadge.text = letter
                    binding.tvPreviewLetterBadge.setTextColor(textColorPrimary)
                } else {
                    binding.ivPreviewIcon.visibility = View.VISIBLE
                    binding.tvPreviewLetterBadge.visibility = View.GONE
                    binding.ivPreviewIcon.setImageResource(R.drawable.ic_web)
                    binding.ivPreviewIcon.imageTintList = ColorStateList.valueOf(textColorSecondary)
                }
            }
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
