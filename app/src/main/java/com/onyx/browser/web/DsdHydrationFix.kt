package com.onyx.browser.web

import android.webkit.WebView

/**
 * Fixes Declarative Shadow DOM (DSD) and progressive hydration stalls on websites
 * where bootstrap or telemetry scripts are blocked by content filters.
 *
 * Modern web applications and component frameworks (e.g., Lit, Stencil, Google I/O portals)
 * use a progressive-hydration pattern where the document or custom element containers start
 * with a `dsd-pending` attribute:
 *   `<body dsd-pending>`
 * with stylesheet rules hiding them until bootstrap completes:
 *   `[dsd-pending] { display: none !important; }`
 *
 * If any script in that bootstrap chain (analytics, GTM, tag manager, or third-party bundle)
 * is intercepted or blocked by the ad-filter, the removal callback never executes and the page
 * remains completely blank despite having full DOM content.
 *
 * This fix executes as a safety net at [onPageFinished] to remove `dsd-pending` attributes across
 * the root document and open shadow roots, guaranteeing that rendered content becomes visible.
 */
object DsdHydrationFix {

    const val FIX_SCRIPT: String = """
        (function() {
            try {
                var removeDsd = function() {
                    function clearInRoot(root) {
                        if (!root || !root.querySelectorAll) return;
                        try {
                            var pending = root.querySelectorAll('[dsd-pending]');
                            if (pending) {
                                for (var i = 0; i < pending.length; i++) {
                                    pending[i].removeAttribute('dsd-pending');
                                }
                            }
                            var all = root.querySelectorAll('*');
                            for (var j = 0; j < all.length; j++) {
                                if (all[j].shadowRoot) {
                                    clearInRoot(all[j].shadowRoot);
                                }
                            }
                        } catch (_) {}
                    }
                    if (document.documentElement && document.documentElement.hasAttribute('dsd-pending')) {
                        document.documentElement.removeAttribute('dsd-pending');
                    }
                    if (document.body && document.body.hasAttribute('dsd-pending')) {
                        document.body.removeAttribute('dsd-pending');
                    }
                    clearInRoot(document);
                };

                removeDsd();
                setTimeout(removeDsd, 800);
            } catch (e) {}
        })();
    """

    fun applyFix(view: WebView?) {
        view?.evaluateJavascript(FIX_SCRIPT, null)
    }
}
