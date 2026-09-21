package com.onyx.browser.ui.menu

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.DialogSiteShieldBinding
import com.onyx.browser.ui.settings.SettingsActivity

class SiteShieldBottomSheetDialog(
    private val currentDomain: String,
    private val onWhitelistChanged: (Boolean) -> Unit
) : DialogFragment() {

    private var _binding: DialogSiteShieldBinding? = null
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
        _binding = DialogSiteShieldBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = BrowserPreferences.getInstance(requireContext())
        val cleanDomain = prefs.cleanDomain(currentDomain)

        binding.tvShieldDomain.text = getString(R.string.protection_for_site, cleanDomain)

        val isWhitelisted = prefs.isDomainWhitelisted(cleanDomain)
        binding.switchDisableAdblock.isChecked = isWhitelisted
        updateStatusView(isWhitelisted)

        binding.switchDisableAdblock.setOnCheckedChangeListener { _, isChecked ->
            prefs.setDomainWhitelisted(cleanDomain, isChecked)
            updateStatusView(isChecked)
            onWhitelistChanged(isChecked)
        }

        binding.btnGlobalAdblockSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
            dismiss()
        }

        binding.btnDoneShield.setOnClickListener {
            dismiss()
        }
    }

    private fun updateStatusView(isWhitelisted: Boolean) {
        if (isWhitelisted) {
            binding.tvShieldStatus.text = getString(R.string.protection_paused)
            binding.ivStatusDot.setColorFilter(ContextCompat.getColor(requireContext(), R.color.text_secondary_dark))
            binding.ivShieldBadgeIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.text_secondary_dark))
        } else {
            binding.tvShieldStatus.text = getString(R.string.protection_active)
            binding.ivStatusDot.setColorFilter(ContextCompat.getColor(requireContext(), R.color.green_success))
            binding.ivShieldBadgeIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.primary))
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
        const val TAG = "SiteShieldDialog"
    }
}
