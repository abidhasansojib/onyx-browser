package com.onyx.browser.ui.dialog

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.databinding.BottomSheetWebPermissionRequestBinding

/**
 * Modern Material 3 permission request bottom sheet matching Chrome / Brave design.
 * Features:
 * - Clear domain display with HTTPS connection status indicator
 * - Prominent permission icon and contextual description
 * - "Allow this time", "Always allow", and "Block" options
 */
class WebPermissionBottomSheetDialog(
    private val host: String,
    private val permissionTitle: String,
    private val permissionDesc: String,
    private val iconResId: Int,
    private val isHttps: Boolean = true,
    private val onAllowedAlways: () -> Unit,
    private val onAllowedOnce: () -> Unit,
    private val onDenied: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetWebPermissionRequestBinding? = null
    private val binding get() = _binding!!
    private var actionHandled = false

    override fun getTheme(): Int = R.style.Theme_OnyxBrowser_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetWebPermissionRequestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { d ->
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.ivPermissionIcon.setImageResource(iconResId)
        binding.tvPermissionTitle.text = permissionTitle
        binding.tvPermissionHost.text = if (isHttps) "https://$host" else "http://$host"
        binding.tvPermissionDesc.text = permissionDesc

        if (isHttps) {
            binding.ivSecurityIcon.setImageResource(R.drawable.ic_lock)
            binding.ivSecurityIcon.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.green_success)
            )
        } else {
            binding.ivSecurityIcon.setImageResource(R.drawable.ic_lock)
            binding.ivSecurityIcon.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.red_danger)
            )
        }

        binding.btnAllowOnce.setOnClickListener {
            actionHandled = true
            dismiss()
            onAllowedOnce()
        }

        binding.btnAllowAlways.setOnClickListener {
            actionHandled = true
            dismiss()
            onAllowedAlways()
        }

        binding.btnBlock.setOnClickListener {
            actionHandled = true
            dismiss()
            onDenied()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!actionHandled) {
            onDenied()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "WebPermissionBottomSheetDialog"
    }
}
