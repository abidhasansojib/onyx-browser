package com.onyx.browser.web

/**
 * Manages web media playback enhancements for Android System WebView:
 * 1. Background Playback: Prevents streaming services (YouTube, Twitch, SoundCloud, Vimeo, Spotify, etc.)
 *    from pausing when the browser is minimized, screen is locked, or another app is opened.
 *    Spoofs the Page Visibility API (document.hidden / document.visibilityState), overrides IntersectionObserver,
 *    patches YouTube ytcfg experiment flags, and blocks visibilitychange/blur/focusout/pagehide auto-pause listeners.
 * 2. Automatic Pause Neutralizer: Prevents website scripts from invoking .pause() when the page
 *    enters the background or loses window focus.
 * 3. MediaSession & Playback Hook: Detects active video/audio, artwork, duration, position, and video
 *    aspect ratios, reporting state to Android's MediaPlaybackService for the lockscreen mini music player.
 * 4. Video-Only PiP Isolation: True DOM isolation that hides all non-video elements, breaks ancestor
 *    containing block traps (transforms, containment, clipping), and forces the active video to occupy
 *    100% of the PiP viewport with black letterboxing.
 */
object MediaPlaybackManager {

    val backgroundPlaybackScript: String = """
        (function() {
            if (window.__onyx_bg_play_active) return;
            window.__onyx_bg_play_active = true;

            try {
                // 1. Patch YouTube ytcfg serialized experiment flags
                function patchYtcfg() {
                    try {
                        if (window.ytcfg && typeof window.ytcfg.get === 'function') {
                            var configs = ['WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH', 'WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH'];
                            configs.forEach(function(key) {
                                var config = window.ytcfg.get(key) || window.ytcfg.get('WEB_PLAYER_CONTEXT_CONFIGS')?.[key];
                                if (config && config.serializedExperimentFlags && typeof config.serializedExperimentFlags === 'string') {
                                    config.serializedExperimentFlags = config.serializedExperimentFlags
                                        .replace(/html5_picture_in_picture_blocking_\w+=true/g, function(m) {
                                            return m.replace('=true', '=false');
                                        });
                                }
                            });
                            if (window.ytcfg.set) {
                                window.ytcfg.set({
                                    'html5_picture_in_picture_blocking_web': false,
                                    'html5_picture_in_picture_blocking_android': false,
                                    'html5_picture_in_picture_blocking_standard_api': false
                                });
                            }
                        }
                    } catch (e) {}
                }
                patchYtcfg();
                document.addEventListener('DOMContentLoaded', patchYtcfg, { once: true });

                // 2. Spoof Document Page Visibility API
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

                // 3. Spoof document.hasFocus() so audio players don't pause on blur/unfocus
                try {
                    document.hasFocus = function() { return true; };
                    if (typeof Document !== 'undefined' && Document.prototype) {
                        Document.prototype.hasFocus = function() { return true; };
                    }
                } catch (e) {}

                // 4. Capture-phase interception of visibilitychange, blur, focusout, and pagehide
                var stopProp = function(e) {
                    e.stopImmediatePropagation();
                };
                window.addEventListener('visibilitychange', stopProp, true);
                document.addEventListener('visibilitychange', stopProp, true);
                window.addEventListener('webkitvisibilitychange', stopProp, true);
                document.addEventListener('webkitvisibilitychange', stopProp, true);
                window.addEventListener('blur', stopProp, true);
                window.addEventListener('focusout', stopProp, true);
                window.addEventListener('pagehide', stopProp, true);

                // Intercept future event listener registrations for visibilitychange
                var origDocAdd = document.addEventListener;
                document.addEventListener = function(type, listener, options) {
                    if (type === 'visibilitychange' || type === 'webkitvisibilitychange') {
                        return;
                    }
                    return origDocAdd.apply(this, arguments);
                };
                var origWinAdd = window.addEventListener;
                window.addEventListener = function(type, listener, options) {
                    if (type === 'visibilitychange' || type === 'webkitvisibilitychange') {
                        return;
                    }
                    return origWinAdd.apply(this, arguments);
                };
                var origTargetAdd = EventTarget.prototype.addEventListener;
                EventTarget.prototype.addEventListener = function(type, listener, options) {
                    if (type === 'visibilitychange' || type === 'webkitvisibilitychange') {
                        return;
                    }
                    return origTargetAdd.apply(this, arguments);
                };

                // 5. Nullify document.onvisibilitychange property setter
                try {
                    Object.defineProperty(document, 'onvisibilitychange', {
                        get: function() { return null; },
                        set: function() {},
                        configurable: true
                    });
                } catch (e) {}

                // 6. Override IntersectionObserver for media elements
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

                // 7. Intercept HTMLMediaElement.prototype.pause
                var origPause = HTMLMediaElement.prototype.pause;
                HTMLMediaElement.prototype.pause = function() {
                    // Allow if explicit user pause from notification or user controls
                    if (window.__onyx_allow_explicit_pause) {
                        return origPause.apply(this, arguments);
                    }
                    // Block automatic pause if the browser is reported in background or document hidden
                    if (window.__onyx_in_background || document.hidden || document.visibilityState === 'hidden') {
                        return;
                    }
                    try {
                        var err = new Error();
                        if (err.stack && (
                            err.stack.indexOf('visibilitychange') !== -1 ||
                            err.stack.indexOf('onblur') !== -1 ||
                            err.stack.indexOf('onHidden') !== -1 ||
                            err.stack.indexOf('pagehide') !== -1
                        )) {
                            return;
                        }
                    } catch (_) {}
                    return origPause.apply(this, arguments);
                };

                // 8. Media State & Metadata Extraction
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

                function reportVideoBounds(elem) {
                    try {
                        if (elem instanceof HTMLVideoElement && window.OnyxMediaBridge && typeof window.OnyxMediaBridge.onVideoBoundsChanged === 'function') {
                            var r = elem.getBoundingClientRect();
                            window.OnyxMediaBridge.onVideoBoundsChanged(r.left, r.top, r.right, r.bottom);
                        }
                    } catch (_) {}
                }

                // Prevent website scripts from muting media while browser is in background
                try {
                    var origMutedDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'muted');
                    if (origMutedDesc && origMutedDesc.set) {
                        Object.defineProperty(HTMLMediaElement.prototype, 'muted', {
                            get: function() {
                                return origMutedDesc.get.call(this);
                            },
                            set: function(val) {
                                if (val && (window.__onyx_in_background || document.hidden || document.visibilityState === 'hidden')) {
                                    return;
                                }
                                return origMutedDesc.set.call(this, val);
                            },
                            configurable: true
                        });
                    }
                } catch (_) {}

                // W3C Picture-in-Picture Web API Polyfill for web video players
                try {
                    var currentPipElem = null;

                    if (!document.pictureInPictureEnabled) {
                        Object.defineProperty(document, 'pictureInPictureEnabled', {
                            get: function() { return true; },
                            configurable: true
                        });
                    }

                    Object.defineProperty(document, 'pictureInPictureElement', {
                        get: function() { return currentPipElem; },
                        configurable: true
                    });

                    document.exitPictureInPicture = function() {
                        return new Promise(function(resolve, reject) {
                            if (!currentPipElem) {
                                reject(new DOMException("No active Picture-in-Picture element", "InvalidStateError"));
                                return;
                            }
                            var old = currentPipElem;
                            currentPipElem = null;
                            if (window.OnyxMediaBridge && typeof window.OnyxMediaBridge.exitVideoPip === 'function') {
                                window.OnyxMediaBridge.exitVideoPip();
                            }
                            try {
                                old.dispatchEvent(new Event('leavepictureinpicture', { bubbles: true }));
                            } catch (_) {}
                            resolve();
                        });
                    };

                    if (typeof HTMLVideoElement !== 'undefined' && HTMLVideoElement.prototype) {
                        HTMLVideoElement.prototype.requestPictureInPicture = function() {
                            var self = this;
                            return new Promise(function(resolve, reject) {
                                if (self.readyState === 0) {
                                    reject(new DOMException("Video is not ready", "InvalidStateError"));
                                    return;
                                }
                                currentPipElem = self;
                                reportVideoBounds(self);

                                if (window.OnyxMediaBridge && typeof window.OnyxMediaBridge.requestVideoPip === 'function') {
                                    window.OnyxMediaBridge.requestVideoPip();
                                }

                                try {
                                    self.dispatchEvent(new Event('enterpictureinpicture', { bubbles: true }));
                                } catch (_) {}

                                resolve({
                                    width: self.videoWidth || self.clientWidth || 320,
                                    height: self.videoHeight || self.clientHeight || 180,
                                    onresize: null
                                });
                            });
                        };
                    }
                } catch (_) {}

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
                    if (isVid) reportVideoBounds(elem);
                }

                function reportMediaProgress(elem) {
                    if (!window.OnyxMediaBridge) return;
                    var now = Date.now();
                    if (now - lastReportedTime < 1000) return;
                    lastReportedTime = now;
                    var dur = (elem.duration && !isNaN(elem.duration)) ? elem.duration : 0;
                    var pos = (elem.currentTime && !isNaN(elem.currentTime)) ? elem.currentTime : 0;
                    window.OnyxMediaBridge.onMediaProgress(pos, dur);
                    if (elem instanceof HTMLVideoElement) reportVideoBounds(elem);
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
                    }, 300);
                }

                document.addEventListener('play', function(e) {
                    if (e.target instanceof HTMLMediaElement) {
                        reportMediaPlaying(e.target);
                    }
                }, true);

                document.addEventListener('playing', function(e) {
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

                // Check currently playing media right now in case script injected after play started
                var currentlyPlaying = Array.from(document.querySelectorAll('video, audio')).find(function(m) {
                    return !m.paused && !m.ended && m.readyState > 1;
                });
                if (currentlyPlaying) {
                    reportMediaPlaying(currentlyPlaying);
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
            setTimeout(function() {
                window.__onyx_allow_explicit_pause = false;
            }, 500);
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
     * Drives the active video player into native fullscreen.
     */
    val requestVideoFullscreenScript: String = """
        (function() {
            function getAllVideos(root) {
                var res = [];
                try {
                    var vids = root.querySelectorAll('video');
                    res = res.concat(Array.from(vids));
                    var all = root.querySelectorAll('*');
                    for (var i = 0; i < all.length; i++) {
                        if (all[i].shadowRoot) {
                            res = res.concat(getAllVideos(all[i].shadowRoot));
                        }
                    }
                } catch (_) {}
                return res;
            }

            var vids = getAllVideos(document);
            document.querySelectorAll('iframe').forEach(function(f) {
                try {
                    if (f.contentDocument) {
                        vids = vids.concat(getAllVideos(f.contentDocument));
                    }
                } catch (_) {}
            });

            var playing = vids.find(function(v) { return !v.paused && !v.ended && v.readyState > 1; })
                || vids.find(function(v) { return !v.paused; })
                || (vids.length > 0 ? vids[0] : null);

            // 1. YouTube mobile / web fullscreen button
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

            // 3. Direct video webkitRequestFullscreen (standard for WebChromeClient.onShowCustomView in WebView)
            if (playing) {
                try {
                    if (typeof playing.webkitRequestFullscreen === 'function') {
                        playing.webkitRequestFullscreen();
                        return 'fullscreen_triggered';
                    } else if (typeof playing.requestFullscreen === 'function') {
                        playing.requestFullscreen();
                        return 'fullscreen_triggered';
                    } else if (typeof playing.webkitEnterFullscreen === 'function') {
                        playing.webkitEnterFullscreen();
                        return 'fullscreen_triggered';
                    }
                } catch (e) {}
            }

            // 4. Native Element requestFullscreen on player container
            var target = ytPlayer || document.querySelector('#player-container-id, ytm-player, #player');
            if (target) {
                try {
                    if (typeof target.webkitRequestFullscreen === 'function') {
                        target.webkitRequestFullscreen();
                        return 'fullscreen_triggered';
                    } else if (typeof target.requestFullscreen === 'function') {
                        target.requestFullscreen();
                        return 'fullscreen_triggered';
                    }
                } catch (e) {}
            }

            return playing ? 'found_inline' : 'not_found';
        })();
    """.trimIndent()

    /**
     * True In-Page DOM Video Isolation for Picture-in-Picture:
     * 1. Finds the active video element.
     * 2. Walks up the ancestor tree and marks every ancestor node.
     * 3. Sets display: none !important on ALL other elements in the body and ancestor branches.
     * 4. Eliminates CSS layout constraints (transform, contain, clip-path, overflow) on all ancestors,
     *    breaking containing block traps.
     * 5. Forces the video to occupy 100vw x 100vh with black letterboxing (object-fit: contain)
     *    at z-index 2147483647.
     * 6. Hides YouTube overlay controls, watermark, header bars, and descriptions.
     */
    val isolateVideoForPipScript: String = """
        (function() {
            try {
                function getAllVideos(root) {
                    var res = [];
                    try {
                        var vids = root.querySelectorAll('video');
                        res = res.concat(Array.from(vids));
                        var all = root.querySelectorAll('*');
                        for (var i = 0; i < all.length; i++) {
                            if (all[i].shadowRoot) {
                                res = res.concat(getAllVideos(all[i].shadowRoot));
                            }
                        }
                    } catch (_) {}
                    return res;
                }

                var vids = getAllVideos(document);
                // Also scan accessible same-origin iframes
                document.querySelectorAll('iframe').forEach(function(f) {
                    try {
                        if (f.contentDocument) {
                            vids = vids.concat(getAllVideos(f.contentDocument));
                        }
                    } catch (_) {}
                });

                var activeVid = vids.find(function(v) { return !v.paused && !v.ended && v.readyState > 1; }) 
                    || vids.find(function(v) { return !v.paused; }) 
                    || vids[0];
                if (!activeVid) return JSON.stringify({ width: 16, height: 9 });

                var oldStyle = document.getElementById('__onyx_pip_style');
                if (oldStyle) oldStyle.remove();

                // 1. Tag the target video
                activeVid.setAttribute('data-onyx-pip-target', 'true');

                // 2. Tag every ancestor of activeVid up to html
                var ancestor = activeVid.parentElement;
                while (ancestor && ancestor !== document.documentElement) {
                    ancestor.setAttribute('data-onyx-pip-ancestor', 'true');
                    ancestor = ancestor.parentElement;
                }

                // 3. Inject isolation stylesheet
                var style = document.createElement('style');
                style.id = '__onyx_pip_style';
                style.textContent = `
                    html, body {
                        overflow: hidden !important;
                        background: #000000 !important;
                        margin: 0 !important;
                        padding: 0 !important;
                        width: 100% !important;
                        height: 100% !important;
                    }
                    /* Hide everything in the body that is not in the ancestor path to the video */
                    body > *:not([data-onyx-pip-ancestor]):not([data-onyx-pip-target]) {
                        display: none !important;
                    }
                    [data-onyx-pip-ancestor] > *:not([data-onyx-pip-ancestor]):not([data-onyx-pip-target]) {
                        display: none !important;
                    }
                    /* Eliminate containing blocks, transforms, clipping, and overflow on ancestors */
                    [data-onyx-pip-ancestor] {
                        transform: none !important;
                        contain: none !important;
                        filter: none !important;
                        clip-path: none !important;
                        perspective: none !important;
                        overflow: visible !important;
                        background: transparent !important;
                    }
                    /* Make the video element fill 100% of the PiP viewport with black letterboxing */
                    video[data-onyx-pip-target] {
                        display: block !important;
                        position: fixed !important;
                        top: 0 !important;
                        left: 0 !important;
                        width: 100vw !important;
                        height: 100vh !important;
                        max-width: 100vw !important;
                        max-height: 100vh !important;
                        z-index: 2147483647 !important;
                        background: #000000 !important;
                        object-fit: contain !important;
                        margin: 0 !important;
                        padding: 0 !important;
                        border: none !important;
                        box-shadow: none !important;
                    }
                    /* Hide website navigation, overlays, player chrome, and watermarks */
                    ytm-mobile-topbar-renderer, ytm-pivot-bar-renderer, #header-bar,
                    .ytp-chrome-top, .ytp-chrome-bottom, .ytp-gradient-top, .ytp-gradient-bottom,
                    .ytp-watermark, .ytp-pause-overlay, ytm-player-control-overlay,
                    .ytm-player-control-overlay, ytm-player-overlay-renderer,
                    .video-annotations, .ytp-ce-element, .ytp-title,
                    .vjs-control-bar, .jw-controls, .plyr__controls,
                    header, nav, footer, aside, .header, .navbar {
                        display: none !important;
                    }
                `;
                document.head.appendChild(style);

                var vidWidth = activeVid.videoWidth || activeVid.clientWidth || 16;
                var vidHeight = activeVid.videoHeight || activeVid.clientHeight || 9;
                return JSON.stringify({ width: vidWidth, height: vidHeight });
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
                var style = document.getElementById('__onyx_pip_style');
                if (style) style.remove();
                document.querySelectorAll('[data-onyx-pip-target]').forEach(function(el) {
                    el.removeAttribute('data-onyx-pip-target');
                });
                document.querySelectorAll('[data-onyx-pip-ancestor]').forEach(function(el) {
                    el.removeAttribute('data-onyx-pip-ancestor');
                });
                document.querySelectorAll('iframe').forEach(function(f) {
                    try {
                        if (f.contentDocument) {
                            var innerStyle = f.contentDocument.getElementById('__onyx_pip_style');
                            if (innerStyle) innerStyle.remove();
                            f.contentDocument.querySelectorAll('[data-onyx-pip-target]').forEach(function(el) {
                                el.removeAttribute('data-onyx-pip-target');
                            });
                            f.contentDocument.querySelectorAll('[data-onyx-pip-ancestor]').forEach(function(el) {
                                el.removeAttribute('data-onyx-pip-ancestor');
                            });
                        }
                    } catch (_) {}
                });
            } catch (e) {}
        })();
    """.trimIndent()
}
