package com.onyx.browser.ui.downloads

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle

data class ExternalDownloaderInfo(
    val id: String,
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    val isKnown: Boolean = true
)

object ExternalDownloaderHelper {

    private val KNOWN_DOWNLOADERS = listOf(
        DownloaderDef("1dm_plus", "1DM+ (IDM+)", "idm.internet.download.manager.plus"),
        DownloaderDef("1dm", "1DM (IDM)", "idm.internet.download.manager"),
        DownloaderDef("1dm_lite", "1DM Lite", "idm.internet.download.manager.lite"),
        DownloaderDef("adm_pro", "ADM Pro", "com.dv.adm.pay"),
        DownloaderDef("adm", "Advanced Download Manager (ADM)", "com.dv.adm"),
        DownloaderDef("fdm", "Free Download Manager (FDM)", "org.freedownloadmanager.fdm"),
        DownloaderDef("download_navi", "Download Navi", "org.nfield.downloadnavi"),
        DownloaderDef("aria2", "Aria2App", "com.gianlu.aria2app")
    )

    private data class DownloaderDef(
        val id: String,
        val defaultName: String,
        val packageName: String
    )

    fun getInstalledDownloaders(context: Context, url: String = "", mimeType: String = ""): List<ExternalDownloaderInfo> {
        val pm = context.packageManager
        val installedList = mutableListOf<ExternalDownloaderInfo>()
        val seenPackages = mutableSetOf<String>()

        // 1. Prioritize known download managers (1DM, 1DM+, ADM, FDM, etc.)
        for (def in KNOWN_DOWNLOADERS) {
            try {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(def.packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    pm.getApplicationInfo(def.packageName, 0)
                }
                val label = pm.getApplicationLabel(appInfo).toString().ifBlank { def.defaultName }
                val icon = pm.getApplicationIcon(appInfo)
                installedList.add(
                    ExternalDownloaderInfo(
                        id = def.id,
                        name = label,
                        packageName = def.packageName,
                        icon = icon,
                        isKnown = true
                    )
                )
                seenPackages.add(def.packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed
            }
        }

        // 2. Discover any other installed apps on the device that can handle file downloading
        if (url.isNotBlank()) {
            try {
                val testIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(url), mimeType.ifBlank { "*/*" })
                }
                val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(testIntent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    pm.queryIntentActivities(testIntent, 0)
                }
                val ignoredPackages = setOf(
                    context.packageName,
                    "com.android.chrome",
                    "org.mozilla.firefox",
                    "com.microsoft.emmx",
                    "com.opera.browser",
                    "com.sec.android.app.sbrowser",
                    "com.brave.browser",
                    "com.duckduckgo.mobile.android"
                )
                for (resolveInfo in activities) {
                    val pkg = resolveInfo.activityInfo.packageName
                    if (pkg !in seenPackages && pkg !in ignoredPackages) {
                        val label = resolveInfo.loadLabel(pm).toString()
                        val icon = resolveInfo.loadIcon(pm)
                        installedList.add(
                            ExternalDownloaderInfo(
                                id = pkg,
                                name = label,
                                packageName = pkg,
                                icon = icon,
                                isKnown = false
                            )
                        )
                        seenPackages.add(pkg)
                    }
                }
            } catch (_: Exception) {
            }
        }

        return installedList
    }

    fun buildDownloadIntent(
        downloader: ExternalDownloaderInfo,
        url: String,
        fileName: String,
        userAgent: String,
        cookies: String,
        referer: String,
        mimeType: String
    ): Intent {
        val targetPackage = downloader.packageName
        val headersBundle = Bundle().apply {
            if (cookies.isNotBlank()) {
                putString("Cookie", cookies)
                putString("cookie", cookies)
            }
            if (userAgent.isNotBlank()) {
                putString("User-Agent", userAgent)
                putString("user-agent", userAgent)
            }
            if (referer.isNotBlank()) {
                putString("Referer", referer)
                putString("referer", referer)
            }
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setPackage(targetPackage)
            setDataAndType(Uri.parse(url), mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Standard Android browser headers bundle
            putExtra("android.provider.Browser.EXTRA_HEADERS", headersBundle)

            // 1DM / IDM parameters
            putExtra("extra_filename", fileName)
            putExtra("extra_cookie", cookies)
            putExtra("extra_cookies", cookies)
            putExtra("extra_user_agent", userAgent)
            putExtra("extra_referer", referer)

            // ADM (Advanced Download Manager) parameters
            putExtra("com.dv.adm.extra.EXTRA_FILENAME", fileName)
            putExtra("com.dv.adm.extra.EXTRA_COOKIES", cookies)
            putExtra("com.dv.adm.extra.EXTRA_USER_AGENT", userAgent)
            putExtra("com.dv.adm.extra.EXTRA_REFERER", referer)

            // FDM / Generic download manager parameters
            putExtra("android.intent.extra.TEXT", url)
            putExtra("android.intent.extra.TITLE", fileName)
            putExtra("title", fileName)
            putExtra("filename", fileName)
            putExtra("Cookie", cookies)
            putExtra("cookies", cookies)
            putExtra("User-Agent", userAgent)
            putExtra("user_agent", userAgent)
            putExtra("Referer", referer)
            putExtra("referer", referer)
        }
    }

    fun openPlayStoreForDownloaders(context: Context) {
        val query = "download manager 1dm adm"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$query")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$query&c=apps")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }
}
