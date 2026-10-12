package com.onyx.browser.ui.settings

import android.content.res.Configuration
import android.os.Bundle
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
        binding.tvAllSitesSubtitle.text = if (configuredCount == 1) {
            "1 site configured"
        } else {
            "$configuredCount sites configured"
        }

        binding.tvCameraStatus.text = if (prefs.isCameraEnabledGlobal) {
            getString(R.string.ask_before_accessing)
        } else {
            getString(R.string.blocked_globally)
        }

        binding.tvMicStatus.text = if (prefs.isMicEnabledGlobal) {
            getString(R.string.ask_before_accessing)
        } else {
            getString(R.string.blocked_globally)
        }

        binding.tvLocationStatus.text = if (prefs.isLocationEnabledGlobal) {
            getString(R.string.ask_before_accessing)
        } else {
            getString(R.string.blocked_globally)
        }
    }
}
