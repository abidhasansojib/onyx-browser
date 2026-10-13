package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.data.preferences.SitePermissionState
import com.onyx.browser.databinding.BottomSheetSitePermissionCategoryBinding

class SitePermissionCategorySheet(
    private val categoryType: Int,
    private val onPermissionChanged: () -> Unit
) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "SitePermissionCategorySheet"
        const val CATEGORY_CAMERA = 1
        const val CATEGORY_MIC = 2
        const val CATEGORY_LOCATION = 3
        const val CATEGORY_NOTIFICATIONS = 4
        const val CATEGORY_JAVASCRIPT = 5
        const val CATEGORY_POPUPS = 6
        const val CATEGORY_AUTOPLAY = 7
        const val CATEGORY_IMAGES = 8
        const val CATEGORY_ZOOM = 9
        const val CATEGORY_MIXED_CONTENT = 10
        const val CATEGORY_DESKTOP = 11
    }

    private var _binding: BottomSheetSitePermissionCategoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: BrowserPreferences

    override fun getTheme(): Int = R.style.Theme_OnyxBrowser_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSitePermissionCategoryBinding.inflate(inflater, container, false)
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
        setupHeader()
        setupGlobalToggle()
        populateSiteLists()
        binding.btnAddSiteException.setOnClickListener { showAddExceptionDialog() }
    }

    private fun setupHeader() {
        val (iconRes, titleRes) = when (categoryType) {
            CATEGORY_CAMERA -> Pair(R.drawable.ic_camera, R.string.camera_settings_title)
            CATEGORY_MIC -> Pair(R.drawable.ic_mic, R.string.mic_settings_title)
            CATEGORY_LOCATION -> Pair(R.drawable.ic_location, R.string.location_settings_title)
            CATEGORY_NOTIFICATIONS -> Pair(R.drawable.ic_bell, R.string.notifications_settings_title)
            CATEGORY_JAVASCRIPT -> Pair(R.drawable.ic_code, R.string.javascript_settings_title)
            CATEGORY_POPUPS -> Pair(R.drawable.ic_open_in_new, R.string.popups_settings_title)
            CATEGORY_AUTOPLAY -> Pair(R.drawable.ic_play_circle, R.string.autoplay_settings_title)
            CATEGORY_IMAGES -> Pair(R.drawable.ic_image, R.string.images_settings_title)
            CATEGORY_ZOOM -> Pair(R.drawable.ic_aspect_ratio, R.string.zoom_settings_title)
            CATEGORY_MIXED_CONTENT -> Pair(R.drawable.ic_lock, R.string.mixed_content_settings_title)
            CATEGORY_DESKTOP -> Pair(R.drawable.ic_desktop, R.string.desktop_site_settings_title)
            else -> Pair(R.drawable.ic_web, R.string.site_settings_title)
        }
        binding.ivCategoryIcon.setImageResource(iconRes)
        binding.tvCategoryTitle.text = getString(titleRes)
        updateSubtitle()
    }

    private fun updateSubtitle() {
        val allowed = getAllowedDomains()
        val blocked = getBlockedDomains()
        val parts = mutableListOf<String>()
        if (allowed.isNotEmpty()) parts.add("${allowed.size} ${if (allowed.size == 1) "site" else "sites"} allowed")
        if (blocked.isNotEmpty()) parts.add("${blocked.size} blocked")
        binding.tvCategorySubtitle.text = if (parts.isEmpty()) getString(R.string.no_exceptions_set) else parts.joinToString(" · ")
    }

    private fun setupGlobalToggle() {
        val isEnabled = getGlobalEnabled()
        updateGlobalToggleUi(isEnabled)

        binding.layoutGlobalToggle.setOnClickListener {
            val newState = !binding.switchGlobalToggle.isChecked
            setGlobalEnabled(newState)
            updateGlobalToggleUi(newState)
            onPermissionChanged()
        }
    }

    private fun getGlobalEnabled(): Boolean = when (categoryType) {
        CATEGORY_CAMERA -> prefs.isCameraEnabledGlobal
        CATEGORY_MIC -> prefs.isMicEnabledGlobal
        CATEGORY_LOCATION -> prefs.isLocationEnabledGlobal
        CATEGORY_NOTIFICATIONS -> prefs.isNotificationsEnabledGlobal
        CATEGORY_JAVASCRIPT -> prefs.isJavaScriptEnabledGlobal
        CATEGORY_POPUPS -> !prefs.isPopupsBlockedGlobal   // toggled: true = allowed
        CATEGORY_AUTOPLAY -> !prefs.isAutoplayBlockedGlobal
        CATEGORY_IMAGES -> prefs.isImagesEnabledGlobal
        CATEGORY_ZOOM -> prefs.isZoomEnabledGlobal
        CATEGORY_MIXED_CONTENT -> !prefs.isMixedContentBlockedGlobal
        CATEGORY_DESKTOP -> prefs.isDesktopMode
        else -> true
    }

    private fun setGlobalEnabled(value: Boolean) = when (categoryType) {
        CATEGORY_CAMERA -> prefs.isCameraEnabledGlobal = value
        CATEGORY_MIC -> prefs.isMicEnabledGlobal = value
        CATEGORY_LOCATION -> prefs.isLocationEnabledGlobal = value
        CATEGORY_NOTIFICATIONS -> prefs.isNotificationsEnabledGlobal = value
        CATEGORY_JAVASCRIPT -> prefs.isJavaScriptEnabledGlobal = value
        CATEGORY_POPUPS -> prefs.isPopupsBlockedGlobal = !value
        CATEGORY_AUTOPLAY -> prefs.isAutoplayBlockedGlobal = !value
        CATEGORY_IMAGES -> prefs.isImagesEnabledGlobal = value
        CATEGORY_ZOOM -> prefs.isZoomEnabledGlobal = value
        CATEGORY_MIXED_CONTENT -> prefs.isMixedContentBlockedGlobal = !value
        CATEGORY_DESKTOP -> prefs.isDesktopMode = value
        else -> {}
    }

    private fun updateGlobalToggleUi(isEnabled: Boolean) {
        binding.switchGlobalToggle.isChecked = isEnabled
        val (titleText, descText) = when (categoryType) {
            CATEGORY_JAVASCRIPT -> if (isEnabled)
                Pair("JavaScript enabled", "Sites can run JavaScript (recommended)")
            else
                Pair("JavaScript blocked", "All sites are blocked from running JavaScript")
            CATEGORY_POPUPS -> if (isEnabled)
                Pair("Pop-ups allowed", "Sites can open new windows and tabs")
            else
                Pair(getString(R.string.blocked_globally), "All sites are blocked from opening pop-ups")
            CATEGORY_AUTOPLAY -> if (isEnabled)
                Pair("Autoplay allowed", "Sites can autoplay audio and video")
            else
                Pair("Autoplay blocked", "Sites cannot autoplay media without interaction")
            CATEGORY_IMAGES -> if (isEnabled)
                Pair("Images shown", "Sites can display images (recommended)")
            else
                Pair("Images blocked", "All sites are blocked from loading images")
            CATEGORY_ZOOM -> if (isEnabled)
                Pair("Zoom allowed", "You can pinch-to-zoom on all pages")
            else
                Pair("Zoom blocked", "Sites control whether pinch-to-zoom is allowed")
            CATEGORY_MIXED_CONTENT -> if (isEnabled)
                Pair("Insecure content allowed", "Sites can load HTTP resources on HTTPS pages")
            else
                Pair("Insecure content blocked", "Sites cannot load HTTP resources on HTTPS pages (recommended)")
            CATEGORY_NOTIFICATIONS -> if (isEnabled)
                Pair(getString(R.string.ask_before_accessing), "Sites must ask before sending notifications")
            else
                Pair(getString(R.string.blocked_globally), "All sites are blocked from sending notifications")
            CATEGORY_DESKTOP -> if (isEnabled)
                Pair("Desktop site on", "Pages show the desktop version of websites")
            else
                Pair("Desktop site off", "Pages show the mobile version of websites")
            else -> if (isEnabled)
                Pair(getString(R.string.ask_before_accessing), "Sites must ask for permission before using this device")
            else
                Pair(getString(R.string.blocked_globally), "All sites are blocked from using this device")
        }
        binding.tvGlobalToggleTitle.text = titleText
        binding.tvGlobalToggleDesc.text = descText
    }

    private fun getAllowedDomains(): Set<String> = when (categoryType) {
        CATEGORY_CAMERA -> prefs.getAllowedCameraDomains()
        CATEGORY_MIC -> prefs.getAllowedMicDomains()
        CATEGORY_LOCATION -> prefs.getAllowedLocationDomains()
        CATEGORY_NOTIFICATIONS -> prefs.getAllowedNotifDomains()
        CATEGORY_JAVASCRIPT -> prefs.getAllowedJsDomains()
        CATEGORY_POPUPS -> prefs.getAllowedPopupDomains()
        CATEGORY_AUTOPLAY -> prefs.getAllowedAutoplayDomains()
        CATEGORY_IMAGES -> prefs.getAllowedImagesDomains()
        CATEGORY_ZOOM -> prefs.getAllowedZoomDomains()
        CATEGORY_MIXED_CONTENT -> prefs.getAllowedMixedDomains()
        CATEGORY_DESKTOP -> prefs.desktopDomains
        else -> emptySet()
    }

    private fun getBlockedDomains(): Set<String> = when (categoryType) {
        CATEGORY_CAMERA -> prefs.getBlockedCameraDomains()
        CATEGORY_MIC -> prefs.getBlockedMicDomains()
        CATEGORY_LOCATION -> prefs.getBlockedLocationDomains()
        CATEGORY_NOTIFICATIONS -> prefs.getBlockedNotifDomains()
        CATEGORY_JAVASCRIPT -> prefs.getBlockedJsDomains()
        CATEGORY_POPUPS -> prefs.getBlockedPopupDomains()
        CATEGORY_AUTOPLAY -> prefs.getBlockedAutoplayDomains()
        CATEGORY_IMAGES -> prefs.getBlockedImagesDomains()
        CATEGORY_ZOOM -> prefs.getBlockedZoomDomains()
        CATEGORY_MIXED_CONTENT -> prefs.getBlockedMixedDomains()
        CATEGORY_DESKTOP -> emptySet()
        else -> emptySet()
    }

    private fun clearSitePermission(domain: String) = when (categoryType) {
        CATEGORY_CAMERA -> prefs.setSiteCameraPermission(domain, SitePermissionState.ASK)
        CATEGORY_MIC -> prefs.setSiteMicPermission(domain, SitePermissionState.ASK)
        CATEGORY_LOCATION -> prefs.setSiteLocationPermission(domain, SitePermissionState.ASK)
        CATEGORY_NOTIFICATIONS -> prefs.setSiteNotificationsPermission(domain, SitePermissionState.ASK)
        CATEGORY_JAVASCRIPT -> prefs.setSiteJavaScriptPermission(domain, SitePermissionState.ASK)
        CATEGORY_POPUPS -> prefs.setSitePopupsPermission(domain, SitePermissionState.ASK)
        CATEGORY_AUTOPLAY -> prefs.setSiteAutoplayPermission(domain, SitePermissionState.ASK)
        CATEGORY_IMAGES -> prefs.setSiteImagesPermission(domain, SitePermissionState.ASK)
        CATEGORY_ZOOM -> prefs.setSiteZoomPermission(domain, SitePermissionState.ASK)
        CATEGORY_MIXED_CONTENT -> prefs.setSiteMixedContentPermission(domain, SitePermissionState.ASK)
        CATEGORY_DESKTOP -> {
            val d = prefs.desktopDomains.toMutableSet()
            d.remove(domain)
            prefs.desktopDomains = d
        }
        else -> {}
    }

    private fun setSitePermissionAllowed(domain: String) = when (categoryType) {
        CATEGORY_CAMERA -> prefs.setSiteCameraPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_MIC -> prefs.setSiteMicPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_LOCATION -> prefs.setSiteLocationPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_NOTIFICATIONS -> prefs.setSiteNotificationsPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_JAVASCRIPT -> prefs.setSiteJavaScriptPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_POPUPS -> prefs.setSitePopupsPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_AUTOPLAY -> prefs.setSiteAutoplayPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_IMAGES -> prefs.setSiteImagesPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_ZOOM -> prefs.setSiteZoomPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_MIXED_CONTENT -> prefs.setSiteMixedContentPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_DESKTOP -> {
            val d = prefs.desktopDomains.toMutableSet()
            d.add(domain)
            prefs.desktopDomains = d
        }
        else -> {}
    }

    private fun setSitePermissionBlocked(domain: String) = when (categoryType) {
        CATEGORY_CAMERA -> prefs.setSiteCameraPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_MIC -> prefs.setSiteMicPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_LOCATION -> prefs.setSiteLocationPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_NOTIFICATIONS -> prefs.setSiteNotificationsPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_JAVASCRIPT -> prefs.setSiteJavaScriptPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_POPUPS -> prefs.setSitePopupsPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_AUTOPLAY -> prefs.setSiteAutoplayPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_IMAGES -> prefs.setSiteImagesPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_ZOOM -> prefs.setSiteZoomPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_MIXED_CONTENT -> prefs.setSiteMixedContentPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_DESKTOP -> clearSitePermission(domain)
        else -> {}
    }

    private fun showAddExceptionDialog() {
        val inputET = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.add_site_exception_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(requireContext()).apply {
            setPadding(padding, 0, padding, 0)
            addView(inputET)
        }
        val isDesktopCategory = categoryType == CATEGORY_DESKTOP
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_site_exception)
            .setView(container)
            .setNeutralButton(R.string.cancel, null)
        if (!isDesktopCategory) {
            builder
                .setPositiveButton(getString(R.string.permission_allowed)) { _, _ ->
                    val domain = prefs.cleanDomain(inputET.text.toString().trim())
                    if (domain.isNotBlank()) {
                        clearSitePermission(domain)
                        setSitePermissionAllowed(domain)
                        populateSiteLists()
                        onPermissionChanged()
                    }
                }
                .setNegativeButton(getString(R.string.permission_blocked)) { _, _ ->
                    val domain = prefs.cleanDomain(inputET.text.toString().trim())
                    if (domain.isNotBlank()) {
                        clearSitePermission(domain)
                        setSitePermissionBlocked(domain)
                        populateSiteLists()
                        onPermissionChanged()
                    }
                }
        } else {
            builder.setPositiveButton("Enable desktop") { _, _ ->
                val domain = prefs.cleanDomain(inputET.text.toString().trim())
                if (domain.isNotBlank()) {
                    setSitePermissionAllowed(domain)
                    populateSiteLists()
                    onPermissionChanged()
                }
            }
        }
        builder.show()
    }

    private fun populateSiteLists() {
        binding.containerAllowedSites.removeAllViews()
        binding.containerBlockedSites.removeAllViews()

        val allowedDomains = getAllowedDomains()
        val blockedDomains = getBlockedDomains()

        val hasAllowed = allowedDomains.isNotEmpty()
        val hasBlocked = blockedDomains.isNotEmpty()

        binding.tvAllowedHeader.visibility = if (hasAllowed) View.VISIBLE else View.GONE
        binding.cardAllowedSites.visibility = if (hasAllowed) View.VISIBLE else View.GONE
        binding.tvBlockedHeader.visibility = if (hasBlocked) View.VISIBLE else View.GONE
        binding.cardBlockedSites.visibility = if (hasBlocked) View.VISIBLE else View.GONE
        binding.layoutEmptySites.visibility = if (!hasAllowed && !hasBlocked) View.VISIBLE else View.GONE

        val allowedList = allowedDomains.sorted()
        for ((index, domain) in allowedList.withIndex()) {
            addDomainRow(binding.containerAllowedSites, domain, true, index < allowedList.size - 1)
        }
        val blockedList = blockedDomains.sorted()
        for ((index, domain) in blockedList.withIndex()) {
            addDomainRow(binding.containerBlockedSites, domain, false, index < blockedList.size - 1)
        }
        updateSubtitle()
    }

    private fun addDomainRow(
        container: LinearLayout,
        domain: String,
        isAllowed: Boolean,
        showDivider: Boolean
    ) {
        val density = resources.displayMetrics.density

        val rowWrapper = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
            setBackgroundResource(typedValue.resourceId)
        }

        val ivGlobe = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_web)
            val iconColor = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorSecondary, iconColor, true)
            setColorFilter(ContextCompat.getColor(context, iconColor.resourceId))
            val iconSize = (18 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = (12 * density).toInt() }
        }

        val tvDomain = TextView(requireContext()).apply {
            text = domain
            textSize = 14.5f
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            setTextColor(ContextCompat.getColor(context, typedValue.resourceId))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvStatus = TextView(requireContext()).apply {
            text = if (isAllowed) getString(R.string.permission_allowed) else getString(R.string.permission_blocked)
            textSize = 11.5f
            setTextColor(
                if (isAllowed) ContextCompat.getColor(context, R.color.green_success)
                else ContextCompat.getColor(context, R.color.red_danger)
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (10 * density).toInt()
            }
        }

        val btnDelete = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_delete)
            setColorFilter(ContextCompat.getColor(context, R.color.settings_subtitle_text))
            val pad = (5 * density).toInt()
            setPadding(pad, pad, pad, pad)
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
            setBackgroundResource(typedValue.resourceId)
            val iconSize = (32 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setOnClickListener {
                clearSitePermission(domain)
                populateSiteLists()
                onPermissionChanged()
            }
        }

        row.addView(ivGlobe)
        row.addView(tvDomain)
        row.addView(tvStatus)
        row.addView(btnDelete)
        rowWrapper.addView(row)

        if (showDivider) {
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = (46 * density).toInt()
                }
                setBackgroundColor(ContextCompat.getColor(context, R.color.settings_card_stroke))
            }
            rowWrapper.addView(divider)
        }

        container.addView(rowWrapper)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

