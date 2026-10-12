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
    }

    private fun setupHeader() {
        when (categoryType) {
            CATEGORY_CAMERA -> {
                binding.ivCategoryIcon.setImageResource(R.drawable.ic_camera)
                binding.tvCategoryTitle.text = getString(R.string.camera_settings_title)
            }
            CATEGORY_MIC -> {
                binding.ivCategoryIcon.setImageResource(R.drawable.ic_mic)
                binding.tvCategoryTitle.text = getString(R.string.mic_settings_title)
            }
            CATEGORY_LOCATION -> {
                binding.ivCategoryIcon.setImageResource(R.drawable.ic_location)
                binding.tvCategoryTitle.text = getString(R.string.location_settings_title)
            }
        }
        updateSubtitle()
    }

    private fun updateSubtitle() {
        val allowed = getAllowedDomains()
        val blocked = getBlockedDomains()
        val parts = mutableListOf<String>()
        if (allowed.isNotEmpty()) parts.add("${allowed.size} ${if (allowed.size == 1) "site" else "sites"} allowed")
        if (blocked.isNotEmpty()) parts.add("${blocked.size} blocked")
        binding.tvCategorySubtitle.text = if (parts.isEmpty()) "No exceptions set" else parts.joinToString(" · ")
    }

    private fun setupGlobalToggle() {
        val isEnabled = when (categoryType) {
            CATEGORY_CAMERA -> prefs.isCameraEnabledGlobal
            CATEGORY_MIC -> prefs.isMicEnabledGlobal
            CATEGORY_LOCATION -> prefs.isLocationEnabledGlobal
            else -> true
        }

        updateGlobalToggleUi(isEnabled)

        binding.layoutGlobalToggle.setOnClickListener {
            val newState = !binding.switchGlobalToggle.isChecked
            when (categoryType) {
                CATEGORY_CAMERA -> prefs.isCameraEnabledGlobal = newState
                CATEGORY_MIC -> prefs.isMicEnabledGlobal = newState
                CATEGORY_LOCATION -> prefs.isLocationEnabledGlobal = newState
            }
            updateGlobalToggleUi(newState)
            onPermissionChanged()
        }
    }

    private fun updateGlobalToggleUi(isEnabled: Boolean) {
        binding.switchGlobalToggle.isChecked = isEnabled
        if (isEnabled) {
            binding.tvGlobalToggleTitle.text = getString(R.string.ask_before_accessing)
            binding.tvGlobalToggleDesc.text = "Sites must ask for permission before using this device"
        } else {
            binding.tvGlobalToggleTitle.text = getString(R.string.blocked_globally)
            binding.tvGlobalToggleDesc.text = "All sites are blocked from using this device"
        }
    }

    private fun getAllowedDomains(): Set<String> = when (categoryType) {
        CATEGORY_CAMERA -> prefs.getAllowedCameraDomains()
        CATEGORY_MIC -> prefs.getAllowedMicDomains()
        CATEGORY_LOCATION -> prefs.getAllowedLocationDomains()
        else -> emptySet()
    }

    private fun getBlockedDomains(): Set<String> = when (categoryType) {
        CATEGORY_CAMERA -> prefs.getBlockedCameraDomains()
        CATEGORY_MIC -> prefs.getBlockedMicDomains()
        CATEGORY_LOCATION -> prefs.getBlockedLocationDomains()
        else -> emptySet()
    }

    private fun populateSiteLists() {
        binding.containerAllowedSites.removeAllViews()
        binding.containerBlockedSites.removeAllViews()

        val allowedDomains = getAllowedDomains()
        val blockedDomains = getBlockedDomains()

        val hasAllowed = allowedDomains.isNotEmpty()
        val hasBlocked = blockedDomains.isNotEmpty()

        // Allowed section
        binding.tvAllowedHeader.visibility = if (hasAllowed) View.VISIBLE else View.GONE
        binding.cardAllowedSites.visibility = if (hasAllowed) View.VISIBLE else View.GONE

        // Blocked section
        binding.tvBlockedHeader.visibility = if (hasBlocked) View.VISIBLE else View.GONE
        binding.cardBlockedSites.visibility = if (hasBlocked) View.VISIBLE else View.GONE

        // Empty state
        binding.layoutEmptySites.visibility = if (!hasAllowed && !hasBlocked) View.VISIBLE else View.GONE

        val allowedList = allowedDomains.sorted()
        for ((index, domain) in allowedList.withIndex()) {
            addDomainRow(
                container = binding.containerAllowedSites,
                domain = domain,
                isAllowed = true,
                showDivider = index < allowedList.size - 1
            )
        }

        val blockedList = blockedDomains.sorted()
        for ((index, domain) in blockedList.withIndex()) {
            addDomainRow(
                container = binding.containerBlockedSites,
                domain = domain,
                isAllowed = false,
                showDivider = index < blockedList.size - 1
            )
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
        }

        // Globe icon
        val ivGlobe = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_web)
            val iconColor = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorSecondary, iconColor, true)
            setColorFilter(ContextCompat.getColor(context, iconColor.resourceId))
            val iconSize = (18 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = (12 * density).toInt()
            }
        }

        val tvDomain = TextView(requireContext()).apply {
            text = domain
            textSize = 14.5f
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            setTextColor(ContextCompat.getColor(context, typedValue.resourceId))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Status chip
        val tvStatus = TextView(requireContext()).apply {
            text = if (isAllowed) getString(R.string.permission_allowed) else getString(R.string.permission_blocked)
            textSize = 11.5f
            setTextColor(
                if (isAllowed) ContextCompat.getColor(context, R.color.green_success)
                else ContextCompat.getColor(context, R.color.red_danger)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (10 * density).toInt()
            }
        }

        // Delete button
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
                when (categoryType) {
                    CATEGORY_CAMERA -> prefs.setSiteCameraPermission(domain, SitePermissionState.ASK)
                    CATEGORY_MIC -> prefs.setSiteMicPermission(domain, SitePermissionState.ASK)
                    CATEGORY_LOCATION -> prefs.setSiteLocationPermission(domain, SitePermissionState.ASK)
                }
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
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1
                ).apply {
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
