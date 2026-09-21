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

data class ActivePasswordManagerInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isGoogle: Boolean
)

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

    /**
     * Inspect Android system settings to detect the currently active / selected
     * Password Manager and Autofill Service (e.g. Google Password Manager, Bitwarden, 1Password, etc.).
     */
    fun getActivePasswordManager(context: Context): ActivePasswordManagerInfo? {
        val pm = context.packageManager
        val contentResolver = context.contentResolver

        var rawService: String? = null

        // 1. Android 14+ (API 34+) primary credential service
        try {
            rawService = Settings.Secure.getString(contentResolver, "credential_service_primary")
        } catch (_: Exception) {}

        // 2. Standard Android Autofill service (API 26+)
        if (rawService.isNullOrBlank()) {
            try {
                rawService = Settings.Secure.getString(contentResolver, "autofill_service")
            } catch (_: Exception) {}
        }

        // 3. Fallback to credential_service list
        if (rawService.isNullOrBlank()) {
            try {
                rawService = Settings.Secure.getString(contentResolver, "credential_service")
            } catch (_: Exception) {}
        }

        val pkgName = extractPackageName(rawService)

        if (!pkgName.isNullOrBlank() && pkgName != "null" && pkgName != "none") {
            if (pkgName == "com.google.android.gms") {
                val icon = try { pm.getApplicationIcon("com.google.android.gms") } catch (_: Exception) { null }
                return ActivePasswordManagerInfo(
                    packageName = "com.google.android.gms",
                    appName = "Google Password Manager",
                    icon = icon,
                    isGoogle = true
                )
            }

            try {
                val appInfo = pm.getApplicationInfo(pkgName, 0)
                val label = pm.getApplicationLabel(appInfo).toString().trim()
                val icon = pm.getApplicationIcon(appInfo)
                val resolvedName = if (label.isNotBlank()) label else getKnownProviderName(pkgName)
                return ActivePasswordManagerInfo(
                    packageName = pkgName,
                    appName = resolvedName,
                    icon = icon,
                    isGoogle = false
                )
            } catch (_: Exception) {
                return ActivePasswordManagerInfo(
                    packageName = pkgName,
                    appName = getKnownProviderName(pkgName),
                    icon = null,
                    isGoogle = false
                )
            }
        }

        // 4. If hasEnabledAutofillServices is true with GMS available
        if (hasEnabledAutofillServices(context)) {
            try {
                pm.getPackageInfo("com.google.android.gms", 0)
                val icon = try { pm.getApplicationIcon("com.google.android.gms") } catch (_: Exception) { null }
                return ActivePasswordManagerInfo(
                    packageName = "com.google.android.gms",
                    appName = "Google Password Manager",
                    icon = icon,
                    isGoogle = true
                )
            } catch (_: Exception) {}
        }

        return null
    }

    private fun extractPackageName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.equals("null", ignoreCase = true) || trimmed.equals("none", ignoreCase = true)) return null

        val firstEntry = trimmed.split(':', ',').firstOrNull { it.isNotBlank() } ?: trimmed

        val cn = ComponentName.unflattenFromString(firstEntry)
        if (cn != null && cn.packageName.isNotBlank()) {
            return cn.packageName
        }

        if (firstEntry.contains('/')) {
            val p = firstEntry.substringBefore('/').trim()
            if (p.isNotBlank()) return p
        }

        return firstEntry.trim().ifBlank { null }
    }

    private fun getKnownProviderName(pkg: String): String {
        return when {
            pkg.contains("bitwarden") -> "Bitwarden"
            pkg.contains("onepassword") -> "1Password"
            pkg.contains("dashlane") -> "Dashlane"
            pkg.contains("lastpass") -> "LastPass"
            pkg.contains("keepass") -> "KeePass"
            pkg.contains("samsungpass") -> "Samsung Pass"
            pkg.contains("nordpass") -> "NordPass"
            pkg.contains("proton") -> "Proton Pass"
            pkg.contains("authenticator") -> "Microsoft Authenticator"
            pkg.contains("enpass") -> "Enpass"
            pkg == "com.google.android.gms" -> "Google Password Manager"
            else -> "Password Manager"
        }
    }

    /**
     * Universally open the user's active password manager application or vault.
     */
    fun openActivePasswordManager(context: Context, manager: ActivePasswordManagerInfo?) {
        if (manager == null) {
            openAutofillServiceSettings(context)
            return
        }

        if (manager.isGoogle) {
            openGooglePasswordManager(context)
            return
        }

        val pm = context.packageManager
        val pkg = manager.packageName

        // 1. Launch intent for the package
        val launchIntent = pm.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launchIntent != null) {
            try {
                context.startActivity(launchIntent)
                return
            } catch (_: Exception) {}
        }

        // 2. Specific intent fallback for known password managers or app details
        val fallbackIntents = listOf(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                `package` = pkg
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        for (intent in fallbackIntents) {
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }

        // 3. Fallback to system autofill settings
        openAutofillServiceSettings(context)
    }

    fun getInstalledAutofillServices(context: Context): List<AutofillServiceInfo> {
        val pm = context.packageManager
        val list = mutableListOf<AutofillServiceInfo>()

        try {
            pm.getPackageInfo("com.google.android.gms", 0)
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
        } catch (_: Exception) {}

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
