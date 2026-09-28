package com.onyx.browser.web

/**
 * Production Media & Video Engine for Onyx Browser.
 *
 * Implements Brave-parity media detection, background video playback keep-alive,
 * W3C Picture-in-Picture polyfill, and streaming player API synchronization.
 *
 * Modeled after Brave's core media architecture:
 * - PlaylistScript.js (property descriptor interception for instant video detection)
 * - MediaBackgroundingScript.js / media_backgrounding.ts (userHitPause, visibility spoofing, auto-resume)
 * - youtube_script_injector_tab_helper.cc (kYoutubeBackgroundPlayback, kYoutubePictureInPictureSupport, kYoutubeFullscreenVideoFitWorkaround)
 */
object MediaPlaybackManager {

    /**
     * Media monitor script — ALWAYS injected at document-start into all frames.
     * Provides:
     * 1. Instant video detection via HTMLMediaElement.src descriptor & setAttribute hooks.
     * 2. MutationObserver scanning for newly added videos across light and shadow DOM.
     * 3. Direct event binding (play, playing, pause, timeupdate, volumechange, ended, loadedmetadata).
     * 4. W3C Picture-in-Picture Web API polyfill.
     * 5. Video bounds reporting for accurate Android PiP sourceRectHint.
     * 6. Universal cross-frame message bus for nested iframes.
     */
    val mediaMonitorScript: String = """
        (function() {
            if (window.__onyx_monitor_active) return;
            window.__onyx_monitor_active = true;

            try {
                // ── 1. W3C Picture-in-Picture Web API Polyfill ─────────────────────────
                var currentPipElem = null;
                if (!document.pictureInPictureEnabled) {
                    try {
                        Object.defineProperty(document, 'pictureInPictureEnabled', {
                            get: function() { return true; },
                            configurable: true
                        });
                    } catch (_) {}
                }

                try {
                    Object.defineProperty(document, 'pictureInPictureElement', {
                        get: function() { return currentPipElem; },
                        configurable: true
                    });
                } catch (_) {}

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
                            if (self.readyState === 0 && !self.src && !self.currentSrc && !self.srcObject) {
                                reject(new DOMException("Video is not ready", "InvalidStateError"));
                                return;
                            }
                            currentPipElem = self;
                            window.__onyx_current_pip_element = self;
                            reportVideoBounds(self);

                            if (window.OnyxMediaBridge && typeof window.OnyxMediaBridge.requestVideoPip === 'function') {
                                window.OnyxMediaBridge.requestVideoPip();
                            }

                            if (window.top && window.top !== window) {
                                try {
                                    window.top.postMessage({
                                        __onyx_cmd: 'pip_request',
                                        width: self.videoWidth || self.clientWidth || 320,
                                        height: self.videoHeight || self.clientHeight || 180
                                    }, '*');
                                } catch (_) {}
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

                // ── 2. Metadata Extraction ──────────────────────────────────────────
                function extractTitle() {
                    try {
                        if (navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.title) {
                            return navigator.mediaSession.metadata.title;
                        }
                        var yt = document.querySelector('h1.title yt-formatted-string, #title h1, .slim-video-information-title, .video-title, ytm-slim-video-metadata-section-renderer .title');
                        if (yt && yt.textContent) return yt.textContent.trim();
                        return document.title || 'Web Media';
                    } catch (_) { return document.title || 'Web Media'; }
                }

                function extractArtist() {
                    try {
                        if (navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.artist) {
                            return navigator.mediaSession.metadata.artist;
                        }
                        var ytChan = document.querySelector('#owner-name, ytm-channel-name, .slim-owner-channel-name, ytm-badge-and-byline-renderer .byline-title');
                        if (ytChan && ytChan.textContent) return ytChan.textContent.trim();
                        return (location.hostname || '').replace(/^www\./, '');
                    } catch (_) { return (location.hostname || '').replace(/^www\./, ''); }
                }

                function extractArtwork(elem) {
                    try {
                        if (navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.artwork && navigator.mediaSession.metadata.artwork.length > 0) {
                            var arts = navigator.mediaSession.metadata.artwork;
                            return arts[arts.length - 1].src || arts[0].src || '';
                        }
                        if (elem instanceof HTMLVideoElement && elem.poster) {
                            return elem.poster;
                        }
                        var ogImg = document.querySelector('meta[property="og:image"]');
                        if (ogImg && ogImg.content) return ogImg.content;
                    } catch (_) {}
                    return '';
                }

                // ── 3. Video Bounds Tracking ────────────────────────────────────────
                var lastReportedTime = 0;
                var lastReportedVideoBounds = { left: 0, top: 0, right: 0, bottom: 0 };

                function reportVideoBounds(elem) {
                    try {
                        if (elem instanceof HTMLVideoElement && window.OnyxMediaBridge && typeof window.OnyxMediaBridge.onVideoBoundsChanged === 'function') {
                            var r = elem.getBoundingClientRect();
                            if (Math.abs(r.left - lastReportedVideoBounds.left) > 4 ||
                                Math.abs(r.top - lastReportedVideoBounds.top) > 4 ||
                                Math.abs(r.right - lastReportedVideoBounds.right) > 4 ||
                                Math.abs(r.bottom - lastReportedVideoBounds.bottom) > 4) {
                                lastReportedVideoBounds = { left: r.left, top: r.top, right: r.right, bottom: r.bottom };
                                window.OnyxMediaBridge.onVideoBoundsChanged(r.left, r.top, r.right, r.bottom);
                            }
                        }
                    } catch (_) {}
                }

                // ── 4. Qualifying Media Detection (Brave-Parity) ─────────────────────
                function isQualifyingMedia(elem) {
                    if (!elem || !(elem instanceof HTMLMediaElement)) return false;
                    if (elem instanceof HTMLVideoElement) {
                        // Filter out tiny invisible ad beacons (< 32px)
                        if (elem.hasAttribute('width') && parseInt(elem.getAttribute('width')) < 32 && parseInt(elem.getAttribute('width')) > 0) return false;
                        if (elem.hasAttribute('height') && parseInt(elem.getAttribute('height')) < 32 && parseInt(elem.getAttribute('height')) > 0) return false;
                        // Filter out short looping decorative background animations with no audio
                        if (elem.loop && (elem.muted || elem.volume === 0) && elem.duration > 0 && elem.duration < 4) return false;
                        return true;
                    }
                    if (elem instanceof HTMLAudioElement) {
                        return true;
                    }
                    return false;
                }

                function reportMediaPlaying(elem) {
                    if (!window.OnyxMediaBridge || !isQualifyingMedia(elem)) return;
                    var isVid = (elem instanceof HTMLVideoElement);
                    var dur = (elem.duration && !isNaN(elem.duration)) ? elem.duration : 0;
                    var pos = (elem.currentTime && !isNaN(elem.currentTime)) ? elem.currentTime : 0;
                    var w = isVid ? (elem.videoWidth || elem.clientWidth || 0) : 0;
                    var h = isVid ? (elem.videoHeight || elem.clientHeight || 0) : 0;
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
                    if (!window.OnyxMediaBridge || !isQualifyingMedia(elem)) return;
                    var now = Date.now();
                    if (now - lastReportedTime < 1000) return;
                    lastReportedTime = now;
                    var dur = (elem.duration && !isNaN(elem.duration)) ? elem.duration : 0;
                    var pos = (elem.currentTime && !isNaN(elem.currentTime)) ? elem.currentTime : 0;
                    window.OnyxMediaBridge.onMediaProgress(pos, dur);
                    if (elem instanceof HTMLVideoElement) reportVideoBounds(elem);
                }

                function getAllMedia(root) {
                    var res = [];
                    try {
                        if (!root) return res;
                        if (root.querySelectorAll) res = res.concat(Array.from(root.querySelectorAll('video, audio')));
                        var all = root.querySelectorAll ? root.querySelectorAll('*') : [];
                        for (var i = 0; i < all.length; i++) {
                            if (all[i].shadowRoot) res = res.concat(getAllMedia(all[i].shadowRoot));
                        }
                    } catch (_) {}
                    return res;
                }

                function reportMediaPaused() {
                    if (!window.OnyxMediaBridge) return;
                    setTimeout(function() {
                        var anyPlaying = getAllMedia(document).some(function(m) {
                            return !m.paused && !m.ended && isQualifyingMedia(m);
                        });
                        if (!anyPlaying) {
                            window.OnyxMediaBridge.onMediaPaused();
                        }
                    }, 350);
                }

                // ── 5. Direct Element Event Binding (Brave Playlist pattern) ────────
                function bindMediaElement(elem) {
                    if (!elem || elem.__onyx_bound) return;
                    elem.__onyx_bound = true;

                    elem.addEventListener('play', function() {
                        elem.__onyx_last_play_time = Date.now();
                        reportMediaPlaying(elem);
                    }, true);

                    elem.addEventListener('playing', function() {
                        reportMediaPlaying(elem);
                    }, true);

                    elem.addEventListener('pause', function() {
                        reportMediaPaused();
                    }, true);

                    elem.addEventListener('ended', function() {
                        reportMediaPaused();
                    }, true);

                    elem.addEventListener('timeupdate', function() {
                        if (!elem.paused) reportMediaProgress(elem);
                    }, true);

                    elem.addEventListener('volumechange', function() {
                        if (!elem.muted && elem.volume > 0 && !elem.paused) {
                            reportMediaPlaying(elem);
                        }
                    }, true);

                    elem.addEventListener('loadedmetadata', function() {
                        if (!elem.paused) reportMediaPlaying(elem);
                        if (elem instanceof HTMLVideoElement) reportVideoBounds(elem);
                    }, true);

                    // Check if already playing when bound
                    if (!elem.paused && !elem.ended && isQualifyingMedia(elem)) {
                        reportMediaPlaying(elem);
                    }

                    if (elem instanceof HTMLVideoElement && isQualifyingMedia(elem)) {
                        var vsrc = elem.currentSrc || elem.src || '';
                        var vw = elem.videoWidth || elem.clientWidth || 0;
                        var vh = elem.videoHeight || elem.clientHeight || 0;
                        if (window.OnyxMediaBridge && typeof window.OnyxMediaBridge.onVideoPresenceChanged === 'function') {
                            window.OnyxMediaBridge.onVideoPresenceChanged(true, vsrc, vw, vh);
                        }
                    }
                }

                // ── 6. MutationObserver & Shadow DOM Media Scanning ─────────────────
                function scanNodeForMedia(node) {
                    if (node instanceof HTMLMediaElement) {
                        bindMediaElement(node);
                    } else if (node.querySelectorAll) {
                        try {
                            node.querySelectorAll('video, audio').forEach(bindMediaElement);
                        } catch (_) {}
                    }
                    if (node.shadowRoot) {
                        try {
                            node.shadowRoot.querySelectorAll('video, audio').forEach(bindMediaElement);
                        } catch (_) {}
                    }
                }

                var mutationQueue = [];
                function processMutationQueue() {
                    for (var i = 0; i < mutationQueue.length; i++) {
                        var m = mutationQueue[i];
                        if (m.addedNodes) {
                            for (var j = 0; j < m.addedNodes.length; j++) {
                                scanNodeForMedia(m.addedNodes[j]);
                            }
                        }
                    }
                    mutationQueue = [];
                }

                var observer = new MutationObserver(function(mutations) {
                    if (!mutationQueue.length) {
                        if (window.requestAnimationFrame) {
                            requestAnimationFrame(processMutationQueue);
                        } else {
                            setTimeout(processMutationQueue, 16);
                        }
                    }
                    for (var k = 0; k < mutations.length; k++) {
                        mutationQueue.push(mutations[k]);
                    }
                });

                if (document.documentElement) {
                    observer.observe(document.documentElement, { childList: true, subtree: true });
                } else {
                    document.addEventListener('DOMContentLoaded', function() {
                        if (document.documentElement) {
                            observer.observe(document.documentElement, { childList: true, subtree: true });
                        }
                    }, { once: true });
                }

                // Initial sweep for existing media elements
                function scanAllMedia() {
                    getAllMedia(document).forEach(bindMediaElement);
                    var vids = getAllMedia(document).filter(function(m) { return m instanceof HTMLVideoElement && isQualifyingMedia(m); });
                    if (vids.length > 0 && window.OnyxMediaBridge && typeof window.OnyxMediaBridge.onVideoPresenceChanged === 'function') {
                        var v = vids[0];
                        var vsrc = v.currentSrc || v.src || '';
                        var vw = v.videoWidth || v.clientWidth || 0;
                        var vh = v.videoHeight || v.clientHeight || 0;
                        window.OnyxMediaBridge.onVideoPresenceChanged(true, vsrc, vw, vh);
                    }
                }
                scanAllMedia();
                document.addEventListener('DOMContentLoaded', scanAllMedia, { once: true });
                setTimeout(scanAllMedia, 1000);
                setTimeout(scanAllMedia, 3000);

                // Document-level fallback event listeners
                document.addEventListener('play', function(e) {
                    if (e.target instanceof HTMLMediaElement) {
                        bindMediaElement(e.target);
                        e.target.__onyx_last_play_time = Date.now();
                        reportMediaPlaying(e.target);
                    }
                }, true);

                document.addEventListener('playing', function(e) {
                    if (e.target instanceof HTMLMediaElement) {
                        bindMediaElement(e.target);
                        reportMediaPlaying(e.target);
                    }
                }, true);

                document.addEventListener('pause', function(e) {
                    if (e.target instanceof HTMLMediaElement) reportMediaPaused();
                }, true);

                document.addEventListener('ended', function(e) {
                    if (e.target instanceof HTMLMediaElement) reportMediaPaused();
                }, true);

                document.addEventListener('timeupdate', function(e) {
                    if (e.target instanceof HTMLMediaElement && !e.target.paused) reportMediaProgress(e.target);
                }, true);

                // ── 8. Hook navigator.mediaSession metadata & action handlers ────────
                var mediaSessionHandlers = window.__onyx_media_session_handlers || {};
                window.__onyx_media_session_handlers = mediaSessionHandlers;
                if (navigator.mediaSession) {
                    try {
                        if (navigator.mediaSession.setActionHandler) {
                            var origSetActionHandler = navigator.mediaSession.setActionHandler.bind(navigator.mediaSession);
                            navigator.mediaSession.setActionHandler = function(action, handler) {
                                if (handler) {
                                    mediaSessionHandlers[action] = handler;
                                } else {
                                    delete mediaSessionHandlers[action];
                                }
                                return origSetActionHandler(action, handler);
                            };
                        }
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
                    } catch (_) {}
                }

                // ── 9. Universal Cross-Frame Message Bus ────────────────────────────
                window.addEventListener('message', function(e) {
                    try {
                        if (!e.data || typeof e.data !== 'object') return;
                        var cmd = e.data.__onyx_cmd;
                        if (!cmd) return;

                        if (cmd === 'play') {
                            window.__onyx_allow_explicit_pause = false;
                            try {
                                if (window.__onyx_media_session_handlers && typeof window.__onyx_media_session_handlers['play'] === 'function') {
                                    window.__onyx_media_session_handlers['play']({ action: 'play' });
                                }
                            } catch (_) {}
                            var yt = document.querySelector('#movie_player, .html5-video-player');
                            if (yt && typeof yt.playVideo === 'function') yt.playVideo();
                            getAllMedia(document).forEach(function(m) {
                                m.userHitPause = false;
                                m.play().catch(function(){});
                            });
                        } else if (cmd === 'pause') {
                            window.__onyx_allow_explicit_pause = true;
                            try {
                                if (window.__onyx_media_session_handlers && typeof window.__onyx_media_session_handlers['pause'] === 'function') {
                                    window.__onyx_media_session_handlers['pause']({ action: 'pause' });
                                }
                            } catch (_) {}
                            var yt = document.querySelector('#movie_player, .html5-video-player');
                            if (yt && typeof yt.pauseVideo === 'function') yt.pauseVideo();
                            getAllMedia(document).forEach(function(m) {
                                m.userHitPause = true;
                                m.pause();
                            });
                            setTimeout(function() { window.__onyx_allow_explicit_pause = false; }, 800);
                        } else if (cmd === 'seek') {
                            var d = e.data.delta || 0;
                            var yt = document.querySelector('#movie_player, .html5-video-player');
                            if (yt && typeof yt.getCurrentTime === 'function' && typeof yt.seekTo === 'function') {
                                yt.seekTo(yt.getCurrentTime() + d, true);
                            }
                            var t = Array.from(document.querySelectorAll('video, audio')).find(function(m) { return !m.paused && m.duration; })
                                 || Array.from(document.querySelectorAll('video, audio')).find(function(m) { return m.duration > 0; });
                            if (t && t.duration) t.currentTime = Math.max(0, Math.min(t.duration, t.currentTime + d));
                        } else if (cmd === 'seek_to') {
                            var p = e.data.pos || 0;
                            var yt = document.querySelector('#movie_player, .html5-video-player');
                            if (yt && typeof yt.seekTo === 'function') yt.seekTo(p, true);
                            var t = Array.from(document.querySelectorAll('video, audio')).find(function(m) { return !m.paused && m.duration; })
                                 || Array.from(document.querySelectorAll('video, audio')).find(function(m) { return m.duration > 0; });
                            if (t && t.duration) t.currentTime = Math.max(0, Math.min(t.duration, p));
                        } else if (cmd === 'set_bg') {
                            window.__onyx_in_background = !!e.data.inBackground;
                        } else if (cmd === 'fullscreen') {
                            var vids = Array.from(document.querySelectorAll('video'));
                            var v = vids.find(function(i) { return !i.paused && !i.ended; }) || vids[0];
                            if (v) {
                                if (typeof v.webkitRequestFullscreen === 'function') v.webkitRequestFullscreen();
                                else if (typeof v.requestFullscreen === 'function') v.requestFullscreen();
                            }
                        } else if (cmd === 'pip') {
                            var vids = Array.from(document.querySelectorAll('video'));
                            var v = vids.find(function(i) { return !i.paused && !i.ended; }) || vids[0];
                            if (v) {
                                reportVideoBounds(v);
                            }
                        } else if (cmd === 'pip_request') {
                            var matchingIframe = Array.from(document.querySelectorAll('iframe')).find(function(f) {
                                return f.contentWindow === e.source;
                            });
                            if (!matchingIframe) {
                                matchingIframe = document.querySelector('iframe');
                            }
                            if (matchingIframe) {
                                window.__onyx_current_pip_element = matchingIframe;
                                try {
                                    var r = matchingIframe.getBoundingClientRect();
                                    if (window.OnyxMediaBridge && typeof window.OnyxMediaBridge.onVideoBoundsChanged === 'function') {
                                        window.OnyxMediaBridge.onVideoBoundsChanged(r.left, r.top, r.right, r.bottom);
                                    }
                                } catch (_) {}
                            }
                            if (window.OnyxMediaBridge && typeof window.OnyxMediaBridge.requestVideoPip === 'function') {
                                window.OnyxMediaBridge.requestVideoPip();
                            }
                        } else if (cmd === 'pip_exit') {
                            if (currentPipElem) {
                                var oldPip = currentPipElem;
                                currentPipElem = null;
                                try { oldPip.dispatchEvent(new Event('leavepictureinpicture', { bubbles: true })); } catch (_) {}
                            }
                            window.__onyx_current_pip_element = null;
                        }

                        // Recursively forward to child iframes
                        document.querySelectorAll('iframe').forEach(function(f) {
                            try { f.contentWindow.postMessage(e.data, '*'); } catch (_) {}
                        });
                    } catch (_) {}
                });

            } catch (e) {}
        })();
    """.trimIndent()

    /**
     * Background video playback keep-alive engine — ONLY injected when isBackgroundPlayEnabled == true.
     * Modeled on Brave's kYoutubeBackgroundPlayback + MediaBackgroundingScript.js:
     * 1. Filters out document.addEventListener('visibilitychange') so web players cannot detect page hide.
     * 2. Overrides Document.prototype.visibilityState to always return 'visible'.
     * 3. Implements userHitPause tracking to automatically neutralize script/blur/background auto-pauses.
     * 4. Patches YouTube ytcfg serializedExperimentFlags to disable PiP blocking flags.
     * 5. Overrides IntersectionObserver so offscreen video elements are never frozen.
     * 6. Blocks background audio muting.
     */
    val backgroundPlaybackScript: String = """
        (function() {
            window.__onyx_bg_play_active = true;
            if (window.__onyx_bg_script_installed) return;
            window.__onyx_bg_script_installed = true;

            try {
                // ── 1. Brave kYoutubeBackgroundPlayback: Filter visibilitychange listeners ──
                if (document._addEventListener === undefined) {
                    document._addEventListener = document.addEventListener;
                    document.addEventListener = function(type, listener, options) {
                        if (window.__onyx_bg_play_active && (type === 'visibilitychange' || type === 'webkitvisibilitychange')) {
                            return; // Filter out website pause-on-hide listeners only while active!
                        }
                        return document._addEventListener.call(this, type, listener, options);
                    };
                }

                // ── 2. Brave MediaBackgrounding: Document.prototype.visibilityState ─────────
                try {
                    var descriptor = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
                    if (descriptor && descriptor.get) {
                        var origGet = descriptor.get;
                        Object.defineProperty(Document.prototype, 'visibilityState', {
                            enumerable: descriptor.enumerable,
                            configurable: true,
                            get: function() {
                                var res = origGet.call(this);
                                if (!window.__onyx_bg_play_active) return res;
                                return (res !== 'visible') ? 'visible' : res;
                            }
                        });
                    }
                } catch (_) {}

                try {
                    var origDocVis = Object.getOwnPropertyDescriptor(document, 'visibilityState');
                    var origDocVisGet = origDocVis ? origDocVis.get : null;
                    Object.defineProperty(document, 'visibilityState', {
                        configurable: true,
                        get: function() {
                            if (!window.__onyx_bg_play_active) {
                                return origDocVisGet ? origDocVisGet.call(document) : 'visible';
                            }
                            return 'visible';
                        }
                    });
                    Object.defineProperty(document, 'hidden', {
                        configurable: true,
                        get: function() {
                            if (!window.__onyx_bg_play_active) return false;
                            return false;
                        }
                    });
                    Object.defineProperty(document, 'webkitHidden', {
                        configurable: true,
                        get: function() {
                            if (!window.__onyx_bg_play_active) return false;
                            return false;
                        }
                    });
                    Object.defineProperty(document, 'webkitVisibilityState', {
                        configurable: true,
                        get: function() {
                            if (!window.__onyx_bg_play_active) return 'visible';
                            return 'visible';
                        }
                    });
                    document.hasFocus = function() { return true; };
                    if (typeof Document !== 'undefined' && Document.prototype) {
                        Document.prototype.hasFocus = function() { return true; };
                    }
                } catch (_) {}

                // Suppress window blur & focusout events during background transitions
                var stopWindowBlur = function(e) {
                    if (!window.__onyx_bg_play_active) return;
                    if (e.target === window || e.target === document) {
                        e.stopImmediatePropagation();
                    }
                };
                window.addEventListener('blur', stopWindowBlur, true);
                window.addEventListener('focusout', stopWindowBlur, true);

                try {
                    Object.defineProperty(window, 'onblur', {
                        get: function() { return null; },
                        set: function() {},
                        configurable: true
                    });
                } catch (_) {}

                var stopVisibilityInBg = function(e) {
                    if (!window.__onyx_bg_play_active) return;
                    if (window.__onyx_in_background) {
                        e.stopImmediatePropagation();
                    }
                };
                window.addEventListener('visibilitychange', stopVisibilityInBg, true);
                document.addEventListener('visibilitychange', stopVisibilityInBg, true);

                try {
                    Object.defineProperty(document, 'onvisibilitychange', {
                        get: function() { return null; },
                        set: function() {},
                        configurable: true
                    });
                } catch (_) {}

                // Track user interaction to differentiate manual pauses from background auto-pauses
                window.__onyx_last_user_touch = 0;
                window.addEventListener('pointerdown', function() { window.__onyx_last_user_touch = Date.now(); }, true);
                window.addEventListener('touchstart', function() { window.__onyx_last_user_touch = Date.now(); }, true);
                window.addEventListener('click', function() { window.__onyx_last_user_touch = Date.now(); }, true);

                // ── 3. Brave MediaBackgrounding: userHitPause & Auto-Resume ────────────────
                var origPause = HTMLMediaElement.prototype.pause;
                HTMLMediaElement.prototype.pause = function() {
                    this.userHitPause = true;
                    return origPause.apply(this, arguments);
                };

                var origPlay = HTMLMediaElement.prototype.play;
                HTMLMediaElement.prototype.play = function() {
                    this.userHitPause = false;
                    return origPlay.apply(this, arguments);
                };

                function addBackgroundListeners(element) {
                    if (!element || element.__onyx_bg_bound) return;
                    element.__onyx_bg_bound = true;

                    element.addEventListener('pause', function() {
                        if (!window.__onyx_bg_play_active) return;
                        if (!element.userHitPause && !element.ended) {
                            // Video paused by page visibility, blur, or OS suspend: auto-resume!
                            origPlay.call(element).catch(function(){});
                        }
                    }, false);

                    element.addEventListener('webkitpresentationmodechanged', function(e) {
                        if (!window.__onyx_bg_play_active) return;
                        e.stopPropagation();
                    }, true);
                }

                document.querySelectorAll('video, audio').forEach(addBackgroundListeners);
                document.addEventListener('play', function(e) {
                    if (e.target instanceof HTMLMediaElement) {
                        addBackgroundListeners(e.target);
                    }
                }, true);

                // ── 4. Brave kYoutubePictureInPictureSupport: ytcfg Flag Patching ──────────
                function modifyYtcfgFlags() {
                    try {
                        if (!window.ytcfg || typeof window.ytcfg.get !== 'function') return;
                        var configs = [
                            window.ytcfg.get('WEB_PLAYER_CONTEXT_CONFIGS')?.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH,
                            window.ytcfg.get('WEB_PLAYER_CONTEXT_CONFIGS')?.WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH,
                            window.ytcfg.get('WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH'),
                            window.ytcfg.get('WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH')
                        ];
                        configs.forEach(function(config) {
                            if (config && config.serializedExperimentFlags && typeof config.serializedExperimentFlags === 'string') {
                                config.serializedExperimentFlags = config.serializedExperimentFlags
                                    .replace(/html5_picture_in_picture_blocking_\w+=true/g, function(m) { return m.replace('=true', '=false'); })
                                    .replace('html5_picture_in_picture_blocking_ontimeupdate=true', 'html5_picture_in_picture_blocking_ontimeupdate=false')
                                    .replace('html5_picture_in_picture_blocking_onresize=true', 'html5_picture_in_picture_blocking_onresize=false')
                                    .replace('html5_picture_in_picture_blocking_document_fullscreen=true', 'html5_picture_in_picture_blocking_document_fullscreen=false')
                                    .replace('html5_picture_in_picture_blocking_standard_api=true', 'html5_picture_in_picture_blocking_standard_api=false')
                                    .replace('html5_picture_in_picture_logging_onresize=true', 'html5_picture_in_picture_logging_onresize=false');
                            }
                        });
                        if (window.ytcfg.set) {
                            window.ytcfg.set({
                                'html5_picture_in_picture_blocking_web': false,
                                'html5_picture_in_picture_blocking_android': false,
                                'html5_picture_in_picture_blocking_standard_api': false
                            });
                        }
                    } catch (_) {}
                }

                modifyYtcfgFlags();
                document.addEventListener('load', function(event) {
                    if (event.target && event.target.tagName === 'SCRIPT' && window.ytcfg) {
                        modifyYtcfgFlags();
                    }
                }, true);

                // Brave kYoutubeFullscreenVideoFitWorkaround: Fit mobile video to viewport
                try {
                    if (location.hostname.includes('youtube.com') || location.hostname.includes('youtu.be')) {
                        var ytFitStyle = document.createElement('style');
                        ytFitStyle.id = '__onyx_yt_fit_style';
                        ytFitStyle.textContent = '#player-container-id:fullscreen video.html5-main-video { width: 100% !important; height: 100dvh !important; left: 0 !important; top: 0 !important; object-fit: contain !important; }';
                        (document.head || document.documentElement).appendChild(ytFitStyle);
                    }
                } catch (_) {}

                // ── 5. IntersectionObserver Override for Media Elements ────────────────────
                if (window.IntersectionObserver) {
                    var OrigIO = window.IntersectionObserver;
                    window.IntersectionObserver = function(cb, opts) {
                        return new OrigIO(function(entries, obs) {
                            return cb(entries.map(function(entry) {
                                if (window.__onyx_bg_play_active && (entry.target instanceof HTMLMediaElement || entry.target?.querySelector('video, audio'))) {
                                    return new Proxy(entry, {
                                        get: function(t, p) {
                                            if (p === 'isIntersecting') return true;
                                            if (p === 'intersectionRatio') return 1.0;
                                            return t[p];
                                        }
                                    });
                                }
                                return entry;
                            }), obs);
                        }, opts);
                    };
                    window.IntersectionObserver.prototype = OrigIO.prototype;
                }

                // ── 6. Prevent Site Scripts From Muting in Background ─────────────────────
                try {
                    var origMuted = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'muted');
                    if (origMuted && origMuted.set) {
                        Object.defineProperty(HTMLMediaElement.prototype, 'muted', {
                            get: function() { return origMuted.get.call(this); },
                            set: function(val) {
                                if (window.__onyx_bg_play_active && val && (window.__onyx_in_background || document.hidden || document.visibilityState === 'hidden')) {
                                    return;
                                }
                                return origMuted.set.call(this, val);
                            },
                            configurable: true
                        });
                    }
                } catch (_) {}

            } catch (e) {}
        })();
    """.trimIndent()

    fun getSetBackgroundStateScript(inBackground: Boolean): String = """
        (function() {
            window.__onyx_in_background = $inBackground;
            var msg = { __onyx_cmd: 'set_bg', inBackground: $inBackground };
            try {
                window.postMessage(msg, '*');
                document.querySelectorAll('iframe').forEach(function(f) {
                    try { f.contentWindow.postMessage(msg, '*'); } catch (_) {}
                });
            } catch (_) {}
        })();
    """.trimIndent()

    val playAllMediaScript: String = """
        (function() {
            window.__onyx_allow_explicit_pause = false;

            function getAllMedia(root) {
                var res = [];
                try {
                    if (!root) return res;
                    if (root.querySelectorAll) res = res.concat(Array.from(root.querySelectorAll('video, audio')));
                    var all = root.querySelectorAll ? root.querySelectorAll('*') : [];
                    for (var i = 0; i < all.length; i++) {
                        if (all[i].shadowRoot) res = res.concat(getAllMedia(all[i].shadowRoot));
                    }
                } catch (_) {}
                return res;
            }

            var msg = { __onyx_cmd: 'play' };
            try {
                window.postMessage(msg, '*');
                document.querySelectorAll('iframe').forEach(function(f) {
                    try {
                        if (f.contentDocument) {
                            getAllMedia(f.contentDocument).forEach(function(m) {
                                m.userHitPause = false;
                                var p = m.play();
                                if (p && typeof p.catch === 'function') p.catch(function(){});
                            });
                        }
                        f.contentWindow.postMessage(msg, '*');
                        f.contentWindow.postMessage('{"event":"command","func":"playVideo","args":""}', '*');
                    } catch (_) {}
                });
            } catch (_) {}

            // 1. Sync with website MediaSession API action handler
            try {
                if (window.__onyx_media_session_handlers && typeof window.__onyx_media_session_handlers['play'] === 'function') {
                    window.__onyx_media_session_handlers['play']({ action: 'play' });
                }
            } catch (_) {}

            // 2. YouTube Player API
            try {
                var yt = document.querySelector('#movie_player, .html5-video-player');
                if (yt && typeof yt.playVideo === 'function') yt.playVideo();
            } catch (_) {}

            // 3. HTML5 Media Elements across light & Shadow DOM
            getAllMedia(document).forEach(function(m) {
                m.userHitPause = false;
                var p = m.play();
                if (p && typeof p.catch === 'function') p.catch(function(){});
            });
        })();
    """.trimIndent()

    val pauseAllMediaScript: String = """
        (function() {
            window.__onyx_allow_explicit_pause = true;

            function getAllMedia(root) {
                var res = [];
                try {
                    if (!root) return res;
                    if (root.querySelectorAll) res = res.concat(Array.from(root.querySelectorAll('video, audio')));
                    var all = root.querySelectorAll ? root.querySelectorAll('*') : [];
                    for (var i = 0; i < all.length; i++) {
                        if (all[i].shadowRoot) res = res.concat(getAllMedia(all[i].shadowRoot));
                    }
                } catch (_) {}
                return res;
            }

            var msg = { __onyx_cmd: 'pause' };
            try {
                window.postMessage(msg, '*');
                document.querySelectorAll('iframe').forEach(function(f) {
                    try {
                        if (f.contentDocument) {
                            getAllMedia(f.contentDocument).forEach(function(m) {
                                m.userHitPause = true;
                                m.pause();
                            });
                        }
                        f.contentWindow.postMessage(msg, '*');
                        f.contentWindow.postMessage('{"event":"command","func":"pauseVideo","args":""}', '*');
                    } catch (_) {}
                });
            } catch (_) {}

            // 1. Sync with website MediaSession API action handler
            try {
                if (window.__onyx_media_session_handlers && typeof window.__onyx_media_session_handlers['pause'] === 'function') {
                    window.__onyx_media_session_handlers['pause']({ action: 'pause' });
                }
            } catch (_) {}

            // 2. YouTube Player API
            try {
                var yt = document.querySelector('#movie_player, .html5-video-player');
                if (yt && typeof yt.pauseVideo === 'function') yt.pauseVideo();
            } catch (_) {}

            // 3. HTML5 Media Elements across light & Shadow DOM
            getAllMedia(document).forEach(function(m) {
                m.userHitPause = true;
                m.pause();
            });

            setTimeout(function() {
                window.__onyx_allow_explicit_pause = false;
            }, 800);
        })();
    """.trimIndent()

    fun getSeekMediaScript(deltaSeconds: Int): String = """
        (function(delta) {
            var msg = { __onyx_cmd: 'seek', delta: delta };
            try {
                window.postMessage(msg, '*');
                document.querySelectorAll('iframe').forEach(function(f) {
                    try {
                        f.contentWindow.postMessage(msg, '*');
                        f.contentWindow.postMessage(JSON.stringify({ event: 'command', func: 'seekTo', args: [delta, true] }), '*');
                    } catch (_) {}
                });
            } catch (_) {}

            // 1. Sync with website MediaSession API seekforward / seekbackward
            try {
                if (delta > 0 && window.__onyx_media_session_handlers && typeof window.__onyx_media_session_handlers['seekforward'] === 'function') {
                    window.__onyx_media_session_handlers['seekforward']({ action: 'seekforward', seekOffset: delta });
                    return;
                } else if (delta < 0 && window.__onyx_media_session_handlers && typeof window.__onyx_media_session_handlers['seekbackward'] === 'function') {
                    window.__onyx_media_session_handlers['seekbackward']({ action: 'seekbackward', seekOffset: Math.abs(delta) });
                    return;
                }
            } catch (_) {}

            // 2. YouTube Player API
            try {
                var yt = document.querySelector('#movie_player, .html5-video-player');
                if (yt && typeof yt.getCurrentTime === 'function' && typeof yt.seekTo === 'function') {
                    yt.seekTo(yt.getCurrentTime() + delta, true);
                    return;
                }
            } catch (_) {}

            function getAllMedia(root) {
                var res = [];
                try {
                    if (!root) return res;
                    if (root.querySelectorAll) res = res.concat(Array.from(root.querySelectorAll('video, audio')));
                    var all = root.querySelectorAll ? root.querySelectorAll('*') : [];
                    for (var i = 0; i < all.length; i++) {
                        if (all[i].shadowRoot) res = res.concat(getAllMedia(all[i].shadowRoot));
                    }
                } catch (_) {}
                return res;
            }

            var mediaList = getAllMedia(document);
            document.querySelectorAll('iframe').forEach(function(f) {
                try {
                    if (f.contentDocument) mediaList = mediaList.concat(getAllMedia(f.contentDocument));
                } catch (_) {}
            });

            var target = mediaList.find(function(m) { return !m.paused && m.duration; })
                      || mediaList.find(function(m) { return m.duration && m.duration > 0; })
                      || mediaList[0];

            if (target && target.duration && !isNaN(target.duration)) {
                target.currentTime = Math.max(0, Math.min(target.duration, target.currentTime + delta));
                try {
                    target.dispatchEvent(new Event('seeking'));
                    target.dispatchEvent(new Event('timeupdate'));
                    target.dispatchEvent(new Event('seeked'));
                } catch (_) {}
            }
        })($deltaSeconds);
    """.trimIndent()

    fun getSeekToPositionScript(positionSeconds: Double): String = """
        (function(pos) {
            var msg = { __onyx_cmd: 'seek_to', pos: pos };
            try {
                window.postMessage(msg, '*');
                document.querySelectorAll('iframe').forEach(function(f) {
                    try {
                        f.contentWindow.postMessage(msg, '*');
                        f.contentWindow.postMessage(JSON.stringify({ event: 'command', func: 'seekTo', args: [pos, true] }), '*');
                    } catch (_) {}
                });
            } catch (_) {}

            // 1. Sync with website MediaSession API seekto
            try {
                if (window.__onyx_media_session_handlers && typeof window.__onyx_media_session_handlers['seekto'] === 'function') {
                    window.__onyx_media_session_handlers['seekto']({ action: 'seekto', seekTime: pos, fastSeek: false });
                    return;
                }
            } catch (_) {}

            // 2. YouTube Player API
            try {
                var yt = document.querySelector('#movie_player, .html5-video-player');
                if (yt && typeof yt.seekTo === 'function') {
                    yt.seekTo(pos, true);
                    return;
                }
            } catch (_) {}

            function getAllMedia(root) {
                var res = [];
                try {
                    if (!root) return res;
                    if (root.querySelectorAll) res = res.concat(Array.from(root.querySelectorAll('video, audio')));
                    var all = root.querySelectorAll ? root.querySelectorAll('*') : [];
                    for (var i = 0; i < all.length; i++) {
                        if (all[i].shadowRoot) res = res.concat(getAllMedia(all[i].shadowRoot));
                    }
                } catch (_) {}
                return res;
            }

            var mediaList = getAllMedia(document);
            document.querySelectorAll('iframe').forEach(function(f) {
                try {
                    if (f.contentDocument) mediaList = mediaList.concat(getAllMedia(f.contentDocument));
                } catch (_) {}
            });

            var target = mediaList.find(function(m) { return !m.paused && m.duration; })
                      || mediaList.find(function(m) { return m.duration && m.duration > 0; })
                      || mediaList[0];

            if (target && target.duration && !isNaN(target.duration)) {
                target.currentTime = Math.max(0, Math.min(target.duration, pos));
                try {
                    target.dispatchEvent(new Event('seeking'));
                    target.dispatchEvent(new Event('timeupdate'));
                    target.dispatchEvent(new Event('seeked'));
                } catch (_) {}
            }
        })($positionSeconds);
    """.trimIndent()

    val skipNextMediaScript: String = """
        (function() {
            // 1. Sync with website MediaSession API nexttrack
            try {
                if (window.__onyx_media_session_handlers && typeof window.__onyx_media_session_handlers['nexttrack'] === 'function') {
                    window.__onyx_media_session_handlers['nexttrack']({ action: 'nexttrack' });
                    return;
                }
            } catch (_) {}

            // 2. YouTube Player API nextVideo
            try {
                var yt = document.querySelector('#movie_player, .html5-video-player');
                if (yt && typeof yt.nextVideo === 'function') {
                    yt.nextVideo();
                    return;
                }
            } catch (_) {}

            // 3. Click next button in player DOM
            try {
                var nextBtn = document.querySelector('.ytp-next-button, [aria-label*="Next" i], [title*="Next" i], .next-track, .btn-next');
                if (nextBtn) {
                    nextBtn.click();
                    return;
                }
            } catch (_) {}

            // 4. Fallback: skip forward 10s
            ${getSeekMediaScript(10)}
        })();
    """.trimIndent()

    val skipPreviousMediaScript: String = """
        (function() {
            // 1. Sync with website MediaSession API previoustrack
            try {
                if (window.__onyx_media_session_handlers && typeof window.__onyx_media_session_handlers['previoustrack'] === 'function') {
                    window.__onyx_media_session_handlers['previoustrack']({ action: 'previoustrack' });
                    return;
                }
            } catch (_) {}

            // 2. YouTube Player API previousVideo
            try {
                var yt = document.querySelector('#movie_player, .html5-video-player');
                if (yt && typeof yt.previousVideo === 'function') {
                    yt.previousVideo();
                    return;
                }
            } catch (_) {}

            // 3. Click previous button in player DOM
            try {
                var prevBtn = document.querySelector('.ytp-prev-button, [aria-label*="Previous" i], [title*="Previous" i], .prev-track, .btn-prev');
                if (prevBtn) {
                    prevBtn.click();
                    return;
                }
            } catch (_) {}

            // 4. Fallback: skip backward 10s
            ${getSeekMediaScript(-10)}
        })();
    """.trimIndent()

    /**
     * Drives the active video player into native fullscreen (Brave kYoutubeFullscreen parity).
     * Injects Brave's kYoutubeFullscreenVideoFitWorkaround CSS.
     */
    val requestVideoFullscreenScript: String = """
        (function() {
            // Brave kYoutubeFullscreenVideoFitWorkaround: ensure video fills viewport cleanly
            try {
                if (!document.getElementById('__onyx_yt_fit_style')) {
                    var s = document.createElement('style');
                    s.id = '__onyx_yt_fit_style';
                    s.textContent = '#player-container-id:fullscreen video.html5-main-video { width: 100% !important; height: 100dvh !important; left: 0 !important; top: 0 !important; object-fit: contain !important; }';
                    (document.head || document.documentElement).appendChild(s);
                }
            } catch (_) {}

            function getAllVideos(root) {
                var res = [];
                try {
                    res = res.concat(Array.from(root.querySelectorAll('video')));
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
                    if (f.contentDocument) vids = vids.concat(getAllVideos(f.contentDocument));
                } catch (_) {}
            });

            var playing = vids.find(function(v) { return !v.paused && !v.ended; })
                || vids[0];

            // 1. Direct video webkitRequestFullscreen (standard for WebChromeClient.onShowCustomView in WebView)
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

            // 2. Native Element requestFullscreen on player container
            var target = document.querySelector('#movie_player, .html5-video-player, #player-container-id, ytm-player, #player');
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

            // 3. YouTube mobile / web fullscreen button
            var ytBtn = document.querySelector('button.fullscreen-icon, button.ytp-fullscreen-button, .ytp-fullscreen-button');
            if (ytBtn) {
                try {
                    if (playing && playing.readyState >= 3) playing.click();
                    ytBtn.click();
                    return 'fullscreen_triggered';
                } catch (e) {}
            }

            return playing ? 'found_inline' : 'not_found';
        })();
    """.trimIndent()

    /**
     * True In-Page DOM Video Isolation for Picture-in-Picture:
     * 1. Finds the active video element.
     * 2. Walks up the ancestor tree penetrating Shadow DOM hosts to html.
     * 3. Sets display: none !important on ALL other elements in the body and ancestor branches.
     * 4. Eliminates CSS layout constraints (transform, contain, clip-path, overflow) on all ancestors.
     * 5. Forces the video to occupy 100vw x 100vh with black letterboxing (object-fit: contain) at z-index 2147483647.
     * 6. Hides YouTube overlay controls, watermark, header bars, and descriptions.
     */
    val isolateVideoForPipScript: String = """
        (function() {
            try {
                // 1. Check if a specific video or iframe was designated by requestPictureInPicture or postMessage
                var activeVid = window.__onyx_current_pip_element;
                if (activeVid && !document.contains(activeVid)) {
                    activeVid = null;
                }

                // 2. Recursive search across light DOM and shadow roots
                function findVideos(root) {
                    var list = [];
                    if (!root) return list;
                    try {
                        var v = root.querySelectorAll('video');
                        if (v) list = list.concat(Array.from(v));
                    } catch (_) {}
                    try {
                        var all = root.querySelectorAll('*');
                        for (var i = 0; i < all.length; i++) {
                            if (all[i].shadowRoot) {
                                list = list.concat(findVideos(all[i].shadowRoot));
                            }
                        }
                    } catch (_) {}
                    return list;
                }

                if (!activeVid) {
                    var vids = findVideos(document);
                    document.querySelectorAll('iframe').forEach(function(f) {
                        try {
                            if (f.contentDocument) {
                                var subVids = findVideos(f.contentDocument);
                                vids = vids.concat(subVids);
                            }
                        } catch (_) {}
                    });

                    // Prioritize video elements: actively playing > recently played > has source > any video
                    activeVid = vids.find(function(v) { return !v.paused && !v.ended; })
                        || vids.find(function(v) { return v.currentTime > 0; })
                        || vids.find(function(v) { return (v.currentSrc || v.src); })
                        || vids[0];
                }

                // 3. If no direct HTML5 video found (e.g. cross-origin iframe streaming player),
                // score all candidate iframes to isolate the streaming player iframe!
                if (!activeVid) {
                    var iframes = Array.from(document.querySelectorAll('iframe'));
                    var scored = iframes.map(function(f) {
                        var score = 0;
                        var src = (f.src || f.getAttribute('data-src') || f.getAttribute('data-url') || '').toLowerCase();
                        var id = (f.id || '').toLowerCase();
                        var cls = (f.className || '').toLowerCase();
                        var name = (f.name || '').toLowerCase();
                        var allow = (f.getAttribute('allow') || '').toLowerCase();
                        var hasFs = f.hasAttribute('allowfullscreen') || f.hasAttribute('webkitallowfullscreen') || f.hasAttribute('mozallowfullscreen');

                        if (hasFs) score += 35;
                        if (allow.includes('fullscreen')) score += 30;
                        if (allow.includes('autoplay')) score += 15;
                        if (allow.includes('encrypted-media')) score += 15;

                        var patterns = [
                            'embed', 'player', 'video', 'stream', 'cloud', 'watch', 'movie', 'film',
                            'youtube', 'youtu.be', 'vimeo', 'twitch', 'dailymotion', 'bilibili',
                            'rumble', 'mp4', 'm3u8', 'hls', '/e/', '/v/', '/e-', '/v-', 'play'
                        ];
                        patterns.forEach(function(p) {
                            if (src.includes(p)) score += 20;
                        });

                        if (id.includes('player') || id.includes('video')) score += 25;
                        if (cls.includes('player') || cls.includes('video')) score += 25;
                        if (name.includes('player') || name.includes('video')) score += 25;

                        var parent = f.closest ? f.closest('#player, .player, [id*="player"], [class*="player"], [id*="video"], [class*="video"], .embed-responsive, .video-container') : null;
                        if (parent) score += 40;

                        try {
                            var r = f.getBoundingClientRect();
                            var area = r.width * r.height;
                            if (area > 25000) {
                                score += 30;
                                var ratio = r.width / (r.height || 1);
                                if (ratio >= 1.1 && ratio <= 2.5) score += 25;
                            }
                            if (r.width < 50 || r.height < 50) score -= 100;
                        } catch (_) {}

                        return { iframe: f, score: score };
                    }).filter(function(item) { return item.score > 0; });

                    scored.sort(function(a, b) { return b.score - a.score; });
                    if (scored.length > 0) {
                        activeVid = scored[0].iframe;
                    }
                }

                // 4. Fallback to common player containers if activeVid still not set
                if (!activeVid) {
                    activeVid = document.querySelector('#movie_player, .html5-video-player, ytm-player, #player-container-id, #player, .video-js, .jwplayer, .plyr, .video-container');
                }

                if (!activeVid) {
                    return JSON.stringify({ found: false, width: 16, height: 9 });
                }

                var oldStyle = document.getElementById('__onyx_pip_style');
                if (oldStyle) oldStyle.remove();

                window.scrollTo(0, 0);

                var target = activeVid;
                var closestPlayer = activeVid.closest ? (activeVid.closest('.html5-video-player') || activeVid.closest('ytm-player') || activeVid.closest('.video-js') || activeVid.closest('.jwplayer') || activeVid.closest('#player-container-id') || activeVid.closest('#player') || activeVid.closest('.video-container')) : null;
                if (closestPlayer) target = closestPlayer;

                target.setAttribute('data-onyx-pip-target', 'true');
                activeVid.setAttribute('data-onyx-pip-target', 'true');

                // Tag every ancestor up to documentElement and remove constraints non-destructively
                var cur = target;
                while (cur && cur !== document.documentElement) {
                    if (cur.setAttribute) {
                        cur.setAttribute('data-onyx-pip-ancestor', 'true');
                        cur.style.setProperty('transform', 'none', 'important');
                        cur.style.setProperty('contain', 'none', 'important');
                        cur.style.setProperty('filter', 'none', 'important');
                        cur.style.setProperty('clip-path', 'none', 'important');
                        cur.style.setProperty('perspective', 'none', 'important');
                        cur.style.setProperty('overflow', 'visible', 'important');
                        cur.style.setProperty('margin', '0', 'important');
                        cur.style.setProperty('padding', '0', 'important');
                    }
                    cur = cur.parentElement || (cur.parentNode && cur.parentNode.host ? cur.parentNode.host : cur.parentNode);
                }

                // Also notify child iframes via postMessage
                try {
                    var msg = { __onyx_cmd: 'pip' };
                    document.querySelectorAll('iframe').forEach(function(f) {
                        try { f.contentWindow.postMessage(msg, '*'); } catch (_) {}
                    });
                } catch (_) {}

                var style = document.createElement('style');
                style.id = '__onyx_pip_style';
                style.textContent = `
                    html, body {
                        overflow: hidden !important;
                        background: #000000 !important;
                        margin: 0 !important;
                        padding: 0 !important;
                        width: 100vw !important;
                        height: 100vh !important;
                    }
                    [data-onyx-pip-target] {
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
                    [data-onyx-pip-target] video,
                    [data-onyx-pip-target] iframe {
                        display: block !important;
                        position: fixed !important;
                        top: 0 !important;
                        left: 0 !important;
                        width: 100vw !important;
                        height: 100vh !important;
                        max-width: 100vw !important;
                        max-height: 100vh !important;
                        object-fit: contain !important;
                        background: #000000 !important;
                        z-index: 2147483647 !important;
                        border: none !important;
                    }
                    ytm-mobile-topbar-renderer, ytm-pivot-bar-renderer, #header-bar,
                    .ytp-chrome-top, .ytp-chrome-bottom, .ytp-gradient-top, .ytp-gradient-bottom,
                    .ytp-watermark, .ytp-pause-overlay, ytm-player-control-overlay,
                    .ytm-player-control-overlay, ytm-player-overlay-renderer,
                    .video-annotations, .ytp-ce-element, .ytp-title,
                    .vjs-control-bar, .jw-controls, .plyr__controls,
                    header, nav, footer, aside, .header, .navbar, .related-items, #comments,
                    ytm-comments-entry-point-header-renderer, ytm-item-section-renderer {
                        display: none !important;
                        opacity: 0 !important;
                        pointer-events: none !important;
                    }
                `;
                (document.head || document.documentElement).appendChild(style);

                var vidWidth = activeVid.videoWidth || activeVid.clientWidth || activeVid.offsetWidth || 16;
                var vidHeight = activeVid.videoHeight || activeVid.clientHeight || activeVid.offsetHeight || 9;
                return JSON.stringify({ found: true, width: vidWidth, height: vidHeight });
            } catch (e) {
                return JSON.stringify({ found: false, error: e.message, width: 16, height: 9 });
            }
        })();
    """.trimIndent()

    /**
     * Restores normal in-page layout when exiting PiP mode.
     */
    val restoreVideoFromPipScript: String = """
        (function() {
            try {
                if (window.__onyx_current_pip_element) {
                    try {
                        window.__onyx_current_pip_element.dispatchEvent(new Event('leavepictureinpicture', { bubbles: true }));
                    } catch (_) {}
                    window.__onyx_current_pip_element = null;
                }
                if (typeof currentPipElem !== 'undefined' && currentPipElem) {
                    var old = currentPipElem;
                    currentPipElem = null;
                    try {
                        old.dispatchEvent(new Event('leavepictureinpicture', { bubbles: true }));
                    } catch (_) {}
                }
                try {
                    var msg = { __onyx_cmd: 'pip_exit' };
                    document.querySelectorAll('iframe').forEach(function(f) {
                        try { f.contentWindow.postMessage(msg, '*'); } catch (_) {}
                    });
                } catch (_) {}
                var style = document.getElementById('__onyx_pip_style');
                if (style) style.remove();
                document.querySelectorAll('[data-onyx-pip-target]').forEach(function(el) {
                    el.removeAttribute('data-onyx-pip-target');
                });
                document.querySelectorAll('[data-onyx-pip-ancestor]').forEach(function(el) {
                    el.removeAttribute('data-onyx-pip-ancestor');
                    el.style.removeProperty('transform');
                    el.style.removeProperty('contain');
                    el.style.removeProperty('filter');
                    el.style.removeProperty('clip-path');
                    el.style.removeProperty('perspective');
                    el.style.removeProperty('overflow');
                    el.style.removeProperty('margin');
                    el.style.removeProperty('padding');
                });
            } catch (e) {}
        })();
    """.trimIndent()
}
