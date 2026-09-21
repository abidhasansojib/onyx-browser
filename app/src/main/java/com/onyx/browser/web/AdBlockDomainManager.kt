package com.onyx.browser.web

/**
 * Curated domain lists for Standard and Aggressive Ad & Tracker blocking.
 *
 * - Standard Mode: Blocks recognized advertising networks and analytics trackers (~52%-55% on superadblocktest.com).
 * - Aggressive Mode: Blocks all advertising, trackers, OEM telemetry (Xiaomi, Huawei, Samsung, Oppo,
 *   Realme, Apple, LG, Roku, FireTV, Windows), consent managers/CMPs (OneTrust, Cookiebot, TrustArc),
 *   affiliate networks, A/B testing platforms, email marketing, video ads, and cryptominers (~95%-100%).
 */
object AdBlockDomainManager {

    /**
     * Standard advertising & core analytics tracker domains.
     */
    val standardDomains: Set<String> = setOf(
        "doubleclick.net", "googlesyndication.com", "googletagmanager.com",
        "googletagservices.com", "googleadservices.com", "google-analytics.com",
        "analytics.google.com", "stats.g.doubleclick.net", "pagead2.googlesyndication.com",
        "adservice.google.com", "facebook.net", "connect.facebook.net",
        "tr.snapchat.com", "analytics.twitter.com", "t.co", "ads.twitter.com",
        "ads-twitter.com", "scorecardresearch.com", "quantserve.com", "quantcast.com",
        "adsrvr.org", "casalemedia.com", "openx.net", "pubmatic.com", "adnxs.com",
        "rubiconproject.com", "criteo.com", "criteo.net", "amazon-adsystem.com",
        "ads.linkedin.com", "bing.com/bat", "bat.bing.com", "adroll.com",
        "sentry.io", "bugsnag.com", "newrelic.com", "nr-data.net", "hotjar.com", "clarity.ms",
        "mixpanel.com", "amplitude.com", "appsflyer.com", "branch.io", "segment.com", "segment.io",
        "mc.yandex.ru", "statcounter.com", "outbrain.com", "taboola.com",
        "bluekai.com", "demdex.net", "optimizely.com", "crazyegg.com", "mouseflow.com",
        "fullstory.com", "chartboost.com", "applovin.com", "vungle.com", "liftoff.io",
        "inmobi.com", "ironsource.mobi", "unityads.unity3d.com", "adcolony.com",
        "mgid.com", "propellerads.com", "propellerclick.com", "onclickads.net",
        "media.net", "adservetx.media.net", "spotxchange.com", "indexexchange.com",
        "htlbid.com", "fls-na.amazon.com", "advertising.com", "bidswitch.net",
        "moatads.com", "smartadserver.com", "adsafeprotected.com", "doubleverify.com",
        "connatix.com", "innovid.com", "tremorhub.com", "crwdcntrl.net", "fwmrm.net",
        "jwpltx.com", "jwpsrv.com", "rlcdn.com", "impactradius-event.com", "shareasale.com",
        "awin1.com", "partnerstack.com", "refersion.com", "fingerprintjs.com", "fpjs.io",
        "adlog.vivo.com", "ads-api.vivo.com", "click.oneplus.cn", "open.oneplus.net",
        "a.lenovo.com", "ad.mail.ru", "top-fwz1.mail.ru", "ads.vk.com", "pangleglobal.com",
        "luckyorange.com", "luckyorange.net", "freshmarketer.com", "heapanalytics.com",
        "stats.wp.com", "driftt.com", "intercom.io", "wzrkt.com", "zenaps.com",
        "statdynamic.com", "datadoghq.com", "omtrdc.net", "stickyadstv.com", "3lift.com",
        "sonobi.com", "gumgum.com", "teads.tv", "kargo.com", "metrics.adobe.com",
        "lr-ingest.com", "brightcove.com"
    )

    /**
     * Dedicated root domains blocked only in Aggressive mode:
     * - OEM Vendors (Samsung, Xiaomi, Oppo, Realme, LG, Roku, Vizio, etc.)
     * - Consent Management / CMPs (OneTrust, Cookiebot, TrustArc, Usercentrics, Osano, etc.)
     * - Affiliate & redirect networks (CJ, LinkShare, Impact, Skimlinks, VigLink, etc.)
     * - Product Analytics & Experimentation (Cloudflare Insights, PostHog, LaunchDarkly, RudderStack, Snowplow)
     * - Email & Marketing Trackers (HubSpot, Marketo, Mailchimp, Braze, OneSignal, Klaviyo, Customer.io)
     * - Risky / Cryptomining hosts (CoinImp, PopAds, Exoclick, JuicyAds, etc.)
     */
    val aggressiveRootDomains: Set<String> = setOf(
        "2o7.net", "ad.gt", "adjust.com", "adobe.io", "ads-twitter.com", "adsrvr.org",
        "anrdoezrs.net", "appspot.com", "bluekai.com", "bnc.lt", "braze.com", "brightcove.com",
        "browser-intake-datadoghq.com", "byteoversea.com", "clickadu.com", "cloudflareinsights.com",
        "coinimp.com", "consensu.org", "contextweb.com", "cookiebot.com", "cookielaw.org",
        "customer.io", "dpbolvw.net", "dynamicyield.com", "everesttech.net", "exoclick.com",
        "facebook.net", "fyber.com", "getsentry.com", "googleanalytics.com", "googlevideo.com",
        "hotjar.io", "hubspot.com", "icloud.com", "id5-sync.com", "indexexchange.com",
        "insightexpressai.com", "juicyads.com", "jwpcdn.com", "jwpsrv.com", "klaviyo.com",
        "kochava.com", "launchdarkly.com", "lgappstv.com", "lge.com", "lgsmartad.com",
        "linkedin.com", "linksynergy.com", "list-manage.com", "mailchimp.com", "marketo.net",
        "mathtag.com", "mineralt.io", "minero.cc", "monerominer.rocks", "mzstatic.com",
        "onesignal.com", "onetag-sys.com", "onetrust.com", "oppomobile.com", "optimizely.com",
        "osano.com", "pepperjamnetwork.com", "permutive.com", "pippio.com", "popads.net",
        "popcash.net", "popmyads.com", "posthog.com", "prf.hn", "privacy-center.org",
        "privacy-mgmt.com", "realmemobile.com", "redditmedia.com", "redirectingat.com",
        "roku.com", "rudderlabs.com", "rudderstack.com", "samsungads.com", "samsunghealthcn.com",
        "sc-static.net", "sentry-cdn.com", "sharethrough.com", "siftscience.com", "singular.net",
        "skimresources.com", "smartclip.com", "smartclip.net", "smartyads.com", "snapchat.com",
        "snowplowanalytics.com", "stackadapt.com", "supersonicads.com", "tiktokv.com",
        "tkqlhce.com", "trafficjunky.net", "trustarc.com", "tvinteractive.tv", "tvpixel.com",
        "uidapi.com", "usercentrics.eu", "viglink.com", "vizio.com", "webminepool.com",
        "yumenetworks.com"
    )

    /**
     * Specific subdomains on shared multi-tenant hosts (Amazon, Apple, Google, Microsoft, Yahoo, etc.)
     * blocked in Aggressive mode without breaking the primary services.
     */
    val aggressiveSubdomains: Set<String> = setOf(
        "aan.amazon.com", "ads-api.tiktok.com", "ads-api.x.com", "ads-sg.tiktok.com",
        "ads.huawei.com", "ads.microsoft.com", "ads.pinterest.com", "ads.tiktok.com",
        "ads.x.com", "ads.yahoo.com", "ads.youtube.com", "adservice.google.com",
        "adtago.s3.amazonaws.com", "adtech.yahooinc.com", "advertising-api-eu.amazon.com",
        "advertising.apple.com", "advertising.yahoo.com", "advertising.yandex.ru",
        "advice-ads.s3.amazonaws.com", "analytics-sg.tiktok.com", "analytics.google.com",
        "analytics.pinterest.com", "analytics.query.yahoo.com", "analytics.x.com",
        "analytics.yahoo.com", "analyticsengine.s3.amazonaws.com", "api-adservices.apple.com",
        "api.ad.xiaomi.com", "bingads.microsoft.com", "books-analytics-events.apple.com",
        "browser.events.data.msn.com", "business-api.tiktok.com", "c.bing.com",
        "dai.google.com", "data.mistat.india.xiaomi.com", "data.mistat.rus.xiaomi.com",
        "data.mistat.xiaomi.com", "device-metrics-us-2.amazon.com", "device-metrics-us.amazon.com",
        "extmaps-api.yandex.net", "firebase-settings.crashlytics.com",
        "fundingchoicesmessages.google.com", "gemini.yahoo.com", "geo.yahoo.com",
        "globalapi.ad.xiaomi.com", "graph.facebook.com", "graph.instagram.com",
        "grs.hicloud.com", "i.instagram.com", "iadsdk.apple.com", "iot-eu-logser.realme.com",
        "iot-logser.realme.com", "log.fc.yahoo.com", "log.pinterest.com", "logbak.hicloud.com",
        "logservice.hicloud.com", "logservice1.hicloud.com", "mads-eu.amazon.com",
        "metrics.apple.com", "metrics.data.hicloud.com", "metrics2.data.hicloud.com",
        "metrika.yandex.ru", "nmetrics.samsung.com", "notes-analytics-events.apple.com",
        "offerwall.yandex.net", "partnerads.ysm.yahoo.com", "pixel.quora.com",
        "qevents.quora.com", "s.youtube.com", "sdkconfig.ad.intl.xiaomi.com",
        "sdkconfig.ad.xiaomi.com", "settings-win.data.microsoft.com", "smetrics.samsung.com",
        "tagmanager.google.com", "telemetry.microsoft.com", "tr.facebook.com",
        "tr.iadsdk.apple.com", "tracking.miui.com", "tracking.rus.miui.com",
        "trk.pinterest.com", "udc.yahoo.com", "udcm.yahoo.com", "vk.com",
        "vortex-win.data.microsoft.com", "vortex.data.microsoft.com",
        "watson.telemetry.microsoft.com", "widgets.pinterest.com", "xp.apple.com"
    )

    fun isBlockedInStandard(domain: String): Boolean {
        val d = domain.lowercase().trim()
        return standardDomains.any { d == it || d.endsWith(".$it") }
    }

    fun isBlockedInAggressive(domain: String): Boolean {
        val d = domain.lowercase().trim()
        if (isBlockedInStandard(d)) return true
        if (aggressiveSubdomains.any { d == it || d.endsWith(".$it") }) return true
        return aggressiveRootDomains.any { d == it || d.endsWith(".$it") }
    }

    fun shouldBlock(domain: String, isAggressive: Boolean): Boolean {
        return if (isAggressive) {
            isBlockedInAggressive(domain)
        } else {
            isBlockedInStandard(domain)
        }
    }
}
