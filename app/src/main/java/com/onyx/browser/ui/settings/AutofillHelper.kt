package com.onyx.browser.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.core.content.ContextCompat
import com.onyx.browser.R

data class AutofillServiceInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isGoogle: Boolean,
    val isCurrent: Boolean
)

object AutofillHelper {

    fun isAutofillSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(AutofillManager::class.java) ?: return false
        return manager.isAutofillSupported
    }

    fun hasEnabledAutofillServices(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(AutofillManager::class.java) ?: return false
        return manager.hasEnabledAutofillServices()
    }

    fun openAutofillServiceSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intents = listOf(
                Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent("android.settings.AUTOFILL_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )

            for (intent in intents) {
                try {
                    context.startActivity(intent)
                    return
                } catch (_: Exception) {}
            }
        }
    }

    fun openGooglePasswordManager(context: Context) {
        val intents = listOf(
            Intent().apply {
                component = ComponentName("com.google.android.gms", "com.google.android.gms.credential.manager.PasswordManagerActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent("com.google.android.gms.settings.AUTOFILL").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent("android.settings.AUTOFILL_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_VIEW, Uri.parse("https://passwords.google.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        for (intent in intents) {
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
    }

    fun getInstalledAutofillServices(context: Context): List<AutofillServiceInfo> {
        val pm = context.packageManager
        val list = mutableListOf<AutofillServiceInfo>()

        // 1. Google Play Services / Google Password Manager (always supported on Android with GMS)
        try {
            val gmsInfo = pm.getPackageInfo("com.google.android.gms", 0)
            val icon = try { pm.getApplicationIcon("com.google.android.gms") } catch (_: Exception) { null }
            list.add(
                AutofillServiceInfo(
                    packageName = "com.google.android.gms",
                    appName = "Google Password Manager",
                    icon = icon,
                    isGoogle = true,
                    isCurrent = false
                )
            )
        } catch (_: Exception) {
            // No GMS
        }

        // 2. Discover apps registering AutofillService (Bitwarden, 1Password, etc.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val serviceIntent = Intent("android.service.autofill.AutofillService")
                val resolved = pm.queryIntentServices(serviceIntent, PackageManager.MATCH_ALL)
                for (info in resolved) {
                    val pkg = info.serviceInfo.packageName
                    if (list.any { it.packageName == pkg }) continue
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        val name = pm.getApplicationLabel(appInfo).toString()
                        val icon = pm.getApplicationIcon(appInfo)
                        list.add(
                            AutofillServiceInfo(
                                packageName = pkg,
                                appName = name,
                                icon = icon,
                                isGoogle = false,
                                isCurrent = false
                            )
                        )
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        // 3. Known third-party password managers check (if not caught by queryIntentServices)
        val knownPackages = listOf(
            "com.x8bit.bitwarden" to "Bitwarden",
            "com.onepassword.android" to "1Password",
            "com.dashlane" to "Dashlane",
            "com.lastpass.lpandroid" to "LastPass",
            "keepass2android.keepass2android" to "KeePass2Android",
            "com.samsung.android.samsungpass" to "Samsung Pass",
            "org.nordpass.android" to "NordPass",
            "me.proton.android.pass" to "Proton Pass",
            "io.enpass.app" to "Enpass",
            "com.enpass.app" to "Enpass"
        )

        for ((pkg, name) in knownPackages) {
            if (list.any { it.packageName == pkg }) continue
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val icon = pm.getApplicationIcon(appInfo)
                val label = pm.getApplicationLabel(appInfo).toString().ifBlank { name }
                list.add(
                    AutofillServiceInfo(
                        packageName = pkg,
                        appName = label,
                        icon = icon,
                        isGoogle = false,
                        isCurrent = false
                    )
                )
            } catch (_: Exception) {}
        }

        return list
    }
}
