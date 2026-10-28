package com.onyx.browser.web

import android.webkit.JavascriptInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Captured HTML form POST submission. Android WebView's DownloadListener only reports the
 * response URL, so a download produced by a POST form (common "Download" buttons on file hosts)
 * would otherwise be re-requested with GET and fail. The body captured here lets the download
 * engine replay the original POST, matching Chrome/Brave behavior.
 */
data class FormPost(
    val url: String,
    val body: String,
    val contentType: String,
    val pageUrl: String,
    val timestamp: Long = System.currentTimeMillis()
)

object FormPostRegistry {
    private const val RECENT_TTL_MS = 60_000L
    private const val DOWNLOAD_TTL_MS = 15 * 60_000L
    private const val MAX_ENTRIES = 32
    private const val MAX_BODY_CHARS = 2 * 1024 * 1024

    private val recent = ConcurrentHashMap<String, FormPost>()
    private val claimed = ConcurrentHashMap<String, FormPost>()

    private fun key(url: String): String = url.substringBefore('#').trimEnd('/').trim()

    fun record(post: FormPost) {
        if (post.body.length > MAX_BODY_CHARS) return
        prune()
        recent[key(post.url)] = post
    }

    /**
     * Called from the DownloadListener. If the download URL matches a POST submitted in the last
     * minute, moves it to the claimed set so the (possibly user-confirmed) download can replay it.
     */
    fun claimForDownload(url: String): FormPost? {
        prune()
        val k = key(url)
        val post = recent.remove(k)
            ?: recent.keys.firstOrNull { key(it) == k }?.let { recent.remove(it) }
            ?: return null
        claimed[k] = post.copy(timestamp = System.currentTimeMillis())
        return post
    }

    fun hasClaimed(url: String): Boolean {
        prune()
        val k = key(url)
        return claimed.containsKey(k) || claimed.keys.any { key(it) == k }
    }

    fun takeClaimed(url: String): FormPost? {
        prune()
        val k = key(url)
        val post = claimed.remove(k)
            ?: claimed.keys.firstOrNull { key(it) == k }?.let { claimed.remove(it) }
        return post
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        recent.entries.removeIf { now - it.value.timestamp > RECENT_TTL_MS }
        claimed.entries.removeIf { now - it.value.timestamp > DOWNLOAD_TTL_MS }
        if (recent.size > MAX_ENTRIES) {
            recent.entries.sortedBy { it.value.timestamp }
                .take(recent.size - MAX_ENTRIES)
                .forEach { recent.remove(it.key) }
        }
    }
}

/**
 * JS bridge that receives serialized POST form submissions from [SUBMIT_HOOK_SCRIPT].
 */
class OnyxFormBridge {
    @JavascriptInterface
    fun recordPost(action: String, contentType: String, body: String, pageUrl: String) {
        if (!(action.startsWith("http://", ignoreCase = true) || action.startsWith("https://", ignoreCase = true))) return
        FormPostRegistry.record(
            FormPost(
                url = action,
                body = body,
                contentType = contentType.ifBlank { "application/x-www-form-urlencoded" },
                pageUrl = pageUrl
            )
        )
    }

    companion object {
        const val INTERFACE_NAME = "OnyxFormBridge"

        /**
         * Hooks submit events, HTMLFormElement.submit() and requestSubmit() in every frame and
         * reports POST bodies to [OnyxFormBridge]. Never alters or cancels the submission.
         */
        val SUBMIT_HOOK_SCRIPT = """
        (function() {
            try {
                if (window.__onyxFormHooked) return;
                window.__onyxFormHooked = true;
                var bridge = window.OnyxFormBridge;
                if (!bridge || typeof bridge.recordPost !== 'function') return;

                function attr(el, name) {
                    try { return el && el.hasAttribute && el.hasAttribute(name) ? (el.getAttribute(name) || '') : null; } catch (_) { return null; }
                }

                function capture(form, submitter) {
                    try {
                        if (!form || form.tagName !== 'FORM') return;
                        if (form.querySelector && form.querySelector('input[type="password"]')) return;
                        var method = (attr(submitter, 'formmethod') || form.getAttribute('method') || form.method || 'get').toLowerCase();
                        if (method !== 'post') return;
                        var action = attr(submitter, 'formaction');
                        action = action !== null ? new URL(action || location.href, document.baseURI).href : (form.action || location.href);
                        if (!/^https?:/i.test(action)) return;
                        var enctype = (attr(submitter, 'formenctype') || form.getAttribute('enctype') || form.enctype || 'application/x-www-form-urlencoded').toLowerCase();

                        var fd;
                        try { fd = submitter ? new FormData(form, submitter) : new FormData(form); } catch (_) { fd = new FormData(form); }
                        if (submitter && submitter.name && !fd.has(submitter.name)) {
                            try { fd.append(submitter.name, submitter.value || ''); } catch (_) {}
                        }

                        var entries = [];
                        var hasFile = false;
                        fd.forEach(function(value, name) {
                            if (typeof value !== 'string') {
                                if (value && value.size > 0) hasFile = true;
                                entries.push([name, value]);
                            } else {
                                entries.push([name, value]);
                            }
                        });
                        if (hasFile) return; // File uploads cannot be replayed

                        var body = '';
                        var contentType = enctype;
                        if (enctype === 'multipart/form-data') {
                            var boundary = '----OnyxFormBoundary' + Math.random().toString(36).slice(2) + Date.now().toString(36);
                            contentType = 'multipart/form-data; boundary=' + boundary;
                            for (var i = 0; i < entries.length; i++) {
                                var n = String(entries[i][0]).replace(/"/g, '%22').replace(/\r?\n/g, ' ');
                                var v = entries[i][1];
                                body += '--' + boundary + '\r\n';
                                if (typeof v === 'string') {
                                    body += 'Content-Disposition: form-data; name="' + n + '"\r\n\r\n' + v + '\r\n';
                                } else {
                                    body += 'Content-Disposition: form-data; name="' + n + '"; filename=""\r\n' +
                                            'Content-Type: application/octet-stream\r\n\r\n\r\n';
                                }
                            }
                            body += '--' + boundary + '--\r\n';
                        } else if (enctype === 'text/plain') {
                            body = entries.map(function(e) { return e[0] + '=' + (typeof e[1] === 'string' ? e[1] : ''); }).join('\r\n');
                        } else {
                            contentType = 'application/x-www-form-urlencoded';
                            var params = new URLSearchParams();
                            entries.forEach(function(e) { params.append(e[0], typeof e[1] === 'string' ? e[1] : ''); });
                            body = params.toString();
                        }
                        bridge.recordPost(action, contentType, body, location.href);
                    } catch (_) {}
                }

                // Bubble-phase listener registered first; record only submissions the page did not cancel.
                window.addEventListener('submit', function(e) {
                    if (e.defaultPrevented) return;
                    capture(e.target, e.submitter || null);
                }, false);

                var proto = window.HTMLFormElement && HTMLFormElement.prototype;
                if (proto && proto.submit) {
                    var origSubmit = proto.submit;
                    proto.submit = function() {
                        capture(this, null);
                        return origSubmit.apply(this, arguments);
                    };
                    try {
                        Object.defineProperty(proto.submit, 'toString', { value: function() { return 'function submit() { [native code] }'; } });
                    } catch (_) {}
                }
            } catch (_) {}
        })();
        """.trimIndent()
    }
}
