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

    fun openAutofillServiceSettings(context: Context, providerPackageName: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intents = mutableListOf<Intent>()

            // On Android 14+ (API 34+), prefer the unified "Passwords, passkeys & autofill" settings screen
            if (Build.VERSION.SDK_INT >= 34) {
                intents.add(
                    Intent("android.settings.CREDENTIAL_PROVIDER").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                if (!providerPackageName.isNullOrBlank() && providerPackageName != "com.google.android.gms") {
                    intents.add(
                        Intent("android.settings.CREDENTIAL_PROVIDER").apply {
                            data = Uri.parse("package:$providerPackageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }

            intents.add(
                Intent("android.settings.AUTOFILL_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            intents.add(
                Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            intents.add(
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

    fun openPasskeyProviderSettings(context: Context, activeManager: ActivePasswordManagerInfo? = null) {
        val preferredThirdPartyPkg = activeManager?.packageName?.takeIf { !activeManager.isGoogle }
            ?: getInstalledAutofillServices(context).firstOrNull { !it.isGoogle }?.packageName

        val intents = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= 34) {
            if (!preferredThirdPartyPkg.isNullOrBlank()) {
                intents.add(
                    Intent("android.settings.CREDENTIAL_PROVIDER").apply {
                        data = Uri.parse("package:$preferredThirdPartyPkg")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            intents.add(
                Intent("android.settings.CREDENTIAL_PROVIDER").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
        intents.add(
            Intent("android.settings.AUTOFILL_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        intents.add(
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
     * Inspect Android AutofillManager (API 28+) and system settings to detect the currently
     * active Password Manager and Credential Provider (Bitwarden, Proton Pass, 1Password, KeePassDX, etc.).
     */
    fun getActivePasswordManager(context: Context): ActivePasswordManagerInfo? {
        val pm = context.packageManager
        val contentResolver = context.contentResolver

        var resolvedPkg: String? = null

        // 1. Official Android 9+ (API 28+) public SDK API — works on targetSdk 35 without SecurityException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val manager = context.getSystemService(AutofillManager::class.java)
                val component = manager?.autofillServiceComponentName
                if (component != null && component.packageName.isNotBlank()) {
                    resolvedPkg = component.packageName
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback to Settings.Secure (primarily for Android 8.0-8.1 / API 26-27)
        if (resolvedPkg.isNullOrBlank()) {
            var rawService: String? = null
            try {
                rawService = Settings.Secure.getString(contentResolver, "credential_service_primary")
            } catch (_: Exception) {}

            if (rawService.isNullOrBlank()) {
                try {
                    rawService = Settings.Secure.getString(contentResolver, "autofill_service")
                } catch (_: Exception) {}
            }

            if (rawService.isNullOrBlank()) {
                try {
                    rawService = Settings.Secure.getString(contentResolver, "credential_service")
                } catch (_: Exception) {}
            }
            resolvedPkg = extractPackageName(rawService)
        }

        if (!resolvedPkg.isNullOrBlank() && resolvedPkg != "null" && resolvedPkg != "none") {
            if (resolvedPkg == "com.google.android.gms") {
                val icon = try { pm.getApplicationIcon("com.google.android.gms") } catch (_: Exception) { null }
                return ActivePasswordManagerInfo(
                    packageName = "com.google.android.gms",
                    appName = "Google Password Manager",
                    icon = icon,
                    isGoogle = true
                )
            }

            try {
                val appInfo = pm.getApplicationInfo(resolvedPkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString().trim()
                val icon = pm.getApplicationIcon(appInfo)
                val resolvedName = if (label.isNotBlank()) label else getKnownProviderName(resolvedPkg)
                return ActivePasswordManagerInfo(
                    packageName = resolvedPkg,
                    appName = resolvedName,
                    icon = icon,
                    isGoogle = false
                )
            } catch (_: Exception) {
                return ActivePasswordManagerInfo(
                    packageName = resolvedPkg,
                    appName = getKnownProviderName(resolvedPkg),
                    icon = null,
                    isGoogle = false
                )
            }
        }

        // 3. Legacy Android 8.0-8.1 fallback when AutofillManager reports an active service without exposing ComponentName
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && hasEnabledAutofillServices(context)) {
            val nonGoogleInstalled = getInstalledAutofillServices(context).firstOrNull { !it.isGoogle }
            if (nonGoogleInstalled != null) {
                return ActivePasswordManagerInfo(
                    packageName = nonGoogleInstalled.packageName,
                    appName = nonGoogleInstalled.appName,
                    icon = nonGoogleInstalled.icon,
                    isGoogle = false
                )
            }
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

        val firstEntry = trimmed.split(':', ',', ';').firstOrNull { it.isNotBlank() } ?: trimmed

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
            pkg.contains("onepassword") || pkg.contains("agilebits") -> "1Password"
            pkg.contains("dashlane") -> "Dashlane"
            pkg.contains("lastpass") -> "LastPass"
            pkg.contains("kunzisoft") -> "KeePassDX"
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
        openAutofillServiceSettings(context, pkg)
    }

    fun getInstalledAutofillServices(context: Context): List<AutofillServiceInfo> {
        val pm = context.packageManager
        val list = mutableListOf<AutofillServiceInfo>()

        val serviceActions = mutableListOf("android.service.autofill.AutofillService")
        if (Build.VERSION.SDK_INT >= 34) {
            serviceActions.add("android.service.credentials.CredentialProviderService")
            serviceActions.add("android.service.credentials.system.CredentialProviderService")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            for (action in serviceActions) {
                try {
                    val resolved = pm.queryIntentServices(Intent(action), PackageManager.MATCH_ALL)
                    for (info in resolved) {
                        val pkg = info.serviceInfo.packageName
                        if (pkg == "android" || list.any { it.packageName == pkg }) continue
                        val isGms = pkg == "com.google.android.gms"
                        try {
                            val appInfo = pm.getApplicationInfo(pkg, 0)
                            val name = if (isGms) "Google Password Manager" else pm.getApplicationLabel(appInfo).toString()
                            val icon = pm.getApplicationIcon(appInfo)
                            list.add(
                                AutofillServiceInfo(
                                    packageName = pkg,
                                    appName = name,
                                    icon = icon,
                                    isGoogle = isGms,
                                    isCurrent = false
                                )
                            )
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }

        val knownPackages = listOf(
            "com.x8bit.bitwarden" to "Bitwarden",
            "com.x8bit.bitwarden.beta" to "Bitwarden Beta",
            "me.proton.android.pass" to "Proton Pass",
            "com.onepassword.android" to "1Password",
            "com.agilebits.onepassword" to "1Password",
            "com.kunzisoft.keepass.free" to "KeePassDX",
            "com.kunzisoft.keepass.libre" to "KeePassDX Libre",
            "keepass2android.keepass2android" to "KeePass2Android",
            "keepass2android.keepass2android_nonet" to "KeePass2Android Offline",
            "com.dashlane" to "Dashlane",
            "org.nordpass.android" to "NordPass",
            "com.lastpass.lpandroid" to "LastPass",
            "io.enpass.app" to "Enpass",
            "com.enpass.app" to "Enpass",
            "com.samsung.android.samsungpass" to "Samsung Pass",
            "com.azure.authenticator" to "Microsoft Authenticator",
            "com.google.android.gms" to "Google Password Manager"
        )

        for ((pkg, name) in knownPackages) {
            if (list.any { it.packageName == pkg }) continue
            val isGms = pkg == "com.google.android.gms"
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val icon = pm.getApplicationIcon(appInfo)
                val label = if (isGms) name else pm.getApplicationLabel(appInfo).toString().ifBlank { name }
                list.add(
                    AutofillServiceInfo(
                        packageName = pkg,
                        appName = label,
                        icon = icon,
                        isGoogle = isGms,
                        isCurrent = false
                    )
                )
            } catch (_: Exception) {}
        }

        return list
    }
}
