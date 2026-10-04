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

    // List of selected items: { element, selector, overlay, badge }
    var selectedItems = [];
    var focusedIndex = -1;
    var isPreviewActive = false;

    // ── Root container for highlight overlays ─────────────────────────────────
    var overlayRoot = document.createElement('div');
    overlayRoot.id = '__onyx_picker_root';
    overlayRoot.style.cssText =
        'position: absolute !important; top: 0 !important; left: 0 !important; ' +
        'width: 0 !important; height: 0 !important; pointer-events: none !important; ' +
        'z-index: 2147483640 !important;';
    (document.body || document.documentElement).appendChild(overlayRoot);

    // ── Floating Action Bar ───────────────────────────────────────────────────
    var bar = document.createElement('div');
    bar.id = '__onyx_picker_bar';
    bar.style.cssText =
        'position: fixed !important; bottom: 20px !important; left: 50% !important; ' +
        'transform: translateX(-50%) !important; z-index: 2147483647 !important; ' +
        'background: #202124 !important; color: #FFFFFF !important; ' +
        'padding: 8px 14px !important; border-radius: 28px !important; ' +
        'border: 1px solid #3C4043 !important; ' +
        'box-shadow: 0 4px 24px rgba(0,0,0,0.65) !important; ' +
        'display: flex !important; align-items: center !important; gap: 8px !important; ' +
        'font-family: -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif !important; ' +
        'font-size: 13px !important; white-space: nowrap !important; max-width: 95vw !important; ' +
        'overflow-x: auto !important; -webkit-overflow-scrolling: touch !important;';

    var infoSpan = document.createElement('span');
    infoSpan.id = '__onyx_picker_info';
    infoSpan.textContent = 'Tap elements to block';
    infoSpan.style.cssText =
        'overflow: hidden; text-overflow: ellipsis; max-width: 120px; flex-shrink: 1; ' +
        'font-weight: 500; color: #E8EAED;';
    bar.appendChild(infoSpan);

    function makeBtn(label, bg, color, border) {
        var b = document.createElement('button');
        b.textContent = label;
        b.style.cssText =
            'padding: 6px 12px; background: ' + bg + '; color: ' + color + '; ' +
            'border: ' + (border || 'none') + '; border-radius: 16px; ' +
            'font-size: 12px; font-weight: 500; cursor: pointer; flex-shrink: 0; outline: none; ' +
            'user-select: none; -webkit-user-select: none; transition: background 0.15s, opacity 0.15s;';
        return b;
    }

    var cancelBtn  = makeBtn('Cancel', 'transparent', '#E8EAED', '1px solid #5F6368');
    var widerBtn   = makeBtn('▲ Wider', '#3C4043', '#E8EAED', 'none');
    var previewBtn = makeBtn('👁 Preview', '#3C4043', '#E8EAED', 'none');
    var blockBtn   = makeBtn('Block', '#5F6368', '#9AA0A6', 'none');

    blockBtn.disabled = true;
    blockBtn.style.cursor = 'not-allowed';
    widerBtn.disabled = true;
    widerBtn.style.opacity = '0.4';
    previewBtn.disabled = true;
    previewBtn.style.opacity = '0.4';

    bar.appendChild(cancelBtn);
    bar.appendChild(widerBtn);
    bar.appendChild(previewBtn);
    bar.appendChild(blockBtn);
    (document.body || document.documentElement).appendChild(bar);

    // ── CSS Escape Helper ─────────────────────────────────────────────────────
    function cssEscape(str) {
        if (typeof CSS !== 'undefined' && CSS.escape) return CSS.escape(str);
        return str.replace(/([\u0000-\u002F\u003A-\u0040\u005B-\u0060\u007B-\u009F])/g, '\\$1');
    }

    var DYNAMIC_ID = /^[0-9]|[\:\.\[\]\{\}@,!%^&*()+=~|<>?/\\'"`;]/;
    var NOISY_CLASS = /^(flex|grid|block|inline|relative|absolute|fixed|sticky|container|col-|row-|w-|h-|p-|m-|gap-|text-|bg-|border-|dark:|sm:|md:|lg:|xl:|css-|sc-)/;

    // ── Robust Selector Generator ─────────────────────────────────────────────
    function generateSelector(el) {
        if (!el || el === document.body || el === document.documentElement) return '';

        // 1. Stable, non-dynamic ID
        if (el.id && typeof el.id === 'string' && el.id.trim().length > 0) {
            var rawId = el.id.trim();
            if (!DYNAMIC_ID.test(rawId) && !/^\d/.test(rawId) && rawId.length <= 60 && !/[a-f0-9]{8,}/i.test(rawId)) {
                var idSel = '#' + cssEscape(rawId);
                try {
                    if (document.querySelectorAll(idSel).length === 1) return idSel;
                } catch(_) {}
            }
        }

        var tag = el.tagName.toLowerCase();

        // 2. Distinctive semantic attributes
        var attrCandidates = ['data-testid', 'data-ad', 'data-slot', 'data-ad-slot', 'data-unit', 'aria-label', 'role'];
        for (var ai = 0; ai < attrCandidates.length; ai++) {
            var attr = attrCandidates[ai];
            var val = el.getAttribute(attr);
            if (val && typeof val === 'string' && val.trim().length > 0 && val.length <= 60) {
                var attrSel = tag + '[' + attr + '="' + cssEscape(val.trim()) + '"]';
                try {
                    if (document.querySelectorAll(attrSel).length === 1) return attrSel;
                } catch(_) {}
            }
        }

        // 3. Stable semantic class names
        if (el.classList && el.classList.length > 0) {
            var classes = Array.prototype.slice.call(el.classList)
                .filter(function(c) {
                    return c && !c.startsWith('__onyx') &&
                           c.length <= 40 &&
                           !/^\d/.test(c) &&
                           !NOISY_CLASS.test(c);
                })
                .slice(0, 3)
                .map(function(c) { return '.' + cssEscape(c); });

            if (classes.length > 0) {
                var classSel = tag + classes.join('');
                try {
                    var matches = document.querySelectorAll(classSel);
                    if (matches.length === 1) return classSel;
                } catch(_) {}
            }
        }

        // 4. Parent contextual qualifier
        var parent = el.parentElement;
        if (parent && parent !== document.body && parent !== document.documentElement) {
            var parentSel = generateSelector(parent);
            if (parentSel) {
                var siblings = Array.prototype.slice.call(parent.children);
                var sameTagSiblings = siblings.filter(function(s) { return s.tagName === el.tagName; });
                if (sameTagSiblings.length > 1) {
                    var nth = sameTagSiblings.indexOf(el) + 1;
                    return parentSel + ' > ' + tag + ':nth-of-type(' + nth + ')';
                } else {
                    return parentSel + ' > ' + tag;
                }
            }
        }

        return tag;
    }

    // ── Highlight Position Updater ────────────────────────────────────────────
    function updateOverlayPositions() {
        if (isPreviewActive) return;
        for (var i = 0; i < selectedItems.length; i++) {
            var item = selectedItems[i];
            if (!item.element || !document.contains(item.element)) {
                item.overlay.style.display = 'none';
                continue;
            }
            var rect = item.element.getBoundingClientRect();
            if (rect.width === 0 && rect.height === 0) {
                item.overlay.style.display = 'none';
            } else {
                item.overlay.style.display = 'block';
                item.overlay.style.top = rect.top + 'px';
                item.overlay.style.left = rect.left + 'px';
                item.overlay.style.width = rect.width + 'px';
                item.overlay.style.height = rect.height + 'px';
            }
        }
    }

    window.addEventListener('scroll', updateOverlayPositions, { passive: true, capture: true });
    window.addEventListener('resize', updateOverlayPositions, { passive: true });

    // ── Update Floating Action Bar State ──────────────────────────────────────
    function updateBarState() {
        var count = selectedItems.length;
        if (count === 0) {
            infoSpan.textContent = 'Tap elements to block';
            blockBtn.textContent = 'Block';
            blockBtn.disabled = true;
            blockBtn.style.background = '#5F6368';
            blockBtn.style.color = '#9AA0A6';
            blockBtn.style.cursor = 'not-allowed';

            widerBtn.disabled = true;
            widerBtn.style.opacity = '0.4';
            previewBtn.disabled = true;
            previewBtn.style.opacity = '0.4';
        } else {
            infoSpan.textContent = count === 1 ? '1 element selected' : count + ' elements selected';
            blockBtn.textContent = 'Block (' + count + ')';
            blockBtn.disabled = false;
            blockBtn.style.background = '#EA4335';
            blockBtn.style.color = '#FFFFFF';
            blockBtn.style.cursor = 'pointer';

            var focusedItem = (focusedIndex >= 0 && focusedIndex < count) ? selectedItems[focusedIndex] : selectedItems[count - 1];
            var canGoWider = focusedItem && focusedItem.element.parentElement &&
                             focusedItem.element.parentElement !== document.body &&
                             focusedItem.element.parentElement !== document.documentElement;
            widerBtn.disabled = !canGoWider;
            widerBtn.style.opacity = canGoWider ? '1' : '0.4';

            previewBtn.disabled = false;
            previewBtn.style.opacity = '1';
        }
    }

    // ── Add or Toggle Element Selection ───────────────────────────────────────
    function toggleElementSelection(el) {
        if (!el || el === overlayRoot || overlayRoot.contains(el) || el === bar || bar.contains(el)) return;

        // Check if clicked element (or its ancestor/descendant) is already selected
        var existingIdx = -1;
        for (var i = 0; i < selectedItems.length; i++) {
            if (selectedItems[i].element === el || selectedItems[i].element.contains(el)) {
                existingIdx = i;
                break;
            }
        }

        if (existingIdx !== -1) {
            // Deselect
            var removed = selectedItems.splice(existingIdx, 1)[0];
            if (removed && removed.overlay && removed.overlay.parentNode) {
                removed.overlay.parentNode.removeChild(removed.overlay);
            }
            focusedIndex = selectedItems.length - 1;
            renumberBadges();
            updateBarState();
            return;
        }

        // Create new selected item overlay
        var sel = generateSelector(el);
        var overlay = document.createElement('div');
        overlay.className = '__onyx_picker_box';
        overlay.style.cssText =
            'position: fixed !important; pointer-events: none !important; ' +
            'z-index: 2147483640 !important; border: 2px solid #EA4335 !important; ' +
            'background: rgba(234,67,53,0.20) !important; border-radius: 4px !important; ' +
            'box-sizing: border-box !important; transition: all 0.05s ease-out !important;';

        var badge = document.createElement('div');
        badge.className = '__onyx_picker_badge';
        badge.textContent = (selectedItems.length + 1);
        badge.style.cssText =
            'position: absolute !important; top: -11px !important; left: -11px !important; ' +
            'background: #EA4335 !important; color: #FFFFFF !important; font-size: 11px !important; ' +
            'font-weight: 700 !important; font-family: sans-serif !important; ' +
            'width: 20px !important; height: 20px !important; line-height: 20px !important; ' +
            'text-align: center !important; border-radius: 50% !important; ' +
            'box-shadow: 0 2px 5px rgba(0,0,0,0.5) !important; pointer-events: none !important;';
        overlay.appendChild(badge);
        overlayRoot.appendChild(overlay);

        selectedItems.push({
            element: el,
            selector: sel,
            overlay: overlay,
            badge: badge
        });
        focusedIndex = selectedItems.length - 1;

        updateOverlayPositions();
        updateBarState();
    }

    function renumberBadges() {
        for (var i = 0; i < selectedItems.length; i++) {
            selectedItems[i].badge.textContent = (i + 1);
        }
    }

    // ── Wider Button Action ───────────────────────────────────────────────────
    widerBtn.onclick = function(e) {
        e.preventDefault(); e.stopPropagation();
        if (selectedItems.length === 0) return;
        var idx = (focusedIndex >= 0 && focusedIndex < selectedItems.length) ? focusedIndex : selectedItems.length - 1;
        var currentItem = selectedItems[idx];
        var parent = currentItem.element.parentElement;
        if (parent && parent !== document.body && parent !== document.documentElement) {
            currentItem.element = parent;
            currentItem.selector = generateSelector(parent);
            updateOverlayPositions();
            updateBarState();
        }
    };

    // ── Preview Button Action ─────────────────────────────────────────────────
    previewBtn.onclick = function(e) {
        e.preventDefault(); e.stopPropagation();
        if (selectedItems.length === 0) return;

        isPreviewActive = !isPreviewActive;
        var existingPreview = document.getElementById('__onyx_preview_style');
        if (existingPreview) existingPreview.remove();

        if (isPreviewActive) {
            previewBtn.textContent = '↩ Restore';
            previewBtn.style.background = '#1A73E8';
            previewBtn.style.color = '#FFFFFF';
            overlayRoot.style.display = 'none';

            var selectors = selectedItems.map(function(item) { return item.selector; }).filter(Boolean);
            if (selectors.length > 0) {
                var pStyle = document.createElement('style');
                pStyle.id = '__onyx_preview_style';
                pStyle.textContent = selectors.join(', ') + ' { display: none !important; }';
                (document.head || document.documentElement).appendChild(pStyle);
            }
        } else {
            previewBtn.textContent = '👁 Preview';
            previewBtn.style.background = '#3C4043';
            previewBtn.style.color = '#E8EAED';
            overlayRoot.style.display = 'block';
            updateOverlayPositions();
        }
    };

    // ── Block Button Action ───────────────────────────────────────────────────
    blockBtn.onclick = function(e) {
        e.preventDefault(); e.stopPropagation();
        if (selectedItems.length === 0) return;

        var prev = document.getElementById('__onyx_preview_style');
        if (prev) prev.remove();

        var selectors = [];
        for (var i = 0; i < selectedItems.length; i++) {
            var s = selectedItems[i].selector;
            if (s && selectors.indexOf(s) === -1) {
                selectors.push(s);
            }
        }
        if (selectors.length === 0) return;

        // 1. Instant hide: inject a permanent <style> element on current page
        try {
            var style = document.createElement('style');
            style.id = '__onyx_user_blocked_' + Date.now();
            style.textContent = selectors.join(', ') + ' { display: none !important; }';
            (document.head || document.documentElement).appendChild(style);
        } catch(_) {
            selectedItems.forEach(function(item) {
                try { item.element.style.setProperty('display', 'none', 'important'); } catch(_) {}
            });
        }

        // 2. Persist rules permanently via OnyxShieldBridge
        try {
            if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.saveCustomCosmeticRules === 'function') {
                window.OnyxShieldBridge.saveCustomCosmeticRules(window.location.hostname, JSON.stringify(selectors));
            } else if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.saveCustomCosmeticRule === 'function') {
                for (var si = 0; si < selectors.length; si++) {
                    window.OnyxShieldBridge.saveCustomCosmeticRule(window.location.hostname, selectors[si]);
                }
            }
        } catch(err) {}

        cleanup();
    };

    // ── Cleanup ───────────────────────────────────────────────────────────────
    function cleanup() {
        window.__onyx_picker_active = false;
        window.removeEventListener('scroll', updateOverlayPositions, true);
        window.removeEventListener('resize', updateOverlayPositions);
        document.removeEventListener('click', onDocClick, true);
        document.removeEventListener('touchstart', onDocTouch, true);

        var prev = document.getElementById('__onyx_preview_style');
        if (prev) prev.remove();

        if (overlayRoot && overlayRoot.parentNode) overlayRoot.parentNode.removeChild(overlayRoot);
        if (bar && bar.parentNode) bar.parentNode.removeChild(bar);

        if (window.OnyxShieldBridge && window.OnyxShieldBridge.onPickerClosed) {
            window.OnyxShieldBridge.onPickerClosed();
        }
    }

    cancelBtn.onclick = function(e) {
        e.preventDefault(); e.stopPropagation();
        cleanup();
    };

    // ── Document Event Listeners ──────────────────────────────────────────────
    function onDocClick(e) {
        if (bar.contains(e.target)) return;
        e.preventDefault(); e.stopPropagation();
        toggleElementSelection(e.target);
    }

    function onDocTouch(e) {
        if (!e.touches || e.touches.length === 0) return;
        var touch = e.touches[0];
        var el = document.elementFromPoint(touch.clientX, touch.clientY);
        if (el && !bar.contains(el)) {
            e.preventDefault(); e.stopPropagation();
            toggleElementSelection(el);
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
    var root = document.getElementById('__onyx_picker_root');
    if (root && root.parentNode) root.parentNode.removeChild(root);
    var b = document.getElementById('__onyx_picker_bar');
    if (b && b.parentNode) b.parentNode.removeChild(b);
    var prev = document.getElementById('__onyx_preview_style');
    if (prev && prev.parentNode) prev.parentNode.removeChild(prev);
    var oldH = document.getElementById('__onyx_picker_highlight');
    if (oldH && oldH.parentNode) oldH.parentNode.removeChild(oldH);
})();
"""
        webView.evaluateJavascript(cancelScript, null)
    }
}
