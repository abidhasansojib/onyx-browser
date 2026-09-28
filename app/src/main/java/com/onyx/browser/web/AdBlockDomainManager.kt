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
        "2975c.v.fwmrm.net", "3lift.com", "a.klaviyo.com", "a.lenovo.com",
        "a.teads.tv", "aan.amazon.com", "aax.amazon-adsystem.com", "acdn.adnxs.com",
        "ad.crwdcntrl.net", "ad.doubleclick.net", "ad.gt", "ad.lgappstv.com",
        "ad.linksynergy.com", "ad.mail.ru", "adc3-launch.adcolony.com", "adclick.g.doubleclick.net",
        "adcolony.com", "adfox.yandex.ru", "adfstat.yandex.ru", "adjust.com",
        "adlog.vivo.com", "adm.hotjar.com", "adnxs.com", "adroll.com",
        "ads-api.tiktok.com", "ads-api.twitter.com", "ads-api.vivo.com", "ads-api.x.com",
        "ads-sg.tiktok.com", "ads-twitter.com", "ads.huawei.com", "ads.linkedin.com",
        "ads.microsoft.com", "ads.pinterest.com", "ads.pubmatic.com", "ads.roku.com",
        "ads.snapchat.com", "ads.stickyadstv.com", "ads.tiktok.com", "ads.tremorhub.com",
        "ads.twitter.com", "ads.vizio.com", "ads.vk.com", "ads.x.com",
        "ads.yahoo.com", "ads.youtube.com", "ads30.adcolony.com", "adsafeprotected.com",
        "adserver.unityads.unity3d.com", "adservetx.media.net", "adservice.google.com", "adsfs.oppomobile.com",
        "adsrvr.org", "adtago.s3.amazonaws.com", "adtech.yahooinc.com", "advertising-api-eu.amazon.com",
        "advertising.apple.com", "advertising.com", "advertising.yahoo.com", "advertising.yandex.ru",
        "advice-ads.s3.amazonaws.com", "adx.ads.oppomobile.com", "afs.googlesyndication.com", "alb.reddit.com",
        "amazon-adsystem.com", "amoeba.web.roku.com", "amplitude.com", "an.facebook.com",
        "an.yandex.ru", "analytics-api.samsunghealthcn.com", "analytics-sg.tiktok.com", "analytics.adobe.io",
        "analytics.google.com", "analytics.pinterest.com", "analytics.pointdrive.linkedin.com", "analytics.query.yahoo.com",
        "analytics.s3.amazonaws.com", "analytics.tiktok.com", "analytics.twitter.com", "analytics.x.com",
        "analytics.yahoo.com", "analyticsengine.s3.amazonaws.com", "apex.go.sonobi.com", "api-adservices.apple.com",
        "api-js.mixpanel.com", "api.ad.xiaomi.com", "api.amplitude.com", "api.bugsnag.com",
        "api.fyber.com", "api.luckyorange.com", "api.mixpanel.com", "api.mouseflow.com",
        "api.onesignal.com", "api.optimizely.com", "api.partnerstack.com", "api.rlcdn.com",
        "api.segment.io", "api.taboola.com", "api.viglink.com", "api.vungle.com",
        "api2.amplitude.com", "api2.branch.io", "app.adjust.com", "app.appsflyer.com",
        "app.bugsnag.com", "app.getsentry.com", "app.posthog.com", "app.usercentrics.eu",
        "applovin.com", "appmetrica.yandex.ru", "appsflyer.com", "as.casalemedia.com",
        "auction.unityads.unity3d.com", "awin1.com", "b.scorecardresearch.com", "bam-cell.nr-data.net",
        "bam.nr-data.net", "bat.bing.com", "bcp.crwdcntrl.net", "bdapi-ads.realmemobile.com",
        "bdapi-in-ads.realmemobile.com", "bea4.v.fwmrm.net", "bidder.criteo.com", "bidswitch.net",
        "bing.com/bat", "bingads.microsoft.com", "bluekai.com", "bnc.lt",
        "books-analytics-events.apple.com", "branch.io", "browser-intake-datadoghq.com", "browser.events.data.msn.com",
        "browser.sentry-cdn.com", "bugsnag.com", "business-api.tiktok.com", "c.amazon-adsystem.com",
        "c.bing.com", "c.clarity.ms", "c.gumgum.com", "capi.connatix.com",
        "careers.hotjar.com", "casalemedia.com", "cd.connatix.com", "cdn-test.mouseflow.com",
        "cdn.cookielaw.org", "cdn.doubleverify.com", "cdn.dynamicyield.com", "cdn.heapanalytics.com",
        "cdn.indexexchange.com", "cdn.kargo.com", "cdn.lr-ingest.com", "cdn.luckyorange.com",
        "cdn.mgid.com", "cdn.mouseflow.com", "cdn.onesignal.com", "cdn.optimizely.com",
        "cdn.permutive.com", "cdn.privacy-mgmt.com", "cdn.rudderlabs.com", "cdn.segment.com",
        "cdn.siftscience.com", "cdn.taboola.com", "cdn.teads.tv", "cdn.viglink.com",
        "chartboost.com", "ck.ads.oppomobile.com", "clarity.ms", "claritybt.freshmarketer.com",
        "click.googleanalytics.com", "click.linksynergy.com", "click.mailchimp.com", "click.oneplus.cn",
        "clickadu.com", "clientstream.launchdarkly.com", "cloudflareinsights.com", "cm.g.doubleclick.net",
        "cmp.inmobi.com", "cmp.osano.com", "cmp.usercentrics.eu", "coinimp.com",
        "config.samsungads.com", "config.unityads.unity3d.com", "connatix.com", "connect.facebook.net",
        "consent.cookiebot.com", "consent.trustarc.com", "consentcdn.cookiebot.com", "contextual.media.net",
        "contextweb.com", "control.kochava.com", "cookiebot.com", "crazyegg.com",
        "criteo.com", "criteo.net", "crwdcntrl.net", "cs.luckyorange.net",
        "ct.pinterest.com", "customer.io", "d.adroll.com", "d.applovin.com",
        "d.impactradius-event.com", "d.reddit.com", "d2wy8f7a9ursnm.cloudfront.net", "dai.google.com",
        "data.ads.oppomobile.com", "data.mistat.india.xiaomi.com", "data.mistat.rus.xiaomi.com", "data.mistat.xiaomi.com",
        "datadoghq.com", "dc.ads.linkedin.com", "decide.mixpanel.com", "demdex.net",
        "device-metrics-us-2.amazon.com", "device-metrics-us.amazon.com", "dis.criteo.com", "doubleclick.net",
        "doubleverify.com", "driftt.com", "eb2.3lift.com", "edge.fullstory.com",
        "eu.posthog.com", "events.hotjar.io", "events.launchdarkly.com", "events.reddit.com",
        "events.redditmedia.com", "events.segment.io", "events3alt.adcolony.com", "exoclick.com",
        "extmaps-api.yandex.net", "fastlane.rubiconproject.com", "fingerprintjs.com", "firebase-settings.crashlytics.com",
        "fls-na.amazon.com", "fpjs.io", "freshmarketer.com", "fullstory.com",
        "fundingchoicesmessages.google.com", "fw.adsafeprotected.com", "fwmrm.net", "fwtracks.freshmarketer.com",
        "g.jwpsrv.com", "gemini.yahoo.com", "geo.yahoo.com", "geolocation.onetrust.com",
        "getsentry.com", "globalapi.ad.xiaomi.com", "go.redirectingat.com", "go.skimresources.com",
        "google-analytics.com", "googleads.g.doubleclick.net", "googleads4.g.doubleclick.net", "googleadservices.com",
        "googleanalytics.com", "googlesyndication.com", "googletagmanager.com", "googletagservices.com",
        "graph.facebook.com", "graph.instagram.com", "grs.hicloud.com", "gtm.mouseflow.com",
        "gum.criteo.com", "gumgum.com", "hbopenbid.pubmatic.com", "heapanalytics.com",
        "hotjar.com", "htlb.casalemedia.com", "htlbid.com", "i.instagram.com",
        "iadsdk.apple.com", "ib.adnxs.com", "id5-sync.com", "identify.hotjar.com",
        "idsync.rlcdn.com", "image6.pubmatic.com", "images.taboola.com", "impactradius-event.com",
        "in.hotjar.com", "indexexchange.com", "info.lgsmartad.com", "ingest.sentry.io",
        "init.supersonicads.com", "inmobi.com", "innovid.com", "insightexpressai.com",
        "insights.adsrvr.org", "insights.hotjar.com", "intercom.io", "iot-eu-logser.realme.com",
        "iot-logser.realme.com", "ir-na.amazon-adsystem.com", "ironsource.mobi", "js-agent.newrelic.com",
        "js.driftt.com", "juicyads.com", "jwpltx.com", "kargo.com",
        "kochava.com", "liftoff.io", "list-manage.com", "live.chartboost.com",
        "log.byteoversea.com", "log.fc.yahoo.com", "log.outbrain.com", "log.pinterest.com",
        "logbak.hicloud.com", "logs.roku.com", "logservice.hicloud.com", "logservice1.hicloud.com",
        "logx.optimizely.com", "lr-ingest.com", "luckyorange.com", "luckyorange.net",
        "m.doubleclick.net", "mads-eu.amazon.com", "match.adsrvr.org", "mc.yandex.ru",
        "mcs-va.tiktokv.com", "media.net", "mediavisor.doubleclick.net", "metrics.adobe.com",
        "metrics.apple.com", "metrics.brightcove.com", "metrics.data.hicloud.com", "metrics.icloud.com",
        "metrics.mzstatic.com", "metrics2.data.hicloud.com", "metrika.yandex.ru", "mgid.com",
        "mineralt.io", "minero.cc", "mixpanel.com", "moatads.com",
        "mon.byteoversea.com", "mon.tiktokv.com", "monerominer.rocks", "mouseflow.com",
        "ms.applovin.com", "mssl.fwmrm.net", "munchkin.marketo.net", "newrelic.com",
        "ngfts.lge.com", "nmetrics.samsung.com", "notes-analytics-events.apple.com", "notify.bugsnag.com",
        "nr-data.net", "nr.taboola.com", "o0.ingest.sentry.io", "o2.mouseflow.com",
        "oas.openx.net", "odb.outbrain.com", "offerwall.yandex.net", "omtrdc.net",
        "onclickads.net", "onetag-sys.com", "open.oneplus.net", "openx.net",
        "optimized-by.rubiconproject.com", "optimizely.com", "outbrain.com", "outcome-ssp.supersonicads.com",
        "pagead.l.doubleclick.net", "pagead2.googleadservices.com", "pagead2.googlesyndication.com", "pangleglobal.com",
        "partnerads.ysm.yahoo.com", "partnerstack.com", "pippio.com", "pixel.adsafeprotected.com",
        "pixel.facebook.com", "pixel.mathtag.com", "pixel.quantserve.com", "pixel.quora.com",
        "pixel.redditmedia.com", "pixel.rubiconproject.com", "pointdrive.linkedin.com", "popads.net",
        "popcash.net", "popmyads.com", "prd.jwpltx.com", "prebid.adnxs.com",
        "prf.hn", "privacyportal.onetrust.com", "prod.uidapi.com", "propellerads.com",
        "propellerclick.com", "pubads.g.doubleclick.net", "pubmatic.com", "px.ads.linkedin.com",
        "px.srvcs.tumblr.com", "qevents.quora.com", "quantcast.com", "quantserve.com",
        "r.lr-ingest.com", "rcm-na.amazon-adsystem.com", "realtime.luckyorange.com", "redirect.viglink.com",
        "redirector.googlevideo.com", "redirector.skimresources.com", "refersion.com", "region1.google-analytics.com",
        "rlcdn.com", "rs.fullstory.com", "rt.applovin.com", "rtb.openx.net",
        "rubiconproject.com", "rudderstack.com", "rum.browser-intake-datadoghq.com", "s.adroll.com",
        "s.clarity.ms", "s.innovid.com", "s.skimresources.com", "s.youtube.com",
        "samsung-com.112.2o7.net", "samsungads.com", "sb.scorecardresearch.com", "sc-analytics.appspot.com",
        "sc-static.net", "scorecardresearch.com", "script.hotjar.com", "sdk-api-v1.singular.net",
        "sdk.iad-01.braze.com", "sdk.privacy-center.org", "sdkconfig.ad.intl.xiaomi.com", "sdkconfig.ad.xiaomi.com",
        "search.spotxchange.com", "secure.adnxs.com", "securepubads.g.doubleclick.net", "segment.com",
        "segment.io", "sentry-cdn.com", "sentry.io", "servicer.mgid.com",
        "sessions.bugsnag.com", "settings-win.data.microsoft.com", "settings.luckyorange.net", "shareasale.com",
        "sharethrough.com", "siftscience.com", "simage2.pubmatic.com", "sin3-ib.adnxs.com",
        "smartadserver.com", "smartclip.com", "smartclip.net", "smartyads.com",
        "smetrics.samsung.com", "snap.licdn.com", "snowplowanalytics.com", "sonobi.com",
        "sourcepoint.mgr.consensu.org", "spotxchange.com", "ssl.google-analytics.com", "ssl.p.jwpcdn.com",
        "sslwidget.criteo.com", "st.dynamicyield.com", "stackadapt.com", "statcounter.com",
        "statdynamic.com", "static.ads-twitter.com", "static.adsafeprotected.com", "static.cloudflareinsights.com",
        "static.criteo.net", "static.doubleclick.net", "static.hotjar.com", "static.klaviyo.com",
        "static.media.net", "stats.g.doubleclick.net", "stats.wp.com", "stickyadstv.com",
        "surveys.hotjar.com", "sync-tm.everesttech.net", "sync.crwdcntrl.net", "sync.kargo.com",
        "sync.mathtag.com", "t.pepperjamnetwork.com", "t.skimresources.com", "taboola.com",
        "tagmanager.google.com", "tags.bluekai.com", "tags.crwdcntrl.net", "teads.tv",
        "telemetry.microsoft.com", "tlx.3lift.com", "tools.mouseflow.com", "top-fwz1.mail.ru",
        "tpc.googlesyndication.com", "tps.doubleverify.com", "tr.facebook.com", "tr.iadsdk.apple.com",
        "tr.snapchat.com", "track.customer.io", "track.hubspot.com", "track.linksynergy.com",
        "tracker.snowplowanalytics.com", "tracking.miui.com", "tracking.rus.miui.com", "trafficjunky.net",
        "trc.taboola.com", "tremorhub.com", "trk.pinterest.com", "tvinteractive.tv",
        "tvpixel.com", "u.openx.net", "udc.yahoo.com", "udcm.yahoo.com",
        "unity3d.com", "unityads.unity3d.com", "upload.luckyorange.net", "us-ads.openx.net",
        "us.i.posthog.com", "us.ibs.lgappstv.com", "us.info.lgsmartad.com", "vars.hotjar.com",
        "vid.connatix.com", "vortex-win.data.microsoft.com", "vortex.data.microsoft.com", "vungle.com",
        "w1.luckyorange.com", "watson.telemetry.microsoft.com", "wd.adcolony.com", "weather-analytics-events.apple.com",
        "webminepool.com", "webview.unityads.unity3d.com", "widget.intercom.io", "widgets.outbrain.com",
        "widgets.pinterest.com", "ws-na.amazon-adsystem.com", "www.anrdoezrs.net", "www.awin1.com",
        "www.coinimp.com", "www.dpbolvw.net", "www.google-analytics.com", "www.googletagmanager.com",
        "www.tkqlhce.com", "wzrkt.com", "xp.apple.com", "ymatuhin.ru",
        "yumenetworks.com", "zenaps.com"
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
        "anrdoezrs.net", "appspot.com", "bluekai.com", "bnc.lt", "braze.com",
        "browser-intake-datadoghq.com", "byteoversea.com", "clickadu.com", "cloudflareinsights.com",
        "coinimp.com", "consensu.org", "contextweb.com", "cookiebot.com", "cookielaw.org",
        "customer.io", "dpbolvw.net", "dynamicyield.com", "everesttech.net", "exoclick.com",
        "fyber.com", "getsentry.com", "googleanalytics.com",
        "hotjar.io", "hubspot.com", "icloud.com", "id5-sync.com", "indexexchange.com",
        "insightexpressai.com", "juicyads.com", "klaviyo.com",
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
        "globalapi.ad.xiaomi.com", "graph.instagram.com",
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
