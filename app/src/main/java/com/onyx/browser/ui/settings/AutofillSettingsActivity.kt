package com.onyx.browser.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityAutofillSettingsBinding

class AutofillSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutofillSettingsBinding
    private lateinit var preferences: BrowserPreferences
    private var activeManager: ActivePasswordManagerInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutofillSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BrowserPreferences.getInstance(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupAutofillStatus()
        setupToggles()
    }

    override fun onResume() {
        super.onResume()
        setupAutofillStatus()
    }

    private fun setupAutofillStatus() {
        val isSupported = AutofillHelper.isAutofillSupported(this)
        activeManager = AutofillHelper.getActivePasswordManager(this)

        if (!isSupported) {
            binding.tvActiveAutofillTitle.text = "Autofill Not Supported"
            binding.tvActiveAutofillSubtitle.text = "Android Autofill requires Android 8.0 or higher."
            binding.btnChangeAutofillService.isEnabled = false
            binding.rowGooglePasswordManager.isEnabled = false
            return
        }

        val manager = activeManager
        if (manager != null) {
            binding.tvActiveAutofillTitle.text = "${manager.appName} Active"
            binding.tvActiveAutofillSubtitle.text = "${manager.appName} is configured as your active system password manager."
            binding.ivAutofillStatusIcon.setColorFilter(getColor(R.color.primary))
            binding.btnChangeAutofillService.text = "Change Autofill Service"

            // Universal "Open [Password Manager]" row
            binding.tvPasswordManagerActionTitle.text = "Open ${manager.appName}"
            if (manager.isGoogle) {
                binding.tvPasswordManagerActionSubtitle.text = "View, edit, and check passwords saved in your Google Account"
            } else {
                binding.tvPasswordManagerActionSubtitle.text = "View, edit, and manage your ${manager.appName} vault and credentials"
            }

            if (manager.icon != null) {
                binding.ivPasswordManagerIcon.setImageDrawable(manager.icon)
                binding.ivPasswordManagerIcon.clearColorFilter()
            } else {
                binding.ivPasswordManagerIcon.setImageResource(R.drawable.ic_lock)
                binding.ivPasswordManagerIcon.setColorFilter(getColor(R.color.primary))
            }
        } else {
            binding.tvActiveAutofillTitle.text = "No Autofill Service Selected"
            binding.tvActiveAutofillSubtitle.text = "Choose Google Password Manager, Bitwarden, or your preferred password manager."
            binding.ivAutofillStatusIcon.setColorFilter(getColor(R.color.red_danger))
            binding.btnChangeAutofillService.text = "Select Autofill Service"

            // Universal row when none selected
            binding.tvPasswordManagerActionTitle.text = "Select Password Manager"
            binding.tvPasswordManagerActionSubtitle.text = "Tap to choose an autofill provider in Android Settings"
            binding.ivPasswordManagerIcon.setImageResource(R.drawable.ic_lock)
            binding.ivPasswordManagerIcon.setColorFilter(getColor(R.color.primary))
        }

        binding.btnChangeAutofillService.setOnClickListener {
            AutofillHelper.openAutofillServiceSettings(this)
        }

        binding.rowGooglePasswordManager.setOnClickListener {
            AutofillHelper.openActivePasswordManager(this, activeManager)
        }
    }

    private fun setupToggles() {
        // Autofill forms toggle
        binding.switchAutofillForms.isChecked = preferences.isAutofillEnabled
        binding.rowAutofillForms.setOnClickListener {
            val newState = !binding.switchAutofillForms.isChecked
            binding.switchAutofillForms.isChecked = newState
            preferences.isAutofillEnabled = newState
        }

        // Save passwords prompt toggle
        binding.switchSavePasswordsPrompt.isChecked = preferences.isSavePasswordsPromptEnabled
        binding.rowSavePasswordsPrompt.setOnClickListener {
            val newState = !binding.switchSavePasswordsPrompt.isChecked
            binding.switchSavePasswordsPrompt.isChecked = newState
            preferences.isSavePasswordsPromptEnabled = newState
        }

        // Passkeys support toggle
        binding.switchPasskeysSupport.isChecked = preferences.isPasskeysEnabled
        binding.rowPasskeysSupport.setOnClickListener {
            val newState = !binding.switchPasskeysSupport.isChecked
            binding.switchPasskeysSupport.isChecked = newState
            preferences.isPasskeysEnabled = newState
        }
    }
}
