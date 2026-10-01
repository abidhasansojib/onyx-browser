package com.onyx.browser.web.translate

import android.webkit.CookieManager
import java.net.URI

/**
 * High-performance Native DOM Translation Manager for Onyx Browser.
 * Replaces deprecated external Google Translate script injection with a CSP-immune,
 * in-process native translation pipeline. Works uniformly across ALL search engines
 * (Google, Bing, DuckDuckGo, Brave Search, Yahoo, Startpage) and strict CSP websites.
 */
object PageTranslateManager {

    fun normalizeLang(code: String): String {
        if (code.isBlank()) return "en"
        val c = code.lowercase().trim()
        return when {
            c == "fil" -> "tl"
            c == "he" -> "iw"
            c == "jv" -> "jw"
            c == "zh-cn" || c == "zh_cn" || c == "zh-hans" -> "zh-CN"
            c == "zh-tw" || c == "zh_tw" || c == "zh-hant" -> "zh-TW"
            c.startsWith("pt") -> if (c.contains("br")) "pt" else "pt"
            else -> code
        }
    }

    /**
     * JavaScript that performs DOM traversal, preserves original text and whitespace,
     * tags text nodes into XML batches, and delegates network translation to OnyxTranslateBridge.
     * Also initializes a MutationObserver to translate dynamically loaded search results (infinite scroll).
     */
    fun getTranslateScript(targetLang: String): String {
        val normLang = normalizeLang(targetLang)
        return """
            (function() {
                var targetLang = '$normLang';
                if (!window.__onyx_translate_state) {
                    window.__onyx_translate_state = {
                        nodes: {},
                        nextId: 0,
                        isTranslating: false,
                        activeLang: '',
                        origDir: undefined,
                        observer: null
                    };
                }
                var state = window.__onyx_translate_state;
                var isSameLang = (state.activeLang === targetLang);

                // Prevent duplicate concurrent scans for the same language
                if (state.isTranslating && isSameLang && Object.keys(state.nodes).length > 0) {
                    return 'already_translating';
                }

                var langChanged = (state.activeLang !== targetLang && state.activeLang !== '');
                state.activeLang = targetLang;
                state.isTranslating = true;

                // Handle RTL language direction
                var isRtl = (targetLang === 'ar' || targetLang === 'he' || targetLang === 'iw' || targetLang === 'fa' || targetLang === 'ur');
                if (isRtl) {
                    if (state.origDir === undefined) {
                        state.origDir = document.documentElement.getAttribute('dir') || '';
                    }
                    document.documentElement.setAttribute('dir', 'rtl');
                } else if (state.origDir !== undefined) {
                    if (state.origDir) {
                        document.documentElement.setAttribute('dir', state.origDir);
                    } else {
                        document.documentElement.removeAttribute('dir');
                    }
                }

                function escapeXml(str) {
                    return str.replace(/&/g, '&amp;')
                              .replace(/</g, '&lt;')
                              .replace(/>/g, '&gt;')
                              .replace(/"/g, '&quot;');
                }

                var skipTags = {
                    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, INPUT: 1,
                    SELECT: 1, OPTION: 1, CODE: 1, PRE: 1, SVG: 1, CANVAS: 1,
                    AUDIO: 1, VIDEO: 1, IFRAME: 1, OBJECT: 1, EMBED: 1
                };

                function shouldSkipElement(el) {
                    if (!el || el.nodeType !== 1) return false;
                    var tag = el.tagName;
                    if (skipTags[tag]) return true;
                    if (el.hasAttribute('translate') && el.getAttribute('translate') === 'no') return true;
                    if (el.classList && (el.classList.contains('notranslate') || el.classList.contains('skiptranslate'))) return true;
                    return false;
                }

                function isPurePunctuationOrNumber(str) {
                    var s = str.trim();
                    if (!s || s.length === 0) return true;
                    return /^[\d\s\.,;:!?'"()\[\]{}\/\\@#$%^&*+=<>~`\-_|]+$/.test(s);
                }

                function collectTextNodes(root) {
                    var eligible = [];
                    var walker = document.createTreeWalker(
                        root,
                        NodeFilter.SHOW_TEXT,
                        {
                            acceptNode: function(node) {
                                var parent = node.parentElement;
                                if (!parent) return NodeFilter.FILTER_REJECT;
                                var cur = parent;
                                while (cur && cur !== document.documentElement) {
                                    if (shouldSkipElement(cur)) return NodeFilter.FILTER_REJECT;
                                    cur = cur.parentElement;
                                }
                                var val = node.nodeValue;
                                if (!val || !val.trim() || isPurePunctuationOrNumber(val)) return NodeFilter.FILTER_REJECT;
                                return NodeFilter.FILTER_ACCEPT;
                            }
                        },
                        false
                    );

                    var n;
                    while ((n = walker.nextNode())) {
                        eligible.push(n);
                    }
                    return eligible;
                }

                function processNodes(nodeList, isInitial) {
                    var batchXml = '';
                    var batchCount = 0;
                    var batchId = state.nextId;
                    var totalDispatched = 0;

                    for (var i = 0; i < nodeList.length; i++) {
                        var node = nodeList[i];
                        var id;
                        if (node.__onyx_id !== undefined) {
                            id = node.__onyx_id;
                        } else {
                            id = state.nextId++;
                            node.__onyx_id = id;
                            node.__onyx_orig = node.nodeValue;
                            state.nodes[id] = node;
                        }

                        // Use original text for translation source
                        var text = node.__onyx_orig || node.nodeValue;
                        var match = text.match(/^(\s*)([\s\S]*?)(\s*)$/);
                        var content = match ? match[2] : text;

                        if (content.length > 0 && !isPurePunctuationOrNumber(content)) {
                            batchXml += '<t id="' + id + '">' + escapeXml(content) + '</t>';
                            batchCount++;
                        }

                        if (batchCount >= 45 || batchXml.length >= 3000) {
                            if (window.OnyxTranslateBridge && window.OnyxTranslateBridge.translateBatch) {
                                window.OnyxTranslateBridge.translateBatch(batchId, batchXml, targetLang, isInitial);
                                totalDispatched++;
                            }
                            batchXml = '';
                            batchCount = 0;
                            batchId = state.nextId;
                        }
                    }

                    if (batchCount > 0) {
                        if (window.OnyxTranslateBridge && window.OnyxTranslateBridge.translateBatch) {
                            window.OnyxTranslateBridge.translateBatch(batchId, batchXml, targetLang, isInitial);
                            totalDispatched++;
                        }
                    }

                    if (totalDispatched === 0 && isInitial) {
                        state.isTranslating = false;
                        if (window.OnyxTranslateBridge && window.OnyxTranslateBridge.onTranslationFinished) {
                            window.OnyxTranslateBridge.onTranslationFinished();
                        }
                    }
                }

                window.__onyx_apply_batch = function(batchId, translations) {
                    if (typeof translations === 'string') {
                        try {
                            translations = JSON.parse(translations);
                        } catch (_) {
                            return;
                        }
                    }
                    if (!translations || typeof translations !== 'object') return;

                    for (var id in translations) {
                        var node = state.nodes[id];
                        if (node) {
                            var transText = translations[id];
                            var orig = node.__onyx_orig || node.nodeValue;
                            var match = orig.match(/^(\s*)([\s\S]*?)(\s*)$/);
                            var leading = match ? match[1] : '';
                            var trailing = match ? match[3] : '';
                            node.__onyx_trans = leading + transText + trailing;
                            // Only update rendered DOM text if translation is still active
                            if (state.isTranslating) {
                                node.nodeValue = node.__onyx_trans;
                            }
                        }
                    }
                };

                // If language changed on an already collected page, re-translate all existing nodes
                if (langChanged && Object.keys(state.nodes).length > 0) {
                    var existingNodes = [];
                    for (var id in state.nodes) {
                        existingNodes.push(state.nodes[id]);
                    }
                    processNodes(existingNodes, true);
                } else {
                    var allNodes = collectTextNodes(document.body || document.documentElement);
                    if (allNodes.length === 0) {
                        state.isTranslating = false;
                        if (window.OnyxTranslateBridge && window.OnyxTranslateBridge.onTranslationFinished) {
                            window.OnyxTranslateBridge.onTranslationFinished();
                        }
                        return 'empty';
                    }
                    processNodes(allNodes, true);
                }

                // Setup MutationObserver for dynamic pagination & infinite scrolling
                if (!state.observer) {
                    var debounceTimer = null;
                    var pendingRoots = [];
                    state.observer = new MutationObserver(function(mutations) {
                        if (!state.isTranslating) return;
                        for (var m = 0; m < mutations.length; m++) {
                            var added = mutations[m].addedNodes;
                            for (var a = 0; a < added.length; a++) {
                                var el = added[a];
                                if (el.nodeType === 1 && !shouldSkipElement(el)) {
                                    pendingRoots.push(el);
                                }
                            }
                        }
                        if (pendingRoots.length > 0) {
                            if (debounceTimer) clearTimeout(debounceTimer);
                            debounceTimer = setTimeout(function() {
                                var newNodes = [];
                                while (pendingRoots.length > 0) {
                                    var root = pendingRoots.shift();
                                    var collected = collectTextNodes(root);
                                    for (var k = 0; k < collected.length; k++) {
                                        if (collected[k].__onyx_id === undefined) {
                                            newNodes.push(collected[k]);
                                        }
                                    }
                                }
                                if (newNodes.length > 0) {
                                    processNodes(newNodes, false);
                                }
                            }, 350);
                        }
                    });
                    state.observer.observe(document.body || document.documentElement, {
                        childList: true,
                        subtree: true
                    });
                }

                return 'started';
            })();
        """.trimIndent()
    }

    /**
     * Restores original untranslated text directly across all modified text nodes with 0ms latency
     * and zero page reload, preserving form inputs, scroll position, and tab memory.
     */
    val restoreOriginalScript: String = """
        (function() {
            var state = window.__onyx_translate_state;
            if (!state) return 'not_initialized';
            state.isTranslating = false;
            for (var id in state.nodes) {
                var node = state.nodes[id];
                if (node && node.__onyx_orig !== undefined) {
                    node.nodeValue = node.__onyx_orig;
                }
            }
            if (state.origDir !== undefined) {
                if (state.origDir) {
                    document.documentElement.setAttribute('dir', state.origDir);
                } else {
                    document.documentElement.removeAttribute('dir');
                }
            }
            return 'restored';
        })();
    """.trimIndent()

    /**
     * Toggles already translated text back into view without making network requests.
     * Returns 'reinitialize' if translations are not yet available for the target language.
     */
    val toggleTranslatedScript: String = """
        (function() {
            var state = window.__onyx_translate_state;
            if (!state || Object.keys(state.nodes).length === 0) return 'reinitialize';
            state.isTranslating = true;
            var hasTrans = false;
            for (var id in state.nodes) {
                var node = state.nodes[id];
                if (node && node.__onyx_trans !== undefined) {
                    node.nodeValue = node.__onyx_trans;
                    hasTrans = true;
                }
            }
            if (!hasTrans) return 'reinitialize';
            var isRtl = (state.activeLang === 'ar' || state.activeLang === 'he' || state.activeLang === 'iw' || state.activeLang === 'fa' || state.activeLang === 'ur');
            if (isRtl) {
                document.documentElement.setAttribute('dir', 'rtl');
            }
            return 'translated';
        })();
    """.trimIndent()

    /**
     * Cleans up observers and memory references on tab close or page reload.
     */
    val cleanupScript: String = """
        (function() {
            var state = window.__onyx_translate_state;
            if (state) {
                if (state.observer) {
                    try { state.observer.disconnect(); } catch (_) {}
                    state.observer = null;
                }
                state.nodes = {};
                state.isTranslating = false;
                delete window.__onyx_translate_state;
            }
            return 'cleaned';
        })();
    """.trimIndent()

    /**
     * Legacy method preserved for compatibility with existing callers.
     * With Native DOM Translation, cookies are no longer used for page translation.
     */
    fun setupCookies(url: String, targetLang: String) {
        clearCookies(url)
    }

    /**
     * Cleans legacy googtrans cookies across all domains to ensure no residual cookies stick.
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
