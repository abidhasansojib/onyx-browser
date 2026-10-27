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
            var host = (location.hostname || '').toLowerCase();
            var href = (location.href || '').toLowerCase();
            var path = (location.pathname || '').toLowerCase();
            var ref = (document.referrer || '').toLowerCase();

            // Never tamper with PiP or Media prototypes on Meta/Facebook, Arkose Labs, CAPTCHA, Turnstile, or Auth pages.
            // Preserves 100% native Blink prototypes and prevents security checkpoints from flagging modified DOM APIs.
            if (/facebook\.com|meta\.com|fb\.com|fb\.me|fbcdn\.net|facebook\.net|fbsbx\.com|instagram\.com|cdninstagram\.com|messenger\.com|threads\.net|accountkit\.com|arkose|funcaptcha|matchkey|turnstile|recaptcha|hcaptcha|datadome|perimeterx|kasada|geetest|challenges\.cloudflare\.com/i.test(host + ' ' + href + ' ' + ref) ||
                path.indexOf('/checkpoint/') !== -1 || path.indexOf('/login/') !== -1 || path.indexOf('/auth/') !== -1 ||
                path.indexOf('/captcha/') !== -1 || path.indexOf('/challenge/') !== -1 || path.indexOf('/fc/') !== -1 ||
                href.indexOf('/checkpoint/') !== -1 || href.indexOf('/challenge/') !== -1 || href.indexOf('/captcha/') !== -1 ||
                href.indexOf('/login/') !== -1 || href.indexOf('/auth/') !== -1 || href.indexOf('lsd=') !== -1 ||
                href.indexOf('jazoest=') !== -1 || href.indexOf('datr=') !== -1) {
                return;
            }

            if (window.__onyx_monitor_active) return;
            window.__onyx_monitor_active = true;

            try {
                // ── 1. W3C Picture-in-Picture Web API Polyfill ─────────────────────────
                var currentPipElem = null;

                // Track user interactions to guard against unsolicited programmatic PiP calls & detect user pause intent
                function recordTouch(e) {
                    window.__onyx_last_user_touch = Date.now();
                    try {
                        var t = e && e.target;
                        if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO' || (t.closest && t.closest('video, audio, .ytp-play-button, button, [role="button"]')))) {
                            window.__onyx_last_player_touch = Date.now();
                        }
                    } catch (_) {}
                }
                window.addEventListener('pointerdown', recordTouch, true);
                window.addEventListener('touchstart', recordTouch, true);
                window.addEventListener('click', recordTouch, true);

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
                            var timeSinceTouch = Date.now() - (window.__onyx_last_user_touch || 0);
                            var isUserGesture = timeSinceTouch < 2500;
                            // Block unprompted script/background requests that cause auto-PiP re-entry loops
                            if (!isUserGesture && !document.hasFocus()) {
                                reject(new DOMException("Must be handling a user gesture to enter Picture-in-Picture", "NotAllowedError"));
                                return;
                            }
                            currentPipElem = self;
                            window.__onyx_current_pip_element = self;
                            var pipW = self.videoWidth || self.clientWidth || 320;
                            var pipH = self.videoHeight || self.clientHeight || 180;
                            if (window.OnyxMediaBridge && typeof window.OnyxMediaBridge.onVideoPresenceChanged === 'function') {
                                window.OnyxMediaBridge.onVideoPresenceChanged(true, self.currentSrc || self.src || '', pipW, pipH);
                            }
                            reportVideoBounds(self);

                            var bridgeCalled = false;
                            if (window.OnyxMediaBridge && typeof window.OnyxMediaBridge.requestVideoPip === 'function') {
                                window.OnyxMediaBridge.requestVideoPip();
                                bridgeCalled = true;
                            }

                            if (window.top && window.top !== window) {
                                try {
                                    window.top.postMessage({
                                        __onyx_cmd: 'pip_request',
                                        width: pipW,
                                        height: pipH,
                                        bridgeHandled: bridgeCalled,
                                        isUserGesture: isUserGesture
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
                var lastReportedVideoBounds = { left: 0, top: 0, right: 0, bottom: 0, vw: 0, vh: 0 };

                function reportVideoBounds(elem) {
                    try {
                        if (!(elem instanceof HTMLVideoElement)) return;
                        var r = elem.getBoundingClientRect();
                        if (r.width < 16 || r.height < 16) return;
                        var vw = window.innerWidth || (document.documentElement ? document.documentElement.clientWidth : 0) || 0;
                        var vh = window.innerHeight || (document.documentElement ? document.documentElement.clientHeight : 0) || 0;
                        if (Math.abs(r.left - lastReportedVideoBounds.left) <= 3 &&
                            Math.abs(r.top - lastReportedVideoBounds.top) <= 3 &&
                            Math.abs(r.right - lastReportedVideoBounds.right) <= 3 &&
                            Math.abs(r.bottom - lastReportedVideoBounds.bottom) <= 3 &&
                            Math.abs(vw - lastReportedVideoBounds.vw) <= 3 &&
                            Math.abs(vh - lastReportedVideoBounds.vh) <= 3) {
                            return;
                        }
                        lastReportedVideoBounds = { left: r.left, top: r.top, right: r.right, bottom: r.bottom, vw: vw, vh: vh };
                        if (window === window.top) {
                            if (window.OnyxMediaBridge) {
                                if (typeof window.OnyxMediaBridge.onVideoBoundsChangedWithViewport === 'function') {
                                    window.OnyxMediaBridge.onVideoBoundsChangedWithViewport(r.left, r.top, r.right, r.bottom, vw, vh);
                                } else if (typeof window.OnyxMediaBridge.onVideoBoundsChanged === 'function') {
                                    window.OnyxMediaBridge.onVideoBoundsChanged(r.left, r.top, r.right, r.bottom);
                                }
                            }
                        } else if (window.parent && window.parent !== window) {
                            window.parent.postMessage({
                                __onyx_cmd: 'subframe_video_bounds',
                                left: r.left,
                                top: r.top,
                                right: r.right,
                                bottom: r.bottom,
                                videoWidth: elem.videoWidth || 0,
                                videoHeight: elem.videoHeight || 0
                            }, '*');
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
                        // Filter out muted looping decorative animations or muted autoplay background visuals
                        var isMuted = elem.muted || elem.volume === 0 || elem.hasAttribute('muted');
                        if (isMuted && (elem.loop || elem.hasAttribute('loop') || (!elem.controls && !elem.hasAttribute('controls')))) {
                            return false;
                        }
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
                        var allMedia = getAllMedia(document);
                        var anyPlaying = allMedia.some(function(m) {
                            return !m.paused && !m.ended && isQualifyingMedia(m);
                        });
                        if (!anyPlaying) {
                            var now = Date.now();
                            var timeSinceTouch = now - Math.max(window.__onyx_last_player_touch || 0, window.__onyx_last_user_touch || 0);
                            var anyUserHitPause = allMedia.some(function(m) { return !!m.userHitPause; });
                            // Touches cannot happen while backgrounded; only explicit native commands count then.
                            var recentTouch = !window.__onyx_in_background && (timeSinceTouch < 3500);
                            var isUserIntent = !!window.__onyx_allow_explicit_pause || anyUserHitPause || recentTouch;
                            if (typeof window.OnyxMediaBridge.onMediaPausedWithIntent === 'function') {
                                window.OnyxMediaBridge.onMediaPausedWithIntent(isUserIntent);
                            } else {
                                window.OnyxMediaBridge.onMediaPaused();
                            }
                        }
                    }, 400);
                }

                function reportMediaEnded(elem) {
                    if (!window.OnyxMediaBridge) return;
                    setTimeout(function() {
                        var anyPlaying = getAllMedia(document).some(function(m) {
                            return !m.paused && !m.ended && isQualifyingMedia(m);
                        });
                        if (!anyPlaying) {
                            if (typeof window.OnyxMediaBridge.onMediaEnded === 'function') {
                                window.OnyxMediaBridge.onMediaEnded();
                            } else if (typeof window.OnyxMediaBridge.onMediaPausedWithIntent === 'function') {
                                window.OnyxMediaBridge.onMediaPausedWithIntent(true);
                            } else {
                                window.OnyxMediaBridge.onMediaPaused();
                            }
                        }
                    }, 300);
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
                        reportMediaEnded(elem);
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
                        if (elem instanceof HTMLVideoElement) {
                            if (elem.videoWidth > 0 && elem.videoHeight > 0 && window.OnyxMediaBridge && typeof window.OnyxMediaBridge.onVideoDimensionsChanged === 'function') {
                                window.OnyxMediaBridge.onVideoDimensionsChanged(elem.videoWidth, elem.videoHeight);
                            }
                            reportVideoBounds(elem);
                        }
                    }, true);

                    if (elem instanceof HTMLVideoElement) {
                        elem.addEventListener('resize', function() {
                            if (elem.videoWidth > 0 && elem.videoHeight > 0 && isQualifyingMedia(elem)) {
                                if (window.OnyxMediaBridge && typeof window.OnyxMediaBridge.onVideoDimensionsChanged === 'function') {
                                    window.OnyxMediaBridge.onVideoDimensionsChanged(elem.videoWidth, elem.videoHeight);
                                }
                                reportVideoBounds(elem);
                            }
                        }, true);
                    }

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
                    if (e.target instanceof HTMLMediaElement) reportMediaEnded(e.target);
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
                        var metaDesc = Object.getOwnPropertyDescriptor(MediaSession.prototype, 'metadata');
                        if (metaDesc && metaDesc.set) {
                            var origMetadataSetter = metaDesc.set;
                            var origMetadataGetter = metaDesc.get;
                            Object.defineProperty(navigator.mediaSession, 'metadata', {
                                get: function() {
                                    return origMetadataGetter ? origMetadataGetter.call(this) : undefined;
                                },
                                set: function(val) {
                                    origMetadataSetter.call(this, val);
                                    if (val && window.OnyxMediaBridge) {
                                        var art = (val.artwork && val.artwork.length > 0) ? (val.artwork[val.artwork.length - 1].src || val.artwork[0].src || '') : '';
                                        window.OnyxMediaBridge.onMediaMetadata(val.title || '', val.artist || '', art);
                                    }
                                },
                                configurable: true,
                                enumerable: true
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
                            var vids = getAllMedia(document).filter(function(m) { return m instanceof HTMLVideoElement; });
                            var v = vids.find(function(i) { return !i.paused && !i.ended; })
                                 || vids.find(function(i) { return i.currentTime > 0; })
                                 || vids[0];
                            if (v) {
                                // Clean previous subframe PiP markers if any
                                var prevSt = document.getElementById('__onyx_pip_style');
                                if (prevSt) prevSt.remove();
                                document.querySelectorAll('[data-onyx-pip-video], [data-onyx-pip-ancestor]').forEach(function(el) {
                                    el.removeAttribute('data-onyx-pip-video');
                                    el.removeAttribute('data-onyx-pip-ancestor');
                                });
                                v.setAttribute('data-onyx-pip-video', 'true');
                                var anc = v.parentElement || (v.getRootNode && v.getRootNode().host ? v.getRootNode().host : null);
                                while (anc && anc !== document.documentElement) {
                                    if (anc.setAttribute) anc.setAttribute('data-onyx-pip-ancestor', 'true');
                                    anc = anc.parentElement || (anc.getRootNode && anc.getRootNode().host ? anc.getRootNode().host : null);
                                }
                                var subStyle = document.createElement('style');
                                subStyle.id = '__onyx_pip_style';
                                subStyle.textContent = [
                                    'html, body { overflow: hidden !important; background: #000 !important; margin: 0 !important; padding: 0 !important; width: 100% !important; height: 100% !important; }',
                                    '[data-onyx-pip-ancestor="true"] { visibility: visible !important; opacity: 1 !important; transform: none !important; will-change: auto !important; contain: none !important; filter: none !important; backdrop-filter: none !important; -webkit-backdrop-filter: none !important; clip-path: none !important; perspective: none !important; isolation: auto !important; overflow: visible !important; position: static !important; z-index: auto !important; }',
                                    'body > *:not([data-onyx-pip-ancestor]):not([data-onyx-pip-video]):not(script):not(style):not(audio),',
                                    '[data-onyx-pip-ancestor] > *:not([data-onyx-pip-ancestor]):not([data-onyx-pip-video]):not(script):not(style):not(audio):not(source):not(track) { visibility: hidden !important; opacity: 0 !important; pointer-events: none !important; }',
                                    '[data-onyx-pip-video="true"] { display: block !important; visibility: visible !important; opacity: 1 !important; position: fixed !important; inset: 0 !important; top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important; width: 100% !important; height: 100% !important; min-width: 100% !important; min-height: 100% !important; max-width: 100% !important; max-height: 100% !important; transform: none !important; object-fit: contain !important; object-position: center center !important; background: #000 !important; z-index: 2147483647 !important; border: none !important; margin: 0 !important; padding: 0 !important; pointer-events: none !important; }'
                                ].join('\n');
                                (document.head || document.documentElement).appendChild(subStyle);
                            }
                        } else if (cmd === 'subframe_video_bounds') {
                            var srcFrame = Array.from(document.querySelectorAll('iframe')).find(function(f) {
                                return f.contentWindow === e.source;
                            });
                            if (srcFrame) {
                                var fr = srcFrame.getBoundingClientRect();
                                var absLeft = fr.left + (e.data.left || 0);
                                var absTop = fr.top + (e.data.top || 0);
                                var absRight = Math.min(fr.right, fr.left + (e.data.right || fr.width));
                                var absBottom = Math.min(fr.bottom, fr.top + (e.data.bottom || fr.height));
                                if (window === window.top) {
                                    var topVw = window.innerWidth || (document.documentElement ? document.documentElement.clientWidth : 0) || 0;
                                    var topVh = window.innerHeight || (document.documentElement ? document.documentElement.clientHeight : 0) || 0;
                                    if (window.OnyxMediaBridge) {
                                        if (e.data.videoWidth > 0 && e.data.videoHeight > 0 && typeof window.OnyxMediaBridge.onVideoDimensionsChanged === 'function') {
                                            window.OnyxMediaBridge.onVideoDimensionsChanged(e.data.videoWidth, e.data.videoHeight);
                                        }
                                        if (typeof window.OnyxMediaBridge.onVideoBoundsChangedWithViewport === 'function') {
                                            window.OnyxMediaBridge.onVideoBoundsChangedWithViewport(absLeft, absTop, absRight, absBottom, topVw, topVh);
                                        } else if (typeof window.OnyxMediaBridge.onVideoBoundsChanged === 'function') {
                                            window.OnyxMediaBridge.onVideoBoundsChanged(absLeft, absTop, absRight, absBottom);
                                        }
                                    }
                                } else if (window.parent && window.parent !== window) {
                                    window.parent.postMessage({
                                        __onyx_cmd: 'subframe_video_bounds',
                                        left: absLeft,
                                        top: absTop,
                                        right: absRight,
                                        bottom: absBottom,
                                        videoWidth: e.data.videoWidth || 0,
                                        videoHeight: e.data.videoHeight || 0
                                    }, '*');
                                }
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
                                    var pVw = window.innerWidth || (document.documentElement ? document.documentElement.clientWidth : 0) || 0;
                                    var pVh = window.innerHeight || (document.documentElement ? document.documentElement.clientHeight : 0) || 0;
                                    if (window.OnyxMediaBridge) {
                                        if (e.data.width > 0 && e.data.height > 0 && typeof window.OnyxMediaBridge.onVideoDimensionsChanged === 'function') {
                                            window.OnyxMediaBridge.onVideoDimensionsChanged(e.data.width, e.data.height);
                                        }
                                        if (typeof window.OnyxMediaBridge.onVideoBoundsChangedWithViewport === 'function') {
                                            window.OnyxMediaBridge.onVideoBoundsChangedWithViewport(r.left, r.top, r.right, r.bottom, pVw, pVh);
                                        } else if (typeof window.OnyxMediaBridge.onVideoBoundsChanged === 'function') {
                                            window.OnyxMediaBridge.onVideoBoundsChanged(r.left, r.top, r.right, r.bottom);
                                        }
                                    }
                                } catch (_) {}
                            }
                            if (!e.data.bridgeHandled && e.data.isUserGesture !== false && window.OnyxMediaBridge && typeof window.OnyxMediaBridge.requestVideoPip === 'function') {
                                window.OnyxMediaBridge.requestVideoPip();
                            }
                        } else if (cmd === 'pip_exit') {
                            var exitStyle = document.getElementById('__onyx_pip_style');
                            if (exitStyle) exitStyle.remove();
                            document.querySelectorAll('[data-onyx-pip-video], [data-onyx-pip-ancestor], [data-onyx-pip-target]').forEach(function(el) {
                                el.removeAttribute('data-onyx-pip-video');
                                el.removeAttribute('data-onyx-pip-ancestor');
                                el.removeAttribute('data-onyx-pip-target');
                            });
                            if (currentPipElem) {
                                var oldPip = currentPipElem;
                                currentPipElem = null;
                                try { oldPip.dispatchEvent(new Event('leavepictureinpicture', { bubbles: true })); } catch (_) {}
                            }
                            window.__onyx_current_pip_element = null;
                            try { window.dispatchEvent(new Event('resize')); } catch (_) {}
                        }

                        // Recursively forward downward commands to child iframes (never upward bounds/request events)
                        if (cmd !== 'pip_request' && cmd !== 'subframe_video_bounds') {
                            document.querySelectorAll('iframe').forEach(function(f) {
                                try { f.contentWindow.postMessage(e.data, '*'); } catch (_) {}
                            });
                        }
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
     * 5. Blocks background audio muting.
     */
    val backgroundPlaybackScript: String = """
        (function() {
            // Do not run background playback patches on Meta/Facebook auth domains.
            // Facebook's login confirmation checks document.hasFocus() and visibilityState as
            // bot-detection signals. Patching these to always return 'visible'/'true' can cause
            // the security verification to flag the session as automated and show "confirmation failed".
            var _bgRef = (document.referrer || '').toLowerCase();
            var _bgHost = (location.hostname || '').toLowerCase();
            var _bgHref = (location.href || '').toLowerCase();
            var _bgPath = (location.pathname || '').toLowerCase();

            if (/facebook\.com|meta\.com|fb\.com|fb\.me|fbcdn\.net|facebook\.net|fbsbx\.com|instagram\.com|cdninstagram\.com|messenger\.com|threads\.net|accountkit\.com|arkose|funcaptcha|matchkey|turnstile|recaptcha|hcaptcha|datadome|perimeterx|kasada|geetest|challenges\.cloudflare\.com/i.test(_bgHost + ' ' + _bgHref + ' ' + _bgRef) ||
                _bgPath.indexOf('/checkpoint/') !== -1 || _bgPath.indexOf('/login/') !== -1 || _bgPath.indexOf('/auth/') !== -1 ||
                _bgPath.indexOf('/captcha/') !== -1 || _bgPath.indexOf('/challenge/') !== -1 || _bgPath.indexOf('/fc/') !== -1 ||
                _bgHref.indexOf('/checkpoint/') !== -1 || _bgHref.indexOf('/challenge/') !== -1 || _bgHref.indexOf('/captcha/') !== -1 ||
                _bgHref.indexOf('/login/') !== -1 || _bgHref.indexOf('/auth/') !== -1 || _bgHref.indexOf('lsd=') !== -1 ||
                _bgHref.indexOf('jazoest=') !== -1 || _bgHref.indexOf('datr=') !== -1) {
                return;
            }

            window.__onyx_bg_play_active = true;
            if (typeof window.__onyx_in_background === 'undefined') {
                window.__onyx_in_background = false;
            }
            if (window.__onyx_bg_script_installed) return;
            window.__onyx_bg_script_installed = true;

            try {
                var isYouTube = /youtube\.com|youtu\.be/i.test(_bgHost);

                // ── 1. Page Visibility Interception (Brave & Mozilla video-bg-play parity) ──
                // Intercept visibility events with capture=true BEFORE any website listeners can receive them
                function suppressVisibilityEvent(e) {
                    if (window.__onyx_bg_play_active) {
                        e.stopImmediatePropagation();
                    }
                }
                window.addEventListener('visibilitychange', suppressVisibilityEvent, true);
                document.addEventListener('visibilitychange', suppressVisibilityEvent, true);
                window.addEventListener('webkitvisibilitychange', suppressVisibilityEvent, true);
                document.addEventListener('webkitvisibilitychange', suppressVisibilityEvent, true);

                if (document._addEventListener === undefined) {
                    document._addEventListener = document.addEventListener;
                    document.addEventListener = function(type, listener, options) {
                        if (window.__onyx_bg_play_active && (type === 'visibilitychange' || type === 'webkitvisibilitychange')) {
                            return;
                        }
                        return document._addEventListener.call(this, type, listener, options);
                    };
                }

                // ── 2. Universal Visibility State Spoofing: Document.prototype.visibilityState ──
                try {
                    var descriptor = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
                    if (descriptor && descriptor.get) {
                        var origGet = descriptor.get;
                        Object.defineProperty(Document.prototype, 'visibilityState', {
                            enumerable: descriptor.enumerable,
                            configurable: true,
                            get: function() {
                                if (window.__onyx_bg_play_active) {
                                    return 'visible';
                                }
                                return origGet.call(this);
                            }
                        });
                    }
                } catch (_) {}

                try {
                    var hiddenDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden');
                    if (hiddenDesc && hiddenDesc.get) {
                        var origHiddenGet = hiddenDesc.get;
                        Object.defineProperty(Document.prototype, 'hidden', {
                            enumerable: hiddenDesc.enumerable,
                            configurable: true,
                            get: function() {
                                if (window.__onyx_bg_play_active) {
                                    return false;
                                }
                                return origHiddenGet.call(this);
                            }
                        });
                    }
                } catch (_) {}

                try {
                    Object.defineProperty(document, 'visibilityState', {
                        configurable: true,
                        get: function() { return 'visible'; }
                    });
                    Object.defineProperty(document, 'hidden', {
                        configurable: true,
                        get: function() { return false; }
                    });
                } catch (_) {}

                // ── 3. Universal Brave MediaBackgrounding: userHitPause & Auto-Resume ──
                var origPause = HTMLMediaElement.prototype.pause;
                HTMLMediaElement.prototype.pause = function() {
                    var now = Date.now();
                    var timeSinceTouch = now - Math.max(window.__onyx_last_player_touch || 0, window.__onyx_last_user_touch || 0);
                    var isExplicit = !!window.__onyx_allow_explicit_pause;
                    var isUserIntent = isExplicit || (!window.__onyx_in_background && timeSinceTouch < 3500);
                    this.userHitPause = isUserIntent;
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

                    // Auto-resume of background pauses is decided natively (MediaPlaybackBridge),
                    // which knows the real app/PiP/call state and rate-limits resumes.

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

                // ── 4. YouTube-Only kYoutubePictureInPictureSupport & Inactivity Keep-Alive ──
                if (isYouTube) {
                    // Refresh last active time so YouTube never shows "Video paused. Continue watching?"
                    setInterval(function() {
                        try {
                            window._lact = Date.now();
                            if (window.ytcfg && typeof window.ytcfg.set === 'function') {
                                window.ytcfg.set('DEVICE_LATENCY', 0);
                            }
                        } catch (_) {}
                    }, 30000);

                    // Re-bind listeners on YouTube SPA navigation
                    ['yt-navigate-finish', 'yt-page-data-updated', 'spfdone'].forEach(function(evtName) {
                        document.addEventListener(evtName, function() {
                            setTimeout(function() {
                                document.querySelectorAll('video, audio').forEach(function(el) {
                                    addBackgroundListeners(el);
                                });
                            }, 300);
                        }, true);
                    });

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
                        var ytFitStyle = document.createElement('style');
                        ytFitStyle.id = '__onyx_yt_fit_style';
                        ytFitStyle.textContent = '#player-container-id:fullscreen video.html5-main-video { width: 100% !important; height: 100dvh !important; left: 0 !important; top: 0 !important; object-fit: contain !important; }';
                        (document.head || document.documentElement).appendChild(ytFitStyle);
                    } catch (_) {}
                }

                // ── 5. Prevent Site Scripts From Muting in Background ─────────────────────
                try {
                    var origMuted = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'muted');
                    if (origMuted && origMuted.set) {
                        Object.defineProperty(HTMLMediaElement.prototype, 'muted', {
                            get: function() { return origMuted.get.call(this); },
                            set: function(val) {
                                if (window.__onyx_bg_play_active && window.__onyx_in_background && val && !window.__onyx_allow_explicit_pause) {
                                    return; // Don't allow websites to mute while backgrounded (foreground mute buttons still work)
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
    /**
     * Non-mutating probe script that locates the active <video> or embedded player <iframe>
     * and returns its aspect dimensions + viewport bounds prior to entering Picture-in-Picture,
     * avoiding full-screen DOM flashes before Android begins the PiP window transition.
     */
    val probeVideoForPipScript: String = """
        (function() {
            try {
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

                var activeEl = window.__onyx_current_pip_element;
                if (activeEl && (!activeEl.isConnected || activeEl.ownerDocument !== document)) {
                    activeEl = null;
                }

                var intrinsicW = 0;
                var intrinsicH = 0;

                if (!activeEl) {
                    var vids = findVideos(document);
                    activeEl = vids.find(function(v) { return !v.paused && !v.ended && (v.videoWidth > 32 || v.clientWidth > 32); })
                        || vids.find(function(v) { return v.currentTime > 0; })
                        || vids.find(function(v) { return !!(v.currentSrc || v.src || v.srcObject); })
                        || vids[0];
                }

                if (!activeEl) {
                    var iframes = Array.from(document.querySelectorAll('iframe'));
                    for (var idx = 0; idx < iframes.length; idx++) {
                        try {
                            var subDoc = iframes[idx].contentDocument;
                            if (subDoc) {
                                var subVids = findVideos(subDoc);
                                var sv = subVids.find(function(v) { return !v.paused && !v.ended; }) || subVids[0];
                                if (sv) {
                                    activeEl = iframes[idx];
                                    intrinsicW = sv.videoWidth || sv.clientWidth || 0;
                                    intrinsicH = sv.videoHeight || sv.clientHeight || 0;
                                    break;
                                }
                            }
                        } catch (_) {}
                    }
                }

                if (!activeEl) {
                    var candidateFrames = Array.from(document.querySelectorAll('iframe'));
                    var scored = candidateFrames.map(function(f) {
                        var score = 0;
                        var src = (f.src || f.getAttribute('data-src') || f.getAttribute('data-url') || '').toLowerCase();
                        var id = (f.id || '').toLowerCase();
                        var cls = (f.className || '').toLowerCase();
                        var allow = (f.getAttribute('allow') || '').toLowerCase();
                        var hasFs = f.hasAttribute('allowfullscreen') || f.hasAttribute('webkitallowfullscreen') || f.hasAttribute('mozallowfullscreen');
                        if (hasFs) score += 35;
                        if (allow.includes('fullscreen')) score += 30;
                        if (allow.includes('autoplay')) score += 15;
                        ['embed', 'player', 'video', 'stream', 'watch', 'youtube', 'youtu.be', 'vimeo', 'twitch', 'dailymotion', 'bilibili', 'mp4', 'm3u8', 'hls', '/e/', '/v/'].forEach(function(p) {
                            if (src.includes(p)) score += 20;
                        });
                        if (id.includes('player') || id.includes('video') || cls.includes('player') || cls.includes('video')) score += 25;
                        try {
                            var r = f.getBoundingClientRect();
                            if (r.width * r.height > 25000) score += 30;
                            if (r.width < 50 || r.height < 50) score -= 100;
                        } catch (_) {}
                        return { iframe: f, score: score };
                    }).filter(function(item) { return item.score > 0; });
                    scored.sort(function(a, b) { return b.score - a.score; });
                    if (scored.length > 0) activeEl = scored[0].iframe;
                }

                if (!activeEl) {
                    return JSON.stringify({ found: false });
                }

                var rect = activeEl.getBoundingClientRect();
                var vw = window.innerWidth || (document.documentElement ? document.documentElement.clientWidth : 0) || 0;
                var vh = window.innerHeight || (document.documentElement ? document.documentElement.clientHeight : 0) || 0;
                var w = intrinsicW || activeEl.videoWidth || Math.round(rect.width) || activeEl.clientWidth || 16;
                var h = intrinsicH || activeEl.videoHeight || Math.round(rect.height) || activeEl.clientHeight || 9;
                return JSON.stringify({
                    found: true,
                    width: w,
                    height: h,
                    left: rect.left,
                    top: rect.top,
                    right: rect.right,
                    bottom: rect.bottom,
                    vw: vw,
                    vh: vh
                });
            } catch (e) {
                return JSON.stringify({ found: false, error: e.message });
            }
        })();
    """.trimIndent()

    /**
     * Isolates ONLY the active <video> (or embedded player <iframe>) inside the PiP window:
     * - Stylesheet-only ancestor & sibling isolation so pre-existing inline styles are never clobbered.
     * - Tags strictly the single active media element with [data-onyx-pip-video="true"], preventing
     *   hidden helper iframes or secondary preload <video> tags from painting solid black boxes over video.
     * - Hides all sibling controls, headers, watermarks, and overlays via visibility/opacity so layout
     *   geometry stays intact and player ResizeObservers never pause playback.
     */
    val isolateVideoForPipScript: String = """
        (function() {
            try {
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

                // 1. Check designated PiP element first (must belong to top document / shadow tree)
                var activeVid = window.__onyx_current_pip_element;
                if (activeVid && (!activeVid.isConnected || activeVid.ownerDocument !== document)) {
                    activeVid = null;
                }

                var subVideoIntrinsicW = 0;
                var subVideoIntrinsicH = 0;

                // 2. Search top-document light DOM + Shadow DOM for HTML5 <video>
                if (!activeVid) {
                    var vids = findVideos(document);
                    activeVid = vids.find(function(v) { return !v.paused && !v.ended && (v.videoWidth > 32 || v.clientWidth > 32); })
                        || vids.find(function(v) { return !v.paused && !v.ended; })
                        || vids.find(function(v) { return v.currentTime > 0 && (v.videoWidth > 32 || v.clientWidth > 32); })
                        || vids.find(function(v) { return !!(v.currentSrc || v.src || v.srcObject); })
                        || vids[0];
                }

                // 3. Check same-origin iframes: isolate the <iframe> in parent DOM and <video> inside iframe
                if (!activeVid) {
                    var sameOriginFrames = Array.from(document.querySelectorAll('iframe'));
                    for (var sf = 0; sf < sameOriginFrames.length; sf++) {
                        try {
                            var subDoc = sameOriginFrames[sf].contentDocument;
                            if (subDoc) {
                                var subVids = findVideos(subDoc);
                                var bestSub = subVids.find(function(v) { return !v.paused && !v.ended; })
                                    || subVids.find(function(v) { return v.currentTime > 0; })
                                    || subVids[0];
                                if (bestSub) {
                                    activeVid = sameOriginFrames[sf];
                                    subVideoIntrinsicW = bestSub.videoWidth || bestSub.clientWidth || 0;
                                    subVideoIntrinsicH = bestSub.videoHeight || bestSub.clientHeight || 0;
                                    break;
                                }
                            }
                        } catch (_) {}
                    }
                }

                // 4. Score cross-origin streaming player iframes if no direct <video> exists
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

                if (!activeVid) {
                    return JSON.stringify({ found: false, width: 16, height: 9 });
                }

                // Clean any previous PiP markers/styles before re-applying (idempotent for playlist transitions)
                var oldStyle = document.getElementById('__onyx_pip_style');
                if (oldStyle) oldStyle.remove();
                document.querySelectorAll('[data-onyx-pip-video], [data-onyx-pip-ancestor], [data-onyx-pip-target]').forEach(function(el) {
                    el.removeAttribute('data-onyx-pip-video');
                    el.removeAttribute('data-onyx-pip-ancestor');
                    el.removeAttribute('data-onyx-pip-target');
                });
                if (Array.isArray(window.__onyx_pip_shadow_roots)) {
                    window.__onyx_pip_shadow_roots.forEach(function(sr) {
                        try {
                            sr.querySelectorAll('.__onyx_pip_shadow_style').forEach(function(s) { s.remove(); });
                            sr.querySelectorAll('[data-onyx-pip-video], [data-onyx-pip-ancestor]').forEach(function(el) {
                                el.removeAttribute('data-onyx-pip-video');
                                el.removeAttribute('data-onyx-pip-ancestor');
                            });
                        } catch (_) {}
                    });
                }
                window.__onyx_pip_shadow_roots = [];

                if (!window.__onyx_pre_pip_scroll) {
                    window.__onyx_pre_pip_scroll = { x: window.scrollX || 0, y: window.scrollY || 0 };
                }
                window.scrollTo(0, 0);

                // Tag ONLY the single active video/iframe element
                activeVid.setAttribute('data-onyx-pip-video', 'true');

                var shadowCss = [
                    ':host { visibility: visible !important; opacity: 1 !important; transform: none !important; contain: none !important; overflow: visible !important; }',
                    '[data-onyx-pip-ancestor="true"] { visibility: visible !important; opacity: 1 !important; transform: none !important; will-change: auto !important; contain: none !important; content-visibility: visible !important; filter: none !important; backdrop-filter: none !important; -webkit-backdrop-filter: none !important; clip-path: none !important; mask: none !important; -webkit-mask: none !important; perspective: none !important; isolation: auto !important; mix-blend-mode: normal !important; overflow: visible !important; position: static !important; z-index: auto !important; }',
                    ':host > *:not([data-onyx-pip-ancestor]):not([data-onyx-pip-video]):not(script):not(style):not(audio):not(source):not(track),',
                    '[data-onyx-pip-ancestor] > *:not([data-onyx-pip-ancestor]):not([data-onyx-pip-video]):not(script):not(style):not(audio):not(source):not(track) { visibility: hidden !important; opacity: 0 !important; pointer-events: none !important; }',
                    '[data-onyx-pip-video="true"] { display: block !important; visibility: visible !important; opacity: 1 !important; position: fixed !important; inset: 0 !important; top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important; width: 100% !important; height: 100% !important; min-width: 100% !important; min-height: 100% !important; max-width: 100% !important; max-height: 100% !important; transform: none !important; object-fit: contain !important; object-position: center center !important; background: #000000 !important; z-index: 2147483647 !important; margin: 0 !important; padding: 0 !important; border: none !important; outline: none !important; box-shadow: none !important; pointer-events: none !important; }'
                ].join('\n');

                function registerShadowRootIfNeeded(node) {
                    try {
                        var rn = node && node.getRootNode ? node.getRootNode() : null;
                        if (rn && rn.host && window.__onyx_pip_shadow_roots.indexOf(rn) === -1) {
                            window.__onyx_pip_shadow_roots.push(rn);
                            var shStyle = document.createElement('style');
                            shStyle.className = '__onyx_pip_shadow_style';
                            shStyle.textContent = shadowCss;
                            rn.appendChild(shStyle);
                        }
                    } catch (_) {}
                }

                registerShadowRootIfNeeded(activeVid);

                var cur = activeVid.parentElement || (activeVid.getRootNode && activeVid.getRootNode().host ? activeVid.getRootNode().host : null);
                while (cur && cur !== document.documentElement) {
                    if (cur.setAttribute) {
                        cur.setAttribute('data-onyx-pip-ancestor', 'true');
                    }
                    registerShadowRootIfNeeded(cur);
                    cur = cur.parentElement || (cur.getRootNode && cur.getRootNode().host ? cur.getRootNode().host : null);
                }

                // Notify child iframes so embedded players also isolate their internal <video>
                try {
                    var msg = { __onyx_cmd: 'pip' };
                    document.querySelectorAll('iframe').forEach(function(f) {
                        try { f.contentWindow.postMessage(msg, '*'); } catch (_) {}
                    });
                } catch (_) {}

                var style = document.createElement('style');
                style.id = '__onyx_pip_style';
                style.textContent = [
                    'html, body {',
                    '  overflow: hidden !important;',
                    '  background: #000000 !important;',
                    '  margin: 0 !important;',
                    '  padding: 0 !important;',
                    '  width: 100% !important;',
                    '  height: 100% !important;',
                    '}',
                    '[data-onyx-pip-ancestor="true"] {',
                    '  visibility: visible !important;',
                    '  opacity: 1 !important;',
                    '  transform: none !important;',
                    '  will-change: auto !important;',
                    '  contain: none !important;',
                    '  content-visibility: visible !important;',
                    '  filter: none !important;',
                    '  backdrop-filter: none !important;',
                    '  -webkit-backdrop-filter: none !important;',
                    '  clip-path: none !important;',
                    '  mask: none !important;',
                    '  -webkit-mask: none !important;',
                    '  perspective: none !important;',
                    '  isolation: auto !important;',
                    '  mix-blend-mode: normal !important;',
                    '  overflow: visible !important;',
                    '  position: static !important;',
                    '  z-index: auto !important;',
                    '}',
                    'body > *:not([data-onyx-pip-ancestor]):not([data-onyx-pip-video]):not(script):not(style):not(audio),',
                    '[data-onyx-pip-ancestor] > *:not([data-onyx-pip-ancestor]):not([data-onyx-pip-video]):not(script):not(style):not(audio):not(source):not(track) {',
                    '  visibility: hidden !important;',
                    '  opacity: 0 !important;',
                    '  pointer-events: none !important;',
                    '}',
                    '[data-onyx-pip-video="true"] {',
                    '  display: block !important;',
                    '  visibility: visible !important;',
                    '  opacity: 1 !important;',
                    '  position: fixed !important;',
                    '  inset: 0 !important;',
                    '  top: 0 !important;',
                    '  left: 0 !important;',
                    '  right: 0 !important;',
                    '  bottom: 0 !important;',
                    '  width: 100% !important;',
                    '  height: 100% !important;',
                    '  min-width: 100% !important;',
                    '  min-height: 100% !important;',
                    '  max-width: 100% !important;',
                    '  max-height: 100% !important;',
                    '  transform: none !important;',
                    '  object-fit: contain !important;',
                    '  object-position: center center !important;',
                    '  background: #000000 !important;',
                    '  z-index: 2147483647 !important;',
                    '  margin: 0 !important;',
                    '  padding: 0 !important;',
                    '  border: none !important;',
                    '  outline: none !important;',
                    '  box-shadow: none !important;',
                    '  pointer-events: none !important;',
                    '}'
                ].join('\n');
                (document.head || document.documentElement).appendChild(style);

                var vidWidth = subVideoIntrinsicW || activeVid.videoWidth || activeVid.clientWidth || activeVid.offsetWidth || 16;
                var vidHeight = subVideoIntrinsicH || activeVid.videoHeight || activeVid.clientHeight || activeVid.offsetHeight || 9;
                return JSON.stringify({ found: true, width: vidWidth, height: vidHeight });
            } catch (e) {
                return JSON.stringify({ found: false, error: e.message, width: 16, height: 9 });
            }
        })();
    """.trimIndent()

    /**
     * Restores normal in-page layout when exiting PiP mode without clobbering original inline styles.
     */
    val restoreVideoFromPipScript: String = """
        (function() {
            try {
                var style = document.getElementById('__onyx_pip_style');
                var hasVideo = document.querySelector('[data-onyx-pip-video]');
                var hasTarget = document.querySelector('[data-onyx-pip-target]');
                var hasAncestor = document.querySelector('[data-onyx-pip-ancestor]');
                var hasShadows = Array.isArray(window.__onyx_pip_shadow_roots) && window.__onyx_pip_shadow_roots.length > 0;
                var hasPipElem = (typeof currentPipElem !== 'undefined' && currentPipElem) || window.__onyx_current_pip_element;
                if (!style && !hasVideo && !hasTarget && !hasAncestor && !hasShadows && !hasPipElem) {
                    return; // PiP was not active; avoid dispatching unsolicited resize events to web apps
                }
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
                if (style) style.remove();
                document.querySelectorAll('[data-onyx-pip-video], [data-onyx-pip-ancestor], [data-onyx-pip-target]').forEach(function(el) {
                    el.removeAttribute('data-onyx-pip-video');
                    el.removeAttribute('data-onyx-pip-ancestor');
                    el.removeAttribute('data-onyx-pip-target');
                });
                if (Array.isArray(window.__onyx_pip_shadow_roots)) {
                    window.__onyx_pip_shadow_roots.forEach(function(sr) {
                        try {
                            sr.querySelectorAll('.__onyx_pip_shadow_style').forEach(function(s) { s.remove(); });
                            sr.querySelectorAll('[data-onyx-pip-video], [data-onyx-pip-ancestor]').forEach(function(el) {
                                el.removeAttribute('data-onyx-pip-video');
                                el.removeAttribute('data-onyx-pip-ancestor');
                            });
                        } catch (_) {}
                    });
                    window.__onyx_pip_shadow_roots = [];
                }
                if (window.__onyx_pre_pip_scroll) {
                    var savedScroll = window.__onyx_pre_pip_scroll;
                    window.__onyx_pre_pip_scroll = null;
                    try { window.scrollTo(savedScroll.x || 0, savedScroll.y || 0); } catch (_) {}
                }
                // Trigger resize so streaming players adapt immediately to full browser dimensions
                try {
                    window.dispatchEvent(new Event('resize'));
                    document.querySelectorAll('iframe').forEach(function(f) {
                        try {
                            f.contentWindow.dispatchEvent(new Event('resize'));
                        } catch (_) {}
                    });
                } catch (_) {}
            } catch (e) {}
        })();
    """.trimIndent()
}
