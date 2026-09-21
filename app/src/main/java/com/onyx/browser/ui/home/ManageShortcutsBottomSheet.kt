package com.onyx.browser.ui.home

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.onyx.browser.R
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.BottomSheetManageShortcutsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ManageShortcutsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetManageShortcutsBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: BrowserPreferences
    private lateinit var adapter: ManageShortcutsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetManageShortcutsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferences = BrowserPreferences.getInstance(requireContext())

        adapter = ManageShortcutsAdapter(
            onEditClick = { shortcut ->
                showEditDialog(shortcut)
            },
            onDeleteClick = { shortcut ->
                preferences.deleteShortcut(shortcut.id)
                Toast.makeText(requireContext(), R.string.shortcut_deleted, Toast.LENGTH_SHORT).show()
            }
        )

        binding.rvManageShortcuts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvManageShortcuts.adapter = adapter

        binding.btnClose.setOnClickListener {
            dismiss()
        }

        binding.btnAddNewShortcut.setOnClickListener {
            addNewShortcut()
        }

        binding.btnAdjustQuickActions.setOnClickListener {
            // val adjustSheet = AdjustQuickActionsBottomSheet()
            // adjustSheet.show(parentFragmentManager, AdjustQuickActionsBottomSheet.TAG)
        }

        // Populate suggested shortcuts horizontal row
        populateSuggestedShortcuts()

        lifecycleScope.launch {
            preferences.shortcutsFlow.collectLatest { list ->
                adapter.submitList(list)
                binding.tvShortcutsCount.text = "MY SHORTCUTS (${list.size})"
            }
        }
    }

    private fun populateSuggestedShortcuts() {
        val suggestedItems = listOf(
            Triple("Google", "https://www.google.com", ShortcutItem.ICON_GOOGLE),
            Triple("YouTube", "https://www.youtube.com", ShortcutItem.ICON_YOUTUBE),
            Triple("GitHub", "https://github.com", ShortcutItem.ICON_GITHUB),
            Triple("Wikipedia", "https://www.wikipedia.org", ShortcutItem.ICON_WIKIPEDIA),
            Triple("Facebook", "https://www.facebook.com", ShortcutItem.ICON_FACEBOOK),
            Triple("Reddit", "https://www.reddit.com", ShortcutItem.ICON_REDDIT),
            Triple("X (Twitter)", "https://x.com", ShortcutItem.DEFAULT_ICON),
            Triple("Instagram", "https://www.instagram.com", ShortcutItem.DEFAULT_ICON),
            Triple("Amazon", "https://www.amazon.com", ShortcutItem.DEFAULT_ICON),
            Triple("Netflix", "https://www.netflix.com", ShortcutItem.DEFAULT_ICON)
        )
        val inflater = LayoutInflater.from(requireContext())
        suggestedItems.forEach { (title, url, iconType) ->
            val itemView = inflater.inflate(R.layout.item_suggested_shortcut, binding.llSuggestedShortcuts, false)
            val iv = itemView.findViewById<AppCompatImageView>(R.id.ivSuggestedIcon)
            val tv = itemView.findViewById<TextView>(R.id.tvSuggestedTitle)
            tv.text = title
            // Map icon type to drawable
            val iconRes = when (iconType) {
                ShortcutItem.ICON_GOOGLE -> R.drawable.ic_brand_google
                ShortcutItem.ICON_YOUTUBE -> R.drawable.ic_brand_youtube
                ShortcutItem.ICON_GITHUB -> R.drawable.ic_brand_github
                ShortcutItem.ICON_WIKIPEDIA -> R.drawable.ic_brand_wikipedia
                ShortcutItem.ICON_FACEBOOK -> R.drawable.ic_brand_facebook
                ShortcutItem.ICON_REDDIT -> R.drawable.ic_brand_reddit
                else -> R.drawable.ic_web
            }
            iv.setImageResource(iconRes)
            if (iconType == ShortcutItem.DEFAULT_ICON) {
                iv.imageTintList = android.content.res.ColorStateList.valueOf(
                    MaterialColors.getColor(iv, android.R.attr.textColorSecondary)
                )
            }
            itemView.setOnClickListener {
                val existing = preferences.getShortcuts()
                if (existing.any { it.url == url }) {
                    Toast.makeText(requireContext(), "Already in shortcuts", Toast.LENGTH_SHORT).show()
                } else {
                    preferences.addShortcut(ShortcutItem(title = title, url = url, iconType = iconType))
                    Toast.makeText(requireContext(), "$title added", Toast.LENGTH_SHORT).show()
                }
            }
            binding.llSuggestedShortcuts.addView(itemView)
        }
    }

    private fun addNewShortcut() {
        val title = binding.etNewTitle.text?.toString()?.trim() ?: ""
        var url = binding.etNewUrl.text?.toString()?.trim() ?: ""

        if (url.isBlank()) {
            binding.etNewUrl.error = getString(R.string.shortcut_url_hint)
            binding.etNewUrl.requestFocus()
            return
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        val displayTitle = if (title.isNotBlank()) title else {
            try {
                java.net.URI(url).host?.removePrefix("www.") ?: url
            } catch (_: Exception) {
                url
            }
        }

        val iconType = ShortcutItem.inferIconType(url)
        val newItem = ShortcutItem(
            title = displayTitle,
            url = url,
            iconType = iconType
        )

        preferences.addShortcut(newItem)
        binding.etNewTitle.text?.clear()
        binding.etNewUrl.text?.clear()
        binding.etNewUrl.error = null

        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)

        Toast.makeText(requireContext(), R.string.shortcut_added, Toast.LENGTH_SHORT).show()
    }

    private fun showEditDialog(shortcut: ShortcutItem) {
        val dialog = EditShortcutDialog(shortcut) { updated ->
            preferences.updateShortcut(updated)
            Toast.makeText(requireContext(), R.string.shortcut_updated, Toast.LENGTH_SHORT).show()
        }
        dialog.show(parentFragmentManager, EditShortcutDialog.TAG)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ManageShortcutsBottomSheet"
    }
}
