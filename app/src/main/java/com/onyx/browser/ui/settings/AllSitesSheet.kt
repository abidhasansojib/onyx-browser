package com.onyx.browser.ui.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
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
        val count = allDomains.size
        binding.tvSiteCountBadge.text = when {
            count == 0 -> "No custom settings"
            count == 1 -> "1 site with custom settings"
            else -> "$count sites with custom settings"
        }
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
            binding.cardSitesList.visibility = View.GONE
            binding.layoutEmptySites.visibility = View.VISIBLE
        } else {
            binding.cardSitesList.visibility = View.VISIBLE
            binding.layoutEmptySites.visibility = View.GONE
            for ((index, domain) in filtered.withIndex()) {
                addSiteRow(domain, showDivider = index < filtered.size - 1)
            }
        }
    }

    private fun addSiteRow(domain: String, showDivider: Boolean) {
        val density = resources.displayMetrics.density

        val rowWrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (16 * density).toInt(),
                (14 * density).toInt(),
                (16 * density).toInt(),
                (14 * density).toInt()
            )
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
            setBackgroundResource(typedValue.resourceId)
            isClickable = true
            isFocusable = true
        }

        // Globe icon placeholder for favicon
        val ivGlobe = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_web)
            val iconColor = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorSecondary, iconColor, true)
            setColorFilter(ContextCompat.getColor(context, iconColor.resourceId))
            val size = (36 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (12 * density).toInt()
            }
        }

        val textContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvDomain = TextView(requireContext()).apply {
            text = domain
            textSize = 14.5f
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            setTextColor(ContextCompat.getColor(context, typedValue.resourceId))
        }

        // Build a permission summary line
        val summaryParts = mutableListOf<String>()
        val cam = prefs.getSiteCameraPermission(domain)
        if (cam == SitePermissionState.ALLOWED) summaryParts.add("Camera: allowed")
        else if (cam == SitePermissionState.BLOCKED) summaryParts.add("Camera: blocked")
        val mic = prefs.getSiteMicPermission(domain)
        if (mic == SitePermissionState.ALLOWED) summaryParts.add("Mic: allowed")
        else if (mic == SitePermissionState.BLOCKED) summaryParts.add("Mic: blocked")
        val loc = prefs.getSiteLocationPermission(domain)
        if (loc == SitePermissionState.ALLOWED) summaryParts.add("Location: allowed")
        else if (loc == SitePermissionState.BLOCKED) summaryParts.add("Location: blocked")
        val notif = prefs.getSiteNotificationsPermission(domain)
        if (notif == SitePermissionState.ALLOWED) summaryParts.add("Notif: allowed")
        else if (notif == SitePermissionState.BLOCKED) summaryParts.add("Notif: blocked")
        val js = prefs.getSiteJavaScriptPermission(domain)
        if (js == SitePermissionState.ALLOWED) summaryParts.add("JS: allowed")
        else if (js == SitePermissionState.BLOCKED) summaryParts.add("JS: blocked")
        val popup = prefs.getSitePopupsPermission(domain)
        if (popup == SitePermissionState.ALLOWED) summaryParts.add("Popups: allowed")
        else if (popup == SitePermissionState.BLOCKED) summaryParts.add("Popups: blocked")
        val auto = prefs.getSiteAutoplayPermission(domain)
        if (auto == SitePermissionState.ALLOWED) summaryParts.add("Autoplay: allowed")
        else if (auto == SitePermissionState.BLOCKED) summaryParts.add("Autoplay: blocked")
        val img = prefs.getSiteImagesPermission(domain)
        if (img == SitePermissionState.ALLOWED) summaryParts.add("Images: allowed")
        else if (img == SitePermissionState.BLOCKED) summaryParts.add("Images: blocked")
        val zoom = prefs.getSiteZoomPermission(domain)
        if (zoom == SitePermissionState.ALLOWED) summaryParts.add("Zoom: allowed")
        else if (zoom == SitePermissionState.BLOCKED) summaryParts.add("Zoom: blocked")
        val mixed = prefs.getSiteMixedContentPermission(domain)
        if (mixed == SitePermissionState.ALLOWED) summaryParts.add("Mixed: allowed")
        else if (mixed == SitePermissionState.BLOCKED) summaryParts.add("Mixed: blocked")
        if (prefs.isDesktopModeForDomain(domain)) summaryParts.add("Desktop: on")
        if (prefs.isDomainWhitelisted(domain)) summaryParts.add("Shields: off")
        val blockingLevel = prefs.getSiteBlockingLevel(domain)
        if (blockingLevel != null) {
            summaryParts.add("Shields: ${if (blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE) "aggressive" else "standard"}")
        }
        if (prefs.isScriptBlockingEnabledForDomain(domain)) summaryParts.add("Scripts: blocked")
        val customCount = prefs.getCustomBlockedSelectorsForDomain(domain).size
        if (customCount > 0) summaryParts.add("$customCount elements blocked")

        val tvSummary = TextView(requireContext()).apply {
            text = if (summaryParts.isNotEmpty()) summaryParts.joinToString(" · ") else "Default permissions"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.settings_subtitle_text))
            setPadding(0, (2 * density).toInt(), 0, 0)
        }

        textContainer.addView(tvDomain)
        textContainer.addView(tvSummary)

        row.setOnClickListener {
            val sheet = SingleSiteSettingsSheet(domain) {
                loadDomains()
                onSitesChanged()
            }
            sheet.show(parentFragmentManager, SingleSiteSettingsSheet.TAG)
        }

        // Clear data icon button (using delete icon, represents clearing)
        val btnClearData = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_delete)
            setColorFilter(ContextCompat.getColor(context, R.color.settings_subtitle_text))
            val pad = (5 * density).toInt()
            setPadding(pad, pad, pad, pad)
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
            setBackgroundResource(typedValue.resourceId)
            val iconSize = (34 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginStart = (4 * density).toInt()
            }
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
                        // Reset permissions too for clean slate
                        prefs.resetSitePermissions(domain)
                        loadDomains()
                        onSitesChanged()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        row.addView(ivGlobe)
        row.addView(textContainer)
        row.addView(btnClearData)
        rowWrapper.addView(row)

        if (showDivider) {
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1
                ).apply {
                    marginStart = (64 * density).toInt()
                }
                setBackgroundColor(ContextCompat.getColor(context, R.color.settings_card_stroke))
            }
            rowWrapper.addView(divider)
        }

        binding.containerSites.addView(rowWrapper)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
