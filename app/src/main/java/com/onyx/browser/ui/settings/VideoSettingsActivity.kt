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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityVideoSettingsBinding

class VideoSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoSettingsBinding
    private lateinit var preferences: BrowserPreferences
    private var pendingPipEnable: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.parseColor("#0B0305")
        window.navigationBarColor = android.graphics.Color.parseColor("#0B0305")

        binding = ActivityVideoSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BrowserPreferences.getInstance(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.videoSettingsRoot) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.updatePadding(top = statusBarInsets.top)
            binding.scrollView.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupPlaybackControls()
    }

    override fun onResume() {
        super.onResume()
        updatePipSwitchState()
    }

    private fun setupPlaybackControls() {
        // Background play switch
        binding.settingBackgroundPlaySwitch.isChecked = preferences.isBackgroundPlayEnabled
        binding.settingBackgroundPlayRow.setOnClickListener {
            val newState = !binding.settingBackgroundPlaySwitch.isChecked
            binding.settingBackgroundPlaySwitch.isChecked = newState
            preferences.isBackgroundPlayEnabled = newState
            if (!newState) {
                com.onyx.browser.media.MediaPlaybackService.stop(this)
            }
        }

        // Picture-in-picture switch
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
}
