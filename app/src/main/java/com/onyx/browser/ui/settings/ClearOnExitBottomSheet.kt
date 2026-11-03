package com.onyx.browser.ui.settings

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.BottomSheetClearOnExitBinding

class ClearOnExitBottomSheet(
    private val onUpdated: () -> Unit = {}
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetClearOnExitBinding? = null
    private val binding get() = _binding!!
    private val prefs by lazy { BrowserPreferences.getInstance(requireContext()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_OnyxBrowser_BottomSheetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetClearOnExitBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize state from preferences
        val isMasterEnabled = prefs.isClearOnExitEnabled
        binding.switchMasterExit.isChecked = isMasterEnabled
        binding.cbExitTabs.isChecked = prefs.isClearOnExitTabsEnabled
        binding.cbExitHistory.isChecked = prefs.isClearOnExitHistoryEnabled
        binding.cbExitCookies.isChecked = prefs.isClearOnExitCookiesEnabled
        binding.cbExitCache.isChecked = prefs.isClearOnExitCacheEnabled

        updateOptionsVisualState(isMasterEnabled)

        // Master toggle click handlers
        binding.cardMasterToggle.setOnClickListener {
            binding.switchMasterExit.toggle()
        }

        binding.switchMasterExit.setOnCheckedChangeListener { _, isChecked ->
            prefs.isClearOnExitEnabled = isChecked
            if (isChecked && !prefs.isClearOnExitTabsEnabled && !prefs.isClearOnExitHistoryEnabled &&
                !prefs.isClearOnExitCookiesEnabled && !prefs.isClearOnExitCacheEnabled) {
                // If turning on with nothing selected, select all by default
                prefs.isClearOnExitTabsEnabled = true
                prefs.isClearOnExitHistoryEnabled = true
                prefs.isClearOnExitCookiesEnabled = true
                prefs.isClearOnExitCacheEnabled = true
                binding.cbExitTabs.isChecked = true
                binding.cbExitHistory.isChecked = true
                binding.cbExitCookies.isChecked = true
                binding.cbExitCache.isChecked = true
            }
            updateOptionsVisualState(isChecked)
            onUpdated()
        }

        // Granular option row click handlers
        binding.rowExitTabs.setOnClickListener {
            if (binding.switchMasterExit.isChecked) {
                binding.cbExitTabs.isChecked = !binding.cbExitTabs.isChecked
                handleOptionChanged()
            }
        }

        binding.rowExitHistory.setOnClickListener {
            if (binding.switchMasterExit.isChecked) {
                binding.cbExitHistory.isChecked = !binding.cbExitHistory.isChecked
                handleOptionChanged()
            }
        }

        binding.rowExitCookies.setOnClickListener {
            if (binding.switchMasterExit.isChecked) {
                binding.cbExitCookies.isChecked = !binding.cbExitCookies.isChecked
                handleOptionChanged()
            }
        }

        binding.rowExitCache.setOnClickListener {
            if (binding.switchMasterExit.isChecked) {
                binding.cbExitCache.isChecked = !binding.cbExitCache.isChecked
                handleOptionChanged()
            }
        }

        binding.btnDone.setOnClickListener {
            dismiss()
        }
    }

    private fun handleOptionChanged() {
        val b = _binding ?: return
        val anySelected = b.cbExitTabs.isChecked || b.cbExitHistory.isChecked ||
                          b.cbExitCookies.isChecked || b.cbExitCache.isChecked

        prefs.isClearOnExitTabsEnabled = b.cbExitTabs.isChecked
        prefs.isClearOnExitHistoryEnabled = b.cbExitHistory.isChecked
        prefs.isClearOnExitCookiesEnabled = b.cbExitCookies.isChecked
        prefs.isClearOnExitCacheEnabled = b.cbExitCache.isChecked

        if (!anySelected && b.switchMasterExit.isChecked) {
            b.switchMasterExit.isChecked = false
            prefs.isClearOnExitEnabled = false
            updateOptionsVisualState(false)
        }
        onUpdated()
    }

    private fun updateOptionsVisualState(isEnabled: Boolean) {
        val b = _binding ?: return
        val targetAlpha = if (isEnabled) 1.0f else 0.45f
        b.cardGranularOptions.alpha = targetAlpha
        b.rowExitTabs.isEnabled = isEnabled
        b.rowExitHistory.isEnabled = isEnabled
        b.rowExitCookies.isEnabled = isEnabled
        b.rowExitCache.isEnabled = isEnabled
        b.cbExitTabs.isEnabled = isEnabled
        b.cbExitHistory.isEnabled = isEnabled
        b.cbExitCookies.isEnabled = isEnabled
        b.cbExitCache.isEnabled = isEnabled
        b.tvMasterStatus.text = if (isEnabled) {
            prefs.getClearOnExitSummary(requireContext())
        } else {
            getString(R.string.clear_on_exit_disabled)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onUpdated()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ClearOnExitBottomSheet"
    }
}
