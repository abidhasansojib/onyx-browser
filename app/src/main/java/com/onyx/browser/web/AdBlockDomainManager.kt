package com.onyx.browser.web

import com.onyx.browser.nativebridge.AdBlockEngine

/**
 * CNAME uncloaking and domain resolution manager for Onyx Shields.
 *
 * Ad and tracker blocking decisions are executed 100% by the native adblock engine (adblock-rust)
 * evaluating 54 compiled Brave filter lists and EasyList, backed by a high-performance in-memory
 * Kotlin LRU Decision Cache for sub-microsecond lookup speeds.
 *
 * This manager provides static CNAME alias resolution to uncloak third-party trackers hidden
 * behind first-party subdomains (e.g. Adobe Omniture, Criteo, Branch, AppsFlyer) prior to rule evaluation.
 */
object AdBlockDomainManager {

    /**
     * Static CNAME mapping for known cloaked first-party tracker subdomains.
     * Maps cloaked aliases to their real third-party tracker domains (Branch, Adobe Omniture,
     * Criteo, Eulerian, AppsFlyer, etc.) so filter rules match against the real tracker host.
     */
    private val cnameAliasMap: Map<String, String> = mapOf(
        "clickmail.stubhub.com" to "stubhub.com.links.criteo.com",
        "email.mg.everyonesocial.com" to "everyonesocial.com.links.criteo.com",
        "wl.spotify.com" to "spotify.sc.omtrdc.net",
        "links.strava.com" to "strava.com.links.criteo.com",
        "digital.att.com" to "att.sc.omtrdc.net",
        "connect.telstrawholesale.com" to "telstra.sc.omtrdc.net",
        "seek.intel.com" to "intel.sc.omtrdc.net",
        "metrics.apple.com" to "apple.sc.omtrdc.net",
        "smetrics.samsung.com" to "samsung.sc.omtrdc.net",
        "metrics.mzstatic.com" to "mzstatic.sc.omtrdc.net",
        "track.hulu.com" to "hulu.sc.omtrdc.net",
        "track.target.com" to "target.sc.omtrdc.net",
        "metrics.walmart.com" to "walmart.sc.omtrdc.net",
        "metrics.bestbuy.com" to "bestbuy.sc.omtrdc.net",
        "data.bloomberg.com" to "bloomberg.sc.omtrdc.net",
        "metrics.costco.com" to "costco.sc.omtrdc.net",
        "analytics.homedepot.com" to "homedepot.sc.omtrdc.net",
        "metrics.nordstrom.com" to "nordstrom.sc.omtrdc.net",
        "metrics.gap.com" to "gap.sc.omtrdc.net",
        "track.groupon.com" to "groupon.sc.omtrdc.net",
        "app.link" to "custom.bnc.lt",
        "link.clover.com" to "custom.bnc.lt",
        "link.groupon.com" to "custom.bnc.lt",
        "link.reddit.com" to "custom.bnc.lt",
        "link.wish.com" to "custom.bnc.lt"
    )

    private val cnameCache = object : android.util.LruCache<String, String>(512) {}

    fun uncloakDomain(domain: String): String {
        val d = domain.lowercase().trim()
        if (d.isBlank()) return d
        val cached = cnameCache.get(d)
        if (cached != null) return cached

        val uncloaked = cnameAliasMap[d] ?: run {
            cnameAliasMap.entries.firstOrNull { d.endsWith(".${it.key}") }?.value ?: d
        }
        cnameCache.put(d, uncloaked)
        return uncloaked
    }

    fun uncloakUrl(url: String): String {
        if (url.isBlank()) return url
        val host = try { android.net.Uri.parse(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        if (host.isBlank()) return url
        val uncloaked = uncloakDomain(host)
        return if (uncloaked != host) url.replace(host, uncloaked) else url
    }

    /**
     * Dynamically queries the AdBlockEngine for domain blocking status using the LRU cache.
     * Replaces the old static hardcoded domain sets with pure filter list evaluation.
     */
    fun shouldBlock(domain: String, isAggressive: Boolean): Boolean {
        if (domain.isBlank()) return false
        val uncloaked = uncloakDomain(domain)
        val resourceType = if (isAggressive) "main_frame" else "other"
        return AdBlockEngine.shouldBlock("https://$uncloaked/", "", resourceType)
    }

    fun isBlockedInStandard(domain: String): Boolean {
        return shouldBlock(domain, isAggressive = false)
    }

    fun isBlockedInAggressive(domain: String): Boolean {
        return shouldBlock(domain, isAggressive = true)
    }
}
