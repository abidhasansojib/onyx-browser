package com.onyx.browser.web

/**
 * Manages web media playback enhancements:
 * 1. Background Playback: Prevents streaming services (YouTube, Twitch, SoundCloud, Vimeo, etc.)
 *    from pausing when the browser is minimized, screen is locked, or another tab/app is opened.
 *    Spoofs the Page Visibility API (document.hidden / document.visibilityState) and blocks
 *    visibilitychange event listeners.
 * 2. Media Keep-Alive: Keeps audio streaming pipes active in the background.
 */
object MediaPlaybackManager {

    val backgroundPlaybackScript: String = """
        (function() {
            if (window.__onyx_bg_play_active) return;
            window.__onyx_bg_play_active = true;

            try {
                // 1. Spoof Document Page Visibility API
                function patchVisibility(target) {
                    try {
                        Object.defineProperty(target, 'hidden', {
                            get: function() { return false; },
                            configurable: true
                        });
                        Object.defineProperty(target, 'visibilityState', {
                            get: function() { return 'visible'; },
                            configurable: true
                        });
                        Object.defineProperty(target, 'webkitHidden', {
                            get: function() { return false; },
                            configurable: true
                        });
                        Object.defineProperty(target, 'webkitVisibilityState', {
                            get: function() { return 'visible'; },
                            configurable: true
                        });
                    } catch (e) {}
                }

                patchVisibility(document);
                if (typeof Document !== 'undefined' && Document.prototype) {
                    patchVisibility(Document.prototype);
                }

                // 2. Intercept visibilitychange event listeners so web players cannot detect background state
                var origAddEventListener = EventTarget.prototype.addEventListener;
                EventTarget.prototype.addEventListener = function(type, listener, options) {
                    if (type === 'visibilitychange' || type === 'webkitvisibilitychange') {
                        return; // Prevent websites from registering visibilitychange pause hooks
                    }
                    return origAddEventListener.apply(this, arguments);
                };

                // 3. Nullify document.onvisibilitychange property setter
                try {
                    Object.defineProperty(document, 'onvisibilitychange', {
                        get: function() { return null; },
                        set: function() {},
                        configurable: true
                    });
                } catch (e) {}

                // 4. Suppress blur and pagehide handlers that try to pause media
                window.addEventListener('pagehide', function(e) {
                    e.stopImmediatePropagation();
                }, true);

            } catch (e) {}
        })();
    """.trimIndent()
}
