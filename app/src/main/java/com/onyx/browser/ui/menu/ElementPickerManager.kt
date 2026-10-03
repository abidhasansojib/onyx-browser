package com.onyx.browser.ui.menu

import android.webkit.WebView

object ElementPickerManager {

    @Volatile
    var isPickerActive: Boolean = false
        private set

    fun resetPickerState() {
        isPickerActive = false
    }

    private const val PICKER_SCRIPT = """
(function() {
    if (window.__onyx_picker_active) return;
    window.__onyx_picker_active = true;

    var selectedElement = null;
    var selectedSelector = '';

    // ── Highlight overlay ────────────────────────────────────────────────────
    var highlightBox = document.createElement('div');
    highlightBox.id = '__onyx_picker_highlight';
    highlightBox.style.cssText =
        'position: fixed !important; pointer-events: none !important; ' +
        'z-index: 2147483640 !important; border: 2px solid #EA4335 !important; ' +
        'background: rgba(234,67,53,0.18) !important; display: none; ' +
        'border-radius: 4px; box-sizing: border-box; transition: top 0.05s,left 0.05s,width 0.05s,height 0.05s;';
    (document.body || document.documentElement).appendChild(highlightBox);

    // ── Floating action bar ───────────────────────────────────────────────────
    var bar = document.createElement('div');
    bar.id = '__onyx_picker_bar';
    bar.style.cssText =
        'position: fixed !important; bottom: 20px !important; left: 50% !important; ' +
        'transform: translateX(-50%) !important; z-index: 2147483647 !important; ' +
        'background: #202124 !important; color: #FFFFFF !important; ' +
        'padding: 10px 16px !important; border-radius: 28px !important; ' +
        'box-shadow: 0 4px 20px rgba(0,0,0,0.55) !important; ' +
        'display: flex !important; align-items: center !important; gap: 10px !important; ' +
        'font-family: -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif !important; ' +
        'font-size: 13px !important; white-space: nowrap !important; max-width: 94vw !important;';

    var infoSpan = document.createElement('span');
    infoSpan.id = '__onyx_picker_info';
    infoSpan.textContent = 'Tap an element to block';
    infoSpan.style.cssText = 'overflow: hidden; text-overflow: ellipsis; max-width: 130px; flex-shrink: 1;';
    bar.appendChild(infoSpan);

    function makeBtn(label, bg, color, border) {
        var b = document.createElement('button');
        b.textContent = label;
        b.style.cssText =
            'padding: 6px 12px; background: ' + bg + '; color: ' + color + '; ' +
            'border: ' + (border || 'none') + '; border-radius: 16px; ' +
            'font-size: 12px; cursor: pointer; flex-shrink: 0;';
        return b;
    }

    var cancelBtn = makeBtn('Cancel', 'transparent', '#E8EAED', '1px solid #5F6368');
    var widerBtn  = makeBtn('▲ Wider', '#3C4043', '#E8EAED', 'none');
    var blockBtn  = makeBtn('Block', '#5F6368', '#9AA0A6', 'none');
    blockBtn.disabled = true;
    blockBtn.style.cursor = 'not-allowed';

    bar.appendChild(cancelBtn);
    bar.appendChild(widerBtn);
    bar.appendChild(blockBtn);
    (document.body || document.documentElement).appendChild(bar);

    // ── Selector helpers ──────────────────────────────────────────────────────
    function cssEscape(str) {
        if (typeof CSS !== 'undefined' && CSS.escape) return CSS.escape(str);
        return str.replace(/([\u0000-\u002F\u003A-\u0040\u005B-\u0060\u007B-\u009F])/g, '\\$1');
    }

    var DYNAMIC_ID = /^[0-9]|[\:\.\[\]\{\}@,!%^&*()+=~|<>?/\\'"`;]/;

    function generateSelector(el) {
        if (!el || el === document.body || el === document.documentElement) return '';

        // Stable ID (skip purely numeric or dynamic-looking IDs)
        if (el.id && typeof el.id === 'string' && el.id.trim().length > 0) {
            var rawId = el.id.trim();
            if (!DYNAMIC_ID.test(rawId) && !/^\d/.test(rawId)) {
                return '#' + cssEscape(rawId);
            }
        }

        var tag = el.tagName.toLowerCase();

        // Stable class names (skip very long / numeric / generated classes)
        if (el.classList && el.classList.length > 0) {
            var classes = Array.prototype.slice.call(el.classList)
                .filter(function(c) {
                    return c && !c.startsWith('__onyx') &&
                           c.length <= 40 &&
                           !/^\d/.test(c) &&
                           !/^[a-z]{0,2}\d{3,}/.test(c); // skip e.g. "sc3829"
                })
                .slice(0, 2)
                .map(function(c) { return '.' + cssEscape(c); });
            if (classes.length > 0) {
                return tag + classes.join('');
            }
        }

        // nth-child fallback with parent
        var parent = el.parentElement;
        if (parent && parent !== document.body) {
            var siblings = Array.prototype.slice.call(parent.children);
            var index = siblings.indexOf(el) + 1;
            var parentSel = generateSelector(parent);
            if (parentSel) return parentSel + ' > ' + tag + ':nth-child(' + index + ')';
        }
        return tag;
    }

    // ── Highlight position updater ────────────────────────────────────────────
    function updateHighlight() {
        if (!selectedElement) return;
        var rect = selectedElement.getBoundingClientRect();
        if (rect.width === 0 && rect.height === 0) return;
        highlightBox.style.display = 'block';
        highlightBox.style.top    = rect.top    + 'px';
        highlightBox.style.left   = rect.left   + 'px';
        highlightBox.style.width  = rect.width  + 'px';
        highlightBox.style.height = rect.height + 'px';
    }

    window.addEventListener('scroll', updateHighlight, { passive: true, capture: true });
    window.addEventListener('resize', updateHighlight, { passive: true });

    // ── Select a target element ───────────────────────────────────────────────
    function selectTarget(el) {
        if (!el || el === highlightBox || el === bar || bar.contains(el)) return;
        selectedElement = el;
        selectedSelector = generateSelector(el);

        updateHighlight();

        infoSpan.textContent = selectedSelector || el.tagName.toLowerCase();
        blockBtn.disabled = false;
        blockBtn.style.background = '#EA4335';
        blockBtn.style.color = '#FFFFFF';
        blockBtn.style.cursor = 'pointer';
        widerBtn.style.opacity = (el.parentElement && el.parentElement !== document.body) ? '1' : '0.4';
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────
    function cleanup() {
        window.__onyx_picker_active = false;
        window.removeEventListener('scroll', updateHighlight, true);
        window.removeEventListener('resize', updateHighlight);
        document.removeEventListener('click', onDocClick, true);
        document.removeEventListener('touchstart', onDocTouch, true);
        if (highlightBox && highlightBox.parentNode) highlightBox.parentNode.removeChild(highlightBox);
        if (bar && bar.parentNode) bar.parentNode.removeChild(bar);
        if (window.OnyxShieldBridge && window.OnyxShieldBridge.onPickerClosed) {
            window.OnyxShieldBridge.onPickerClosed();
        }
    }

    cancelBtn.onclick = function(e) { e.preventDefault(); e.stopPropagation(); cleanup(); };

    widerBtn.onclick = function(e) {
        e.preventDefault(); e.stopPropagation();
        if (selectedElement && selectedElement.parentElement &&
            selectedElement.parentElement !== document.body &&
            selectedElement.parentElement !== document.documentElement) {
            selectTarget(selectedElement.parentElement);
        }
    };

    blockBtn.onclick = function(e) {
        e.preventDefault(); e.stopPropagation();
        if (!selectedElement || !selectedSelector) return;

        // 1. Instant hide — inject a <style> rule to hide ALL matching elements
        try {
            var style = document.createElement('style');
            style.id = '__onyx_blocked_style_' + Date.now();
            style.textContent = selectedSelector + ' { display: none !important; }';
            (document.head || document.documentElement).appendChild(style);
        } catch(err) {
            // Fallback: just hide the single node
            selectedElement.style.setProperty('display', 'none', 'important');
        }

        // 2. Persist as a custom cosmetic rule
        try {
            if (window.OnyxShieldBridge && window.OnyxShieldBridge.saveCustomCosmeticRule) {
                window.OnyxShieldBridge.saveCustomCosmeticRule(window.location.hostname, selectedSelector);
            }
        } catch(err) {}

        cleanup();
    };

    function onDocClick(e) {
        if (bar.contains(e.target)) return;
        e.preventDefault(); e.stopPropagation();
        selectTarget(e.target);
    }

    function onDocTouch(e) {
        if (!e.touches || e.touches.length === 0) return;
        var touch = e.touches[0];
        var el = document.elementFromPoint(touch.clientX, touch.clientY);
        if (el && !bar.contains(el)) {
            e.preventDefault(); e.stopPropagation();
            selectTarget(el);
        }
    }

    document.addEventListener('click', onDocClick, true);
    document.addEventListener('touchstart', onDocTouch, { capture: true, passive: false });
})();
"""

    fun startPicker(webView: WebView) {
        isPickerActive = true
        webView.evaluateJavascript(PICKER_SCRIPT, null)
    }

    fun cancelPicker(webView: WebView) {
        isPickerActive = false
        val cancelScript = """
(function() {
    window.__onyx_picker_active = false;
    var h = document.getElementById('__onyx_picker_highlight');
    if (h && h.parentNode) h.parentNode.removeChild(h);
    var b = document.getElementById('__onyx_picker_bar');
    if (b && b.parentNode) b.parentNode.removeChild(b);
})();
"""
        webView.evaluateJavascript(cancelScript, null)
    }
}
