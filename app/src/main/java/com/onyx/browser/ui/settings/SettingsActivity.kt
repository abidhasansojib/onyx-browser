package com.onyx.browser.ui.settings

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
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
    private var pendingPipEnable: Boolean = false

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
        updatePipSwitchState()
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
            val newState = !binding.settingBackgroundPlaySwitch.isChecked
            binding.settingBackgroundPlaySwitch.isChecked = newState
            preferences.isBackgroundPlayEnabled = newState
            if (!newState) {
                com.onyx.browser.media.MediaPlaybackService.stop(this)
            }
        }

        binding.settingPipSwitch.isChecked = preferences.isPipEnabled && isPipPermissionAllowed()
        binding.settingPipRow.setOnClickListener {
            val willEnable = !binding.settingPipSwitch.isChecked
            if (willEnable) {
                if (!isPipPermissionAllowed()) {
                    Toast.makeText(this, "Please enable Picture-in-Picture permission", Toast.LENGTH_SHORT).show()
                    pendingPipEnable = true
                    openPipSystemSettings()
                } else {
                    preferences.isPipEnabled = true
                    binding.settingPipSwitch.isChecked = true
                }
            } else {
                preferences.isPipEnabled = false
                binding.settingPipSwitch.isChecked = false
                com.onyx.browser.media.MediaPlaybackBridge.isVideoPlaying = false
            }
        }

        binding.settingPipRow.setOnLongClickListener {
            openPipSystemSettings()
            true
        }
    }

    private fun updatePipSwitchState() {
        val hasPipPerm = isPipPermissionAllowed()
        if (pendingPipEnable) {
            pendingPipEnable = false
            if (hasPipPerm) {
                preferences.isPipEnabled = true
                Toast.makeText(this, "Picture-in-Picture enabled", Toast.LENGTH_SHORT).show()
            } else {
                preferences.isPipEnabled = false
            }
        } else if (preferences.isPipEnabled && !hasPipPerm) {
            // User revoked permission in Android system settings
            preferences.isPipEnabled = false
        }
        binding.settingPipSwitch.isChecked = preferences.isPipEnabled && hasPipPerm
    }

    private fun isPipPermissionAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        }
    }

    private fun openPipSystemSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val pipIntent = Intent(
                    "android.settings.PICTURE_IN_PICTURE_SETTINGS",
                    Uri.parse("package:$packageName")
                )
                startActivity(pipIntent)
                return
            } catch (_: Exception) {}

            try {
                val pipIntent = Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS")
                startActivity(pipIntent)
                return
            } catch (_: Exception) {}
        }

        try {
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(overlayIntent)
            return
        } catch (_: Exception) {}

        try {
            val appDetailsIntent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(appDetailsIntent)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open system settings", Toast.LENGTH_SHORT).show()
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

