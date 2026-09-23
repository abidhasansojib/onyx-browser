package com.onyx.browser.web.translate

import android.webkit.CookieManager
import java.net.URI

object PageTranslateManager {

    /**
     * Injects the in-page DOM translation engine into the active webpage.
     * Unlike web proxy translation (translate.google.com/translate?u=...), this runs
     * directly within the current DOM, preserving URLs, logins, sessions, and cookies,
     * without triggering HTTP 429 Rate Limit Exceeded errors.
     */
    fun getTranslateScript(targetLang: String): String = """
        (function() {
            var targetLang = '$targetLang';
            var host = window.location.hostname || '';

            // 1. Set Google Translate cookies for both root path and domain
            try {
                document.cookie = 'googtrans=/auto/' + targetLang + '; path=/;';
                if (host) {
                    document.cookie = 'googtrans=/auto/' + targetLang + '; domain=.' + host + '; path=/;';
                }
            } catch (_) {}

            // 2. Inject CSS to hide Google banner frame, tooltips, and prevent body displacement
            if (!document.getElementById('__onyx_translate_style')) {
                var style = document.createElement('style');
                style.id = '__onyx_translate_style';
                style.textContent = `
                    .goog-te-banner-frame, .goog-te-banner-frame.skiptranslate,
                    iframe.goog-te-banner-frame, #goog-gt-tt, .goog-te-balloon-frame {
                        display: none !important;
                        visibility: hidden !important;
                        height: 0 !important;
                        opacity: 0 !important;
                        pointer-events: none !important;
                    }
                    body {
                        top: 0px !important;
                        position: static !important;
                    }
                    .goog-text-highlight {
                        background: transparent !important;
                        box-shadow: none !important;
                    }
                    #onyx_translate_element {
                        display: none !important;
                        visibility: hidden !important;
                    }
                    .skiptranslate:not(.goog-te-gadget) {
                        display: none !important;
                    }
                `;
                (document.head || document.documentElement).appendChild(style);
            }

            // 3. If combo already exists, trigger change immediately
            var combo = document.querySelector('.goog-te-combo');
            if (combo) {
                combo.value = targetLang;
                combo.dispatchEvent(new Event('change', { bubbles: true }));
                return 'switched';
            }

            // 4. Create hidden translate container
            if (!document.getElementById('onyx_translate_element')) {
                var div = document.createElement('div');
                div.id = 'onyx_translate_element';
                div.style.display = 'none';
                (document.body || document.documentElement).appendChild(div);
            }

            // 5. Define initialization hook
            window.onyxTranslateInit = function() {
                try {
                    new google.translate.TranslateElement({
                        pageLanguage: 'auto',
                        autoDisplay: false,
                        multilanguagePage: true
                    }, 'onyx_translate_element');

                    var attempts = 0;
                    var interval = setInterval(function() {
                        var c = document.querySelector('.goog-te-combo');
                        if (c) {
                            clearInterval(interval);
                            c.value = targetLang;
                            c.dispatchEvent(new Event('change', { bubbles: true }));
                        } else if (++attempts > 40) {
                            clearInterval(interval);
                        }
                    }, 100);
                } catch (e) {}
            };

            // 6. Inject Google Translate Element script if not already present
            if (!document.getElementById('__onyx_translate_script')) {
                var s = document.createElement('script');
                s.id = '__onyx_translate_script';
                s.src = 'https://translate.google.com/translate_a/element.js?cb=onyxTranslateInit';
                (document.head || document.documentElement).appendChild(s);
            } else {
                if (typeof window.onyxTranslateInit === 'function') {
                    window.onyxTranslateInit();
                }
            }

            return 'initialized';
        })();
    """.trimIndent()

    /**
     * Switches the active in-page translation to another target language.
     */
    fun getSwitchLanguageScript(targetLang: String): String = """
        (function() {
            var targetLang = '$targetLang';
            var host = window.location.hostname || '';
            try {
                document.cookie = 'googtrans=/auto/' + targetLang + '; path=/;';
                if (host) {
                    document.cookie = 'googtrans=/auto/' + targetLang + '; domain=.' + host + '; path=/;';
                }
            } catch (_) {}

            var combo = document.querySelector('.goog-te-combo');
            if (combo) {
                combo.value = targetLang;
                combo.dispatchEvent(new Event('change', { bubbles: true }));
                return 'switched';
            }
            return 'pending';
        })();
    """.trimIndent()

    /**
     * Restores the page's original untranslated text directly in the DOM.
     * Uses a 4-step cascade for maximum reliability:
     * 1. Clears googtrans cookies.
     * 2. Tries the .goog-te-combo element (set to empty / show original).
     * 3. Scans iframes for a "Show original" / "Restore" button.
     * 4. Falls back to removing all injected translate elements and reloading.
     */
    val restoreOriginalScript: String = """
        (function() {
            var host = window.location.hostname || '';
            // 1. Clear googtrans cookies
            try {
                document.cookie = 'googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/';
                if (host) {
                    document.cookie = 'googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC; domain=.' + host + '; path=/';
                }
            } catch (_) {}

            // 2. Try combo element (set to show original)
            var combo = document.querySelector('.goog-te-combo');
            if (combo) {
                combo.value = '';
                combo.selectedIndex = 0;
                combo.dispatchEvent(new Event('change', { bubbles: true }));
                setTimeout(function() {
                    // Verify if text is still translated and force reload if needed
                    var cls = document.documentElement.getAttribute('class') || '';
                    if (cls.indexOf('translated') !== -1) {
                        window.location.reload();
                    }
                }, 1000);
                return 'restored_combo';
            }

            // 3. Try the Google Translate restore button in the banner iframe
            try {
                var frames = document.querySelectorAll('iframe');
                for (var i = 0; i < frames.length; i++) {
                    var f = frames[i];
                    try {
                        var doc = f.contentDocument || f.contentWindow.document;
                        var btns = doc.querySelectorAll('button, a');
                        for (var j = 0; j < btns.length; j++) {
                            var txt = (btns[j].textContent || '').toLowerCase();
                            if (txt.indexOf('original') !== -1 || txt.indexOf('restore') !== -1) {
                                btns[j].click();
                                return 'restored_iframe';
                            }
                        }
                    } catch (_) {}
                }
            } catch (_) {}

            // 4. Fallback: remove injected translate elements and reload
            var el = document.getElementById('onyx_translate_element');
            if (el) el.remove();
            var script = document.getElementById('__onyx_translate_script');
            if (script) script.remove();
            var style = document.getElementById('__onyx_translate_style');
            if (style) style.remove();
            window.location.reload();
            return 'reloading';
        })();
    """.trimIndent()

    /**
     * Pre-configures Android CookieManager with the googtrans cookie before injecting scripts.
     */
    fun setupCookies(url: String, targetLang: String) {
        try {
            val uri = URI(url)
            val host = uri.host ?: return
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setCookie(url, "googtrans=/auto/$targetLang; path=/")
            cm.setCookie(url, "googtrans=/auto/$targetLang; domain=.$host; path=/")
        } catch (_: Exception) {}
    }

    /**
     * Clears googtrans cookies for restoring original text.
     */
    fun clearCookies(url: String) {
        try {
            val uri = URI(url)
            val host = uri.host ?: return
            val cm = CookieManager.getInstance()
            cm.setCookie(url, "googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/")
            cm.setCookie(url, "googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC; domain=.$host; path=/")
        } catch (_: Exception) {}
    }
}
