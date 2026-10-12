package com.onyx.browser.ui.settings

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityDownloadSettingsBinding
import com.onyx.browser.ui.theme.AccentColorManager

class DownloadSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadSettingsBinding
    private lateinit var preferences: BrowserPreferences

    private val downloadOptions = arrayOf(
        "Ask before download",
        "Internal downloader",
        "External download manager"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentColorManager.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)

        // Edge-to-edge system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val canvasBg = ContextCompat.getColor(this, R.color.settings_canvas_bg)
        window.statusBarColor = canvasBg
        window.navigationBarColor = canvasBg

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode

        binding = ActivityDownloadSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BrowserPreferences.getInstance(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.downloadSettingsRoot) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.updatePadding(top = statusBarInsets.top)
            binding.scrollView.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

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

        setupDownloadControls()
    }

    private fun setupDownloadControls() {
        fun updateLabel() {
            val idx = preferences.downloadManagerBehavior.coerceIn(0, 2)
            binding.tvCurrentDownloadManager.text = downloadOptions[idx]
        }

        updateLabel()

        binding.settingDownloadManagerRow.setOnClickListener {
            val currentIndex = preferences.downloadManagerBehavior.coerceIn(0, 2)
            val sheet = DownloadManagerPickerSheet(currentIndex) { which ->
                preferences.downloadManagerBehavior = which
                updateLabel()
            }
            sheet.show(supportFragmentManager, DownloadManagerPickerSheet.TAG)
        }

        binding.settingDownloadWifiOnlySwitch.isChecked = preferences.isDownloadWifiOnly
        binding.settingDownloadWifiOnlyRow.setOnClickListener {
            val newState = !binding.settingDownloadWifiOnlySwitch.isChecked
            binding.settingDownloadWifiOnlySwitch.isChecked = newState
            preferences.isDownloadWifiOnly = newState
        }
    }
}
