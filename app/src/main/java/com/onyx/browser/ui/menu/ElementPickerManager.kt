package com.onyx.browser.ui.menu

import android.os.Looper
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
    if (window.__onyx_picker_active) {
        if (typeof window.__onyx_picker_cleanup === 'function') {
            try { window.__onyx_picker_cleanup(); } catch(_) {}
        }
    }
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
        'position: fixed !important; bottom: calc(20px + env(safe-area-inset-bottom, 0px)) !important; left: 50% !important; ' +
        'transform: translateX(-50%) !important; z-index: 2147483647 !important; ' +
        'background: #202124 !important; color: #FFFFFF !important; ' +
        'padding: 8px 14px !important; border-radius: 28px !important; ' +
        'border: 1px solid #3C4043 !important; ' +
        'box-shadow: 0 4px 24px rgba(0,0,0,0.65) !important; ' +
        'display: flex !important; align-items: center !important; gap: 8px !important; ' +
        'font-family: -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif !important; ' +
        'font-size: 13px !important; white-space: nowrap !important; max-width: 95vw !important; ' +
        'overflow-x: auto !important; -webkit-overflow-scrolling: touch !important; ' +
        'pointer-events: auto !important; touch-action: manipulation !important;';

    // Helper to test if element is part of the picker bar
    function isBarElement(target) {
        if (!target) return false;
        try {
            return target === bar || (bar && bar.contains(target));
        } catch(_) {
            return false;
        }
    }

    // Stop events on the bar from propagating to page content
    function stopBarEvent(e) {
        if (e) {
            e.stopPropagation();
        }
    }
    bar.addEventListener('touchstart', stopBarEvent, { passive: true });
    bar.addEventListener('touchmove', stopBarEvent, { passive: true });
    bar.addEventListener('touchend', stopBarEvent, { passive: false });
    bar.addEventListener('click', stopBarEvent);

    var infoSpan = document.createElement('span');
    infoSpan.id = '__onyx_picker_info';
    infoSpan.textContent = 'Tap elements to block';
    infoSpan.style.cssText =
        'overflow: hidden; text-overflow: ellipsis; max-width: 120px; flex-shrink: 1; ' +
        'font-weight: 500; color: #E8EAED; user-select: none; -webkit-user-select: none;';
    bar.appendChild(infoSpan);

    function makeBtn(label, bg, color, border) {
        var b = document.createElement('button');
        b.type = 'button';
        b.textContent = label;
        b.style.cssText =
            'padding: 7px 13px !important; min-height: 36px !important; background: ' + bg + ' !important; color: ' + color + ' !important; ' +
            'border: ' + (border || 'none') + ' !important; border-radius: 18px !important; ' +
            'font-size: 12px !important; font-weight: 500 !important; cursor: pointer !important; flex-shrink: 0 !important; outline: none !important; ' +
            'user-select: none !important; -webkit-user-select: none !important; ' +
            'pointer-events: auto !important; touch-action: manipulation !important; ' +
            'transition: background 0.15s, opacity 0.15s !important;';
        return b;
    }

    // Helper to bind touches and clicks reliably on mobile devices without synthetic delay or cancellation
    function bindBtn(btn, action) {
        var lastTrigger = 0;
        function trigger(e) {
            var now = Date.now();
            if (now - lastTrigger < 350) {
                if (e) {
                    if (e.cancelable) e.preventDefault();
                    e.stopPropagation();
                }
                return;
            }
            lastTrigger = now;
            if (e) {
                if (e.cancelable) e.preventDefault();
                e.stopPropagation();
            }
            action();
        }
        btn.addEventListener('touchend', trigger, { passive: false });
        btn.addEventListener('click', trigger, true);
        btn.addEventListener('pointerup', trigger, true);
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

    var DYNAMIC_ID = /[^a-zA-Z0-9_-]/;
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
        if (!el || el === overlayRoot || overlayRoot.contains(el) || isBarElement(el)) return;

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
    bindBtn(widerBtn, function() {
        if (widerBtn.disabled || selectedItems.length === 0) return;
        var idx = (focusedIndex >= 0 && focusedIndex < selectedItems.length) ? focusedIndex : selectedItems.length - 1;
        var currentItem = selectedItems[idx];
        if (!currentItem) return;
        var parent = currentItem.element.parentElement;
        if (parent && parent !== document.body && parent !== document.documentElement) {
            currentItem.element = parent;
            currentItem.selector = generateSelector(parent);
            updateOverlayPositions();
            updateBarState();
        }
    });

    // ── Preview Button Action ─────────────────────────────────────────────────
    bindBtn(previewBtn, function() {
        if (previewBtn.disabled || selectedItems.length === 0) return;

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
    });

    // ── Block Button Action ───────────────────────────────────────────────────
    bindBtn(blockBtn, function() {
        if (blockBtn.disabled || selectedItems.length === 0) return;

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
    });

    // ── Cleanup ───────────────────────────────────────────────────────────────
    function cleanup() {
        window.__onyx_picker_active = false;
        window.__onyx_picker_cleanup = null;

        try { window.removeEventListener('scroll', updateOverlayPositions, true); } catch(_) {}
        try { window.removeEventListener('resize', updateOverlayPositions); } catch(_) {}
        try { document.removeEventListener('click', onDocClick, true); } catch(_) {}
        try { document.removeEventListener('touchstart', onDocTouchStart, true); } catch(_) {}
        try { document.removeEventListener('touchmove', onDocTouchMove, true); } catch(_) {}
        try { document.removeEventListener('touchend', onDocTouchEnd, true); } catch(_) {}

        try {
            var prev = document.getElementById('__onyx_preview_style');
            if (prev && prev.parentNode) prev.parentNode.removeChild(prev);
        } catch(_) {}

        try {
            if (overlayRoot && overlayRoot.parentNode) overlayRoot.parentNode.removeChild(overlayRoot);
        } catch(_) {}

        try {
            if (bar && bar.parentNode) bar.parentNode.removeChild(bar);
        } catch(_) {}

        try {
            if (window.OnyxShieldBridge && typeof window.OnyxShieldBridge.onPickerClosed === 'function') {
                window.OnyxShieldBridge.onPickerClosed();
            }
        } catch(_) {}
    }

    window.__onyx_picker_cleanup = cleanup;

    bindBtn(cancelBtn, function() {
        cleanup();
    });

    // ── Document Event Listeners ──────────────────────────────────────────────
    var touchStartX = 0;
    var touchStartY = 0;
    var isTouchMoved = false;

    function onDocTouchStart(e) {
        if (isBarElement(e.target)) return;
        if (!e.touches || e.touches.length === 0) return;
        var t = e.touches[0];
        touchStartX = t.clientX;
        touchStartY = t.clientY;
        isTouchMoved = false;
    }

    function onDocTouchMove(e) {
        if (isBarElement(e.target)) return;
        if (!e.touches || e.touches.length === 0) return;
        var t = e.touches[0];
        var dx = Math.abs(t.clientX - touchStartX);
        var dy = Math.abs(t.clientY - touchStartY);
        if (dx > 10 || dy > 10) {
            isTouchMoved = true;
        }
    }

    function onDocTouchEnd(e) {
        if (isBarElement(e.target)) return;
        if (isTouchMoved) return;
        if (!e.changedTouches || e.changedTouches.length === 0) return;
        var t = e.changedTouches[0];
        var el = document.elementFromPoint(t.clientX, t.clientY);
        if (!el || isBarElement(el)) return;
        if (e.cancelable) e.preventDefault();
        e.stopPropagation();
        toggleElementSelection(el);
    }

    function onDocClick(e) {
        if (isBarElement(e.target)) return;
        // Suppress synthetic clicks on touch-capable screens to prevent double-toggle
        if (window.matchMedia && window.matchMedia('(pointer: coarse)').matches) {
            return;
        }
        if (e.cancelable) e.preventDefault();
        e.stopPropagation();
        toggleElementSelection(e.target);
    }

    document.addEventListener('click', onDocClick, true);
    document.addEventListener('touchstart', onDocTouchStart, { capture: true, passive: true });
    document.addEventListener('touchmove', onDocTouchMove, { capture: true, passive: true });
    document.addEventListener('touchend', onDocTouchEnd, { capture: true, passive: false });
})();
"""

    fun startPicker(webView: WebView) {
        isPickerActive = true
        if (Looper.myLooper() == Looper.getMainLooper()) {
            webView.evaluateJavascript(PICKER_SCRIPT, null)
        } else {
            webView.post { webView.evaluateJavascript(PICKER_SCRIPT, null) }
        }
    }

    fun cancelPicker(webView: WebView) {
        isPickerActive = false
        val cancelScript = """
(function() {
    try {
        if (typeof window.__onyx_picker_cleanup === 'function') {
            window.__onyx_picker_cleanup();
        }
    } catch(_) {}
    window.__onyx_picker_active = false;
    window.__onyx_picker_cleanup = null;
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
        if (Looper.myLooper() == Looper.getMainLooper()) {
            webView.evaluateJavascript(cancelScript, null)
        } else {
            webView.post { webView.evaluateJavascript(cancelScript, null) }
        }
    }
}
