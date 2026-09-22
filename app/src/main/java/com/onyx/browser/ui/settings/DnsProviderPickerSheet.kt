package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.onyx.browser.R
import com.onyx.browser.databinding.BottomSheetDnsProviderPickerBinding

class DnsProviderPickerSheet(
    private val currentProviderUrl: String,
    private val onSelected: (String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDnsProviderPickerBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_OnyxBrowser_BottomSheetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDnsProviderPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val isKnown = currentProviderUrl in listOf(DNS_CLOUDFLARE, DNS_GOOGLE, DNS_NEXTDNS)
        val initialSelection = if (isKnown) currentProviderUrl else DNS_CUSTOM
        updateSelection(initialSelection)

        if (!isKnown && currentProviderUrl.isNotBlank()) {
            binding.layoutCustomInput.visibility = View.VISIBLE
            binding.etCustomDns.setText(currentProviderUrl)
            binding.tvCustomDnsSummary.text = currentProviderUrl
        }

        binding.optionCloudflare.setOnClickListener {
            onSelected(DNS_CLOUDFLARE)
            dismiss()
        }

        binding.optionGoogle.setOnClickListener {
            onSelected(DNS_GOOGLE)
            dismiss()
        }

        binding.optionNextDns.setOnClickListener {
            onSelected(DNS_NEXTDNS)
            dismiss()
        }

        binding.optionCustom.setOnClickListener {
            updateSelection(DNS_CUSTOM)
            binding.layoutCustomInput.visibility = View.VISIBLE
            if (binding.etCustomDns.text.isNullOrBlank() && !isKnown) {
                binding.etCustomDns.setText(currentProviderUrl)
            }
            binding.etCustomDns.requestFocus()
        }

        binding.btnSaveCustomDns.setOnClickListener {
            val url = binding.etCustomDns.text?.toString()?.trim() ?: ""
            if (url.startsWith("https://", ignoreCase = true)) {
                onSelected(url)
                dismiss()
            } else {
                binding.tilCustomDns.error = "URL must start with https://"
            }
        }

        binding.btnDismiss.setOnClickListener {
            dismiss()
        }
    }

    private fun updateSelection(selectedKey: String) {
        val colorPrimary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val colorOutline = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutline)

        val isCloudflare = selectedKey == DNS_CLOUDFLARE
        binding.ivCheckCloudflare.visibility = if (isCloudflare) View.VISIBLE else View.INVISIBLE
        binding.optionCloudflare.strokeColor = if (isCloudflare) colorPrimary else colorOutline
        binding.optionCloudflare.strokeWidth = if (isCloudflare) dpToPx(2) else dpToPx(1)

        val isGoogle = selectedKey == DNS_GOOGLE
        binding.ivCheckGoogle.visibility = if (isGoogle) View.VISIBLE else View.INVISIBLE
        binding.optionGoogle.strokeColor = if (isGoogle) colorPrimary else colorOutline
        binding.optionGoogle.strokeWidth = if (isGoogle) dpToPx(2) else dpToPx(1)

        val isNextDns = selectedKey == DNS_NEXTDNS
        binding.ivCheckNextDns.visibility = if (isNextDns) View.VISIBLE else View.INVISIBLE
        binding.optionNextDns.strokeColor = if (isNextDns) colorPrimary else colorOutline
        binding.optionNextDns.strokeWidth = if (isNextDns) dpToPx(2) else dpToPx(1)

        val isCustom = selectedKey == DNS_CUSTOM
        binding.ivCheckCustom.visibility = if (isCustom) View.VISIBLE else View.INVISIBLE
        binding.optionCustom.strokeColor = if (isCustom) colorPrimary else colorOutline
        binding.optionCustom.strokeWidth = if (isCustom) dpToPx(2) else dpToPx(1)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DnsProviderPickerSheet"
        const val DNS_CLOUDFLARE = "https://cloudflare-dns.com/dns-query"
        const val DNS_GOOGLE = "https://dns.google/dns-query"
        const val DNS_NEXTDNS = "https://dns.nextdns.io"
        const val DNS_CUSTOM = "__custom__"
    }
}
