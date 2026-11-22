package com.onyx.browser.web.interceptor

import java.net.URI
import java.net.URLDecoder

/**
 * Stateless, unit-testable URL sanitizer and domain classifier for privacy,
 * tracking parameter removal, AMP canonicalization, and OAuth/CAPTCHA bypass detection.
 */
object PrivacyUrlSanitizer {

    // Global tracking query parameters (synced with Brave's query-filter.json)
    val TRACKING_PARAMS: Set<String> = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_id", "utm_source_platform", "utm_creative_format", "utm_marketing_tactic",
        "__hsfp", "__hssc", "__hstc", "__s",
        "_bhlid", "_branch_match_id", "_branch_referrer", "_gl", "_hsenc", "_openstat",
        "at_recipient_id", "at_recipient_list", "bbeml", "bsft_clkid", "bsft_uid",
        "dclid", "epik", "et_rid",
        "fb_action_ids", "fb_comment_id", "fbclid",
        "gbraid", "gclid", "gclsrc", "guce_referrer", "guce_referrer_sig",
        "hsctatracking", "igshid", "irclickid", "li_fat_id",
        "mc_cid", "mc_eid", "mkt_tok", "ml_subscriber", "ml_subscriber_hash", "msclkid", "mtm_cid",
        "oft_c", "oft_ck", "oft_d", "oft_id", "oft_ids", "oft_k", "oft_lk", "oft_sk",
        "oly_anon_id", "oly_enc_id", "pk_cid", "rb_clickid",
        "s_cid", "sc_customer", "sc_eh", "sc_uid", "sfmc_activityid", "sfmc_id",
        "sms_click", "sms_source", "sms_uph", "srsltid", "ss_email_id", "syclid",
        "ttclid", "twclid", "unicorn_click_id",
        "vero_conv", "vero_id", "vgo_ee", "wbraid", "wickedid",
        "yclid", "ymclid", "ysclid"
    )

    private val FACEBOOK_TRACKING_PARAMS: Set<String> = setOf(
        "__cft__[0]", "__tn__", "acontext", "external_ref", "gd_impression_id",
        "idorvanity", "mibextid", "rdid", "store_visit_source", "with_pv"
    )

    private val INSTAGRAM_TRACKING_PARAMS: Set<String> = setOf(
        "igsh", "igshid", "stkn"
    )

    private val TWITTER_TRACKING_PARAMS: Set<String> = setOf(
        "ref_src", "ref_url", "s", "t"
    )

    private val YOUTUBE_TRACKING_PARAMS: Set<String> = setOf(
        "si", "pp"
    )

    private val REDDIT_TRACKING_PARAMS: Set<String> = setOf(
        "utm_name", "rdt", "share_id"
    )

    private val TIKTOK_TRACKING_PARAMS: Set<String> = setOf(
        "_t", "_r", "checksum", "sec_user_id", "share_app_id", "share_author_id", "share_link_id"
    )

    // Bounce / affiliate hosts from Brave's debounce.json mapped to their destination query parameter(s)
    private val DEBOUNCE_HOST_SUFFIX_PARAMS: List<Pair<String, List<String>>> = listOf(
        "go.redirectingat.com" to listOf("url"),
        "go.skimresources.com" to listOf("url"),
        "shop-links.co" to listOf("url"),
        "howl.me" to listOf("url"),
        "anrdoezrs.net" to listOf("url"),
        "dpbolvw.net" to listOf("url"),
        "tkqlhce.com" to listOf("url"),
        "jdoqocy.com" to listOf("url"),
        "kqzyfj.com" to listOf("url"),
        "pntrac.com" to listOf("url"),
        "pjtra.com" to listOf("url"),
        "pjatr.com" to listOf("url"),
        "pntrs.com" to listOf("url"),
        "gopjn.com" to listOf("url"),
        "avantlink.com" to listOf("url"),
        "pepperjamnetwork.com" to listOf("url"),
        "tradedoubler.com" to listOf("url", "_td_deeplink"),
        "effiliation.com" to listOf("url"),
        "flexlinks.com" to listOf("url"),
        "digidip.net" to listOf("url"),
        "narrativ.com" to listOf("url"),
        "mailchimp.com" to listOf("url"),
        "pvn.mediamarkt.de" to listOf("url"),
        "pvn.saturn.de" to listOf("url"),
        "prsm1.com" to listOf("url"),
        "cc.zdnet.com" to listOf("url"),
        "cc.cnet.com" to listOf("url"),
        "cc.mashable.com" to listOf("url"),
        "cc.techbargains.com" to listOf("url"),
        "c212.net" to listOf("u"),
        "dttq.net" to listOf("u"),
        "awin1.com" to listOf("ued", "p"),
        "click.linksynergy.com" to listOf("murl"),
        "target.georiot.com" to listOf("GR_URL"),
        "buy.geni.us" to listOf("GR_URL"),
        "page.link" to listOf("ofl"),
        "rd.bizrate.com" to listOf("t"),
        "rd.connexity.net" to listOf("t"),
        "googleadservices.com" to listOf("adurl"),
        "shopping.yahoo.com" to listOf("gcReferrer"),
        "app.adjust.com" to listOf("redirect"),
        "t.cfjump.com" to listOf("Url"),
        "r.bttn.io" to listOf("btn_url"),
        "trk.pmsrv.co" to listOf("rurl"),
        "trk.pm-srv.co" to listOf("rurl")
    )

    private val AMP_QUERY_KEYS: Set<String> = setOf(
        "amp", "_amp", "amp_js_v", "amp_gsa", "usqp"
    )

    private fun isDomainTrackingParam(host: String, lowerKey: String): Boolean {
        if (host.isEmpty()) return false
        return when {
            host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.com" ->
                FACEBOOK_TRACKING_PARAMS.contains(lowerKey)
            host == "instagram.com" || host.endsWith(".instagram.com") ->
                INSTAGRAM_TRACKING_PARAMS.contains(lowerKey)
            host == "twitter.com" || host.endsWith(".twitter.com") || host == "x.com" || host.endsWith(".x.com") ->
                TWITTER_TRACKING_PARAMS.contains(lowerKey)
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" ->
                YOUTUBE_TRACKING_PARAMS.contains(lowerKey)
            host == "reddit.com" || host.endsWith(".reddit.com") ->
                REDDIT_TRACKING_PARAMS.contains(lowerKey)
            host == "tiktok.com" || host.endsWith(".tiktok.com") ->
                TIKTOK_TRACKING_PARAMS.contains(lowerKey)
            else -> false
        }
    }

    /**
     * Unwraps known outbound click-tracking / affiliate bounce redirect URLs
     * (Brave Debounce parity + social/search outbound link shims), and strips tracking parameters.
     */
    fun unwrapTrackingRedirect(url: String, maxDepth: Int = 3): String {
        var current = url
        var depth = 0
        while (depth < maxDepth) {
            val next = unwrapSingleBounce(current) ?: break
            if (next == current) break
            current = next
            depth++
        }
        return stripTrackingParams(current)
    }

    private fun unwrapSingleBounce(url: String): String? {
        if (url.isBlank()) return null
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: return null
            val path = uri.rawPath ?: ""

            // 1. Path-encoded redirect trackers from Brave's debounce.json
            // click.redditmail.com/CL0/<encoded_url>/... or *.awstrack.me/L0/<encoded_url>/...
            if ((host == "click.redditmail.com" && path.startsWith("/CL0/")) ||
                ((host == "awstrack.me" || host.endsWith(".awstrack.me")) && path.startsWith("/L0/"))) {
                val prefix = if (path.startsWith("/CL0/")) "/CL0/" else "/L0/"
                val encodedSegment = path.removePrefix(prefix).substringBefore('/')
                extractSafeHttpUrl(encodedSegment)?.let { return it }
            }

            // click.pstmrk.it/2s/<target>/... or /3s/<target>/...
            if (host == "click.pstmrk.it" && path.length > 4) {
                val mHttps = Regex("^/[23]t?sm?/([^/]+).*$").find(path)
                if (mHttps != null) {
                    val decoded = try { URLDecoder.decode(mHttps.groupValues[1], "UTF-8") } catch (_: Exception) { mHttps.groupValues[1] }
                    extractSafeHttpUrl("https://$decoded")?.let { return it }
                }
            }

            // 2. Social & Search Outbound Link Shims
            val targetParamNames: List<String>? = when {
                (host == "l.facebook.com" || host == "lm.facebook.com" || host == "l.messenger.com") &&
                    (path == "/l.php" || path.startsWith("/l/")) -> listOf("u")
                host == "l.instagram.com" -> listOf("u")
                (host.startsWith("www.google.") || host.startsWith("google.")) && path == "/url" ->
                    listOf("q", "url")
                (host == "www.youtube.com" || host == "m.youtube.com" || host == "youtube.com") && path == "/redirect" ->
                    listOf("q")
                host == "out.reddit.com" -> listOf("url")
                host == "t.umblr.com" && path.startsWith("/redirect") -> listOf("z")
                host == "steamcommunity.com" && path.startsWith("/linkfilter") -> listOf("url", "u")
                host == "disq.us" && path.startsWith("/url") -> listOf("url")
                else -> DEBOUNCE_HOST_SUFFIX_PARAMS.firstOrNull { (suffix, _) ->
                    host == suffix || host.endsWith(".$suffix")
                }?.second
            }

            if (targetParamNames != null) {
                val rawQuery = uri.rawQuery ?: return null
                for (paramName in targetParamNames) {
                    val rawVal = extractQueryParam(rawQuery, paramName) ?: continue
                    val candidate = if (host == "disq.us" && rawVal.contains(':')) {
                        // disq.us appends :<signature> after the URL
                        val lastColon = rawVal.lastIndexOf(':')
                        val prefixUrl = if (lastColon > 8) rawVal.substring(0, lastColon) else rawVal
                        prefixUrl
                    } else {
                        rawVal
                    }
                    extractSafeHttpUrl(candidate)?.let { return it }
                }
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractQueryParam(rawQuery: String, targetKey: String): String? {
        for (part in rawQuery.split('&')) {
            if (part.isBlank()) continue
            val eqIdx = part.indexOf('=')
            if (eqIdx <= 0) continue
            val key = part.substring(0, eqIdx)
            if (key.equals(targetKey, ignoreCase = true)) {
                val rawVal = part.substring(eqIdx + 1)
                return try {
                    URLDecoder.decode(rawVal.replace("+", "%2B"), "UTF-8")
                } catch (_: Exception) {
                    rawVal
                }
            }
        }
        return null
    }

    private fun extractSafeHttpUrl(rawCandidate: String): String? {
        var candidate = rawCandidate.trim()
        if (candidate.isBlank()) return null
        if (candidate.startsWith("http%3A", ignoreCase = true) ||
            candidate.startsWith("https%3A", ignoreCase = true)) {
            candidate = try {
                URLDecoder.decode(candidate.replace("+", "%2B"), "UTF-8").trim()
            } catch (_: Exception) {
                candidate
            }
        }
        if (!candidate.startsWith("http://", ignoreCase = true) &&
            !candidate.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return try {
            val parsed = URI(candidate)
            val scheme = parsed.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !parsed.host.isNullOrBlank()) {
                candidate
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Strip known tracking query parameters from a URL (global + domain-specific).
     * Returns the cleaned URL, or the original if no parameters were stripped.
     */
    fun stripTrackingParams(url: String): String {
        if (url.isBlank() || !url.contains('?')) return url
        return try {
            val host = try { URI(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
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
                val lowerKey = decodedKey.lowercase()
                if (TRACKING_PARAMS.contains(lowerKey) ||
                    lowerKey.startsWith("utm_") ||
                    isDomainTrackingParam(host, lowerKey)) {
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
     * Detect Google/Bing AMP viewer URLs, AMP Project CDN cache URLs (`*.cdn.ampproject.org`),
     * and AMP query parameters, returning the canonical publisher URL.
     * Returns `null` if the URL is not a recognizable AMP URL.
     */
    fun resolveAmpUrl(url: String): String? {
        if (url.isBlank()) return null
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: return null
            val path = uri.rawPath ?: ""

            // 1. Google & Bing AMP viewer paths across all regional TLDs:
            //    https://www.google.*/amp/s/example.com/path -> https://example.com/path
            //    https://www.google.*/amp/example.com/path   -> http://example.com/path
            val isGoogleOrBingHost = host == "google.com" || host.startsWith("www.google.") ||
                    host.startsWith("google.") || host == "bing.com" || host == "www.bing.com"
            if (isGoogleOrBingHost) {
                if (path.startsWith("/amp/s/") && path.length > 7) {
                    val remainder = path.removePrefix("/amp/s/")
                    val querySuffix = stripAmpCacheQuery(uri.rawQuery)
                    val fragmentSuffix = uri.rawFragment?.takeIf { it.isNotEmpty() }?.let { "#$it" } ?: ""
                    return extractSafeHttpUrl("https://$remainder$querySuffix$fragmentSuffix")
                }
                if (path.startsWith("/amp/") && path.length > 5) {
                    val remainder = path.removePrefix("/amp/")
                    val querySuffix = stripAmpCacheQuery(uri.rawQuery)
                    val fragmentSuffix = uri.rawFragment?.takeIf { it.isNotEmpty() }?.let { "#$it" } ?: ""
                    return extractSafeHttpUrl("http://$remainder$querySuffix$fragmentSuffix")
                }
            }

            // 2. Google AMP Project CDN cache:
            //    https://*-com.cdn.ampproject.org/v/s/example.com/path -> https://example.com/path
            //    https://*-com.cdn.ampproject.org/c/s/example.com/path -> https://example.com/path
            if (host == "cdn.ampproject.org" || host.endsWith(".cdn.ampproject.org")) {
                val httpsMatch = Regex("^/(?:v|c|wp)/s/(.+)$").find(path)
                if (httpsMatch != null) {
                    val remainder = httpsMatch.groupValues[1]
                    val querySuffix = stripAmpCacheQuery(uri.rawQuery)
                    val fragmentSuffix = uri.rawFragment?.takeIf { it.isNotEmpty() }?.let { "#$it" } ?: ""
                    return extractSafeHttpUrl("https://$remainder$querySuffix$fragmentSuffix")
                }
                val httpMatch = Regex("^/(?:v|c|wp)/(.+)$").find(path)
                if (httpMatch != null) {
                    val remainder = httpMatch.groupValues[1]
                    val querySuffix = stripAmpCacheQuery(uri.rawQuery)
                    val fragmentSuffix = uri.rawFragment?.takeIf { it.isNotEmpty() }?.let { "#$it" } ?: ""
                    return extractSafeHttpUrl("http://$remainder$querySuffix$fragmentSuffix")
                }
            }

            // 3. AMP query parameters (?amp=1, ?amp=true, ?amp, ?_amp=true, ?outputType=amp, ?output=amp)
            val hashIndex = url.indexOf('#')
            val fragment = if (hashIndex != -1) url.substring(hashIndex) else ""
            val withoutFragment = if (hashIndex != -1) url.substring(0, hashIndex) else url
            val queryIndex = withoutFragment.indexOf('?')
            if (queryIndex != -1) {
                val base = withoutFragment.substring(0, queryIndex)
                val rawQuery = withoutFragment.substring(queryIndex + 1)
                val parts = rawQuery.split('&').filter { it.isNotBlank() }
                val hasAmpParam = parts.any { part -> isAmpQueryParameter(part) }
                if (hasAmpParam) {
                    val remaining = parts.filterNot { part -> isAmpQueryParameter(part) }
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

    private fun isAmpQueryParameter(part: String): Boolean {
        val k = part.substringBefore('=').lowercase()
        val hasEq = part.contains('=')
        val v = part.substringAfter('=', "").lowercase()
        return when {
            (k == "amp" || k == "_amp") && (!hasEq || v.isEmpty() || v == "1" || v == "true") -> true
            (k == "outputtype" || k == "output") && v == "amp" -> true
            AMP_QUERY_KEYS.contains(k) && (k == "amp_js_v" || k == "amp_gsa") -> true
            else -> false
        }
    }

    private fun stripAmpCacheQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrBlank()) return ""
        val remaining = rawQuery.split('&').filter { part ->
            part.isNotBlank() && !AMP_QUERY_KEYS.contains(part.substringBefore('=').lowercase())
        }
        return if (remaining.isEmpty()) "" else "?${remaining.joinToString("&")}"
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
                d == "oauth2.googleapis.com" || d == "id.google.com" ||
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
