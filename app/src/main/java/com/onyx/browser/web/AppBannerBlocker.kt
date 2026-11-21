package com.onyx.browser.web

/**
 * Hides "Open in app" / "Use the app" / smart app banners.
 *
 * Static CSS selectors alone miss most modern sites (Facebook, Instagram, X, Reddit, Pinterest...)
 * because they inject obfuscated, late-rendered banners inside single-page apps. This script:
 * - removes smart-banner meta tags,
 * - hides well-known banner selectors,
 * - uses a throttled MutationObserver to find short "open/use/get/install app" prompts and hides
 *   only their fixed/sticky full-width container (never page content, dialogs with inputs, or body).
 */
object AppBannerBlocker {

    val script: String = """
        (function() {
            if (window.__onyxAppBannerBlock) return;
            window.__onyxAppBannerBlock = true;
            try {
                document.querySelectorAll(
                    'meta[name="apple-itunes-app"], meta[name="google-play-app"]'
                ).forEach(function(m) { m.parentNode && m.parentNode.removeChild(m); });
            } catch (e) {}

            var css = [
                '#app-banner, .app-banner, .smartbanner, .smart-banner, .smartbanner-show,',
                '#smart-app-banner, .open-in-app, .branch-banner-content, #branch-banner-iframe,',
                '[id*="app-banner"], [class*="app-banner"], [class*="smart-banner"],',
                '[class*="app-download-banner"], [class*="AppBanner"], [class*="appBanner"],',
                '[data-testid*="app-banner"], [data-testid*="openApp"], [data-testid="open-app-banner"],',
                '#mobile-app-banner, .xpromo-nsfw-blocking-container, shreddit-experience-tree {',
                '  display: none !important; visibility: hidden !important;',
                '}'
            ].join('');
            try {
                var style = document.createElement('style');
                style.id = 'onyx-app-banner-block';
                style.textContent = css;
                (document.head || document.documentElement).appendChild(style);
            } catch (e) {}

            var PROMPT = /^(open|use|get|install|download|continue in|view in|switch to)\b.{0,24}\b(app|application)\b|^open app$|^use app$|^not now$/i;
            var APP_LINK = /^(fb|fb-messenger|instagram|twitter|intent|market|snssdk\d*|pinterest):\/\/|play\.google\.com\/store|apps\.apple\.com|app\.link|onelink\.me|go\.onelink/i;
            var scheduled = false;

            function isBannerContainer(el) {
                if (!el || el === document.body || el === document.documentElement) return false;
                var cs = getComputedStyle(el);
                if (cs.display === 'none') return false;
                var r = el.getBoundingClientRect();
                var vw = window.innerWidth, vh = window.innerHeight;
                if (r.width < vw * 0.6 || r.height > vh * 0.45 || r.height < 24) return false;
                var pos = cs.position;
                var nearEdge = r.top < 8 || (vh - r.bottom) < 8;
                return (pos === 'fixed' || pos === 'sticky') || nearEdge && r.height < 140;
            }

            function hideContainerOf(node) {
                var el = node;
                for (var i = 0; i < 8 && el && el !== document.body; i++, el = el.parentElement) {
                    if (el.querySelector && el.querySelector('input[type="password"], input[type="email"], video')) return;
                    if (isBannerContainer(el)) {
                        el.style.setProperty('display', 'none', 'important');
                        el.setAttribute('data-onyx-banner-hidden', '1');
                        return;
                    }
                }
            }

            function scan() {
                scheduled = false;
                try {
                    var nodes = document.querySelectorAll('a, button, [role="button"], div[tabindex], span');
                    for (var i = 0; i < nodes.length && i < 1500; i++) {
                        var n = nodes[i];
                        if (n.hasAttribute('data-onyx-banner-hidden') || n.closest('[data-onyx-banner-hidden]')) continue;
                        var href = n.getAttribute && n.getAttribute('href');
                        var isAppLink = href && APP_LINK.test(href);
                        var txt = (n.innerText || '').trim();
                        if (isAppLink || (txt.length > 0 && txt.length <= 28 && n.children.length <= 2 && PROMPT.test(txt))) {
                            hideContainerOf(n);
                        }
                    }
                } catch (e) {}
            }

            function schedule() {
                if (scheduled) return;
                scheduled = true;
                setTimeout(scan, 400);
            }

            scan();
            try {
                new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
            } catch (e) {}
        })();
    """.trimIndent()
}
