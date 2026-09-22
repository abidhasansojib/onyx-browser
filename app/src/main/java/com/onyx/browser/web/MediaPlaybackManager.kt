package com.onyx.browser.web

/**
 * Manages web media playback enhancements inspired by Brave-core:
 * 1. Background Playback: Prevents streaming services (YouTube, Twitch, SoundCloud, Vimeo, Spotify, etc.)
 *    from pausing when the browser is minimized, screen is locked, or another tab/app is opened.
 *    Spoofs the Page Visibility API (document.hidden / document.visibilityState), overrides IntersectionObserver,
 *    patches YouTube ytcfg experiment flags, and blocks visibilitychange/blur/pagehide auto-pause listeners.
 * 2. Automatic Pause Neutralizer: Prevents website scripts from invoking .pause() when the page
 *    enters the background.
 * 3. MediaSession & Playback Hook: Detects active video/audio, artwork, duration, position, and video
 *    aspect ratios, reporting state to Android's MediaPlaybackService for the lockscreen mini music player.
 * 4. Video-Only PiP Isolation: Drives player into native fullscreen custom view (like Brave's kYoutubeFullscreen)
 *    with a resilient fixed 100vw/100vh CSS isolation fallback so only the video displays in PiP.
 */
object MediaPlaybackManager {

    val backgroundPlaybackScript: String = """
        (function() {
            if (window.__onyx_bg_play_active) return;
            window.__onyx_bg_play_active = true;

            try {
                // 1. Patch YouTube ytcfg serialized experiment flags (ported from Brave's kYoutubePictureInPictureSupport)
                function patchYtcfg() {
                    try {
                        if (window.ytcfg && typeof window.ytcfg.get === 'function') {
                            var config = window.ytcfg.get('WEB_PLAYER_CONTEXT_CONFIGS')?.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH;
                            if (config && config.serializedExperimentFlags && typeof config.serializedExperimentFlags === 'string') {
                                config.serializedExperimentFlags = config.serializedExperimentFlags
                                    .replace('html5_picture_in_picture_blocking_ontimeupdate=true', 'html5_picture_in_picture_blocking_ontimeupdate=false')
                                    .replace('html5_picture_in_picture_blocking_onresize=true', 'html5_picture_in_picture_blocking_onresize=false')
                                    .replace('html5_picture_in_picture_blocking_document_fullscreen=true', 'html5_picture_in_picture_blocking_document_fullscreen=false')
                                    .replace('html5_picture_in_picture_blocking_standard_api=true', 'html5_picture_in_picture_blocking_standard_api=false')
                                    .replace('html5_picture_in_picture_logging_onresize=true', 'html5_picture_in_picture_logging_onresize=false');
                            }
                        }
                    } catch (e) {}
                }
                patchYtcfg();
                document.addEventListener('DOMContentLoaded', patchYtcfg, { once: true });

                // 2. Spoof Document Page Visibility API (ported from Brave's kYoutubeBackgroundPlayback)
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

                // 3. Spoof document.hasFocus() so audio players don't pause on blur
                try {
                    document.hasFocus = function() { return true; };
                } catch (e) {}

                // 4. Intercept visibilitychange event listeners
                var origDocAddEventListener = document.addEventListener;
                document.addEventListener = function(type, listener, options) {
                    if (type === 'visibilitychange' || type === 'webkitvisibilitychange') {
                        return;
                    }
                    return origDocAddEventListener.apply(this, arguments);
                };

                var origWinAddEventListener = window.addEventListener;
                window.addEventListener = function(type, listener, options) {
                    if (type === 'visibilitychange' || type === 'webkitvisibilitychange') {
                        return;
                    }
                    return origWinAddEventListener.apply(this, arguments);
                };

                var origEventTargetAddEventListener = EventTarget.prototype.addEventListener;
                EventTarget.prototype.addEventListener = function(type, listener, options) {
                    if (type === 'visibilitychange' || type === 'webkitvisibilitychange') {
                        return;
                    }
                    return origEventTargetAddEventListener.apply(this, arguments);
                };

                // 5. Nullify document.onvisibilitychange property setter
                try {
                    Object.defineProperty(document, 'onvisibilitychange', {
                        get: function() { return null; },
                        set: function() {},
                        configurable: true
                    });
                } catch (e) {}

                // 6. Suppress pagehide and blur events that try to pause media
                window.addEventListener('pagehide', function(e) {
                    e.stopImmediatePropagation();
                }, true);

                // 7. Override IntersectionObserver for media elements so hidden/scrolled videos don't pause
                if (window.IntersectionObserver) {
                    var OrigIntersectionObserver = window.IntersectionObserver;
                    window.IntersectionObserver = function(callback, options) {
                        var wrappedCallback = function(entries, observer) {
                            var modifiedEntries = entries.map(function(entry) {
                                if (entry.target instanceof HTMLMediaElement || entry.target?.querySelector('video, audio')) {
                                    return new Proxy(entry, {
                                        get: function(target, prop) {
                                            if (prop === 'isIntersecting') return true;
                                            if (prop === 'intersectionRatio') return 1.0;
                                            return target[prop];
                                        }
                                    });
                                }
                                return entry;
                            });
                            return callback(modifiedEntries, observer);
                        };
                        return new OrigIntersectionObserver(wrappedCallback, options);
                    };
                    window.IntersectionObserver.prototype = OrigIntersectionObserver.prototype;
                }

                // 8. Intercept HTMLMediaElement.prototype.pause when app is in background
                var origPause = HTMLMediaElement.prototype.pause;
                HTMLMediaElement.prototype.pause = function() {
                    if (window.__onyx_in_background && !window.__onyx_allow_explicit_pause) {
                        return; // Disallow background auto-pause from website scripts
                    }
                    return origPause.apply(this, arguments);
                };

                // 9. Media State & Metadata Extraction
                function extractTitle() {
                    if (navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.title) {
                        return navigator.mediaSession.metadata.title;
                    }
                    var yt = document.querySelector('h1.title yt-formatted-string, #title h1, .slim-video-information-title, .video-title, ytm-slim-video-metadata-section-renderer .title');
                    if (yt && yt.textContent) return yt.textContent.trim();
                    return document.title || 'Web Media';
                }

                function extractArtist() {
                    if (navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.artist) {
                        return navigator.mediaSession.metadata.artist;
                    }
                    var ytChan = document.querySelector('#owner-name, ytm-channel-name, .slim-owner-channel-name, ytm-badge-and-byline-renderer .byline-title');
                    if (ytChan && ytChan.textContent) return ytChan.textContent.trim();
                    return location.hostname.replace(/^www\./, '');
                }

                function extractArtwork(elem) {
                    if (navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.artwork && navigator.mediaSession.metadata.artwork.length > 0) {
                        var arts = navigator.mediaSession.metadata.artwork;
                        return arts[arts.length - 1].src || arts[0].src || '';
                    }
                    if (elem instanceof HTMLVideoElement && elem.poster) {
                        return elem.poster;
                    }
                    var ogImg = document.querySelector('meta[property="og:image"]');
                    if (ogImg && ogImg.content) return ogImg.content;
                    return '';
                }

                var lastReportedTime = 0;

                function reportMediaPlaying(elem) {
                    if (!window.OnyxMediaBridge) return;
                    var isVid = (elem instanceof HTMLVideoElement);
                    var dur = (elem.duration && !isNaN(elem.duration)) ? elem.duration : 0;
                    var pos = (elem.currentTime && !isNaN(elem.currentTime)) ? elem.currentTime : 0;
                    var w = isVid ? (elem.videoWidth || 0) : 0;
                    var h = isVid ? (elem.videoHeight || 0) : 0;
                    window.OnyxMediaBridge.onMediaStateChanged(
                        extractTitle(),
                        extractArtist(),
                        extractArtwork(elem),
                        true,
                        pos,
                        dur,
                        isVid,
                        w,
                        h
                    );
                }

                function reportMediaProgress(elem) {
                    if (!window.OnyxMediaBridge) return;
                    var now = Date.now();
                    if (now - lastReportedTime < 1000) return; // Throttle to 1s
                    lastReportedTime = now;
                    var dur = (elem.duration && !isNaN(elem.duration)) ? elem.duration : 0;
                    var pos = (elem.currentTime && !isNaN(elem.currentTime)) ? elem.currentTime : 0;
                    window.OnyxMediaBridge.onMediaProgress(pos, dur);
                }

                function reportMediaPaused() {
                    if (!window.OnyxMediaBridge) return;
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

                document.addEventListener('timeupdate', function(e) {
                    if (e.target instanceof HTMLMediaElement && !e.target.paused) {
                        reportMediaProgress(e.target);
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
                                    var art = (val.artwork && val.artwork.length > 0) ? val.artwork[val.artwork.length - 1].src : '';
                                    window.OnyxMediaBridge.onMediaMetadata(val.title || '', val.artist || '', art);
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

    fun getSeekToPositionScript(positionSeconds: Double): String = """
        (function(pos) {
            var media = document.querySelectorAll('video, audio');
            media.forEach(function(m) {
                if (m.duration && !isNaN(m.duration)) {
                    m.currentTime = Math.max(0, Math.min(m.duration, pos));
                }
            });
        })($positionSeconds);
    """.trimIndent()

    /**
     * Drives the active video player into native fullscreen, ported from Brave's kYoutubeFullscreen
     * in youtube_script_injector_tab_helper.cc.
     * When native fullscreen is reached, Android WebView invokes onShowCustomView(), isolating the
     * video decode surface so the PiP window contains 100% video without browser chrome.
     */
    val requestVideoFullscreenScript: String = """
        (function() {
            var vids = Array.from(document.querySelectorAll('video'));
            var playing = vids.find(function(v) { return !v.paused && !v.ended && v.readyState > 1; });
            if (!playing && vids.length > 0) playing = vids[0];

            // 1. YouTube mobile fullscreen button
            var ytBtn = document.querySelector('button.fullscreen-icon, button.ytp-fullscreen-button, .ytp-fullscreen-button');
            if (ytBtn) {
                try {
                    if (playing && playing.readyState >= 3) playing.click();
                    ytBtn.click();
                    return 'fullscreen_triggered';
                } catch (e) {}
            }

            // 2. YouTube player object toggleFullscreen
            var ytPlayer = document.querySelector('#movie_player, .html5-video-player');
            if (ytPlayer && typeof ytPlayer.toggleFullscreen === 'function' && !ytPlayer.classList.contains('ytp-fullscreen')) {
                try {
                    ytPlayer.toggleFullscreen();
                    return 'fullscreen_triggered';
                } catch (e) {}
            }

            // 3. Native Element requestFullscreen
            var target = ytPlayer || document.querySelector('#player-container-id, ytm-player, #player') || playing;
            if (target && target.requestFullscreen) {
                try {
                    target.requestFullscreen();
                    return 'fullscreen_triggered';
                } catch (e) {}
            } else if (target && target.webkitRequestFullscreen) {
                try {
                    target.webkitRequestFullscreen();
                    return 'fullscreen_triggered';
                } catch (e) {}
            }

            return playing ? 'found_inline' : 'not_found';
        })();
    """.trimIndent()

    /**
     * In-Page CSS Isolation: Fixes the active video element to 100vw x 100vh with black background
     * and z-index 2147483647 as a resilient fallback if native custom view is unavailable.
     */
    val isolateVideoForPipScript: String = """
        (function() {
            try {
                var styleId = '__onyx_pip_style';
                var existingStyle = document.getElementById(styleId);
                if (!existingStyle) {
                    var style = document.createElement('style');
                    style.id = styleId;
                    style.textContent = `
                        html.__onyx_pip_active, body.__onyx_pip_active {
                            overflow: hidden !important;
                            background: #000000 !important;
                        }
                        video.__onyx_pip_video {
                            position: fixed !important;
                            top: 0 !important;
                            left: 0 !important;
                            width: 100vw !important;
                            height: 100vh !important;
                            z-index: 2147483647 !important;
                            background: #000000 !important;
                            object-fit: contain !important;
                            margin: 0 !important;
                            padding: 0 !important;
                        }
                    `;
                    document.head.appendChild(style);
                }

                document.documentElement.classList.add('__onyx_pip_active');
                document.body?.classList.add('__onyx_pip_active');

                var vids = Array.from(document.querySelectorAll('video'));
                var activeVid = vids.find(function(v) { return !v.paused && !v.ended; }) || vids[0];
                if (activeVid) {
                    activeVid.classList.add('__onyx_pip_video');
                    return JSON.stringify({
                        width: activeVid.videoWidth || activeVid.clientWidth || 16,
                        height: activeVid.videoHeight || activeVid.clientHeight || 9
                    });
                }
            } catch (e) {}
            return JSON.stringify({ width: 16, height: 9 });
        })();
    """.trimIndent()

    /**
     * Restores normal in-page layout when exiting PiP mode.
     */
    val restoreVideoFromPipScript: String = """
        (function() {
            try {
                document.documentElement.classList.remove('__onyx_pip_active');
                document.body?.classList.remove('__onyx_pip_active');
                document.querySelectorAll('video.__onyx_pip_video').forEach(function(v) {
                    v.classList.remove('__onyx_pip_video');
                });
                var style = document.getElementById('__onyx_pip_style');
                if (style) style.remove();
            } catch (e) {}
        })();
    """.trimIndent()
}
