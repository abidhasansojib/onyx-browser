package com.onyx.browser.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityAccessibilitySettingsBinding

class AccessibilitySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccessibilitySettingsBinding
    private lateinit var preferences: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.parseColor("#0B0305")
        window.navigationBarColor = android.graphics.Color.parseColor("#0B0305")

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

        setupAccessibilityControls()
    }

    override fun onResume() {
        super.onResume()
        binding.settingScrollToTopSwitch.isChecked = preferences.isScrollToTopEnabled
        binding.settingBiometricSwitch.isChecked = preferences.isBiometricIncognitoEnabled
        com.onyx.browser.ui.widget.SearchWidgetProvider.updateAllWidgets(this)
    }

    private fun setupAccessibilityControls() {
        // Search Widget Pinning
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
