package com.onyx.browser.ui.home

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
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
            val adjustSheet = AdjustQuickActionsBottomSheet()
            adjustSheet.show(parentFragmentManager, AdjustQuickActionsBottomSheet.TAG)
        }

        lifecycleScope.launch {
            preferences.shortcutsFlow.collectLatest { list ->
                adapter.submitList(list)
                binding.tvShortcutsCount.text = "${getString(R.string.shortcuts)} (${list.size})"
            }
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
