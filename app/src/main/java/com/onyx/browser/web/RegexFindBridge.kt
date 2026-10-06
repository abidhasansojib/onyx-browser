package com.onyx.browser.web

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RegexFindBridge(
    private val webView: WebView,
    private val onFindResult: (Int, Int) -> Unit
) {

    private var isRegexMode = false

    fun setRegexMode(enabled: Boolean) {
        isRegexMode = enabled
        clearMatches()
    }

    fun getRegexMode(): Boolean {
        return isRegexMode
    }

    fun find(query: String) {
        if (!isRegexMode) {
            webView.findAllAsync(query)
            return
        }

        val quotedQuery = JSONObject.quote(query)

        // Inject and execute JS for Regex finding
        val js = """
            (function() {
                // Clear previous highlights
                const existing = document.querySelectorAll('mark.onyx-regex-highlight');
                existing.forEach(el => {
                    const parent = el.parentNode;
                    if (parent) {
                        parent.replaceChild(document.createTextNode(el.textContent), el);
                        parent.normalize();
                    }
                });

                const queryStr = $quotedQuery;
                if (!queryStr) {
                    window.RegexFindBridge.reportResult(0, 0);
                    return;
                }

                let regex;
                try {
                    regex = new RegExp(queryStr, "gi");
                } catch(e) {
                    window.RegexFindBridge.reportResult(0, 0);
                    return;
                }

                let matchCount = 0;
                
                function walk(node) {
                    if (node.nodeType === 3) {
                        const text = node.nodeValue;
                        let match;
                        let lastIndex = 0;
                        const fragments = [];
                        
                        while ((match = regex.exec(text)) !== null) {
                            if (match.index === regex.lastIndex) {
                                regex.lastIndex++;
                            }
                            if (match[0].length === 0) continue;
                            
                            matchCount++;
                            
                            const before = text.substring(lastIndex, match.index);
                            if (before) {
                                fragments.push(document.createTextNode(before));
                            }
                            
                            const mark = document.createElement('mark');
                            mark.className = 'onyx-regex-highlight';
                            mark.style.backgroundColor = 'yellow';
                            mark.style.color = 'black';
                            mark.id = 'onyx-regex-match-' + matchCount;
                            mark.textContent = match[0];
                            fragments.push(mark);
                            
                            lastIndex = match.index + match[0].length;
                        }
                        
                        if (fragments.length > 0) {
                            const after = text.substring(lastIndex);
                            if (after) {
                                fragments.push(document.createTextNode(after));
                            }
                            
                            const parent = node.parentNode;
                            if (parent) {
                                fragments.forEach(frag => {
                                    parent.insertBefore(frag, node);
                                });
                                parent.removeChild(node);
                            }
                        }
                    } else if (node.nodeType === 1 && node.nodeName !== 'SCRIPT' && node.nodeName !== 'STYLE' && node.nodeName !== 'MARK') {
                        // avoid changing DOM while iterating childNodes by copying them to an array
                        const children = Array.from(node.childNodes);
                        children.forEach(child => walk(child));
                    }
                }
                
                walk(document.body);
                
                window.onyxRegexMatchCount = matchCount;
                window.onyxRegexActiveIndex = matchCount > 0 ? 1 : 0;
                
                window.onyxHighlightActive = function() {
                    const marks = document.querySelectorAll('mark.onyx-regex-highlight');
                    marks.forEach(el => {
                        el.style.backgroundColor = 'yellow';
                    });
                    
                    const active = document.getElementById('onyx-regex-match-' + window.onyxRegexActiveIndex);
                    if (active) {
                        active.style.backgroundColor = 'orange';
                        active.scrollIntoView({behavior: 'smooth', block: 'center'});
                    }
                };

                if (matchCount > 0 && typeof window.onyxHighlightActive === 'function') {
                    window.onyxHighlightActive();
                }
                window.RegexFindBridge.reportResult(window.onyxRegexActiveIndex, window.onyxRegexMatchCount);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    fun findNext(forward: Boolean) {
        if (!isRegexMode) {
            webView.findNext(forward)
            return
        }

        val js = """
            (function() {
                if (window.onyxRegexMatchCount && window.onyxRegexMatchCount > 0) {
                    if ($forward) {
                        window.onyxRegexActiveIndex++;
                        if (window.onyxRegexActiveIndex > window.onyxRegexMatchCount) {
                            window.onyxRegexActiveIndex = 1;
                        }
                    } else {
                        window.onyxRegexActiveIndex--;
                        if (window.onyxRegexActiveIndex < 1) {
                            window.onyxRegexActiveIndex = window.onyxRegexMatchCount;
                        }
                    }
                    if (typeof window.onyxHighlightActive === 'function') {
                        window.onyxHighlightActive();
                    }
                    window.RegexFindBridge.reportResult(window.onyxRegexActiveIndex, window.onyxRegexMatchCount);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
    
    fun clearMatches() {
        webView.clearMatches()
        val js = """
            (function() {
                const existing = document.querySelectorAll('mark.onyx-regex-highlight');
                existing.forEach(el => {
                    const parent = el.parentNode;
                    if (parent) {
                        parent.replaceChild(document.createTextNode(el.textContent), el);
                        parent.normalize();
                    }
                });
                window.onyxRegexMatchCount = 0;
                window.onyxRegexActiveIndex = 0;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    @JavascriptInterface
    fun reportResult(activeIndex: Int, matchCount: Int) {
        CoroutineScope(Dispatchers.Main).launch {
            onFindResult(activeIndex, matchCount)
        }
    }
}
