package com.onyx.browser.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.R
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivitySettingsBinding
import com.onyx.browser.nativebridge.AdBlockEngine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferences: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BrowserPreferences.getInstance(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupSearchEnginePreference()
        setupAppearancePreference()
        setupPrivacySettings()
        setupAutofillSettings()
        setupDownloadPreferences()
        setupMediaPreferences()
        setupAccessibilitySettings()
        setupAboutPreference()
    }

    override fun onResume() {
        super.onResume()
        updateSearchEngineDisplay()
        binding.settingScrollToTopSwitch.isChecked = preferences.isScrollToTopEnabled
        binding.settingBiometricSwitch.isChecked = preferences.isBiometricIncognitoEnabled
        com.onyx.browser.ui.widget.SearchWidgetProvider.updateAllWidgets(this)
    }

    private fun setupSearchEnginePreference() {
        updateSearchEngineDisplay()

        binding.settingSearchEngineRow.setOnClickListener {
            startActivity(android.content.Intent(this, SearchEngineSettingsActivity::class.java))
        }
    }

    private fun updateSearchEngineDisplay() {
        val current = preferences.searchEngine
        binding.ivCurrentSearchEngineIcon.setImageResource(current.iconResId)
        binding.tvCurrentSearchEngineName.text = current.displayName
    }

    private fun setupAutofillSettings() {
        binding.settingAutofillRow.setOnClickListener {
            startActivity(android.content.Intent(this, AutofillSettingsActivity::class.java))
        }
    }

    private fun setupAppearancePreference() {
        updateThemeDisplay()

        binding.settingThemeRow.setOnClickListener {
            val sheet = ThemePickerSheet(preferences.themeMode) { selectedMode ->
                if (preferences.themeMode != selectedMode) {
                    preferences.themeMode = selectedMode
                    preferences.applyTheme()
                    updateThemeDisplay()
                    recreate()
                }
            }
            sheet.show(supportFragmentManager, ThemePickerSheet.TAG)
        }
    }

    private fun updateThemeDisplay() {
        val themeText = when (preferences.themeMode) {
            BrowserPreferences.THEME_DARK -> getString(R.string.theme_dark)
            BrowserPreferences.THEME_LIGHT -> getString(R.string.theme_light)
            else -> getString(R.string.theme_system)
        }
        binding.tvCurrentTheme.text = themeText
    }

    private fun setupPrivacySettings() {
        binding.settingShieldsRow.setOnClickListener {
            startActivity(android.content.Intent(this, ShieldsActivity::class.java))
        }
        
        updateUaSpooferText()
        binding.settingUaSpooferRow.setOnClickListener {
            showUaSpooferDialog()
        }
    }
    
    private fun updateUaSpooferText() {
        binding.tvUaSpooferStatus.text = com.onyx.browser.web.UserAgentManager.getDisplayName(
            preferences.userAgentSpoofTemplate,
            preferences
        )
    }

    private fun showUaSpooferDialog() {
        UserAgentPickerSheet {
            updateUaSpooferText()
        }.show(supportFragmentManager, "UserAgentPickerSheet")
    }

    private fun setupDownloadPreferences() {
        val options = arrayOf(
            "Ask before download",
            "Internal downloader",
            "External download manager"
        )
        
        fun updateLabel() {
            val idx = preferences.downloadManagerBehavior.coerceIn(0, 2)
            binding.tvCurrentDownloadManager.text = options[idx]
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
    }

    private fun setupMediaPreferences() {
        binding.settingBackgroundPlaySwitch.isChecked = preferences.isBackgroundPlayEnabled
        binding.settingBackgroundPlayRow.setOnClickListener {
            binding.settingBackgroundPlaySwitch.isChecked = !binding.settingBackgroundPlaySwitch.isChecked
        }
        binding.settingBackgroundPlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.isBackgroundPlayEnabled = isChecked
            if (!isChecked) {
                com.onyx.browser.media.MediaPlaybackService.stop(this)
            }
        }

        binding.settingPipSwitch.isChecked = preferences.isPipEnabled
        binding.settingPipRow.setOnClickListener {
            binding.settingPipSwitch.isChecked = !binding.settingPipSwitch.isChecked
        }
        binding.settingPipSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.isPipEnabled = isChecked
            if (!isChecked) {
                com.onyx.browser.media.MediaPlaybackBridge.isVideoPlaying = false
            }
            try {
                val intent = android.content.Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", android.net.Uri.parse("package:$packageName"))
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = android.content.Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS")
                    startActivity(intent)
                } catch (e2: Exception) {}
            }
        }

        binding.settingDisplayOverOtherAppsRow.setOnClickListener {
            openDisplayOverOtherAppsSettings()
        }
    }

    private fun openDisplayOverOtherAppsSettings() {
        val pm = packageManager
        var launched = false

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val pipIntent = android.content.Intent(
                    "android.settings.PICTURE_IN_PICTURE_SETTINGS",
                    android.net.Uri.parse("package:$packageName")
                )
                if (pipIntent.resolveActivity(pm) != null) {
                    startActivity(pipIntent)
                    launched = true
                }
            } catch (_: Exception) {}
        }

        if (!launched) {
            try {
                val appDetailsIntent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(appDetailsIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open system settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupAboutPreference() {
        binding.settingAboutRow.setOnClickListener {
            startActivity(android.content.Intent(this, AboutActivity::class.java))
        }
    }

    private fun setupAccessibilitySettings() {
        // Search Widget (Add to Home Screen)
        binding.settingSearchWidgetRow.setOnClickListener {
            com.onyx.browser.ui.widget.SearchWidgetManager.requestPinSearchWidget(this)
        }

        // Scroll to Top Button
        binding.settingScrollToTopSwitch.isChecked = preferences.isScrollToTopEnabled
        binding.settingScrollToTopRow.setOnClickListener {
            val newState = !binding.settingScrollToTopSwitch.isChecked
            binding.settingScrollToTopSwitch.isChecked = newState
            preferences.isScrollToTopEnabled = newState
        }

        // Biometric Incognito Protection
        binding.settingBiometricSwitch.isChecked = preferences.isBiometricIncognitoEnabled
        binding.settingBiometricRow.setOnClickListener {
            val currentlyEnabled = preferences.isBiometricIncognitoEnabled
            val executor = androidx.core.content.ContextCompat.getMainExecutor(this)

            if (!currentlyEnabled) {
                // User wants to enable -> authenticate for setup
                if (com.onyx.browser.ui.common.BiometricAuthHelper.canAuthenticate(this)) {
                    val prompt = androidx.biometric.BiometricPrompt(
                        this,
                        executor,
                        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                                super.onAuthenticationSucceeded(result)
                                preferences.isBiometricIncognitoEnabled = true
                                binding.settingBiometricSwitch.isChecked = true
                                Toast.makeText(this@SettingsActivity, "Biometric Incognito Protection enabled", Toast.LENGTH_SHORT).show()
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                super.onAuthenticationError(errorCode, errString)
                                binding.settingBiometricSwitch.isChecked = preferences.isBiometricIncognitoEnabled
                            }

                            override fun onAuthenticationFailed() {
                                super.onAuthenticationFailed()
                                // Allow user retry on biometric prompt UI
                            }
                        }
                    )
                    val promptInfo = com.onyx.browser.ui.common.BiometricAuthHelper.createPromptInfo(
                        title = "Biometric Incognito Setup",
                        subtitle = "Confirm fingerprint or screen lock to enable"
                    )
                    prompt.authenticate(promptInfo)
                } else {
                    Toast.makeText(this, "Please set up a screen lock or fingerprint in Android Settings first", Toast.LENGTH_LONG).show()
                }
            } else {
                // User wants to disable -> authenticate first
                val prompt = androidx.biometric.BiometricPrompt(
                    this,
                    executor,
                    object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            preferences.isBiometricIncognitoEnabled = false
                            binding.settingBiometricSwitch.isChecked = false
                            Toast.makeText(this@SettingsActivity, "Biometric Incognito Protection disabled", Toast.LENGTH_SHORT).show()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            binding.settingBiometricSwitch.isChecked = preferences.isBiometricIncognitoEnabled
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            // Allow user retry on biometric prompt UI
                        }
                    }
                )
                val promptInfo = com.onyx.browser.ui.common.BiometricAuthHelper.createPromptInfo(
                    title = "Confirm Identity",
                    subtitle = "Confirm identity to disable Biometric Incognito Protection"
                )
                prompt.authenticate(promptInfo)
            }
        }
    }
}

