package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityAutofillSettingsBinding
import com.onyx.browser.databinding.ItemAutofillServiceBinding

class AutofillSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutofillSettingsBinding
    private lateinit var preferences: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutofillSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BrowserPreferences.getInstance(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupAutofillStatus()
        setupInstalledServicesList()
        setupToggles()
    }

    override fun onResume() {
        super.onResume()
        setupAutofillStatus()
    }

    private fun setupAutofillStatus() {
        val isSupported = AutofillHelper.isAutofillSupported(this)
        val hasService = AutofillHelper.hasEnabledAutofillServices(this)

        if (!isSupported) {
            binding.tvActiveAutofillTitle.text = "Autofill Not Supported"
            binding.tvActiveAutofillSubtitle.text = "Android Autofill requires Android 8.0 or higher."
            binding.btnChangeAutofillService.isEnabled = false
            return
        }

        if (hasService) {
            binding.tvActiveAutofillTitle.text = "Autofill Service Active"
            binding.tvActiveAutofillSubtitle.text = "Google Password Manager or third-party autofill service is enabled."
            binding.ivAutofillStatusIcon.setColorFilter(getColor(R.color.primary))
        } else {
            binding.tvActiveAutofillTitle.text = "No Autofill Service Selected"
            binding.tvActiveAutofillSubtitle.text = "Choose Google Password Manager or your preferred password manager."
            binding.ivAutofillStatusIcon.setColorFilter(getColor(R.color.red_danger))
        }

        binding.btnChangeAutofillService.setOnClickListener {
            AutofillHelper.openAutofillServiceSettings(this)
        }

        binding.rowGooglePasswordManager.setOnClickListener {
            AutofillHelper.openGooglePasswordManager(this)
        }
    }

    private fun setupInstalledServicesList() {
        val container = binding.llInstalledServices
        container.removeAllViews()

        val services = AutofillHelper.getInstalledAutofillServices(this)

        for (service in services) {
            val itemBinding = ItemAutofillServiceBinding.inflate(
                LayoutInflater.from(this),
                container,
                false
            )

            itemBinding.tvServiceName.text = service.appName
            itemBinding.tvServicePackage.text = service.packageName

            if (service.icon != null) {
                itemBinding.ivServiceIcon.setImageDrawable(service.icon)
            } else {
                itemBinding.ivServiceIcon.setImageResource(R.drawable.ic_lock)
            }

            itemBinding.btnSelectService.setOnClickListener {
                if (service.isGoogle) {
                    AutofillHelper.openGooglePasswordManager(this)
                } else {
                    AutofillHelper.openAutofillServiceSettings(this)
                }
            }

            itemBinding.root.setOnClickListener {
                if (service.isGoogle) {
                    AutofillHelper.openGooglePasswordManager(this)
                } else {
                    AutofillHelper.openAutofillServiceSettings(this)
                }
            }

            container.addView(itemBinding.root)
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
