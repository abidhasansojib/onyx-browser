package com.onyx.browser.web.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.onyx.browser.ui.dialog.OpenInAppPromptDialog
import com.onyx.browser.web.TabActionCallback
import java.net.URI
import java.util.Collections

/**
 * Handles external application navigation, deep-link `intent://` resolution,
 * custom scheme dispatching (`mailto:`, `tel:`, `sms:`, `geo:`, `market:`, social apps),
 * fallback URL safety validation, [OpenInAppPromptDialog] prompts, and redirect loop breaking.
 */
class ExternalNavigationHandler(
    @Suppress("unused")
    private val tabActionCallback: TabActionCallback? = null
) {

    var onOpenInAppPrompt: ((intent: Intent, appName: String?, fallback: (() -> Unit)?) -> Unit)? = null

    // ── Redirect Loop Breaker ─────────────────────────────────────────────────────
    private val recentNavigations = ArrayDeque<Pair<String, Long>>()

    // Bounded LRU cache of URLs where user explicitly chose "Stay in Onyx", avoiding unbounded memory growth
    private val bypassAppInterceptUrls = object {
        private val set = Collections.synchronizedSet(LinkedHashSet<String>())

        fun add(url: String): Boolean {
            synchronized(set) {
                if (set.size >= MAX_BYPASS_CACHE_SIZE) {
                    val iterator = set.iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
                return set.add(url)
            }
        }

        fun remove(url: String): Boolean {
            return set.remove(url)
        }
    }

    fun addBypassUrl(url: String): Boolean = bypassAppInterceptUrls.add(url)

    fun consumeBypassUrl(url: String, normalizedUrl: String = url.trimEnd('/')): Boolean {
        return bypassAppInterceptUrls.remove(url) || bypassAppInterceptUrls.remove(normalizedUrl)
    }

    /**
     * Tracks main-frame navigations within a [BURST_WINDOW_MS] sliding window and stops loading
     * when either:
     * 1. The same normalized URL (stripped of query parameters) is visited >= [EXACT_LOOP_THRESHOLD] times, or
     * 2. Two distinct normalized URLs alternate back-and-forth >= [PING_PONG_MIN_TOTAL] times (>= [PING_PONG_PER_URL_THRESHOLD] each).
     */
    fun checkAndBreakRedirectLoop(
        view: WebView?,
        url: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        while (recentNavigations.isNotEmpty() && (nowMs - recentNavigations.first().second) > BURST_WINDOW_MS) {
            recentNavigations.removeFirst()
        }

        val normalized = url.substringBefore('?')
        recentNavigations.addLast(normalized to nowMs)

        val exactCount = recentNavigations.count { it.first == normalized }
        if (exactCount >= EXACT_LOOP_THRESHOLD) {
            Log.w(TAG, "Redirect loop detected for $url (count: $exactCount) - breaking loop")
            view?.stopLoading()
            recentNavigations.clear()
            return true
        }

        // Detect cyclic alternating ping-pong loop (A -> B -> A -> B -> A -> B)
        if (recentNavigations.size >= PING_PONG_MIN_TOTAL) {
            val distinctUrls = recentNavigations.map { it.first }.distinct()
            if (distinctUrls.size == 2) {
                val (u1, u2) = distinctUrls
                val c1 = recentNavigations.count { it.first == u1 }
                val c2 = recentNavigations.count { it.first == u2 }
                if (c1 >= PING_PONG_PER_URL_THRESHOLD && c2 >= PING_PONG_PER_URL_THRESHOLD) {
                    Log.w(TAG, "Alternating redirect loop detected between $u1 and $u2 - breaking loop")
                    view?.stopLoading()
                    recentNavigations.clear()
                    return true
                }
            }
        }

        return false
    }

    fun isExternalAppInstalledForIntent(context: Context, intent: Intent): Boolean {
        return try {
            val pm = context.packageManager
            val resolveInfoList = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfoList.any { it.activityInfo?.packageName != context.packageName }
        } catch (_: Exception) {
            false
        }
    }

    fun getAppNameForIntent(context: Context, intent: Intent, fallbackName: String? = null): String? {
        try {
            val pm = context.packageManager
            val resolveInfo = pm.resolveActivity(intent, 0)
            if (resolveInfo != null) {
                val label = resolveInfo.loadLabel(pm).toString()
                if (label.isNotBlank()) return label
            }
        } catch (_: Exception) {}
        return fallbackName
    }

    fun promptOrLaunchApp(
        context: Context,
        intent: Intent,
        appName: String? = null,
        fallback: (() -> Unit)? = null
    ): Boolean {
        if (!isExternalAppInstalledForIntent(context, intent)) {
            fallback?.invoke()
            return false
        }
        val resolvedName = getAppNameForIntent(context, intent, appName)
        val prompt = onOpenInAppPrompt
        if (prompt != null) {
            prompt(intent, resolvedName, fallback)
            return true
        }
        val activity = findFragmentActivity(context)
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            val promptDialog = OpenInAppPromptDialog.newInstance(
                intent = intent,
                appName = resolvedName,
                onStayInOnyx = { fallback?.invoke() },
                onOpenInApp = null
            )
            promptDialog.showSafely(activity.supportFragmentManager)
            return true
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            fallback?.invoke()
            false
        } catch (_: Exception) {
            fallback?.invoke()
            false
        }
    }

    fun dispatchExternalScheme(
        context: Context,
        view: WebView?,
        uri: Uri,
        url: String,
        scheme: String,
        isOpenLinksInAppEnabled: Boolean,
        onNavigateFallbackUrl: ((Uri) -> Boolean)? = null
    ): Boolean {
        val normalizedScheme = scheme.lowercase()
        if (normalizedScheme == "intent") {
            return handleIntentScheme(context, view, url, isOpenLinksInAppEnabled, onNavigateFallbackUrl)
        }

        // Essential communication schemes always open external apps directly
        val isEssentialScheme = isEssentialCommunicationScheme(normalizedScheme)

        // If user explicitly disabled "Open links in app" and this is not essential communication
        if (!isOpenLinksInAppEnabled && !isEssentialScheme) {
            return true
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (isEssentialScheme) {
            return try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                handleAppNotFoundFallback(context, view, normalizedScheme, uri)
                true
            } catch (_: Exception) {
                true
            }
        }

        val appName = resolveKnownAppName(normalizedScheme)
        if (!promptOrLaunchApp(context, intent, appName, fallback = null)) {
            handleAppNotFoundFallback(context, view, normalizedScheme, uri)
        }
        return true
    }

    fun handleIntentScheme(
        context: Context,
        view: WebView?,
        url: String,
        isOpenLinksInAppEnabled: Boolean,
        onNavigateFallbackUrl: ((Uri) -> Boolean)? = null
    ): Boolean {
        return try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
                removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

            // Security check: Block attempts to target internal Onyx Browser components
            val targetPkg = intent.`package`
            if (targetPkg != null && targetPkg.equals(context.packageName, ignoreCase = true)) {
                return false
            }

            fun resolveSafeWebUri(rawUrl: String?): Uri? {
                if (!isSafeFallbackUrl(rawUrl)) return null
                val parsed = try { Uri.parse(rawUrl!!.trim()) } catch (_: Exception) { null } ?: return null
                val scheme = parsed.scheme?.lowercase()
                if (!isSafeFallbackScheme(scheme)) return null
                if (parsed.host.isNullOrBlank()) return null
                return parsed
            }

            fun navigateSafeWebUri(uri: Uri) {
                val target = uri.toString()
                bypassAppInterceptUrls.add(target)
                val handled = onNavigateFallbackUrl?.invoke(uri) == true
                if (!handled) {
                    view?.post { view.loadUrl(target) }
                }
            }

            val fallbackUri = resolveSafeWebUri(intent.getStringExtra("browser_fallback_url"))
                ?: intent.data?.let { resolveSafeWebUri(it.toString()) }

            val fallbackAction: (() -> Unit)? = if (fallbackUri != null) {
                { navigateSafeWebUri(fallbackUri) }
            } else null

            val isInstalled = isExternalAppInstalledForIntent(context, intent)

            if (isInstalled && isOpenLinksInAppEnabled) {
                return promptOrLaunchApp(
                    context = context,
                    intent = intent,
                    appName = null,
                    fallback = fallbackAction
                )
            }

            // 1st Fallback: If app is not installed or open-in-app is disabled, navigate to web fallback
            if (fallbackUri != null) {
                navigateSafeWebUri(fallbackUri)
                return true
            }

            // 2nd Fallback: explicit package -> Google Play Store
            val pkg = intent.`package`
            if (!pkg.isNullOrBlank() && isOpenLinksInAppEnabled) {
                try {
                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(marketIntent)
                } catch (_: Exception) {
                    view?.post { view.loadUrl("https://play.google.com/store/apps/details?id=$pkg") }
                }
            }
            true
        } catch (_: Exception) {
            true
        }
    }

    fun handleAppNotFoundFallback(context: Context, view: WebView?, scheme: String, @Suppress("UNUSED_PARAMETER") uri: Uri) {
        val appPackage = resolveFallbackStorePackage(scheme)
        if (appPackage != null) {
            try {
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackage")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(marketIntent)
            } catch (_: Exception) {
                view?.post { view.loadUrl("https://play.google.com/store/apps/details?id=$appPackage") }
            }
        } else {
            try {
                Toast.makeText(context, "No app found to handle this link", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    fun tryOpenAppForHttpLink(
        context: Context,
        view: WebView?,
        uri: Uri,
        url: String,
        isOpenLinksInAppEnabled: Boolean
    ): Boolean {
        if (!isOpenLinksInAppEnabled) return false
        val host = uri.host?.lowercase() ?: return false

        // Prevent dialog harassment on internal same-site navigations (e.g. browsing inside youtube.com or reddit.com)
        val currentUrl = view?.url
        if (!currentUrl.isNullOrBlank()) {
            val currentHost = try { Uri.parse(currentUrl).host?.lowercase() } catch (_: Exception) { null }
            if (!currentHost.isNullOrBlank()) {
                val isSameSite = currentHost == host ||
                        (host.endsWith(".$currentHost") || currentHost.endsWith(".$host")) ||
                        (host.contains("youtube.com") && currentHost.contains("youtube.com")) ||
                        (host.contains("reddit.com") && currentHost.contains("reddit.com"))
                if (isSameSite) {
                    return false
                }
            }
        }

        val stayInOnyxAction: () -> Unit = {
            bypassAppInterceptUrls.add(url)
            view?.post {
                view.loadUrl(url)
            }
        }

        // Quick dispatch for dedicated app links when clicked from within webpages:
        // E.g. t.me links: https://t.me/username -> tg://resolve?domain=username
        if (host == "t.me" || host == "telegram.me") {
            val path = uri.path?.removePrefix("/") ?: ""
            if (path.isNotBlank() && !path.startsWith("s/") && !path.startsWith("share") && !path.contains("/")) {
                val tgUri = Uri.parse("tg://resolve?domain=$path")
                val intent = Intent(Intent.ACTION_VIEW, tgUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (isExternalAppInstalledForIntent(context, intent)) {
                    return promptOrLaunchApp(context, intent, "Telegram", fallback = stayInOnyxAction)
                }
            }
        } else if (host == "wa.me") {
            val phone = uri.path?.removePrefix("/") ?: ""
            if (phone.isNotBlank()) {
                val text = uri.getQueryParameter("text")
                val waUrl = if (!text.isNullOrBlank()) {
                    "whatsapp://send?phone=$phone&text=${Uri.encode(text)}"
                } else {
                    "whatsapp://send?phone=$phone"
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (isExternalAppInstalledForIntent(context, intent)) {
                    return promptOrLaunchApp(context, intent, "WhatsApp", fallback = stayInOnyxAction)
                }
            }
        } else if (host.contains("youtube.com") || host == "youtu.be" || host.contains("reddit.com")) {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // To prevent an infinite loop where the system resolves the link back to Onyx Browser,
            // we query for activities and filter out our own package.
            val pm = context.packageManager
            val resolveInfoList = try {
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            } catch (_: Exception) {
                emptyList()
            }
            val externalApp = resolveInfoList.firstOrNull { it.activityInfo?.packageName != context.packageName }

            if (externalApp != null) {
                intent.setPackage(externalApp.activityInfo.packageName)
                val appLabel = externalApp.loadLabel(pm).toString()
                return promptOrLaunchApp(context, intent, appLabel, fallback = stayInOnyxAction)
            }
        }
        return false
    }

    private fun findFragmentActivity(context: Context?): FragmentActivity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is FragmentActivity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    companion object {
        private const val TAG = "ExternalNavHandler"
        const val BURST_WINDOW_MS = 4000L
        const val EXACT_LOOP_THRESHOLD = 4
        const val PING_PONG_MIN_TOTAL = 6
        const val PING_PONG_PER_URL_THRESHOLD = 3
        private const val MAX_BYPASS_CACHE_SIZE = 100

        private val INTERNAL_WEB_SCHEMES = setOf(
            "http",
            "https",
            "about",
            "data",
            "blob",
            "javascript",
            "file",
            "content"
        )

        private val ESSENTIAL_COMMUNICATION_SCHEMES = setOf(
            "tel",
            "mailto",
            "sms",
            "smsto",
            "mms",
            "geo"
        )

        /**
         * Validates that a fallback URL scheme from an `intent://` URI is strictly HTTP or HTTPS.
         * Rejects `javascript:`, `file:`, `intent:`, `data:`, `content:`, and null/blank schemes.
         */
        fun isSafeFallbackScheme(scheme: String?): Boolean {
            if (scheme.isNullOrBlank()) return false
            val normalized = scheme.trim().lowercase()
            return normalized == "http" || normalized == "https"
        }

        /**
         * Validates a full fallback URL string (e.g. `browser_fallback_url` extra in `intent://`),
         * ensuring a safe `http`/`https` scheme and a non-empty host.
         */
        fun isSafeFallbackUrl(rawUrl: String?): Boolean {
            if (rawUrl.isNullOrBlank()) return false
            return try {
                val trimmed = rawUrl.trim()
                val parsed = URI(trimmed)
                isSafeFallbackScheme(parsed.scheme) && !parsed.host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Returns `true` when [scheme] is a custom/external scheme that should be dispatched
         * outside WebView rather than handled as an internal web navigation.
         */
        fun isCustomExternalScheme(scheme: String): Boolean {
            val normalized = scheme.trim().lowercase()
            if (normalized.isEmpty()) return false
            return normalized !in INTERNAL_WEB_SCHEMES
        }

        fun isEssentialCommunicationScheme(scheme: String): Boolean {
            return scheme.trim().lowercase() in ESSENTIAL_COMMUNICATION_SCHEMES
        }

        fun resolveKnownAppName(scheme: String): String? {
            return when (scheme.trim().lowercase()) {
                "tg", "telegram" -> "Telegram"
                "whatsapp" -> "WhatsApp"
                "twitter", "x" -> "X (Twitter)"
                "instagram" -> "Instagram"
                "fb" -> "Facebook"
                "fb-messenger" -> "Messenger"
                "discord" -> "Discord"
                "spotify" -> "Spotify"
                "market" -> "Google Play"
                else -> null
            }
        }

        fun resolveFallbackStorePackage(scheme: String): String? {
            return when (scheme.trim().lowercase()) {
                "tg", "telegram" -> "org.telegram.messenger"
                "whatsapp" -> "com.whatsapp"
                "twitter", "x" -> "com.twitter.android"
                "instagram" -> "com.instagram.android"
                "fb" -> "com.facebook.katana"
                "fb-messenger" -> "com.facebook.orca"
                "discord" -> "com.discord"
                "sgnl", "signal" -> "org.thoughtcrime.securesms"
                "viber" -> "com.viber.voip"
                "skype" -> "com.skype.raider"
                "line" -> "jp.naver.line.android"
                "spotify" -> "com.spotify.music"
                else -> null
            }
        }
    }
}
