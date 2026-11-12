package com.onyx.browser.web.interceptor

import java.net.URI
import java.net.URLDecoder

/**
 * Stateless, unit-testable URL sanitizer and domain classifier for privacy,
 * tracking parameter removal, AMP canonicalization, and OAuth/CAPTCHA bypass detection.
 */
object PrivacyUrlSanitizer {

    val TRACKING_PARAMS: Set<String> = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_id", "utm_source_platform", "utm_creative_format", "utm_marketing_tactic",
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid",
        "mc_eid", "mc_cid", "oly_enc_id", "oly_anon_id",
        "_openstat", "vero_id", "mkt_tok",
        "twclid", "msclkid", "ttclid", "li_fat_id",
        "igshid", "s_cid", "srsltid", "epik"
    )

    /**
     * Strip known tracking query parameters from a URL.
     * Returns the cleaned URL, or the original if no parameters were stripped.
     */
    fun stripTrackingParams(url: String): String {
        if (url.isBlank() || !url.contains('?')) return url
        return try {
            val hashIndex = url.indexOf('#')
            val fragment = if (hashIndex != -1) url.substring(hashIndex) else ""
            val withoutFragment = if (hashIndex != -1) url.substring(0, hashIndex) else url

            val queryIndex = withoutFragment.indexOf('?')
            if (queryIndex == -1) return url

            val base = withoutFragment.substring(0, queryIndex)
            val rawQuery = withoutFragment.substring(queryIndex + 1)
            if (rawQuery.isBlank()) return url

            val parts = rawQuery.split('&')
            var strippedAny = false
            val cleanParts = ArrayList<String>(parts.size)

            for (part in parts) {
                if (part.isBlank()) continue
                val rawKey = part.substringBefore('=')
                val decodedKey = try {
                    URLDecoder.decode(rawKey.replace("+", "%2B"), "UTF-8")
                } catch (_: Exception) {
                    rawKey
                }
                if (TRACKING_PARAMS.contains(decodedKey) || TRACKING_PARAMS.contains(decodedKey.lowercase())) {
                    strippedAny = true
                } else {
                    cleanParts.add(part)
                }
            }

            if (!strippedAny) return url

            if (cleanParts.isEmpty()) {
                "$base$fragment"
            } else {
                "$base?${cleanParts.joinToString("&")}$fragment"
            }
        } catch (_: Exception) {
            url
        }
    }

    /**
     * Detect Google AMP URLs and return the canonical URL.
     * Returns `null` if the URL is not an AMP URL.
     */
    fun resolveAmpUrl(url: String): String? {
        if (url.isBlank()) return null
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: return null
            val path = uri.rawPath ?: ""

            // Google AMP cache: https://www.google.com/amp/s/example.com/path
            if ((host == "www.google.com" || host == "google.com") && path.startsWith("/amp/s/")) {
                val remainder = path.removePrefix("/amp/s/")
                val querySuffix = uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""
                val fragmentSuffix = uri.rawFragment?.takeIf { it.isNotEmpty() }?.let { "#$it" } ?: ""
                return "https://$remainder$querySuffix$fragmentSuffix"
            }

            // ?amp=1 query param (clean AMP query parameter)
            val hashIndex = url.indexOf('#')
            val fragment = if (hashIndex != -1) url.substring(hashIndex) else ""
            val withoutFragment = if (hashIndex != -1) url.substring(0, hashIndex) else url
            val queryIndex = withoutFragment.indexOf('?')
            if (queryIndex != -1) {
                val base = withoutFragment.substring(0, queryIndex)
                val rawQuery = withoutFragment.substring(queryIndex + 1)
                val parts = rawQuery.split('&').filter { it.isNotBlank() }
                val hasAmpOne = parts.any { part ->
                    val k = part.substringBefore('=')
                    val v = part.substringAfter('=', "")
                    k.equals("amp", ignoreCase = true) && v == "1"
                }
                if (hasAmpOne) {
                    val remaining = parts.filterNot { part ->
                        part.substringBefore('=').equals("amp", ignoreCase = true)
                    }
                    return if (remaining.isEmpty()) {
                        "$base$fragment"
                    } else {
                        "$base?${remaining.joinToString("&")}$fragment"
                    }
                }
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    fun isMetaDomain(domain: String): Boolean {
        if (domain.isBlank()) return false
        val d = domain.lowercase()
        return d == "facebook.com" || d.endsWith(".facebook.com") ||
                d == "meta.com" || d.endsWith(".meta.com") ||
                d == "fb.com" || d.endsWith(".fb.com") ||
                d == "fb.me" || d.endsWith(".fb.me") ||
                d == "fbcdn.net" || d.endsWith(".fbcdn.net") ||
                d == "facebook.net" || d.endsWith(".facebook.net") ||
                d == "fbsbx.com" || d.endsWith(".fbsbx.com") ||
                d == "messenger.com" || d.endsWith(".messenger.com") ||
                d == "instagram.com" || d.endsWith(".instagram.com") ||
                d == "cdninstagram.com" || d.endsWith(".cdninstagram.com") ||
                d == "threads.net" || d.endsWith(".threads.net") ||
                d == "accountkit.com" || d.endsWith(".accountkit.com") ||
                d == "workplace.com" || d.endsWith(".workplace.com")
    }

    fun isCaptchaOrAuthUrl(url: String, domain: String): Boolean {
        val d = domain.lowercase()
        val u = url.lowercase()
        return d.contains("recaptcha") || d.contains("hcaptcha") ||
                d.contains("arkose") || d.contains("arkoselabs") || d.contains("funcaptcha") ||
                d.contains("matchkey") || d.contains("turnstile") || d.contains("geetest") || d.contains("datadome") ||
                d.contains("kasada") || d.contains("perimeterx") ||
                d == "fbsbx.com" || d.endsWith(".fbsbx.com") ||
                d == "challenges.cloudflare.com" || d.endsWith(".challenges.cloudflare.com") ||
                u.contains("recaptcha") || u.contains("hcaptcha") || u.contains("arkose") ||
                u.contains("funcaptcha") || u.contains("matchkey") || u.contains("turnstile") ||
                u.contains("/captcha/arkose/") || u.contains("meta-api.arkoselabs.com") ||
                u.contains("client-api.arkoselabs.com") ||
                u.contains("/cdn-cgi/challenge-platform") || u.contains("/cdn-cgi/cf-challenge") ||
                u.contains("__cf_chl") ||
                u.contains("/checkpoint/") || u.contains("/challenge/") ||
                u.contains("/captcha/") || u.contains("/security-check") ||
                u.contains("/waf/") || u.contains("/bot-detection") ||
                u.contains("/human-verification") || u.contains("login/device-based") ||
                u.contains("/two_step_verification") || u.contains("/save-device/") ||
                u.contains("/trusted-devices/") || u.contains("/login_attempt") ||
                u.contains("/fc/api") || u.contains("/fc/assets") || u.contains("/client-api/") ||
                u.contains("/checkpoint") || u.contains("/confirm") ||
                u.contains("lsd=") || u.contains("jazoest=") || u.contains("datr=")
    }

    fun isOAuthOrLoginProvider(domain: String): Boolean {
        if (domain.isBlank()) return false
        val d = domain.lowercase()
        return isMetaDomain(d) ||
                d.contains("arkose") || d.contains("funcaptcha") ||
                // Google identity and auth
                d == "accounts.google.com" || d.endsWith(".accounts.google.com") ||
                d == "oauth2.googleapis.com" || d == "apis.google.com" ||
                d == "ssl.gstatic.com" || d == "www.gstatic.com" ||
                d == "id.google.com" ||
                // Apple Sign-In
                d == "appleid.apple.com" || (d.endsWith(".apple.com") && (d.contains("auth") || d.contains("appleid"))) ||
                d == "idmsa.apple.com" || d == "signin.apple.com" ||
                // Microsoft / Azure AD / MSAL
                d == "login.microsoftonline.com" || d == "login.microsoft.com" ||
                d == "login.live.com" || d == "account.microsoft.com" ||
                d == "aadcdn.msftauth.net" || d == "aadcdn.msauth.net" ||
                d == "msauth.net" || d.endsWith(".msauth.net") ||
                // GitHub OAuth and asset domains
                d == "github.com" || d.endsWith(".github.com") || d == "api.github.com" ||
                d == "githubassets.com" || d.endsWith(".githubassets.com") ||
                d == "githubusercontent.com" || d.endsWith(".githubusercontent.com") ||
                // Twitter/X OAuth
                d == "api.twitter.com" || d == "api.x.com" || d == "twitter.com" || d == "x.com" ||
                // TikTok OAuth
                d == "open-api.tiktok.com" || (d.endsWith(".tiktok.com") && d.contains("auth")) ||
                // Spotify OAuth
                d == "accounts.spotify.com" ||
                // Yahoo OAuth
                d == "login.yahoo.com" ||
                // Slack OAuth
                d == "slack.com" || d.endsWith(".slack.com") ||
                // PayPal payment flows
                d == "paypal.com" || d == "www.paypal.com" || d.endsWith(".paypal.com") ||
                d == "paypalobjects.com" || d.endsWith(".paypalobjects.com") ||
                // Stripe payment flows
                d == "stripe.com" || d.endsWith(".stripe.com") ||
                d == "js.stripe.com" || d == "m.stripe.network" || d.endsWith(".stripe.network") ||
                // Auth0
                d.endsWith(".auth0.com") || d == "auth0.com" ||
                d.endsWith(".us.auth0.com") || d.endsWith(".eu.auth0.com") ||
                // Okta
                d.endsWith(".okta.com") || d == "okta.com" ||
                d.endsWith(".oktapreview.com") ||
                // OneLogin & PingIdentity
                d.endsWith(".onelogin.com") || d.endsWith(".pingidentity.com") ||
                // Keycloak / generic auth subdomains
                d.startsWith("auth.") || d.startsWith("login.") || d.startsWith("sso.") ||
                d.startsWith("id.") || d.startsWith("identity.") || d.startsWith("account.") ||
                d.startsWith("accounts.") || d.startsWith("signin.") || d.startsWith("oauth.") ||
                // Firebase Auth
                d.endsWith(".firebaseapp.com") || d == "securetoken.googleapis.com" ||
                // Amazon / AWS Cognito
                d.endsWith(".amazoncognito.com") || d == "cognito-identity.amazonaws.com" ||
                d == "amazon.com" || d == "www.amazon.com" ||
                // LinkedIn OAuth
                d == "linkedin.com" || d == "www.linkedin.com" ||
                // Twitch OAuth
                d == "id.twitch.tv" ||
                // Discord OAuth
                d == "discord.com" || d == "discordapp.com" ||
                // Cloudflare Access
                d.endsWith(".cloudflareaccess.com") ||
                // Generic OAuth indicators
                d.contains("oauth") || d.contains("openid") || d.contains("saml")
    }

    fun isOAuthOrLoginUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val u = url.lowercase()
        return u.contains("/oauth") || u.contains("/oauth2") ||
                u.contains("/openid-connect") || u.contains("/saml") ||
                u.contains("/signin") || u.contains("/login") ||
                u.contains("/auth/") || u.contains("/authenticate") ||
                u.contains("/sso/") || u.contains("/checkpoint/") ||
                u.contains("/challenge/") ||
                u.contains("code=") || u.contains("access_token=") ||
                u.contains("id_token=") || u.contains("oauth_token=") ||
                (u.contains("state=") && (u.contains("auth") || u.contains("login") || u.contains("oauth")))
    }

    fun isMediaUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return clean.endsWith(".m3u8") || clean.endsWith(".mpd") || clean.endsWith(".ts") ||
                clean.endsWith(".m2ts") || clean.endsWith(".m4s") || clean.endsWith(".cmfv") ||
                clean.endsWith(".cmfa") || clean.endsWith(".fmp4") || clean.endsWith(".mp4") ||
                clean.endsWith(".m4v") || clean.endsWith(".webm") || clean.endsWith(".mkv") ||
                clean.endsWith(".ogv") || clean.endsWith(".ogg") || clean.endsWith(".flv") ||
                clean.endsWith(".mp3") || clean.endsWith(".aac") || clean.endsWith(".m4a") ||
                clean.endsWith(".opus") || clean.endsWith(".flac") || clean.endsWith(".wav")
    }

    fun isThirdPartyDomain(requestHost: String, pageHost: String): Boolean {
        if (requestHost.isBlank() || pageHost.isBlank()) return false
        val req = requestHost.lowercase()
        val page = pageHost.lowercase()
        return req != page && !req.endsWith(".$page") && !page.endsWith(".$req")
    }
}
