package com.onyx.browser.ui.settings

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivitySiteSettingsBinding
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.web.SiteDataManager

class SiteSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySiteSettingsBinding
    private lateinit var prefs: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentColorManager.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)

        // Edge-to-edge system bars matching settings_canvas_bg
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val canvasBg = ContextCompat.getColor(this, R.color.settings_canvas_bg)
        window.statusBarColor = canvasBg
        window.navigationBarColor = canvasBg

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode

        binding = ActivitySiteSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.siteSettingsRoot) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.updatePadding(top = statusBarInsets.top)
            binding.scrollView.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        prefs = BrowserPreferences.getInstance(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        // Pin top heading color statically to canvasBg (disable lift-on-scroll elevation tint)
        binding.appBarLayout.apply {
            isLiftOnScroll = false
            setLiftable(false)
            setLifted(false)
            elevation = 0f
            stateListAnimator = null
            outlineProvider = null
            statusBarForeground = null
            setBackgroundColor(canvasBg)
            backgroundTintList = ColorStateList.valueOf(canvasBg)
        }
        binding.toolbar.apply {
            elevation = 0f
            outlineProvider = null
            setBackgroundColor(canvasBg)
            backgroundTintList = ColorStateList.valueOf(canvasBg)
        }

        setupRows()
        updateStatuses()
    }

    private fun setupRows() {
        binding.rowAllSites.setOnClickListener {
            val sheet = AllSitesSheet {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, AllSitesSheet.TAG)
        }

        binding.rowCameraPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_CAMERA) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowMicPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_MIC) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowLocationPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_LOCATION) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowClearAllSiteData.setOnClickListener {
            val domains = prefs.getAllConfiguredDomains()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_site_data)
                .setMessage("Clear cookies and site data for all ${domains.size} configured sites?")
                .setPositiveButton(R.string.clear_site_data) { _, _ ->
                    val ctx = applicationContext
                    for (domain in domains) {
                        SiteDataManager.clearSiteData(ctx, domain)
                    }
                    Toast.makeText(this, "Cleared cookies and site data for all sites", Toast.LENGTH_SHORT).show()
                    updateStatuses()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun updateStatuses() {
        val configuredCount = prefs.getAllConfiguredDomains().size
        binding.tvAllSitesSubtitle.text = if (configuredCount == 0) {
            getString(R.string.all_sites_desc)
        } else if (configuredCount == 1) {
            "1 site with custom settings"
        } else {
            "$configuredCount sites with custom settings"
        }

        // Camera
        val cameraEnabled = prefs.isCameraEnabledGlobal
        binding.tvCameraStatus.text = if (cameraEnabled) {
            getString(R.string.ask_before_accessing)
        } else {
            getString(R.string.blocked_globally)
        }
        val cameraAllowed = prefs.getAllowedCameraDomains().size
        val cameraBlocked = prefs.getBlockedCameraDomains().size
        updateCountBadge(binding.tvCameraCount, cameraAllowed, cameraBlocked)

        // Microphone
        val micEnabled = prefs.isMicEnabledGlobal
        binding.tvMicStatus.text = if (micEnabled) {
            getString(R.string.ask_before_accessing)
        } else {
            getString(R.string.blocked_globally)
        }
        val micAllowed = prefs.getAllowedMicDomains().size
        val micBlocked = prefs.getBlockedMicDomains().size
        updateCountBadge(binding.tvMicCount, micAllowed, micBlocked)

        // Location
        val locationEnabled = prefs.isLocationEnabledGlobal
        binding.tvLocationStatus.text = if (locationEnabled) {
            getString(R.string.ask_before_accessing)
        } else {
            getString(R.string.blocked_globally)
        }
        val locAllowed = prefs.getAllowedLocationDomains().size
        val locBlocked = prefs.getBlockedLocationDomains().size
        updateCountBadge(binding.tvLocationCount, locAllowed, locBlocked)

        // Clear data subtitle
        val siteCount = prefs.getAllConfiguredDomains().size
        binding.tvClearDataSubtitle.text = if (siteCount == 0) {
            getString(R.string.site_data_desc)
        } else {
            "$siteCount sites with stored data"
        }
    }

    /** Shows a compact "{N}" count badge when sites have non-default settings. */
    private fun updateCountBadge(badge: android.widget.TextView, allowed: Int, blocked: Int) {
        val total = allowed + blocked
        if (total > 0) {
            badge.visibility = View.VISIBLE
            badge.text = total.toString()
        } else {
            badge.visibility = View.GONE
        }
    }
}
