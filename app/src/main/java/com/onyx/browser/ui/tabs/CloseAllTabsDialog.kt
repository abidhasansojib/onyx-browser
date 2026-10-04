package com.onyx.browser.ui.tabs

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import com.onyx.browser.R
import com.onyx.browser.databinding.DialogConfirmCloseAllTabsBinding
import com.onyx.browser.ui.browser.TabManager

class CloseAllTabsDialog @JvmOverloads constructor(
    private var tabManager: TabManager? = null,
    private var isIncognito: Boolean = false,
    private var onTabsClosed: ((closedCount: Int) -> Unit)? = null
) : DialogFragment() {

    private var _binding: DialogConfirmCloseAllTabsBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        _binding = DialogConfirmCloseAllTabsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tm = tabManager ?: TabManager.activeInstance
        val tabList = if (isIncognito) {
            tm?.incognitoTabs?.value ?: emptyList()
        } else {
            tm?.normalTabs?.value ?: emptyList()
        }
        val count = tabList.size

        if (isIncognito) {
            binding.tvDialogTitle.text = getString(R.string.close_all_incognito_tabs_title)
            binding.tvDialogMessage.text = getString(R.string.close_all_incognito_tabs_desc, count)
        } else {
            binding.tvDialogTitle.text = getString(R.string.close_all_tabs_title)
            binding.tvDialogMessage.text = if (count == 1) {
                getString(R.string.close_single_tab_desc)
            } else {
                getString(R.string.close_all_tabs_desc, count)
            }
        }

        binding.tvTabCountPill.text = if (count == 1) {
            getString(R.string.single_tab_to_be_closed_count)
        } else {
            getString(R.string.tabs_to_be_closed_count, count)
        }

        binding.btnCancelCloseTabs.setOnClickListener {
            dismissAllowingStateLoss()
        }

        binding.btnConfirmCloseTabs.setOnClickListener {
            tm?.closeAllTabs(incognitoOnly = isIncognito, canUndo = true)
            onTabsClosed?.invoke(count)
            dismissAllowingStateLoss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val maxAllowedWidth = (420 * displayMetrics.density).toInt()
            val targetWidth = (displayMetrics.widthPixels * 0.90).toInt().coerceAtMost(maxAllowedWidth)
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CloseAllTabsDialog"
    }
}
