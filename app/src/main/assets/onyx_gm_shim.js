// Onyx Browser Userscript GM API Shim (Tampermonkey / Violentmonkey compatible)
// Templated per-script: __SCRIPT_ID__, __GM_TOKEN__, and __GM_INFO__ are substituted at inject time.
(function () {
    if (typeof globalThis === 'undefined') {
        try {
            (typeof self !== 'undefined' ? self : window).globalThis =
                (typeof self !== 'undefined' ? self : window);
        } catch (e) {}
    }

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

    var hub = window.__onyxGM;
    if (!hub) {
        hub = window.__onyxGM = {
            xhrCallbacks: {},
            menuCallbacks: {},
            seq: 0,
            handleXhr: function (reqId, eventName, payload) {
                var self = this;
                setTimeout(function () {
                    var cb = self.xhrCallbacks[reqId];
                    if (!cb) return;
                    var resp;
                    try { resp = JSON.parse(payload); } catch (e) { resp = {}; }
                    if (cb.responseType === 'json' && typeof resp.responseText === 'string') {
                        try { resp.response = JSON.parse(resp.responseText); } catch (e2) {}
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
            invokeMenu: function (fnId) {
                var cb = this.menuCallbacks[fnId];
                if (cb) cb();
            }
        };
    }

    var bridge = window.onyxGM;

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
        if (!bridge) return;
        try {
            bridge.gmSetValue(GM_TOKEN, key, JSON.stringify(value));
        } catch (e) {}
    }

    function GM_deleteValue(key) {
        if (!bridge) return;
        try {
            bridge.gmDeleteValue(GM_TOKEN, key);
        } catch (e) {}
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
        (document.head || document.documentElement).appendChild(style);
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
        (parent || document.head || document.documentElement).appendChild(el);
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
        var text = typeof detailsOrText === 'string' ? detailsOrText : (detailsOrText ? detailsOrText.text : '');
        try { if (bridge) bridge.gmNotification(GM_TOKEN, String(text)); } catch (e) {}
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
        registerMenuCommand: GM_registerMenuCommand,
        unregisterMenuCommand: GM_unregisterMenuCommand,
        openInTab: GM_openInTab,
        setClipboard: GM_setClipboard,
        notification: GM_notification,
        log: GM_log
    };

    return {
        GM: GM,
        GM_info: GM_INFO,
        unsafeWindow: window,
        GM_getValue: GM_getValue,
        GM_setValue: GM_setValue,
        GM_deleteValue: GM_deleteValue,
        GM_listValues: GM_listValues,
        GM_addStyle: GM_addStyle,
        GM_addElement: GM_addElement,
        GM_xmlhttpRequest: GM_xmlhttpRequest,
        GM_registerMenuCommand: GM_registerMenuCommand,
        GM_unregisterMenuCommand: GM_unregisterMenuCommand,
        GM_openInTab: GM_openInTab,
        GM_setClipboard: GM_setClipboard,
        GM_notification: GM_notification,
        GM_log: GM_log
    };
})();
