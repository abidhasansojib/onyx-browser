package com.onyx.browser.ui.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.data.preferences.SitePermissionState
import com.onyx.browser.databinding.BottomSheetAllSitesBinding
import com.onyx.browser.web.SiteDataManager

class AllSitesSheet(
    private val onSitesChanged: () -> Unit
) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "AllSitesSheet"
    }

    private var _binding: BottomSheetAllSitesBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: BrowserPreferences
    private var allDomains: List<String> = emptyList()
    private var searchQuery: String = ""

    override fun getTheme(): Int = R.style.Theme_OnyxBrowser_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAllSitesBinding.inflate(inflater, container, false)
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
        prefs = BrowserPreferences.getInstance(requireContext())

        binding.etSearchSites.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty().trim().lowercase()
                renderSitesList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadDomains()
    }

    private fun loadDomains() {
        allDomains = prefs.getAllConfiguredDomains().toList().sorted()
        binding.tvSiteCountBadge.text = "${allDomains.size} sites"
        renderSitesList()
    }

    private fun renderSitesList() {
        binding.containerSites.removeAllViews()
        val filtered = if (searchQuery.isEmpty()) {
            allDomains
        } else {
            allDomains.filter { it.contains(searchQuery) }
        }

        if (filtered.isEmpty()) {
            binding.tvEmptySites.visibility = View.VISIBLE
        } else {
            binding.tvEmptySites.visibility = View.GONE
            for (domain in filtered) {
                addSiteRow(domain)
            }
        }
    }

    private fun addSiteRow(domain: String) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(
                (20 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (20 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt()
            )
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
            setBackgroundResource(typedValue.resourceId)
        }

        val textContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvDomain = TextView(requireContext()).apply {
            text = domain
            textSize = 15f
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            setTextColor(ContextCompat.getColor(context, typedValue.resourceId))
        }

        val summaryItems = mutableListOf<String>()
        val cam = prefs.getSiteCameraPermission(domain)
        if (cam != SitePermissionState.ASK) summaryItems.add("Camera: ${cam.name.lowercase()}")
        val mic = prefs.getSiteMicPermission(domain)
        if (mic != SitePermissionState.ASK) summaryItems.add("Mic: ${mic.name.lowercase()}")
        val loc = prefs.getSiteLocationPermission(domain)
        if (loc != SitePermissionState.ASK) summaryItems.add("Location: ${loc.name.lowercase()}")
        if (prefs.isDomainWhitelisted(domain)) summaryItems.add("Shields: off")

        val tvSummary = TextView(requireContext()).apply {
            text = if (summaryItems.isNotEmpty()) summaryItems.joinToString(" · ") else "Default permissions"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.settings_subtitle_text))
            setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        textContainer.addView(tvDomain)
        textContainer.addView(tvSummary)

        // Clear data icon button
        val btnClearData = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_web)
            setColorFilter(ContextCompat.getColor(context, R.color.settings_title_text))
            val pad = (6 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
            setBackgroundResource(typedValue.resourceId)
            setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.clear_site_data_dialog_title, domain))
                    .setMessage(getString(R.string.clear_site_data_dialog_message))
                    .setPositiveButton(R.string.clear_site_data) { _, _ ->
                        val ctx = requireContext().applicationContext
                        SiteDataManager.clearSiteData(ctx, domain) {
                            Toast.makeText(
                                ctx,
                                getString(R.string.clear_site_data_done, domain),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        // Reset permissions delete button
        val btnReset = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_delete)
            setColorFilter(ContextCompat.getColor(context, R.color.red_danger))
            val pad = (6 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
            setBackgroundResource(typedValue.resourceId)
            setOnClickListener {
                prefs.resetSitePermissions(domain)
                prefs.setDomainWhitelisted(domain, false)
                loadDomains()
                onSitesChanged()
            }
        }

        row.addView(textContainer)
        row.addView(btnClearData)
        row.addView(btnReset)
        binding.containerSites.addView(row)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
