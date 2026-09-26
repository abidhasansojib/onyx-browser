package com.onyx.browser.web.translate

import android.webkit.CookieManager
import java.net.URI

object PageTranslateManager {

    /**
     * Injects the in-page DOM translation engine into the active webpage.
     * Thoroughly purges previous googtrans cookies to prevent language sticking (e.g. defaulting to Bangla),
     * applies off-screen (instead of display:none) styling so Google's iframe initializes cleanly,
     * and sets the new language accurately.
     */
    fun getTranslateScript(targetLang: String): String = """
        (function() {
            var targetLang = '$targetLang';
            var host = window.location.hostname || '';

            function normalizeLang(code) {
                if (!code) return '';
                var c = code.toLowerCase().trim();
                if (c === 'fil') return 'tl';
                if (c === 'he') return 'iw';
                if (c === 'jv') return 'jw';
                if (c === 'zh-cn' || c === 'zh_cn' || c === 'zh-hans') return 'zh-CN';
                if (c === 'zh-tw' || c === 'zh_tw' || c === 'zh-hant') return 'zh-TW';
                return code;
            }

            // 1. Thoroughly purge old googtrans cookies across all domains & paths
            var domains = ['', host, '.' + host];
            var parts = host.split('.');
            while (parts.length > 1) {
                domains.push('.' + parts.join('.'));
                domains.push(parts.join('.'));
                parts.shift();
            }
            var paths = ['/', '', window.location.pathname];
            for (var d = 0; d < domains.length; d++) {
                for (var p = 0; p < paths.length; p++) {
                    var domainStr = domains[d] ? '; domain=' + domains[d] : '';
                    var pathStr = paths[p] ? '; path=' + paths[p] : '';
                    document.cookie = 'googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC' + pathStr + domainStr;
                }
            }

            // 2. Set Google Translate cookies for root domain and current host
            var rootDomain = parts.length > 1 ? parts.slice(-2).join('.') : host;
            document.cookie = 'googtrans=/auto/' + targetLang + '; path=/;';
            if (rootDomain) {
                document.cookie = 'googtrans=/auto/' + targetLang + '; domain=.' + rootDomain + '; path=/;';
            }
            if (host && host !== rootDomain) {
                document.cookie = 'googtrans=/auto/' + targetLang + '; domain=.' + host + '; path=/;';
            }

            // 3. Inject CSS to hide Google banner frame off-screen (never display:none so iframes stay active)
            if (!document.getElementById('__onyx_translate_style')) {
                var style = document.createElement('style');
                style.id = '__onyx_translate_style';
                style.textContent = `
                    .goog-te-banner-frame, .goog-te-banner-frame.skiptranslate,
                    iframe.goog-te-banner-frame, #goog-gt-tt, .goog-te-balloon-frame {
                        position: absolute !important;
                        top: -9999px !important;
                        left: -9999px !important;
                        width: 1px !important;
                        height: 1px !important;
                        opacity: 0 !important;
                        pointer-events: none !important;
                        visibility: hidden !important;
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

            function applyComboTarget(c, lang) {
                if (!c || !c.options) return false;
                var normTarget = normalizeLang(lang).toLowerCase();
                var rawTarget = lang.toLowerCase();
                var baseLang = rawTarget.split('-')[0].split('_')[0];
                var foundIndex = -1;

                for (var i = 0; i < c.options.length; i++) {
                    var optVal = (c.options[i].value || '').toLowerCase();
                    if (optVal === normTarget || optVal === rawTarget) {
                        foundIndex = i;
                        break;
                    }
                }
                if (foundIndex === -1) {
                    for (var i = 0; i < c.options.length; i++) {
                        var optVal = (c.options[i].value || '').toLowerCase();
                        if (optVal === baseLang || optVal.startsWith(baseLang + '-')) {
                            foundIndex = i;
                            break;
                        }
                    }
                }

                if (foundIndex !== -1) {
                    c.selectedIndex = foundIndex;
                    c.value = c.options[foundIndex].value;
                } else {
                    c.value = lang;
                }
                c.dispatchEvent(new Event('change', { bubbles: true }));
                c.dispatchEvent(new Event('input', { bubbles: true }));
                return true;
            }

            // 4. If combo already exists, trigger change immediately
            var combo = document.querySelector('.goog-te-combo');
            if (combo) {
                applyComboTarget(combo, targetLang);
                return 'switched';
            }

            // 5. Create hidden translate container
            if (!document.getElementById('onyx_translate_element')) {
                var div = document.createElement('div');
                div.id = 'onyx_translate_element';
                div.style.display = 'none';
                (document.body || document.documentElement).appendChild(div);
            }

            // 6. Define initialization hook
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
                        if (c && c.options && c.options.length > 1) {
                            clearInterval(interval);
                            applyComboTarget(c, targetLang);
                        } else if (++attempts > 50) {
                            clearInterval(interval);
                            if (c) applyComboTarget(c, targetLang);
                        }
                    }, 80);
                } catch (e) {}
            };

            // 7. Inject Google Translate Element script if not already present
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
     * Clears old cookies to prevent sticking, updates the combo element, and
     * if the combo is not present in the DOM (e.g. after restore or page navigation),
     * returns 'reinitialize' so the caller can reinject the translation engine.
     */
    fun getSwitchLanguageScript(targetLang: String): String = """
        (function() {
            var targetLang = '$targetLang';
            var host = window.location.hostname || '';

            function normalizeLang(code) {
                if (!code) return '';
                var c = code.toLowerCase().trim();
                if (c === 'fil') return 'tl';
                if (c === 'he') return 'iw';
                if (c === 'jv') return 'jw';
                if (c === 'zh-cn' || c === 'zh_cn' || c === 'zh-hans') return 'zh-CN';
                if (c === 'zh-tw' || c === 'zh_tw' || c === 'zh-hant') return 'zh-TW';
                return code;
            }

            // 1. Thoroughly purge old googtrans cookies across all domains & paths
            var domains = ['', host, '.' + host];
            var parts = host.split('.');
            while (parts.length > 1) {
                domains.push('.' + parts.join('.'));
                domains.push(parts.join('.'));
                parts.shift();
            }
            var paths = ['/', '', window.location.pathname];
            for (var d = 0; d < domains.length; d++) {
                for (var p = 0; p < paths.length; p++) {
                    var domainStr = domains[d] ? '; domain=' + domains[d] : '';
                    var pathStr = paths[p] ? '; path=' + paths[p] : '';
                    document.cookie = 'googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC' + pathStr + domainStr;
                }
            }

            // 2. Set new cookie
            var rootDomain = parts.length > 1 ? parts.slice(-2).join('.') : host;
            document.cookie = 'googtrans=/auto/' + targetLang + '; path=/;';
            if (rootDomain) {
                document.cookie = 'googtrans=/auto/' + targetLang + '; domain=.' + rootDomain + '; path=/;';
            }
            if (host && host !== rootDomain) {
                document.cookie = 'googtrans=/auto/' + targetLang + '; domain=.' + host + '; path=/;';
            }

            // 3. Find combo and switch
            var combo = document.querySelector('.goog-te-combo');
            if (combo && combo.options && combo.options.length > 1) {
                var normTarget = normalizeLang(targetLang).toLowerCase();
                var rawTarget = targetLang.toLowerCase();
                var baseLang = rawTarget.split('-')[0].split('_')[0];
                var foundIndex = -1;

                for (var i = 0; i < combo.options.length; i++) {
                    var optVal = (combo.options[i].value || '').toLowerCase();
                    if (optVal === normTarget || optVal === rawTarget) {
                        foundIndex = i;
                        break;
                    }
                }
                if (foundIndex === -1) {
                    for (var i = 0; i < combo.options.length; i++) {
                        var optVal = (combo.options[i].value || '').toLowerCase();
                        if (optVal === baseLang || optVal.startsWith(baseLang + '-')) {
                            foundIndex = i;
                            break;
                        }
                    }
                }

                if (foundIndex !== -1) {
                    combo.selectedIndex = foundIndex;
                    combo.value = combo.options[foundIndex].value;
                } else {
                    combo.value = targetLang;
                }

                combo.dispatchEvent(new Event('change', { bubbles: true }));
                combo.dispatchEvent(new Event('input', { bubbles: true }));
                return 'switched';
            }

            return 'reinitialize';
        })();
    """.trimIndent()

    /**
     * Restores the page's original untranslated text directly in the DOM without reloading the webpage.
     * 1. Clears googtrans cookies across all domains & paths.
     * 2. Triggers Google's native restore button in the off-screen banner iframe.
     * 3. Sets combo to option 0 (empty / original) and dispatches events.
     * 4. Cleans translation classes from html and body.
     * 5. Avoids force reloading the page unless in-place restore fails.
     */
    val restoreOriginalScript: String = """
        (function() {
            var host = window.location.hostname || '';
            // 1. Clear googtrans cookies across all domains and paths
            var domains = ['', host, '.' + host];
            var parts = host.split('.');
            while (parts.length > 1) {
                domains.push('.' + parts.join('.'));
                domains.push(parts.join('.'));
                parts.shift();
            }
            var paths = ['/', '', window.location.pathname];
            for (var d = 0; d < domains.length; d++) {
                for (var p = 0; p < paths.length; p++) {
                    var domainStr = domains[d] ? '; domain=' + domains[d] : '';
                    var pathStr = paths[p] ? '; path=' + paths[p] : '';
                    document.cookie = 'googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC' + pathStr + domainStr;
                }
            }

            // 2. Try the native Google Translate restore button in iframes
            try {
                var iframes = document.querySelectorAll('iframe.goog-te-banner-frame, iframe[id*="goog"], .skiptranslate iframe, iframe');
                for (var i = 0; i < iframes.length; i++) {
                    try {
                        var doc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                        if (doc) {
                            var restoreBtn = doc.querySelector('[id*="restore"], .goog-close-link, .goog-te-button');
                            if (restoreBtn) {
                                restoreBtn.click();
                                return 'restored_iframe_btn';
                            }
                            var allElements = doc.querySelectorAll('button, a, span, div');
                            for (var j = 0; j < allElements.length; j++) {
                                var t = (allElements[j].textContent || allElements[j].innerText || '').toLowerCase();
                                if (t.indexOf('original') !== -1 || t.indexOf('restore') !== -1) {
                                    allElements[j].click();
                                    return 'restored_iframe_text';
                                }
                            }
                        }
                    } catch (_) {}
                }
            } catch (_) {}

            // 3. Try combo element (set to show original)
            var combo = document.querySelector('.goog-te-combo');
            if (combo) {
                combo.selectedIndex = 0;
                combo.value = '';
                combo.dispatchEvent(new Event('change', { bubbles: true }));
                combo.dispatchEvent(new Event('input', { bubbles: true }));
            }

            // 4. Clean translation classes from html and body
            try {
                document.documentElement.classList.remove('translated-ltr', 'translated-rtl');
                document.body.classList.remove('translated-ltr', 'translated-rtl');
            } catch (_) {}

            // 5. In-place check: do not reload if text was restored
            setTimeout(function() {
                var isStillTranslated = document.documentElement.classList.contains('translated-ltr') ||
                                         document.documentElement.classList.contains('translated-rtl') ||
                                         (document.documentElement.getAttribute('class') || '').indexOf('translated') !== -1;
                if (isStillTranslated) {
                    window.location.reload();
                }
            }, 800);

            return 'restored';
        })();
    """.trimIndent()

    /**
     * Pre-configures Android CookieManager with the googtrans cookie before injecting scripts.
     * Clears all conflicting domain cookies first.
     */
    fun setupCookies(url: String, targetLang: String) {
        try {
            val uri = URI(url)
            val host = uri.host ?: return
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)

            // Clear old googtrans first across domains
            clearCookies(url)

            cm.setCookie(url, "googtrans=/auto/$targetLang; path=/")
            val parts = host.split(".").toMutableList()
            while (parts.size > 1) {
                val d = "." + parts.joinToString(".")
                cm.setCookie(url, "googtrans=/auto/$targetLang; domain=$d; path=/")
                parts.removeAt(0)
            }
            cm.flush()
        } catch (_: Exception) {}
    }

    /**
     * Clears googtrans cookies for restoring original text across all domain permutations.
     */
    fun clearCookies(url: String) {
        try {
            val uri = URI(url)
            val host = uri.host ?: return
            val cm = CookieManager.getInstance()
            val domains = mutableListOf("", host, ".$host")
            val parts = host.split(".").toMutableList()
            while (parts.size > 1) {
                domains.add("." + parts.joinToString("."))
                domains.add(parts.joinToString("."))
                parts.removeAt(0)
            }
            for (d in domains.distinct()) {
                val domainClause = if (d.isNotEmpty()) "; domain=$d" else ""
                cm.setCookie(url, "googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/$domainClause")
                cm.setCookie(url, "googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=$domainClause")
            }
            cm.flush()
        } catch (_: Exception) {}
    }
}
