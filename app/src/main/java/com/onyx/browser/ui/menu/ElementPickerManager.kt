package com.onyx.browser.ui.menu

import android.webkit.WebView

object ElementPickerManager {

    private const val PICKER_SCRIPT = """
(function() {
    if (window.__onyx_picker_active) return;
    window.__onyx_picker_active = true;

    var selectedElement = null;
    var selectedSelector = '';

    // 1. Highlight Box Overlay
    var highlightBox = document.createElement('div');
    highlightBox.id = '__onyx_picker_highlight';
    highlightBox.style.cssText = 'position: fixed !important; pointer-events: none !important; z-index: 2147483640 !important; ' +
        'border: 2px solid #EA4335 !important; background: rgba(234, 67, 53, 0.25) !important; ' +
        'display: none; border-radius: 4px; box-sizing: border-box; transition: all 0.05s ease-out;';
    (document.body || document.documentElement).appendChild(highlightBox);

    // 2. Floating Bottom Bar
    var bar = document.createElement('div');
    bar.id = '__onyx_picker_bar';
    bar.style.cssText = 'position: fixed !important; bottom: 20px !important; left: 50% !important; ' +
        'transform: translateX(-50%) !important; z-index: 2147483647 !important; ' +
        'background: #202124 !important; color: #FFFFFF !important; padding: 10px 18px !important; ' +
        'border-radius: 28px !important; box-shadow: 0 4px 20px rgba(0,0,0,0.5) !important; ' +
        'display: flex !important; align-items: center !important; gap: 12px !important; ' +
        'font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif !important; ' +
        'font-size: 13px !important; white-space: nowrap !important; max-width: 90vw !important;';

    var infoSpan = document.createElement('span');
    infoSpan.id = '__onyx_picker_info';
    infoSpan.textContent = 'Tap an element to block';
    infoSpan.style.cssText = 'overflow: hidden; text-overflow: ellipsis; max-width: 140px;';
    bar.appendChild(infoSpan);

    var cancelBtn = document.createElement('button');
    cancelBtn.textContent = 'Cancel';
    cancelBtn.style.cssText = 'padding: 6px 12px; background: transparent; color: #E8EAED; ' +
        'border: 1px solid #5F6368; border-radius: 16px; font-size: 12px; cursor: pointer;';
    bar.appendChild(cancelBtn);

    var blockBtn = document.createElement('button');
    blockBtn.textContent = 'Block';
    blockBtn.style.cssText = 'padding: 6px 14px; background: #5F6368; color: #9AA0A6; ' +
        'border: none; border-radius: 16px; font-weight: bold; font-size: 12px; cursor: not-allowed;';
    blockBtn.disabled = true;
    bar.appendChild(blockBtn);

    (document.body || document.documentElement).appendChild(bar);

    function cleanup() {
        window.__onyx_picker_active = false;
        document.removeEventListener('click', onDocClick, true);
        document.removeEventListener('touchstart', onDocTouch, true);
        if (highlightBox && highlightBox.parentNode) highlightBox.parentNode.removeChild(highlightBox);
        if (bar && bar.parentNode) bar.parentNode.removeChild(bar);
    }

    cancelBtn.onclick = function(e) {
        e.preventDefault();
        e.stopPropagation();
        cleanup();
    };

    blockBtn.onclick = function(e) {
        e.preventDefault();
        e.stopPropagation();
        if (selectedElement && selectedSelector) {
            try {
                selectedElement.style.setProperty('display', 'none', 'important');
                if (window.OnyxShieldBridge && window.OnyxShieldBridge.saveCustomCosmeticRule) {
                    window.OnyxShieldBridge.saveCustomCosmeticRule(window.location.hostname, selectedSelector);
                }
            } catch(err) {}
        }
        cleanup();
    };

    function generateSelector(el) {
        if (!el || el === document.body || el === document.documentElement) return '';
        if (el.id && typeof el.id === 'string' && el.id.trim().length > 0) {
            var cleanId = el.id.trim().replace(/(:|\.|\[|\]|,|=|@)/g, '\\${'$'}1');
            return '#' + cleanId;
        }
        var tag = el.tagName.toLowerCase();
        if (el.classList && el.classList.length > 0) {
            var classes = Array.prototype.slice.call(el.classList)
                .filter(function(c) { return c && !c.startsWith('__onyx'); })
                .slice(0, 2)
                .map(function(c) { return '.' + c.replace(/(:|\.|\[|\]|,|=|@)/g, '\\${'$'}1'); });
            if (classes.length > 0) {
                return tag + classes.join('');
            }
        }
        var parent = el.parentElement;
        if (parent && parent !== document.body) {
            var siblings = Array.prototype.slice.call(parent.children);
            var index = siblings.indexOf(el) + 1;
            var parentSel = generateSelector(parent);
            if (parentSel) {
                return parentSel + ' > ' + tag + ':nth-child(' + index + ')';
            }
        }
        return tag;
    }

    function selectTarget(el) {
        if (!el || el === highlightBox || el === bar || bar.contains(el)) return;
        selectedElement = el;
        selectedSelector = generateSelector(el);

        var rect = el.getBoundingClientRect();
        highlightBox.style.display = 'block';
        highlightBox.style.top = rect.top + 'px';
        highlightBox.style.left = rect.left + 'px';
        highlightBox.style.width = rect.width + 'px';
        highlightBox.style.height = rect.height + 'px';

        infoSpan.textContent = selectedSelector || 'Selected element';
        blockBtn.disabled = false;
        blockBtn.style.background = '#EA4335';
        blockBtn.style.color = '#FFFFFF';
        blockBtn.style.cursor = 'pointer';
    }

    function onDocClick(e) {
        if (bar.contains(e.target)) return;
        e.preventDefault();
        e.stopPropagation();
        selectTarget(e.target);
    }

    function onDocTouch(e) {
        if (!e.touches || e.touches.length === 0) return;
        var touch = e.touches[0];
        var el = document.elementFromPoint(touch.clientX, touch.clientY);
        if (el && !bar.contains(el)) {
            e.preventDefault();
            e.stopPropagation();
            selectTarget(el);
        }
    }

    document.addEventListener('click', onDocClick, true);
    document.addEventListener('touchstart', onDocTouch, { capture: true, passive: false });
})();
"""

    fun startPicker(webView: WebView) {
        webView.evaluateJavascript(PICKER_SCRIPT, null)
    }

    fun cancelPicker(webView: WebView) {
        val cancelScript = """
(function() {
    window.__onyx_picker_active = false;
    var highlight = document.getElementById('__onyx_picker_highlight');
    if (highlight && highlight.parentNode) highlight.parentNode.removeChild(highlight);
    var bar = document.getElementById('__onyx_picker_bar');
    if (bar && bar.parentNode) bar.parentNode.removeChild(bar);
})();
"""
        webView.evaluateJavascript(cancelScript, null)
    }
}
