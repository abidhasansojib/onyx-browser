package com.onyx.browser.ui.settings

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
import com.onyx.browser.databinding.ActivityAddressBarSettingsBinding
import com.onyx.browser.ui.theme.AccentColorManager

class AddressBarSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddressBarSettingsBinding
    private lateinit var preferences: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentColorManager.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)

        // Edge-to-edge system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val canvasBg = ContextCompat.getColor(this, R.color.settings_canvas_bg)
        @Suppress("DEPRECATION")
        window.statusBarColor = canvasBg
        @Suppress("DEPRECATION")
        window.navigationBarColor = canvasBg

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode

        binding = ActivityAddressBarSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BrowserPreferences.getInstance(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.addressBarSettingsRoot) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.updatePadding(top = statusBarInsets.top)
            binding.scrollView.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.appBarLayout.apply {
            isLiftOnScroll = false
            setLiftable(false)
            setLifted(false)
        }

        binding.switchReplaceHomeWithRefresh.isChecked = preferences.isHomeReplacedWithRefresh
        binding.cardReplaceHomeWithRefresh.setOnClickListener {
            val newState = !binding.switchReplaceHomeWithRefresh.isChecked
            binding.switchReplaceHomeWithRefresh.isChecked = newState
            preferences.isHomeReplacedWithRefresh = newState
        }

        binding.switchAutoHideToolbar.isChecked = preferences.isAutoHideToolbarEnabled
        binding.cardAutoHideToolbar.setOnClickListener {
            val newState = !binding.switchAutoHideToolbar.isChecked
            binding.switchAutoHideToolbar.isChecked = newState
            preferences.isAutoHideToolbarEnabled = newState
        }
    }
}
