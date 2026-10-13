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
                    prefs.setDomainWhitelisted(domain, false)
                    val d = prefs.desktopDomains.toMutableSet()
                    d.remove(domain)
                    prefs.desktopDomains = d
                    populatePermissions()
                    onSiteChanged()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnResetPermissions.setOnClickListener {
            prefs.resetSitePermissions(domain)
            prefs.setDomainWhitelisted(domain, false)
            val d = prefs.desktopDomains.toMutableSet()
            d.remove(domain)
            prefs.desktopDomains = d
            populatePermissions()
            onSiteChanged()
            Toast.makeText(requireContext(), "Permissions reset for $domain", Toast.LENGTH_SHORT).show()
        }

        populatePermissions()
    }

    private data class PermissionItem(
        val key: String,
        val iconRes: Int,
        val title: String,
        val getState: () -> String,
        val onChoose: (Int) -> Unit
    )

    private fun populatePermissions() {
        binding.containerPermissions.removeAllViews()

        val items = listOf(
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
                    if (prefs.desktopDomains.contains(domain)) getString(R.string.permission_allowed)
                    else getString(R.string.default_permission)
                },
                onChoose = { which ->
                    val d = prefs.desktopDomains.toMutableSet()
                    if (which == 0) { // Allowed
                        d.add(domain)
                    } else { // Blocked or Default
                        d.remove(domain)
                    }
                    prefs.desktopDomains = d
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
                key = "auto_shred",
                iconRes = R.drawable.ic_broom,
                title = getString(R.string.forget_site_on_close),
                getState = {
                    if (prefs.isSiteAutoShredEnabled(domain)) getString(R.string.permission_allowed)
                    else getString(R.string.default_permission)
                },
                onChoose = { which ->
                    prefs.setSiteAutoShredEnabled(domain, which == 0)
                    refresh()
                }
            )
        )

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
                getString(R.string.permission_allowed) -> setTextColor(ContextCompat.getColor(context, R.color.green_success))
                getString(R.string.permission_blocked) -> setTextColor(ContextCompat.getColor(context, R.color.red_danger))
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
            val options = arrayOf(
                getString(R.string.permission_allowed),
                getString(R.string.permission_blocked),
                getString(R.string.default_permission)
            )
            val checkedIndex = stateToIndex(stateLabel)
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
