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
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.onyx.browser.BuildConfig
import com.onyx.browser.R
import com.onyx.browser.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
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
        setupLinks()
    }

    private fun setupAppInfo() {
        // App Version
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

        val versionName = packageInfo?.versionName ?: "1.0.0"
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
        val buildType = if (BuildConfig.DEBUG) "Debug" else "Release"
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
