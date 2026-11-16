package com.onyx.browser.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.BuildConfig
import com.onyx.browser.R
import com.onyx.browser.databinding.ActivityAboutBinding
import com.onyx.browser.update.AppUpdateManager
import com.onyx.browser.update.AppUpdatePromptHelper
import com.onyx.browser.update.UpdateCheckResult
import com.onyx.browser.update.UpdateInfo
import com.onyx.browser.ui.theme.AccentColorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private var isCheckingUpdate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentColorManager.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge transparent system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode

        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply WindowInsets to avoid collision with status bar and navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.aboutRoot) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.updatePadding(top = statusBarInsets.top)
            binding.scrollView.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupAppInfo()
        setupUpdateChecker()
        setupLinks()
    }

    override fun onResume() {
        super.onResume()
        AppUpdatePromptHelper.handleResumeInstall(this)
    }

    private fun setupAppInfo() {
        val versionName = AppUpdateManager.getInstalledVersionName(this)
        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        } catch (_: Exception) {
            null
        }

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode ?: 1L
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toLong() ?: 1L
        }

        val versionText = "$versionName (Build $versionCode)"
        binding.tvHeaderVersionBadge.text = "v$versionName"
        binding.tvAppVersion.text = versionText

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Universal"
        val buildType = if (AppUpdateManager.isPrerelease(this)) "Prerelease" else "Release"
        binding.tvAppBuildType.text = "$buildType • $abi"

        binding.rowAppVersion.setOnClickListener {
            copyToClipboard(getString(R.string.about_app_version), versionText)
        }

        // Package Name
        binding.tvPackageName.text = packageName
        binding.rowPackageName.setOnClickListener {
            copyToClipboard(getString(R.string.about_package_name), packageName)
        }

        // Operating System
        val osVersionName = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        binding.tvOsVersion.text = osVersionName
        binding.tvDeviceModel.text = deviceModel

        binding.rowOsVersion.setOnClickListener {
            copyToClipboard(getString(R.string.about_os_version), "$osVersionName • $deviceModel")
        }

        // WebView Engine
        val webViewPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()
        } else {
            null
        }

        val webViewVersion = webViewPackage?.versionName ?: run {
            try {
                packageManager.getPackageInfo("com.google.android.webview", 0).versionName
            } catch (_: Exception) {
                try {
                    packageManager.getPackageInfo("com.android.chrome", 0).versionName
                } catch (_: Exception) {
                    "Unknown"
                }
            }
        }
        val webViewName = webViewPackage?.packageName ?: "Android System WebView"
        binding.tvWebViewVersion.text = webViewVersion
        binding.tvWebViewPackage.text = webViewName

        binding.rowWebViewVersion.setOnClickListener {
            copyToClipboard(getString(R.string.about_webview_version), "$webViewVersion ($webViewName)")
        }
    }

    private fun setupUpdateChecker() {
        binding.rowCheckUpdates.setOnClickListener {
            performUpdateCheck(manual = true)
        }

        // Automatically perform a non-blocking check when viewing About screen
        performUpdateCheck(manual = false)
    }

    private fun performUpdateCheck(manual: Boolean) {
        if (isCheckingUpdate) return
        isCheckingUpdate = true

        binding.pbCheckingUpdate.visibility = View.VISIBLE
        binding.ivUpdateChevron.visibility = View.GONE
        binding.tvUpdateStatus.setText(R.string.checking_for_updates)

        lifecycleScope.launch {
            val result = AppUpdateManager.checkForUpdate(this@AboutActivity)

            withContext(Dispatchers.Main) {
                isCheckingUpdate = false
                binding.pbCheckingUpdate.visibility = View.GONE
                binding.ivUpdateChevron.visibility = View.VISIBLE

                when (result) {
                    is UpdateCheckResult.Available -> {
                        binding.tvUpdateStatus.text = "${getString(R.string.update_available)} (${result.updateInfo.tagName})"
                        AppUpdatePromptHelper.showUpdateAvailableDialog(this@AboutActivity, result.updateInfo)
                    }
                    is UpdateCheckResult.UpToDate -> {
                        binding.tvUpdateStatus.setText(R.string.app_up_to_date)
                        if (manual) {
                            MaterialAlertDialogBuilder(this@AboutActivity)
                                .setTitle(R.string.app_up_to_date)
                                .setMessage(getString(R.string.app_up_to_date_desc, "v${result.currentVersion}"))
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                    is UpdateCheckResult.RateLimited -> {
                        binding.tvUpdateStatus.text = "Check limit reached"
                        if (manual) {
                            MaterialAlertDialogBuilder(this@AboutActivity)
                                .setTitle("Update Check")
                                .setMessage(result.message)
                                .setPositiveButton(R.string.view_on_github) { _, _ ->
                                    openUrl("https://github.com/abidhasansojib/onyx-browser/releases")
                                }
                                .setNegativeButton(R.string.cancel, null)
                                .show()
                        }
                    }
                    is UpdateCheckResult.Error -> {
                        binding.tvUpdateStatus.setText(R.string.check_for_updates_desc)
                        if (manual) {
                            Toast.makeText(this@AboutActivity, result.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun setupLinks() {
        // Contact Developer (Telegram)
        binding.rowContactDeveloper.setOnClickListener {
            openUrl("https://t.me/abidhasansojib")
        }

        // Report Bug (GitHub Issues)
        binding.rowReportBug.setOnClickListener {
            openUrl("https://github.com/abidhasansojib/onyx-browser/issues")
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.copied_to_clipboard, label), Toast.LENGTH_SHORT).show()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open link: $url", Toast.LENGTH_SHORT).show()
        }
    }
}
