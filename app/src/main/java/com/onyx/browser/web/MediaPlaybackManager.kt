package com.onyx.browser.web

/**
 * Manages web media playback enhancements:
 * 1. Background Playback: Prevents streaming services (YouTube, Twitch, SoundCloud, Vimeo, etc.)
 *    from pausing when the browser is minimized, screen is locked, or another tab/app is opened.
 *    Spoofs the Page Visibility API (document.hidden / document.visibilityState) and blocks
 *    visibilitychange/blur event listeners.
 * 2. Automatic Pause Neutralizer: Prevents website scripts from invoking .pause() when the page
 *    enters the background.
 * 3. MediaSession & Playback Hook: Detects active video/audio and reports metadata/state to
 *    Android's MediaPlaybackService for the mini player lockscreen notification.
 * 4. In-page Video PiP Isolation: Finds and extracts playing video elements to enter native PiP.
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

                // 5. Intercept HTMLMediaElement.prototype.pause when app is in background
                var origPause = HTMLMediaElement.prototype.pause;
                HTMLMediaElement.prototype.pause = function() {
                    if (window.__onyx_in_background && !window.__onyx_allow_explicit_pause) {
                        return; // Disallow background auto-pause from website scripts
                    }
                    return origPause.apply(this, arguments);
                };

                // 6. Media State Reporting to Onyx Android Bridge
                function extractTitle() {
                    if (navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.title) {
                        return navigator.mediaSession.metadata.title;
                    }
                    // YouTube mobile title check
                    var yt = document.querySelector('h1.title yt-formatted-string, #title h1, .slim-video-information-title, .video-title');
                    if (yt && yt.textContent) return yt.textContent.trim();
                    return document.title || 'Web Media';
                }

                function extractArtist() {
                    if (navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.artist) {
                        return navigator.mediaSession.metadata.artist;
                    }
                    var ytChan = document.querySelector('#owner-name, ytm-channel-name, .slim-owner-channel-name');
                    if (ytChan && ytChan.textContent) return ytChan.textContent.trim();
                    return location.hostname.replace(/^www\./, '');
                }

                function reportMediaPlaying(elem) {
                    if (!window.OnyxMediaBridge) return;
                    var isVid = (elem instanceof HTMLVideoElement);
                    window.OnyxMediaBridge.onMediaPlaying(extractTitle(), extractArtist(), isVid);
                }

                function reportMediaPaused() {
                    if (!window.OnyxMediaBridge) return;
                    // Check if any other media element is still playing
                    setTimeout(function() {
                        var anyPlaying = Array.from(document.querySelectorAll('video, audio')).some(function(m) {
                            return !m.paused && !m.ended && m.readyState > 1;
                        });
                        if (!anyPlaying) {
                            window.OnyxMediaBridge.onMediaPaused();
                        }
                    }, 250);
                }

                document.addEventListener('play', function(e) {
                    if (e.target instanceof HTMLMediaElement) {
                        reportMediaPlaying(e.target);
                    }
                }, true);

                document.addEventListener('pause', function(e) {
                    if (e.target instanceof HTMLMediaElement) {
                        reportMediaPaused();
                    }
                }, true);

                document.addEventListener('ended', function(e) {
                    if (e.target instanceof HTMLMediaElement) {
                        reportMediaPaused();
                    }
                }, true);

                // Hook navigator.mediaSession metadata updates
                if (navigator.mediaSession) {
                    var origMetadataSetter = Object.getOwnPropertyDescriptor(MediaSession.prototype, 'metadata')?.set;
                    if (origMetadataSetter) {
                        Object.defineProperty(navigator.mediaSession, 'metadata', {
                            set: function(val) {
                                origMetadataSetter.call(this, val);
                                if (val && window.OnyxMediaBridge) {
                                    window.OnyxMediaBridge.onMediaMetadata(val.title || '', val.artist || '');
                                }
                            },
                            configurable: true
                        });
                    }
                }

            } catch (e) {}
        })();
    """.trimIndent()

    fun getSetBackgroundStateScript(inBackground: Boolean): String =
        "window.__onyx_in_background = $inBackground;"

    val playAllMediaScript: String = """
        (function() {
            var media = document.querySelectorAll('video, audio');
            media.forEach(function(m) {
                m.play().catch(function(){});
            });
        })();
    """.trimIndent()

    val pauseAllMediaScript: String = """
        (function() {
            window.__onyx_allow_explicit_pause = true;
            var media = document.querySelectorAll('video, audio');
            media.forEach(function(m) {
                m.pause();
            });
            window.__onyx_allow_explicit_pause = false;
        })();
    """.trimIndent()

    fun getSeekMediaScript(deltaSeconds: Int): String = """
        (function(delta) {
            var media = document.querySelectorAll('video, audio');
            media.forEach(function(m) {
                if (!m.paused && m.duration) {
                    m.currentTime = Math.max(0, Math.min(m.duration, m.currentTime + delta));
                }
            });
        })($deltaSeconds);
    """.trimIndent()

    val requestVideoFullscreenScript: String = """
        (function() {
            var vids = Array.from(document.querySelectorAll('video'));
            var playing = vids.find(function(v) { return !v.paused && !v.ended && v.readyState > 1; });
            if (!playing && vids.length > 0) playing = vids[0];
            if (playing) {
                if (playing.requestFullscreen) {
                    playing.requestFullscreen();
                } else if (playing.webkitRequestFullscreen) {
                    playing.webkitRequestFullscreen();
                }
                return true;
            }
            return false;
        })();
    """.trimIndent()
}
