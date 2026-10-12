package com.onyx.browser.ui.settings

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityAccessibilitySettingsBinding
import com.onyx.browser.ui.theme.AccentColorManager

class AccessibilitySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccessibilitySettingsBinding
    private lateinit var preferences: BrowserPreferences

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

        binding = ActivityAccessibilitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BrowserPreferences.getInstance(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.accessibilitySettingsRoot) { _, insets ->
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

        setupAccessibilityControls()
    }

    override fun onResume() {
        super.onResume()
        binding.settingAddToHomeScreenSwitch.isChecked = preferences.isAddToHomeScreenEnabled
        binding.settingScrollToTopSwitch.isChecked = preferences.isScrollToTopEnabled
        binding.settingBiometricSwitch.isChecked = preferences.isBiometricIncognitoEnabled
        binding.settingPullToRefreshSwitch.isChecked = preferences.isPullToRefreshEnabled
        binding.settingForceDarkModeSwitch.isChecked = preferences.isForceDarkModeEnabled
        com.onyx.browser.ui.widget.SearchWidgetProvider.updateAllWidgets(this)
    }

    private fun setupAccessibilityControls() {
        // Search Widget Pinning
        binding.settingSearchWidgetRow.setOnClickListener {
            com.onyx.browser.ui.widget.SearchWidgetManager.requestPinSearchWidget(this)
        }

        // Add to Home Screen in Menu Toggle
        binding.settingAddToHomeScreenSwitch.isChecked = preferences.isAddToHomeScreenEnabled
        binding.settingAddToHomeScreenRow.setOnClickListener {
            val newState = !binding.settingAddToHomeScreenSwitch.isChecked
            binding.settingAddToHomeScreenSwitch.isChecked = newState
            preferences.isAddToHomeScreenEnabled = newState
        }

        // Scroll to Top Button
        binding.settingScrollToTopSwitch.isChecked = preferences.isScrollToTopEnabled
        binding.settingScrollToTopRow.setOnClickListener {
            val newState = !binding.settingScrollToTopSwitch.isChecked
            binding.settingScrollToTopSwitch.isChecked = newState
            preferences.isScrollToTopEnabled = newState
        }

        // Pull to Refresh Toggle
        binding.settingPullToRefreshSwitch.isChecked = preferences.isPullToRefreshEnabled
        binding.settingPullToRefreshRow.setOnClickListener {
            val newState = !binding.settingPullToRefreshSwitch.isChecked
            binding.settingPullToRefreshSwitch.isChecked = newState
            preferences.isPullToRefreshEnabled = newState
        }

        // Force Dark Mode for Web Contents
        binding.settingForceDarkModeSwitch.isChecked = preferences.isForceDarkModeEnabled
        binding.settingForceDarkModeRow.setOnClickListener {
            val newState = !binding.settingForceDarkModeSwitch.isChecked
            binding.settingForceDarkModeSwitch.isChecked = newState
            preferences.isForceDarkModeEnabled = newState
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
                                Toast.makeText(this@AccessibilitySettingsActivity, "Biometric Incognito Protection enabled", Toast.LENGTH_SHORT).show()
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                super.onAuthenticationError(errorCode, errString)
                                binding.settingBiometricSwitch.isChecked = preferences.isBiometricIncognitoEnabled
                            }

                            override fun onAuthenticationFailed() {
                                super.onAuthenticationFailed()
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
                            Toast.makeText(this@AccessibilitySettingsActivity, "Biometric Incognito Protection disabled", Toast.LENGTH_SHORT).show()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            binding.settingBiometricSwitch.isChecked = preferences.isBiometricIncognitoEnabled
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
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
