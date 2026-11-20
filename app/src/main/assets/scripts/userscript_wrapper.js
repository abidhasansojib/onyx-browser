(function(){
var __exclude = [{{EXCLUDE_ARRAY}}];
var __match = [{{MATCH_ARRAY}}];
function __matchesUrl(u) {
  for (var __i = 0; __i < __exclude.length; __i++) { if (__exclude[__i].test(u)) return false; }
  if (__match.length === 0) return true;
  for (var __j = 0; __j < __match.length; __j++) { if (__match[__j].test(u)) return true; }
  return false;
}
if ({{NO_FRAMES}} && window.self !== window.top) return;
var __scriptId = {{SCRIPT_ID}};
var __scriptKey = {{SCRIPT_KEY}};
var __scriptName = {{SCRIPT_NAME}};
var __resources = {{RESOURCES}};
var GM_info = {
  script: {
    name: {{SCRIPT_NAME}},
    namespace: {{NAMESPACE}},
    version: {{VERSION}},
    description: {{DESCRIPTION}},
    author: {{AUTHOR}},
    matches: __match.map(function(r){ return r.source; }),
    includes: [],
    excludes: [],
    resources: __resources,
    connects: [{{CONNECTS}}],
    grant: [{{GRANTS}}],
    runAt: {{RUN_AT}},
    noframes: {{NO_FRAMES}},
    unwrap: {{UNWRAP}},
    injectInto: {{INJECT_INTO}},
    sandbox: {{SANDBOX}}
  },
  version: '1.0',
  scriptHandler: 'OnyxBrowser',
  scriptMetaStr: null,
  scriptWillUpdate: false,
  isIncognito: {{IS_INCOGNITO}},
  sandboxMode: {{SANDBOX_MODE}},
  downloadMode: 'native'
};
var __isIsolated = {{IS_ISOLATED}};
var __pageWindow = window;
var unsafeWindow = __pageWindow;
var __sandboxWindow = __pageWindow;
if (__isIsolated && typeof Proxy !== 'undefined') {
  var __localProps = Object.create(null);
  __sandboxWindow = new Proxy(__pageWindow, {
    has: function(target, prop) {
      return (prop in __localProps) || (prop in target);
    },
    get: function(target, prop) {
      if (prop === 'window' || prop === 'self' || prop === 'globalThis') return __sandboxWindow;
      if (prop === 'unsafeWindow') return __pageWindow;
      if (prop === 'top') return target.top === target ? __sandboxWindow : target.top;
      if (prop === 'parent') return target.parent === target ? __sandboxWindow : target.parent;
      if (prop in __localProps) return __localProps[prop];
      var val = target[prop];
      return typeof val === 'function' ? val.bind(target) : val;
    },
    set: function(target, prop, value) {
      __localProps[prop] = value;
      return true;
    },
    deleteProperty: function(target, prop) {
      if (prop in __localProps) {
        delete __localProps[prop];
        return true;
      }
      return false;
    }
  });
}
function GM_log() { try { console.log.apply(console, arguments); } catch(__e) {} }
function GM_addStyle(css) {
  var __s = document.createElement('style');
  __s.setAttribute('data-userscript', __scriptName);
  __s.textContent = css;
  var __t = document.head || document.documentElement;
  if (__t) { __t.appendChild(__s); return __s; }
  if (typeof MutationObserver !== 'undefined') {
    var __obs = new MutationObserver(function() {
      var __cur = document.head || document.documentElement;
      if (__cur) { __obs.disconnect(); __cur.appendChild(__s); }
    });
    __obs.observe(document, { childList: true, subtree: true });
  } else {
    document.addEventListener('DOMContentLoaded', function() {
      (document.head || document.documentElement).appendChild(__s);
    });
  }
  return __s;
}
function GM_addElement(a, b, c) {
  var __tag, __attrs, __parent;
  if (typeof a === 'string') { __tag = a; __attrs = b || {}; __parent = c || document.head || document.documentElement; }
  else { __parent = a || document.head || document.documentElement; __tag = b; __attrs = c || {}; }
  var __el = document.createElement(__tag);
  for (var __k in __attrs) {
    if (__attrs.hasOwnProperty(__k)) {
      if (__k === 'textContent') __el.textContent = __attrs[__k];
      else __el.setAttribute(__k, __attrs[__k]);
    }
  }
  if (__parent) __parent.appendChild(__el);
  return __el;
}
function GM_getValue(key, defaultValue) {
  try {
    if (window.onyxGM && window.onyxGM.getValue) {
      var raw = window.onyxGM.getValue(__scriptKey, String(key));
      if (raw === null || raw === undefined) return defaultValue;
      return JSON.parse(raw);
    }
  } catch(__e) {}
  return defaultValue;
}
function GM_setValue(key, value) {
  try {
    if (window.onyxGM && window.onyxGM.setValue) {
      window.onyxGM.setValue(__scriptKey, String(key), JSON.stringify(value));
    }
  } catch(__e) {}
}
function GM_deleteValue(key) {
  try {
    if (window.onyxGM && window.onyxGM.deleteValue) {
      window.onyxGM.deleteValue(__scriptKey, String(key));
    }
  } catch(__e) {}
}
function GM_listValues() {
  try {
    if (window.onyxGM && window.onyxGM.listValues) {
      var raw = window.onyxGM.listValues(__scriptKey);
      return raw ? JSON.parse(raw) : [];
    }
  } catch(__e) {}
  return [];
}
function GM_addValueChangeListener(name, callback) {
  if (!window.__usRuntime.valueListeners[__scriptKey]) { window.__usRuntime.valueListeners[__scriptKey] = {}; }
  var __id = 'vl_' + Math.random().toString(36).substring(2);
  window.__usRuntime.valueListeners[__scriptKey][__id] = function(k, ov, nv, r) {
    if (k === name) { try { callback(k, ov, nv, r); } catch(__e) {} }
  };
  return __id;
}
function GM_removeValueChangeListener(id) {
  if (window.__usRuntime.valueListeners[__scriptKey]) {
    delete window.__usRuntime.valueListeners[__scriptKey][id];
  }
}
function GM_openInTab(url, options) {
  var active = true;
  if (typeof options === 'boolean') active = !options;
  else if (options && typeof options === 'object') active = options.active !== false;
  try {
    if (window.onyxGM && window.onyxGM.openInTab) {
      window.onyxGM.openInTab(url, active);
    }
  } catch(__e) {}
  return { closed: false, close: function() {} };
}
function GM_setClipboard(text, type) {
  try {
    if (window.onyxGM && window.onyxGM.setClipboard) {
      window.onyxGM.setClipboard(String(text));
      return;
    }
  } catch(__e) {}
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(String(text)).catch(function() {});
    }
  } catch(__e2) {}
}
function GM_notification(details, ondone) {
  try {
    var __opts = (typeof details === 'string') ? { text: details, title: ondone } : (details || {});
    var __title = (typeof __opts.title === 'string' && __opts.title) ? __opts.title : __scriptName;
    if (window.onyxGM && window.onyxGM.notify) {
      window.onyxGM.notify(__scriptName, __title, __opts.text || '', location.hostname || '');
    }
    if (typeof __opts.ondone === 'function') setTimeout(__opts.ondone, 0);
  } catch(__e) {}
}
var __xhrSeq = 0;
function GM_xmlhttpRequest(details) {
  details = details || {};
  try {
    var __id = 'x_' + __scriptId + '_' + (++__xhrSeq) + '_' + Date.now();
    var __payload = {
      url: details.url,
      method: (details.method || 'GET').toUpperCase(),
      headers: details.headers || {},
      timeout: details.timeout || 0,
      responseType: details.responseType || '',
      anonymous: !!details.anonymous
    };
    if (details.data !== undefined && details.data !== null) {
      __payload.data = (typeof details.data === 'string') ? details.data : JSON.stringify(details.data);
    }
    window.__usRuntime.xhrCallbacks[__id] = function(result, error) {
      if (error) {
        if (details.onerror) details.onerror({ error: error.error || 'error', finalUrl: details.url, readyState: 4, status: 0, context: details.context });
        return;
      }
      var __resp = result.responseText;
      var __respObj = __resp;
      if (details.responseType === 'json') {
        try { __respObj = JSON.parse(__resp); } catch(__pe) { __respObj = null; }
      } else if ((details.responseType === 'blob' || details.responseType === 'arraybuffer') && result.responseBase64) {
        try {
          var __binStr = atob(result.responseBase64);
          var __binLen = __binStr.length;
          var __u8 = new Uint8Array(__binLen);
          for (var __bi = 0; __bi < __binLen; __bi++) { __u8[__bi] = __binStr.charCodeAt(__bi); }
          if (details.responseType === 'blob') {
            var __mime = 'application/octet-stream';
            if (result.responseHeaders) {
              var __mMatch = result.responseHeaders.match(/content-type:\s*([^\r\n;]+)/i);
              if (__mMatch) __mime = __mMatch[1].trim();
            }
            __respObj = new Blob([__u8], { type: __mime });
          } else {
            __respObj = __u8.buffer;
          }
        } catch(__be) { console.error('[UserScript] binary conversion error', __be); }
      }
      var __r = {
        status: result.status,
        statusText: result.statusText,
        responseText: __resp,
        response: __respObj,
        readyState: 4,
        finalUrl: result.finalUrl,
        responseHeaders: result.responseHeaders,
        context: details.context
      };
      if (details.onreadystatechange) try { details.onreadystatechange(__r); } catch(__e3) {}
      if (details.onload) details.onload(__r);
    };
    if (window.onyxGM && window.onyxGM.xhr) {
      window.onyxGM.xhr(__id, JSON.stringify(__payload));
    } else {
      delete window.__usRuntime.xhrCallbacks[__id];
      if (details.onerror) details.onerror({ error: 'Bridge unavailable', context: details.context });
    }
    return {
      abort: function() {
        try { if (window.onyxGM && window.onyxGM.abortXhr) window.onyxGM.abortXhr(__id); } catch(__ae) {}
        if (details.onabort) details.onabort();
      }
    };
  } catch(__e) {
    if (details.onerror) details.onerror({ error: String(__e), context: details.context });
    return { abort: function() {} };
  }
}
function GM_download(details, filename) {
  try {
    var __opts = (typeof details === 'string') ? { url: details, name: filename } : (details || {});
    var __u = __opts.url || '';
    if (__u.indexOf('blob:') === 0 || __u.indexOf('data:') === 0) {
      var __a = document.createElement('a');
      __a.href = __u;
      __a.download = __opts.name || 'download';
      __a.style.display = 'none';
      (document.body || document.documentElement).appendChild(__a);
      __a.click();
      setTimeout(function() {
        __a.remove();
        if (__opts.onload) __opts.onload();
      }, 100);
      return { abort: function() {} };
    }
    if (window.onyxGM && window.onyxGM.download) {
      window.onyxGM.download(__u, __opts.name || 'download');
    }
    if (__opts.onload) setTimeout(__opts.onload, 100);
  } catch(__e) {
    if (details && details.onerror) details.onerror(String(__e));
  }
}
function GM_getResourceText(name) {
  var __r = __resources[name];
  if (!__r) return undefined;
  if (__r.text !== undefined) return __r.text;
  if (__r.base64) {
    try { return decodeURIComponent(escape(atob(__r.base64))); }
    catch(__e) { try { return atob(__r.base64); } catch(__e2) { return undefined; } }
  }
  return undefined;
}
function GM_getResourceURL(name) {
  var __r = __resources[name];
  if (!__r) return undefined;
  return 'data:' + (__r.mime || 'application/octet-stream') + ';base64,' + (__r.base64 || '');
}
function GM_registerMenuCommand(caption, onClick, accessKey) {
  var __id = 'm_' + __scriptId + '_' + (++__xhrSeq);
  window.__usRuntime.menuCommands[__id] = { scriptName: __scriptName, caption: caption, onClick: onClick, accessKey: accessKey };
  __usRenderMenuButton();
  try { if (window.onyxGM && window.onyxGM.registerMenuCommand) window.onyxGM.registerMenuCommand(caption, __id); } catch(__e) {}
  return __id;
}
function GM_unregisterMenuCommand(id) {
  delete window.__usRuntime.menuCommands[id];
  __usRenderMenuButton();
  try { if (window.onyxGM && window.onyxGM.unregisterMenuCommand) window.onyxGM.unregisterMenuCommand(id); } catch(__e) {}
}
function __usRenderMenuButton() {
  try {
    var __ids = Object.keys(window.__usRuntime.menuCommands);
    var __btn = document.getElementById('__us_menu_btn');
    if (__ids.length === 0) { if (__btn) __btn.remove(); return; }
    if (!__btn) {
      __btn = document.createElement('div');
      __btn.id = '__us_menu_btn';
      __btn.style.cssText = 'position:fixed;right:14px;bottom:80px;z-index:2147483647;width:38px;height:38px;border-radius:19px;background:rgba(30,30,34,0.88);backdrop-filter:blur(10px);color:#fff;display:flex;align-items:center;justify-content:center;font:16px sans-serif;box-shadow:0 3px 10px rgba(0,0,0,0.45);cursor:pointer;user-select:none;border:1px solid rgba(255,255,255,0.12);';
      __btn.textContent = '\u2699';
      var __menu = document.createElement('div');
      __menu.id = '__us_menu_list';
      __menu.style.cssText = 'position:fixed;right:14px;bottom:126px;z-index:2147483647;background:#202024;color:#fff;border-radius:12px;box-shadow:0 4px 16px rgba(0,0,0,0.55);display:none;min-width:180px;max-width:280px;font:13px sans-serif;overflow:hidden;border:1px solid rgba(255,255,255,0.1);';
      (document.body || document.documentElement).appendChild(__btn);
      (document.body || document.documentElement).appendChild(__menu);
      __btn.addEventListener('click', function(e) {
        e.stopPropagation();
        __menu.style.display = (__menu.style.display === 'none' ? 'block' : 'none');
      });
      document.addEventListener('click', function() {
        if (__menu) __menu.style.display = 'none';
      });
    }
    var __menuEl = document.getElementById('__us_menu_list');
    if (__menuEl) {
      __menuEl.innerHTML = '';
      __ids.forEach(function(__mid) {
        var __cmd = window.__usRuntime.menuCommands[__mid];
        var __item = document.createElement('div');
        __item.textContent = __cmd.caption;
        __item.style.cssText = 'padding:11px 16px;border-bottom:1px solid rgba(255,255,255,0.06);cursor:pointer;transition:background 0.15s;';
        __item.addEventListener('click', function(e) {
          e.stopPropagation();
          __menuEl.style.display = 'none';
          try { __cmd.onClick(); } catch(__e) {}
        });
        __menuEl.appendChild(__item);
      });
    }
  } catch(__e) {}
}
var GM = {
  info: GM_info,
  log: function() { GM_log.apply(null, arguments); return Promise.resolve(); },
  getValue: function(k, d) { return Promise.resolve(GM_getValue(k, d)); },
  setValue: function(k, v) { GM_setValue(k, v); return Promise.resolve(); },
  deleteValue: function(k) { GM_deleteValue(k); return Promise.resolve(); },
  listValues: function() { return Promise.resolve(GM_listValues()); },
  getValues: function(k) { return Promise.resolve(GM_getValues(k)); },
  setValues: function(v) { GM_setValues(v); return Promise.resolve(); },
  deleteValues: function(k) { GM_deleteValues(k); return Promise.resolve(); },
  getTab: function() { return Promise.resolve(GM_getTab()); },
  saveTab: function(o) { GM_saveTab(o); return Promise.resolve(); },
  getTabs: function() { return Promise.resolve(GM_getTabs()); },
  addStyle: GM_addStyle,
  addElement: GM_addElement,
  xmlHttpRequest: function(d) {
    return new Promise(function(res) {
      var __d = Object.assign({}, d);
      var __ol = __d.onload, __oe = __d.onerror;
      __d.onload = function(r) { if (__ol) __ol(r); res(r); };
      __d.onerror = function(r) { if (__oe) __oe(r); res(r); };
      GM_xmlhttpRequest(__d);
    });
  },
  download: function(d, f) { GM_download(d, f); return Promise.resolve(); },
  getResourceText: function(n) { return Promise.resolve(GM_getResourceText(n)); },
  getResourceUrl: function(n) { return Promise.resolve(GM_getResourceURL(n)); },
  getResourceURL: function(n) { return Promise.resolve(GM_getResourceURL(n)); },
  registerMenuCommand: function(c, cb, a) { return Promise.resolve(GM_registerMenuCommand(c, cb, a)); },
  unregisterMenuCommand: function(id) { return Promise.resolve(GM_unregisterMenuCommand(id)); },
  openInTab: function(u, o) { return Promise.resolve(GM_openInTab(u, o)); },
  setClipboard: function(t, ty) { GM_setClipboard(t, ty); return Promise.resolve(); },
  notification: function(d, od) { GM_notification(d, od); return Promise.resolve(); },
  addValueChangeListener: function(n, cb) { return Promise.resolve(GM_addValueChangeListener(n, cb)); },
  removeValueChangeListener: function(id) { return Promise.resolve(GM_removeValueChangeListener(id)); }
};
function GM_getValues(keys) {
  var out = {};
  if (Array.isArray(keys)) { keys.forEach(function(k) { out[k] = GM_getValue(k); }); }
  else if (keys && typeof keys === 'object') { Object.keys(keys).forEach(function(k) { out[k] = GM_getValue(k, keys[k]); }); }
  else { GM_listValues().forEach(function(k) { out[k] = GM_getValue(k); }); }
  return out;
}
function GM_setValues(values) {
  if (values && typeof values === 'object') { Object.keys(values).forEach(function(k) { GM_setValue(k, values[k]); }); }
}
function GM_deleteValues(keys) {
  if (Array.isArray(keys)) { keys.forEach(function(k) { GM_deleteValue(k); }); }
}
var __tabKey = '__onyx_gm_tab_' + __scriptKey;
function GM_getTab(cb) {
  var o = {};
  try { o = JSON.parse(sessionStorage.getItem(__tabKey) || '{}'); } catch(__e) {}
  if (typeof cb === 'function') cb(o);
  return o;
}
function GM_saveTab(obj) {
  try { sessionStorage.setItem(__tabKey, JSON.stringify(obj || {})); } catch(__e) {}
}
function GM_getTabs(cb) {
  var all = {}; all[0] = GM_getTab();
  if (typeof cb === 'function') cb(all);
  return all;
}
var GM_xmlHttpRequest = GM_xmlhttpRequest;
var __hasExecuted = false;
function __runUserScript() {
  if (__hasExecuted) return;
  __hasExecuted = true;
  (function(window, self, globalThis, unsafeWindow) {
    try {
{{REQUIRE_BLOCK}}
{{CODE}}
    } catch(__e) {
      console.error('[UserScript] ' + __scriptName + ' failed', __e);
    }
  }).call(__sandboxWindow, __sandboxWindow, __sandboxWindow, __sandboxWindow, __pageWindow);
}
var __runAt = {{RUN_AT}};
function __attemptRun() {
  if (__hasExecuted) return;
  if (!__matchesUrl(location.href)) return;
  if (__runAt === 'document-start') {
    __runUserScript();
  } else if (__runAt === 'document-end') {
    if (document.readyState !== 'loading') { __runUserScript(); }
    else { document.addEventListener('DOMContentLoaded', __runUserScript); }
  } else {
    if (document.readyState === 'complete') { __runUserScript(); }
    else { window.addEventListener('load', __runUserScript); }
  }
}
__attemptRun();
window.addEventListener('onyx:urlchange', function() { __hasExecuted = false; __attemptRun(); });
window.addEventListener('yt-navigate-finish', function() { __hasExecuted = false; __attemptRun(); });
})();
