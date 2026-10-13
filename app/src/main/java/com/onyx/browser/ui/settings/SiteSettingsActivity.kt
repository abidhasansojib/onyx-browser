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

        binding.rowNotificationsPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_NOTIFICATIONS) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowSensorsPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_SENSORS) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowClipboardPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_CLIPBOARD) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowGoogleSignInPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_GOOGLE_SIGNIN) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowJavaScriptPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_JAVASCRIPT) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowPopupsPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_POPUPS) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowAutoplayPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_AUTOPLAY) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowImagesPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_IMAGES) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowZoomPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_ZOOM) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowMixedContentPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_MIXED_CONTENT) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowSoundPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_SOUND) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowAutoDownloadsPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_AUTO_DOWNLOADS) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowCookiesSiteData.setOnClickListener {
            val sheet = CookieModePickerSheet(prefs.cookieBlockingMode) { selectedMode ->
                prefs.cookieBlockingMode = selectedMode
                updateStatuses()
            }
            sheet.show(supportFragmentManager, CookieModePickerSheet.TAG)
        }

        binding.rowDesktopSite.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_DESKTOP) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowProtectedContent.setOnClickListener {
            val items = arrayOf(
                getString(R.string.protected_content_allow),
                getString(R.string.protected_content_block)
            )
            val checkedItem = if (prefs.isProtectedContentEnabledGlobal) 0 else 1
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.protected_content_settings_title)
                .setSingleChoiceItems(items, checkedItem) { dialog, which ->
                    prefs.isProtectedContentEnabledGlobal = (which == 0)
                    updateStatuses()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.rowAutoShredPermission.setOnClickListener {
            val sheet = SitePermissionCategorySheet(SitePermissionCategorySheet.CATEGORY_AUTO_SHRED) {
                updateStatuses()
            }
            sheet.show(supportFragmentManager, SitePermissionCategorySheet.TAG)
        }

        binding.rowClearAllSiteData.setOnClickListener {
            val domains = prefs.getAllConfiguredDomains()
            val count = domains.size
            val message = if (count == 0) {
                "Clear all cookies and browsing data stored on this device?"
            } else {
                "Clear all cookies and browsing data for $count sites on this device? All site permission exceptions will also be reset."
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_site_data)
                .setMessage(message)
                .setPositiveButton(R.string.clear_site_data) { _, _ ->
                    val ctx = applicationContext
                    // 1. Reset all per-site permission exceptions
                    for (domain in domains) {
                        prefs.resetSitePermissions(domain)
                        prefs.setDomainWhitelisted(domain, false)
                    }
                    // 2. Clear desktop mode domain overrides
                    prefs.desktopDomains = emptySet()
                    // 3. Full WebStorage wipe
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    // 4. Full cookie wipe
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.CookieManager.getInstance().flush()
                    // 5. Per-domain cookie clear for good measure
                    for (domain in domains) {
                        SiteDataManager.clearSiteData(ctx, domain)
                    }
                    Toast.makeText(this, "Cleared all cookies and site data", Toast.LENGTH_SHORT).show()
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

        // Notifications
        val notifEnabled = prefs.isNotificationsEnabledGlobal
        binding.tvNotificationsStatus.text = if (notifEnabled) {
            getString(R.string.ask_before_accessing)
        } else {
            getString(R.string.blocked_globally)
        }
        updateCountBadge(binding.tvNotificationsCount, prefs.getAllowedNotifDomains().size, prefs.getBlockedNotifDomains().size)

        // Sensors
        val sensorsBlocked = prefs.isSensorsBlockedGlobal
        binding.tvSensorsStatus.text = if (sensorsBlocked) {
            getString(R.string.blocked_globally)
        } else {
            getString(R.string.policy_allowed_label)
        }
        updateCountBadge(binding.tvSensorsCount, prefs.getAllowedSensorsDomains().size, prefs.getBlockedSensorsDomains().size)

        // Clipboard
        val clipboardBlocked = prefs.isClipboardBlockedGlobal
        binding.tvClipboardStatus.text = if (clipboardBlocked) {
            getString(R.string.blocked_globally)
        } else {
            getString(R.string.ask_before_accessing)
        }
        updateCountBadge(binding.tvClipboardCount, prefs.getAllowedClipboardDomains().size, prefs.getBlockedClipboardDomains().size)

        // Google Sign-In
        val googleSignInBlocked = prefs.isGoogleSignInBlockedGlobal
        binding.tvGoogleSignInStatus.text = if (googleSignInBlocked) {
            getString(R.string.policy_blocked_label)
        } else {
            getString(R.string.policy_allowed_label)
        }
        updateCountBadge(binding.tvGoogleSignInCount, prefs.getAllowedGoogleSignInDomains().size, prefs.getBlockedGoogleSignInDomains().size)

        // JavaScript
        val jsEnabled = prefs.isJavaScriptEnabledGlobal
        binding.tvJavaScriptStatus.text = if (jsEnabled) {
            getString(R.string.policy_allowed_label)
        } else {
            getString(R.string.policy_blocked_label)
        }
        updateCountBadge(binding.tvJavaScriptCount, prefs.getAllowedJsDomains().size, prefs.getBlockedJsDomains().size)

        // Popups
        val popupsBlocked = prefs.isPopupsBlockedGlobal
        binding.tvPopupsStatus.text = if (popupsBlocked) {
            getString(R.string.policy_blocked_label)
        } else {
            getString(R.string.policy_allowed_label)
        }
        updateCountBadge(binding.tvPopupsCount, prefs.getAllowedPopupDomains().size, prefs.getBlockedPopupDomains().size)

        // Autoplay
        val autoplayBlocked = prefs.isAutoplayBlockedGlobal
        binding.tvAutoplayStatus.text = if (autoplayBlocked) {
            getString(R.string.policy_blocked_label)
        } else {
            getString(R.string.policy_allowed_label)
        }
        updateCountBadge(binding.tvAutoplayCount, prefs.getAllowedAutoplayDomains().size, prefs.getBlockedAutoplayDomains().size)

        // Images
        val imagesEnabled = prefs.isImagesEnabledGlobal
        binding.tvImagesStatus.text = if (imagesEnabled) {
            getString(R.string.policy_allowed_label)
        } else {
            getString(R.string.policy_blocked_label)
        }
        updateCountBadge(binding.tvImagesCount, prefs.getAllowedImagesDomains().size, prefs.getBlockedImagesDomains().size)

        // Zoom
        val zoomEnabled = prefs.isZoomEnabledGlobal
        binding.tvZoomStatus.text = if (zoomEnabled) {
            getString(R.string.policy_allowed_label)
        } else {
            getString(R.string.policy_blocked_label)
        }
        updateCountBadge(binding.tvZoomCount, prefs.getAllowedZoomDomains().size, prefs.getBlockedZoomDomains().size)

        // Mixed Content
        val mixedBlocked = prefs.isMixedContentBlockedGlobal
        binding.tvMixedContentStatus.text = if (mixedBlocked) {
            getString(R.string.policy_blocked_label)
        } else {
            getString(R.string.policy_allowed_label)
        }
        updateCountBadge(binding.tvMixedContentCount, prefs.getAllowedMixedDomains().size, prefs.getBlockedMixedDomains().size)

        // Sound
        val soundMuted = prefs.isSoundMutedGlobal
        binding.tvSoundStatus.text = if (soundMuted) {
            getString(R.string.policy_blocked_label)
        } else {
            getString(R.string.policy_allowed_label)
        }
        updateCountBadge(binding.tvSoundCount, prefs.getAllowedSoundDomains().size, prefs.getBlockedSoundDomains().size)

        // Automatic Downloads
        val autoDownloadsBlocked = prefs.isAutoDownloadsBlockedGlobal
        binding.tvAutoDownloadsStatus.text = if (autoDownloadsBlocked) {
            getString(R.string.policy_blocked_label)
        } else {
            getString(R.string.policy_allowed_label)
        }
        updateCountBadge(binding.tvAutoDownloadsCount, prefs.getAllowedAutoDownloadsDomains().size, prefs.getBlockedAutoDownloadsDomains().size)

        // Cookies & site data
        binding.tvCookiesStatus.text = when (prefs.cookieBlockingMode) {
            BrowserPreferences.COOKIE_BLOCK_NONE -> getString(R.string.cookies_all_allowed)
            BrowserPreferences.COOKIE_BLOCK_THIRD_PARTY -> getString(R.string.cookies_third_party_blocked)
            BrowserPreferences.COOKIE_BLOCK_ALL -> getString(R.string.cookies_all_blocked)
            else -> getString(R.string.cookies_site_data_desc)
        }

        // Desktop site
        val desktopGlobal = prefs.isDesktopMode
        binding.tvDesktopSiteStatus.text = if (desktopGlobal) {
            getString(R.string.desktop_site_enabled)
        } else {
            getString(R.string.desktop_site_disabled)
        }
        val desktopExceptionsCount = prefs.desktopDomains.size
        updateCountBadge(binding.tvDesktopSiteCount, desktopExceptionsCount, 0)

        // Protected content
        val protectedEnabled = prefs.isProtectedContentEnabledGlobal
        binding.tvProtectedContentStatus.text = if (protectedEnabled) {
            getString(R.string.policy_allowed_label)
        } else {
            getString(R.string.policy_blocked_label)
        }

        // Auto-shred on tab close
        val autoShredAll = prefs.isCookieAutoclearOnCloseEnabled
        val autoShredSites = prefs.autoShredDomains.size
        binding.tvAutoShredStatus.text = if (autoShredAll) {
            "All sites on close"
        } else if (autoShredSites > 0) {
            "$autoShredSites configured sites"
        } else {
            getString(R.string.auto_shred_settings_desc)
        }
        updateCountBadge(binding.tvAutoShredCount, autoShredSites, 0)

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
