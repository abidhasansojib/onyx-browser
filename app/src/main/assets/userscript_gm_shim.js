// Onyx Browser Userscript GM API Shim (Tampermonkey / Violentmonkey compatible)
// Templated per-script: __SCRIPT_ID__, __GM_TOKEN__, __GM_INFO__, and __GM_RESOURCES__ are substituted at inject time.
(function () {
    if (typeof globalThis === 'undefined') {
        try {
            (typeof self !== 'undefined' ? self : window).globalThis =
                (typeof self !== 'undefined' ? self : window);
        } catch (e) {}
    }

    // SPA Dynamic Navigation: notify userscripts of URL changes via HTML5 History API
    (function () {
        if (window.__onyxSpaHookInstalled) return;
        window.__onyxSpaHookInstalled = true;
        var _lastUrl = location.href;
        function _notifyUrlChange() {
            if (location.href !== _lastUrl) {
                _lastUrl = location.href;
                try {
                    window.dispatchEvent(new CustomEvent('onyx:urlchange', { detail: { url: location.href } }));
                } catch (_) {}
            }
        }
        var _pushState = history.pushState;
        if (_pushState) {
            history.pushState = function () {
                var ret = _pushState.apply(this, arguments);
                _notifyUrlChange();
                return ret;
            };
        }
        var _replaceState = history.replaceState;
        if (_replaceState) {
            history.replaceState = function () {
                var ret = _replaceState.apply(this, arguments);
                _notifyUrlChange();
                return ret;
            };
        }
        window.addEventListener('popstate', _notifyUrlChange);
    })();

    // Mobile Touch to Mouse Translation: Enables desktop mouse-hover userscripts (e.g. video downloaders) on mobile Android touchscreens
    (function () {
        if (window.__onyxTouchMouseShimInstalled) return;
        window.__onyxTouchMouseShimInstalled = true;
        function dispatchSimulatedMouseEvent(type, touch) {
            try {
                var ev = new MouseEvent(type, {
                    bubbles: true,
                    cancelable: true,
                    view: window,
                    clientX: touch.clientX,
                    clientY: touch.clientY,
                    screenX: touch.screenX,
                    screenY: touch.screenY
                });
                (touch.target || window).dispatchEvent(ev);
            } catch (_) {}
        }
        window.addEventListener('touchstart', function (e) {
            if (e.touches && e.touches[0]) {
                dispatchSimulatedMouseEvent('mousemove', e.touches[0]);
            }
        }, { passive: true, capture: true });
        window.addEventListener('touchmove', function (e) {
            if (e.touches && e.touches[0]) {
                dispatchSimulatedMouseEvent('mousemove', e.touches[0]);
            }
        }, { passive: true, capture: true });
    })();

    (function () {
        var Orig = window.Response;
        if (!Orig) return;
        try {
            window.Response = class extends Orig {
                constructor(body, init) {
                    init = init || {};
                    var s = init.status;
                    if (body != null && (s === 204 || s === 205 || s === 304)) super(null, init);
                    else super(body, init);
                }
            };
        } catch (e) {}
    })();

    var SCRIPT_ID = __SCRIPT_ID__;
    var GM_TOKEN = __GM_TOKEN__;
    var GM_INFO = __GM_INFO__;
    var __resources = __GM_RESOURCES__;

    var hub = window.__onyxGM;
    if (!hub) {
        hub = {
            xhrCallbacks: {},
            downloadCallbacks: {},
            menuCallbacks: {},
            valListeners: {},
            seq: 0,
            valSeq: 0,
            notifyValueChanged: function (scriptId, key, oldValue, newValue) {
                for (var id in this.valListeners) {
                    var entry = this.valListeners[id];
                    if (entry && (entry.scriptId === scriptId || entry.scriptId == null) && entry.name === key) {
                        try {
                            var oldVal = oldValue != null ? JSON.parse(oldValue) : undefined;
                            var newVal = newValue != null ? JSON.parse(newValue) : undefined;
                            entry.cb(key, oldVal, newVal, false);
                        } catch (e) {
                            console.error('onyx GM valueChange callback error', e);
                        }
                    }
                }
            },
            handleXhr: function (reqId, eventName, payload) {
                var self = this;
                setTimeout(function () {
                    var cb = self.xhrCallbacks[reqId];
                    if (!cb) return;
                    var resp;
                    try { resp = JSON.parse(payload); } catch (e) { resp = {}; }
                    if (cb.responseType === 'json' && typeof resp.responseText === 'string') {
                        try { resp.response = JSON.parse(resp.responseText); } catch (e2) {}
                    } else if ((cb.responseType === 'blob' || cb.responseType === 'arraybuffer') && resp.responseBase64) {
                        try {
                            var binStr = atob(resp.responseBase64);
                            var binLen = binStr.length;
                            var u8 = new Uint8Array(binLen);
                            for (var bi = 0; bi < binLen; bi++) {
                                u8[bi] = binStr.charCodeAt(bi);
                            }
                            if (cb.responseType === 'blob') {
                                var mimeType = 'application/octet-stream';
                                if (resp.responseHeaders) {
                                    var mimeMatch = resp.responseHeaders.match(/content-type:\s*([^\r\n;]+)/i);
                                    if (mimeMatch) mimeType = mimeMatch[1].trim();
                                }
                                resp.response = new Blob([u8], { type: mimeType });
                            } else {
                                resp.response = u8.buffer;
                            }
                        } catch (binErr) {
                            console.error('onyx GM_xhr binary conversion error', binErr);
                        }
                    }
                    try {
                        if (eventName === 'progress') {
                            if (cb.onprogress) cb.onprogress(resp);
                        } else if (eventName === 'error') {
                            if (cb.onerror) cb.onerror(resp);
                        } else if (eventName === 'timeout') {
                            if (cb.ontimeout) cb.ontimeout(resp);
                            else if (cb.onerror) cb.onerror(resp);
                        } else if (eventName === 'load') {
                            resp.readyState = 4;
                            if (cb.onreadystatechange) cb.onreadystatechange(resp);
                            if (cb.onload) cb.onload(resp);
                        }
                    } catch (cbErr) {
                        console.error('onyx GM_xhr callback error', cbErr);
                    }
                    if (eventName === 'load' || eventName === 'error' || eventName === 'timeout') {
                        delete self.xhrCallbacks[reqId];
                    }
                }, 0);
            },
            handleDownload: function (reqId, eventName, payload) {
                var self = this;
                setTimeout(function () {
                    var cb = self.downloadCallbacks[reqId];
                    if (!cb) return;
                    var data;
                    try { data = JSON.parse(payload); } catch (e) { data = {}; }
                    try {
                        if (eventName === 'progress') {
                            if (cb.onprogress) cb.onprogress(data);
                        } else if (eventName === 'error') {
                            if (cb.onerror) cb.onerror(data);
                        } else if (eventName === 'timeout') {
                            if (cb.ontimeout) cb.ontimeout(data);
                            else if (cb.onerror) cb.onerror(data);
                        } else if (eventName === 'load') {
                            if (cb.onload) cb.onload(data);
                        }
                    } catch (cbErr) {
                        console.error('onyx GM_download callback error', cbErr);
                    }
                    if (eventName === 'load' || eventName === 'error' || eventName === 'timeout') {
                        delete self.downloadCallbacks[reqId];
                    }
                }, 0);
            },
            invokeMenu: function (fnId) {
                var cb = this.menuCallbacks[fnId];
                if (cb) cb();
            }
        };
        try {
            Object.defineProperty(window, '__onyxGM', {
                value: hub,
                writable: true,
                configurable: true,
                enumerable: false
            });
        } catch (_) {
            window.__onyxGM = hub;
        }
    }

    var bridge = window.onyxGM;
    if (bridge) {
        try {
            Object.defineProperty(window, 'onyxGM', {
                value: bridge,
                writable: false,
                configurable: true,
                enumerable: false
            });
        } catch (_) {}
    }

    function GM_getValue(key, defaultValue) {
        try {
            var raw = bridge ? bridge.gmGetValue(GM_TOKEN, key) : null;
            if (raw === null || raw === undefined) return defaultValue;
            return JSON.parse(raw);
        } catch (e) {
            return defaultValue;
        }
    }

    function GM_setValue(key, value) {
        var oldVal = GM_getValue(key);
        if (bridge) {
            try {
                bridge.gmSetValue(GM_TOKEN, key, JSON.stringify(value));
            } catch (e) {}
        }
        if (hub && hub.notifyValueChanged) {
            hub.notifyValueChanged(SCRIPT_ID, key, JSON.stringify(oldVal), JSON.stringify(value));
        }
    }

    function GM_deleteValue(key) {
        var oldVal = GM_getValue(key);
        if (bridge) {
            try {
                bridge.gmDeleteValue(GM_TOKEN, key);
            } catch (e) {}
        }
        if (hub && hub.notifyValueChanged) {
            hub.notifyValueChanged(SCRIPT_ID, key, JSON.stringify(oldVal), null);
        }
    }

    function GM_listValues() {
        if (!bridge) return [];
        try {
            return JSON.parse(bridge.gmListValues(GM_TOKEN) || '[]');
        } catch (e) {
            return [];
        }
    }

    function GM_addStyle(css) {
        var style = document.createElement('style');
        style.textContent = css;
        var target = document.head || document.documentElement;
        if (target) {
            target.appendChild(style);
        } else if (typeof MutationObserver !== 'undefined') {
            var observer = new MutationObserver(function () {
                var el = document.head || document.documentElement;
                if (el) {
                    observer.disconnect();
                    el.appendChild(style);
                }
            });
            observer.observe(document, { childList: true, subtree: true });
        } else {
            document.addEventListener('DOMContentLoaded', function () {
                var el = document.head || document.documentElement;
                if (el) el.appendChild(style);
            });
        }
        return style;
    }

    function GM_addElement(parentOrTag, tagOrAttrs, maybeAttrs) {
        var parent, tag, attrs;
        if (typeof parentOrTag === 'string') {
            parent = null; tag = parentOrTag; attrs = tagOrAttrs || {};
        } else {
            parent = parentOrTag; tag = tagOrAttrs; attrs = maybeAttrs || {};
        }
        var el = document.createElement(tag);
        for (var k in attrs) {
            if (!attrs.hasOwnProperty(k)) continue;
            if (k === 'textContent') el.textContent = attrs[k];
            else el.setAttribute(k, attrs[k]);
        }
        var target = parent || document.head || document.documentElement;
        if (target) {
            target.appendChild(el);
        } else if (typeof MutationObserver !== 'undefined') {
            var observer = new MutationObserver(function () {
                var container = parent || document.head || document.documentElement;
                if (container) {
                    observer.disconnect();
                    container.appendChild(el);
                }
            });
            observer.observe(document, { childList: true, subtree: true });
        }
        return el;
    }

    function GM_xmlhttpRequest(details) {
        var reqId = SCRIPT_ID + ':' + (++hub.seq);
        hub.xhrCallbacks[reqId] = {
            onload: details.onload,
            onerror: details.onerror,
            ontimeout: details.ontimeout,
            onprogress: details.onprogress,
            onreadystatechange: details.onreadystatechange,
            responseType: details.responseType || ''
        };
        var wire = {
            method: details.method || 'GET',
            url: details.url,
            headers: details.headers || {},
            data: details.data != null ? details.data : null,
            responseType: details.responseType || '',
            timeout: details.timeout || 0,
            user: details.user || null,
            password: details.password || null
        };
        try {
            if (bridge) {
                bridge.gmXhr(GM_TOKEN, reqId, JSON.stringify(wire));
            } else {
                delete hub.xhrCallbacks[reqId];
                if (details.onerror) details.onerror({ error: 'Bridge unavailable' });
            }
        } catch (e) {
            delete hub.xhrCallbacks[reqId];
            if (details.onerror) details.onerror({ error: String(e) });
        }
        return {
            abort: function () {
                try { if (bridge) bridge.gmAbortXhr(GM_TOKEN, reqId); } catch (e) {}
            }
        };
    }

    function GM_download(detailsOrUrl, name) {
        var details;
        if (typeof detailsOrUrl === 'string') {
            details = { url: detailsOrUrl, name: name || '' };
        } else {
            details = detailsOrUrl || {};
        }

        if (typeof details.url === 'string' && (details.url.indexOf('blob:') === 0 || details.url.indexOf('data:') === 0)) {
            try {
                var a = document.createElement('a');
                a.href = details.url;
                a.download = details.name || 'download';
                a.style.display = 'none';
                (document.body || document.documentElement).appendChild(a);
                a.click();
                setTimeout(function () {
                    a.parentNode && a.parentNode.removeChild(a);
                    if (details.onload) details.onload();
                }, 100);
            } catch (dlErr) {
                if (details.onerror) details.onerror({ error: 'download_error', details: String(dlErr) });
            }
            return { abort: function () {} };
        }

        var reqId = SCRIPT_ID + ':dl:' + (++hub.seq);
        hub.downloadCallbacks[reqId] = {
            onload: details.onload,
            onerror: details.onerror,
            ontimeout: details.ontimeout,
            onprogress: details.onprogress
        };

        var wire = {
            url: details.url,
            name: details.name || '',
            headers: details.headers || {},
            timeout: details.timeout || 0,
            saveAs: !!details.saveAs
        };

        try {
            if (bridge && bridge.gmDownload) {
                bridge.gmDownload(GM_TOKEN, reqId, JSON.stringify(wire));
            } else {
                delete hub.downloadCallbacks[reqId];
                if (details.onerror) details.onerror({ error: 'not_whitelisted', details: 'Bridge unavailable' });
            }
        } catch (e) {
            delete hub.downloadCallbacks[reqId];
            if (details.onerror) details.onerror({ error: 'download_error', details: String(e) });
        }

        return {
            abort: function () {
                try { if (bridge && bridge.gmAbortDownload) bridge.gmAbortDownload(GM_TOKEN, reqId); } catch (e) {}
            }
        };
    }

    function GM_getResourceText(name) {
        var r = __resources && __resources[name];
        if (!r) return undefined;
        if (r.text !== undefined) return r.text;
        if (r.base64) {
            try { return decodeURIComponent(escape(atob(r.base64))); }
            catch (_) { return atob(r.base64); }
        }
        return undefined;
    }

    function GM_getResourceURL(name) {
        var r = __resources && __resources[name];
        if (!r) return undefined;
        var mime = r.mime || 'application/octet-stream';
        return 'data:' + mime + ';base64,' + (r.base64 || '');
    }

    function GM_addValueChangeListener(name, callback) {
        var id = ++hub.valSeq;
        hub.valListeners[id] = { scriptId: SCRIPT_ID, name: name, cb: callback };
        return id;
    }

    function GM_removeValueChangeListener(id) {
        delete hub.valListeners[id];
    }

    function GM_registerMenuCommand(caption, fn) {
        var fnId = SCRIPT_ID + ':menu:' + (++hub.seq);
        hub.menuCallbacks[fnId] = fn;
        try { if (bridge) bridge.gmRegisterMenuCommand(GM_TOKEN, String(caption), fnId); } catch (e) {}
        return fnId;
    }

    function GM_unregisterMenuCommand(fnId) {
        delete hub.menuCallbacks[fnId];
        try { if (bridge) bridge.gmUnregisterMenuCommand(GM_TOKEN, fnId); } catch (e) {}
    }

    function GM_openInTab(url, options) {
        var active = true;
        if (typeof options === 'boolean') active = !options;
        else if (options && typeof options === 'object') active = options.active !== false;
        try { if (bridge) bridge.gmOpenInTab(GM_TOKEN, url, active); } catch (e) {}
        return { closed: false, close: function () {} };
    }

    function GM_setClipboard(text) {
        try { if (bridge) bridge.gmSetClipboard(GM_TOKEN, String(text)); } catch (e) {}
    }

    function GM_log() {
        try { if (bridge) bridge.gmLog(GM_TOKEN, Array.prototype.join.call(arguments, ' ')); } catch (e) {}
    }

    function GM_notification(detailsOrText, title, image, onclick) {
        var text = '';
        var t = '';
        if (typeof detailsOrText === 'string') {
            text = detailsOrText;
            t = title || '';
        } else if (detailsOrText && typeof detailsOrText === 'object') {
            text = detailsOrText.text || '';
            t = detailsOrText.title || title || '';
        }
        var msg = (t && text) ? (t + ': ' + text) : (t || text);
        try { if (bridge) bridge.gmNotification(GM_TOKEN, String(msg)); } catch (e) {}
    }

    var GM = {
        info: GM_INFO,
        getValue: function (key, def) { return Promise.resolve(GM_getValue(key, def)); },
        setValue: function (key, val) { GM_setValue(key, val); return Promise.resolve(); },
        deleteValue: function (key) { GM_deleteValue(key); return Promise.resolve(); },
        listValues: function () { return Promise.resolve(GM_listValues()); },
        addStyle: GM_addStyle,
        addElement: GM_addElement,
        xmlHttpRequest: GM_xmlhttpRequest,
        download: function (detailsOrUrl, name) { return GM_download(detailsOrUrl, name); },
        registerMenuCommand: GM_registerMenuCommand,
        unregisterMenuCommand: GM_unregisterMenuCommand,
        openInTab: GM_openInTab,
        setClipboard: GM_setClipboard,
        notification: GM_notification,
        log: GM_log,
        getResourceText: GM_getResourceText,
        getResourceUrl: GM_getResourceURL,
        getResourceURL: GM_getResourceURL,
        addValueChangeListener: GM_addValueChangeListener,
        removeValueChangeListener: GM_removeValueChangeListener
    };

    return {
        GM: GM,
        GM_info: GM_INFO,
        unsafeWindow: (typeof unsafeWindow !== 'undefined' ? unsafeWindow : window),
        GM_getValue: GM_getValue,
        GM_setValue: GM_setValue,
        GM_deleteValue: GM_deleteValue,
        GM_listValues: GM_listValues,
        GM_addStyle: GM_addStyle,
        GM_addElement: GM_addElement,
        GM_xmlhttpRequest: GM_xmlhttpRequest,
        GM_download: GM_download,
        GM_registerMenuCommand: GM_registerMenuCommand,
        GM_unregisterMenuCommand: GM_unregisterMenuCommand,
        GM_openInTab: GM_openInTab,
        GM_setClipboard: GM_setClipboard,
        GM_notification: GM_notification,
        GM_log: GM_log,
        GM_getResourceText: GM_getResourceText,
        GM_getResourceURL: GM_getResourceURL,
        GM_addValueChangeListener: GM_addValueChangeListener,
        GM_removeValueChangeListener: GM_removeValueChangeListener
    };
})();
