package com.onyx.browser.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityDownloadSettingsBinding

class DownloadSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadSettingsBinding
    private lateinit var preferences: BrowserPreferences

    private val downloadOptions = arrayOf(
        "Ask before download",
        "Internal downloader",
        "External download manager"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.parseColor("#0B0305")
        window.navigationBarColor = android.graphics.Color.parseColor("#0B0305")

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
