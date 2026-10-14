package com.onyx.browser.ui.settings

import android.os.Bundle
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
import com.onyx.browser.databinding.BottomSheetSingleSiteSettingsBinding
import com.onyx.browser.web.SiteDataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SingleSiteSettingsSheet(
    private val rawDomain: String,
    private val onSiteChanged: () -> Unit
) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "SingleSiteSettingsSheet"
    }

    private var _binding: BottomSheetSingleSiteSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: BrowserPreferences
    private lateinit var domain: String

    override fun getTheme(): Int = R.style.Theme_OnyxBrowser_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSingleSiteSettingsBinding.inflate(inflater, container, false)
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
        domain = prefs.cleanDomain(rawDomain)

        binding.tvSiteDomain.text = domain

        binding.rowClearSiteData.setOnClickListener {
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
                    prefs.resetSitePermissions(domain)
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            com.onyx.browser.data.filter.FilterListManager.recompileFilters(ctx)
                        } catch (_: Exception) {}
                    }
                    populatePermissions()
                    onSiteChanged()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnResetPermissions.setOnClickListener {
            val ctx = requireContext().applicationContext
            prefs.resetSitePermissions(domain)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    com.onyx.browser.data.filter.FilterListManager.recompileFilters(ctx)
                } catch (_: Exception) {}
            }
            populatePermissions()
            onSiteChanged()
            Toast.makeText(requireContext(), getString(R.string.permission_reset_done, domain), Toast.LENGTH_SHORT).show()
        }

        populatePermissions()
    }

    private data class PermissionItem(
        val key: String,
        val iconRes: Int,
        val title: String,
        val getState: () -> String,
        val options: List<String>? = null,
        val getCheckedIndex: (() -> Int)? = null,
        val onChoose: (Int) -> Unit
    )

    private fun populatePermissions() {
        binding.containerPermissions.removeAllViews()

        val items = mutableListOf<PermissionItem>()

        // 1. Brave Shields (Shields Up / Down)
        items.add(
            PermissionItem(
                key = "shields",
                iconRes = R.drawable.ic_shield_lock,
                title = getString(R.string.single_site_shields_title),
                getState = {
                    if (prefs.isDomainWhitelisted(domain)) getString(R.string.shields_down)
                    else getString(R.string.shields_up)
                },
                options = listOf(
                    getString(R.string.shields_up),
                    getString(R.string.shields_down),
                    getString(R.string.default_permission)
                ),
                getCheckedIndex = {
                    if (prefs.isDomainWhitelisted(domain)) 1 else 0
                },
                onChoose = { which ->
                    when (which) {
                        0 -> prefs.setDomainWhitelisted(domain, false)
                        1 -> prefs.setDomainWhitelisted(domain, true)
                        else -> prefs.setDomainWhitelisted(domain, false)
                    }
                    refresh()
                }
            )
        )

        // 2. Ad & tracker blocking level (Standard / Aggressive / Default)
        items.add(
            PermissionItem(
                key = "blocking_level",
                iconRes = R.drawable.ic_shield,
                title = getString(R.string.single_site_blocking_level_title),
                getState = {
                    when (prefs.getSiteBlockingLevel(domain)) {
                        BrowserPreferences.BLOCKING_STANDARD -> getString(R.string.blocking_standard)
                        BrowserPreferences.BLOCKING_AGGRESSIVE -> getString(R.string.blocking_aggressive)
                        else -> "${getString(R.string.default_permission)} (${if (prefs.blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE) getString(R.string.blocking_aggressive) else getString(R.string.blocking_standard)})"
                    }
                },
                options = listOf(
                    getString(R.string.blocking_standard),
                    getString(R.string.blocking_aggressive),
                    "${getString(R.string.default_permission)} (${if (prefs.blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE) getString(R.string.blocking_aggressive) else getString(R.string.blocking_standard)})"
                ),
                getCheckedIndex = {
                    when (prefs.getSiteBlockingLevel(domain)) {
                        BrowserPreferences.BLOCKING_STANDARD -> 0
                        BrowserPreferences.BLOCKING_AGGRESSIVE -> 1
                        else -> 2
                    }
                },
                onChoose = { which ->
                    when (which) {
                        0 -> prefs.setSiteBlockingLevel(domain, BrowserPreferences.BLOCKING_STANDARD)
                        1 -> prefs.setSiteBlockingLevel(domain, BrowserPreferences.BLOCKING_AGGRESSIVE)
                        else -> prefs.setSiteBlockingLevel(domain, null)
                    }
                    refresh()
                }
            )
        )

        // 3. Custom Blocked Elements (if any exist on this domain)
        val customSelectors = prefs.getCustomBlockedSelectorsForDomain(domain)
        if (customSelectors.isNotEmpty()) {
            items.add(
                PermissionItem(
                    key = "custom_blocked",
                    iconRes = R.drawable.ic_block_element,
                    title = getString(R.string.custom_blocked_elements_title),
                    getState = {
                        if (customSelectors.size == 1) getString(R.string.custom_blocked_elements_single)
                        else getString(R.string.custom_blocked_elements_plural, customSelectors.size)
                    },
                    options = listOf(
                        getString(R.string.permission_reset),
                        getString(R.string.cancel)
                    ),
                    getCheckedIndex = { 1 },
                    onChoose = { which ->
                        if (which == 0) {
                            val ctx = requireContext().applicationContext
                            prefs.clearCustomBlockedRulesForDomain(domain)
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    com.onyx.browser.data.filter.FilterListManager.recompileFilters(ctx)
                                } catch (_: Exception) {}
                            }
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.custom_blocked_elements_reset_done, domain),
                                Toast.LENGTH_SHORT
                            ).show()
                            refresh()
                        }
                    }
                )
            )
        }

        val standardItems = listOf(
            PermissionItem(
                key = "camera",
                iconRes = R.drawable.ic_camera,
                title = getString(R.string.camera_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteCameraPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteCameraPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "mic",
                iconRes = R.drawable.ic_mic,
                title = getString(R.string.mic_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteMicPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteMicPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "location",
                iconRes = R.drawable.ic_location,
                title = getString(R.string.location_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteLocationPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteLocationPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "notifications",
                iconRes = R.drawable.ic_bell,
                title = getString(R.string.notifications_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteNotificationsPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteNotificationsPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "javascript",
                iconRes = R.drawable.ic_code,
                title = getString(R.string.javascript_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteJavaScriptPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteJavaScriptPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "popups",
                iconRes = R.drawable.ic_open_in_new,
                title = getString(R.string.popups_settings_title),
                getState = { getStandardStateLabel(prefs.getSitePopupsPermission(domain)) },
                onChoose = { which ->
                    prefs.setSitePopupsPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "autoplay",
                iconRes = R.drawable.ic_play_circle,
                title = getString(R.string.autoplay_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteAutoplayPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteAutoplayPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "images",
                iconRes = R.drawable.ic_image,
                title = getString(R.string.images_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteImagesPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteImagesPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "zoom",
                iconRes = R.drawable.ic_aspect_ratio,
                title = getString(R.string.zoom_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteZoomPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteZoomPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "mixed_content",
                iconRes = R.drawable.ic_lock,
                title = getString(R.string.mixed_content_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteMixedContentPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteMixedContentPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "desktop",
                iconRes = R.drawable.ic_desktop,
                title = getString(R.string.desktop_site_settings_title),
                getState = {
                    when (prefs.getSiteDesktopModeState(domain)) {
                        SitePermissionState.ALLOWED -> getString(R.string.desktop_site)
                        SitePermissionState.BLOCKED -> "Mobile"
                        SitePermissionState.ASK -> getString(R.string.default_permission)
                    }
                },
                options = listOf(
                    getString(R.string.desktop_site),
                    "Mobile",
                    getString(R.string.default_permission)
                ),
                getCheckedIndex = {
                    when (prefs.getSiteDesktopModeState(domain)) {
                        SitePermissionState.ALLOWED -> 0
                        SitePermissionState.BLOCKED -> 1
                        SitePermissionState.ASK -> 2
                    }
                },
                onChoose = { which ->
                    when (which) {
                        0 -> prefs.setSiteDesktopModeState(domain, SitePermissionState.ALLOWED)
                        1 -> prefs.setSiteDesktopModeState(domain, SitePermissionState.BLOCKED)
                        else -> prefs.setSiteDesktopModeState(domain, SitePermissionState.ASK)
                    }
                    refresh()
                }
            ),
            PermissionItem(
                key = "google_signin",
                iconRes = R.drawable.ic_brand_google,
                title = getString(R.string.google_signin_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteGoogleSignInPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteGoogleSignInPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "sensors",
                iconRes = R.drawable.ic_sensors,
                title = getString(R.string.sensors_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteSensorsPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteSensorsPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "clipboard",
                iconRes = R.drawable.ic_clipboard,
                title = getString(R.string.clipboard_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteClipboardPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteClipboardPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "sound",
                iconRes = R.drawable.ic_volume,
                title = getString(R.string.sound_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteSoundPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteSoundPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "auto_downloads",
                iconRes = R.drawable.ic_download,
                title = getString(R.string.auto_downloads_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteAutoDownloadsPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteAutoDownloadsPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "usb",
                iconRes = R.drawable.ic_usb,
                title = getString(R.string.usb_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteUsbPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteUsbPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "serial",
                iconRes = R.drawable.ic_cpu,
                title = getString(R.string.serial_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteSerialPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteSerialPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "hid",
                iconRes = R.drawable.ic_gamepad,
                title = getString(R.string.hid_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteHidPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteHidPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "midi",
                iconRes = R.drawable.ic_music,
                title = getString(R.string.midi_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteMidiPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteMidiPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "hand_tracking",
                iconRes = R.drawable.ic_hand,
                title = getString(R.string.hand_tracking_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteHandTrackingPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteHandTrackingPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "idle",
                iconRes = R.drawable.ic_idle,
                title = getString(R.string.idle_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteIdlePermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteIdlePermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "fonts",
                iconRes = R.drawable.ic_fonts,
                title = getString(R.string.fonts_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteFontsPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteFontsPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "bluetooth",
                iconRes = R.drawable.ic_bluetooth,
                title = getString(R.string.bluetooth_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteBluetoothPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteBluetoothPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "wake_lock",
                iconRes = R.drawable.ic_sun,
                title = getString(R.string.wake_lock_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteWakeLockPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteWakeLockPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "payment",
                iconRes = R.drawable.ic_credit_card,
                title = getString(R.string.payment_settings_title),
                getState = { getStandardStateLabel(prefs.getSitePaymentPermission(domain)) },
                onChoose = { which ->
                    prefs.setSitePaymentPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "file_editing",
                iconRes = R.drawable.ic_file_edit,
                title = getString(R.string.file_editing_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteFileEditingPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteFileEditingPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "vr",
                iconRes = R.drawable.ic_vr,
                title = getString(R.string.vr_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteVrPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteVrPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "nfc",
                iconRes = R.drawable.ic_nfc,
                title = getString(R.string.nfc_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteNfcPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteNfcPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "window_management",
                iconRes = R.drawable.ic_window_management,
                title = getString(R.string.window_management_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteWindowManagementPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteWindowManagementPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "storage_access",
                iconRes = R.drawable.ic_storage_access,
                title = getString(R.string.storage_access_settings_title),
                getState = { getStandardStateLabel(prefs.getSiteStorageAccessPermission(domain)) },
                onChoose = { which ->
                    prefs.setSiteStorageAccessPermission(domain, indexToState(which))
                    refresh()
                }
            ),
            PermissionItem(
                key = "webrtc",
                iconRes = R.drawable.ic_network,
                title = getString(R.string.webrtc_settings_title),
                getState = {
                    when (prefs.getSiteWebRtcPermission(domain)) {
                        SitePermissionState.ALLOWED -> "Protected"
                        SitePermissionState.BLOCKED -> "Unprotected"
                        SitePermissionState.ASK -> getString(R.string.default_permission)
                    }
                },
                options = listOf(
                    "Protect local IP",
                    "Allow local IP (unprotected)",
                    getString(R.string.default_permission)
                ),
                getCheckedIndex = {
                    when (prefs.getSiteWebRtcPermission(domain)) {
                        SitePermissionState.ALLOWED -> 0
                        SitePermissionState.BLOCKED -> 1
                        SitePermissionState.ASK -> 2
                    }
                },
                onChoose = { which ->
                    when (which) {
                        0 -> prefs.setSiteWebRtcPermission(domain, SitePermissionState.ALLOWED)
                        1 -> prefs.setSiteWebRtcPermission(domain, SitePermissionState.BLOCKED)
                        else -> prefs.setSiteWebRtcPermission(domain, SitePermissionState.ASK)
                    }
                    refresh()
                }
            ),
            PermissionItem(
                key = "auto_shred",
                iconRes = R.drawable.ic_broom,
                title = getString(R.string.forget_site_on_close),
                getState = {
                    when (prefs.getSiteAutoShredState(domain)) {
                        SitePermissionState.ALLOWED -> getString(R.string.forget_site_on_close)
                        SitePermissionState.BLOCKED -> "Keep site data"
                        SitePermissionState.ASK -> getString(R.string.default_permission)
                    }
                },
                options = listOf(
                    getString(R.string.forget_site_on_close),
                    "Keep site data",
                    getString(R.string.default_permission)
                ),
                getCheckedIndex = {
                    when (prefs.getSiteAutoShredState(domain)) {
                        SitePermissionState.ALLOWED -> 0
                        SitePermissionState.BLOCKED -> 1
                        SitePermissionState.ASK -> 2
                    }
                },
                onChoose = { which ->
                    when (which) {
                        0 -> prefs.setSiteAutoShredState(domain, SitePermissionState.ALLOWED)
                        1 -> prefs.setSiteAutoShredState(domain, SitePermissionState.BLOCKED)
                        else -> prefs.setSiteAutoShredState(domain, SitePermissionState.ASK)
                    }
                    refresh()
                }
            )
        )
        items.addAll(standardItems)

        for ((index, item) in items.withIndex()) {
            addPermissionRow(item, showDivider = index < items.size - 1)
        }
    }

    private fun refresh() {
        populatePermissions()
        onSiteChanged()
    }

    private fun getStandardStateLabel(state: SitePermissionState): String = when (state) {
        SitePermissionState.ALLOWED -> getString(R.string.permission_allowed)
        SitePermissionState.BLOCKED -> getString(R.string.permission_blocked)
        SitePermissionState.ASK -> getString(R.string.default_permission)
    }

    private fun indexToState(index: Int): SitePermissionState = when (index) {
        0 -> SitePermissionState.ALLOWED
        1 -> SitePermissionState.BLOCKED
        else -> SitePermissionState.ASK
    }

    private fun stateToIndex(label: String): Int = when (label) {
        getString(R.string.permission_allowed) -> 0
        getString(R.string.permission_blocked) -> 1
        else -> 2
    }

    private fun addPermissionRow(item: PermissionItem, showDivider: Boolean) {
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

        val icon = ImageView(requireContext()).apply {
            setImageResource(item.iconRes)
            val iconColor = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorSecondary, iconColor, true)
            setColorFilter(ContextCompat.getColor(context, iconColor.resourceId))
            val size = (20 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (14 * density).toInt()
            }
        }

        val tvTitle = TextView(requireContext()).apply {
            text = item.title
            textSize = 14.5f
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            setTextColor(ContextCompat.getColor(context, typedValue.resourceId))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val stateLabel = item.getState()
        val tvState = TextView(requireContext()).apply {
            text = stateLabel
            textSize = 12f
            when (stateLabel) {
                getString(R.string.permission_allowed),
                getString(R.string.shields_up),
                getString(R.string.desktop_site) -> setTextColor(ContextCompat.getColor(context, R.color.green_success))
                getString(R.string.permission_blocked),
                getString(R.string.shields_down) -> setTextColor(ContextCompat.getColor(context, R.color.red_danger))
                else -> setTextColor(ContextCompat.getColor(context, R.color.settings_subtitle_text))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (6 * density).toInt()
            }
        }

        val chevron = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(ContextCompat.getColor(context, R.color.settings_subtitle_text))
            val size = (18 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

        row.addView(icon)
        row.addView(tvTitle)
        row.addView(tvState)
        row.addView(chevron)

        row.setOnClickListener {
            val options = item.options?.toTypedArray() ?: arrayOf(
                getString(R.string.permission_allowed),
                getString(R.string.permission_blocked),
                getString(R.string.default_permission)
            )
            val checkedIndex = item.getCheckedIndex?.invoke() ?: stateToIndex(stateLabel)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(item.title)
                .setSingleChoiceItems(options, checkedIndex) { dialog, which ->
                    item.onChoose(which)
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        rowWrapper.addView(row)

        if (showDivider) {
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1
                ).apply {
                    marginStart = (50 * density).toInt()
                }
                setBackgroundColor(ContextCompat.getColor(context, R.color.settings_card_stroke))
            }
            rowWrapper.addView(divider)
        }

        binding.containerPermissions.addView(rowWrapper)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
