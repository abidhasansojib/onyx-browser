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
