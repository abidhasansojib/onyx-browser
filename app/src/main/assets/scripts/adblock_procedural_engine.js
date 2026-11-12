// ── Full Brave Procedural Cosmetic Filter Engine (procedural_filters.ts port) ───
var proceduralRules = null;
var proceduralTimer = null;

var _compileRegEx = function(regexText) {
    var regexParts = regexText.split('/');
    var regexPattern = regexParts[1];
    var regexArgs = regexParts[2];
    return new RegExp(regexPattern, regexArgs);
};

var _testMatches = function(test, value, exact) {
    if (typeof value !== 'string') value = String(value || '');
    if (test && test.charAt(0) === '/') {
        try { return _compileRegEx(test).test(value); } catch(_) { return false; }
    }
    if (test === '') return value.trim() === '';
    if (exact) return value === test;
    return value.indexOf(test) !== -1;
};

var _extractKeyFromStr = function(text) {
    var quotedTerminator = '"=';
    var unquotedTerminator = '=';
    var isQuoted = text.charAt(0) === '"';
    var terminator = isQuoted ? quotedTerminator : unquotedTerminator;
    var needlePos = isQuoted ? 1 : 0;
    var idx = text.indexOf(terminator, needlePos);
    if (idx === -1) {
        var key = text;
        if (isQuoted && text.endsWith('"')) {
            key = text.slice(1, text.length - 1);
        }
        return [key, undefined];
    }
    var keyStr = text.slice(needlePos, idx);
    return [keyStr, idx + terminator.length];
};

var _extractValueFromStr = function(text, uriEncode, needlePosition) {
    var pos = needlePosition || 0;
    var isQuoted = text.charAt(pos) === '"';
    var endIdx = isQuoted ? text.length - 1 : text.length;
    if (isQuoted) pos += 1;
    var valStr = text.slice(pos, endIdx);
    if (uriEncode) {
        valStr = encodeURIComponent(valStr);
    }
    return valStr;
};

var _extractValueMatchRuleFromStr = function(text, uriEncode, needlePosition) {
    var testStr = _extractValueFromStr(text, uriEncode, needlePosition);
    return function(targetText) {
        return _testMatches(testStr, targetText);
    };
};

var _parseKeyValueMatchRules = function(arg) {
    var extracted = _extractKeyFromStr(arg);
    var key = extracted[0];
    var needlePos = extracted[1];
    var keyMatch = function(k) { return _testMatches(key, k, true); };
    var valMatch = undefined;
    if (needlePos !== undefined) {
        var valStr = _extractValueFromStr(arg, false, needlePos);
        valMatch = function(v) { return _testMatches(valStr, v, true); };
    }
    return [keyMatch, valMatch];
};

var _parseCSSInstruction = function(arg) {
    var colonIdx = arg.indexOf(':');
    if (colonIdx === -1) return ['', ''];
    return [arg.substring(0, colonIdx).trim(), arg.substring(colonIdx + 1).trim()];
};

var _stripCssOperator = function(operator, selector) {
    return selector.replace(operator, '').trim();
};

var operatorCssSelector = function(selector, element) {
    var trimmed = selector.trim();
    try {
        if (trimmed.charAt(0) === '+') {
            var nextSib = element.nextElementSibling;
            if (!nextSib) return [];
            var subOp = _stripCssOperator('+', trimmed);
            return nextSib.matches(subOp) ? [nextSib] : [];
        } else if (trimmed.charAt(0) === '~') {
            var parent = element.parentNode;
            if (!parent) return [];
            var subOp = _stripCssOperator('~', trimmed);
            var siblings = Array.prototype.slice.call(parent.children);
            return siblings.filter(function(sib) { return sib !== element && sib.matches(subOp); });
        } else if (trimmed.charAt(0) === '>') {
            var subOp = _stripCssOperator('>', trimmed);
            var children = Array.prototype.slice.call(element.children);
            return children.filter(function(child) { return child.matches(subOp); });
        } else if (selector.charAt(0) === ' ') {
            return Array.prototype.slice.call(element.querySelectorAll(':scope ' + trimmed));
        }
        if (element.matches(selector)) return [element];
    } catch(_) {}
    return [];
};

var operatorHasText = function(instruction, element) {
    var text = element.innerText || element.textContent || '';
    var valueTest = _extractValueMatchRuleFromStr(instruction);
    return valueTest(text) ? [element] : [];
};

var operatorMinTextLength = function(instruction, element) {
    var minLen = parseInt(instruction, 10);
    if (isNaN(minLen)) return [];
    var text = (element.innerText || element.textContent || '').trim();
    return text.length >= minLen ? [element] : [];
};

var operatorMatchesAttr = function(instruction, element) {
    var parsed = _parseKeyValueMatchRules(instruction);
    var keyTest = parsed[0];
    var valTest = parsed[1];
    var names = element.getAttributeNames ? element.getAttributeNames() : [];
    for (var i = 0; i < names.length; i++) {
        var attrName = names[i];
        if (!keyTest(attrName)) continue;
        var attrVal = element.getAttribute(attrName);
        if (attrVal === null || (valTest !== undefined && !valTest(attrVal))) continue;
        return [element];
    }
    return [];
};

var operatorMatchesProperty = function(instruction, element) {
    var parsed = _parseKeyValueMatchRules(instruction);
    var keyTest = parsed[0];
    var valTest = parsed[1];
    for (var prop in element) {
        try {
            if (!keyTest(prop)) continue;
            if (valTest !== undefined && !valTest(element[prop])) continue;
            return [element];
        } catch(_) {}
    }
    return [];
};

var operatorMatchesCSS = function(beforeOrAfter, instruction, element) {
    var parsed = _parseCSSInstruction(instruction);
    var cssKey = parsed[0];
    var expectedVal = parsed[1];
    var computed = window.getComputedStyle(element, beforeOrAfter);
    var styleValue = computed ? computed.getPropertyValue(cssKey) : null;
    if (!styleValue) return [];
    var matched = false;
    if (expectedVal.charAt(0) === '/' && expectedVal.endsWith('/')) {
        try { matched = _compileRegEx(expectedVal).test(styleValue); } catch(_) {}
    } else {
        matched = (expectedVal === styleValue);
    }
    return matched ? [element] : [];
};

var operatorMatchesMedia = function(instruction, element) {
    try { return window.matchMedia(instruction).matches ? [element] : []; } catch(_) { return []; }
};

var operatorMatchesPath = function(instruction, element) {
    var pathAndQuery = window.location.pathname + window.location.search;
    var matchRule = _extractValueMatchRuleFromStr(instruction, true);
    return matchRule(pathAndQuery) ? [element] : [];
};

var operatorUpward = function(instruction, element) {
    var steps = parseInt(instruction, 10);
    if (!isNaN(steps) && steps > 0 && steps < 256) {
        var cur = element;
        while (cur && steps > 0) {
            cur = cur.parentElement;
            steps--;
        }
        return cur ? [cur] : [];
    }
    if (Array.isArray(instruction)) {
        var cur = element.parentElement;
        while (cur) {
            var m = compileAndApplyProceduralSelector(instruction, [cur]);
            if (m.length > 0) return [cur];
            cur = cur.parentElement;
        }
        return [];
    }
    if (typeof instruction === 'string' && instruction.length > 0) {
        try {
            var nearest = element.closest(instruction);
            return nearest ? [nearest] : [];
        } catch(_) {}
    }
    return [];
};

var operatorXPath = function(instruction, element) {
    try {
        var result = document.evaluate(
            instruction,
            element,
            null,
            XPathResult.UNORDERED_NODE_ITERATOR_TYPE,
            null
        );
        var matches = [];
        var curr = result.iterateNext();
        while (curr) {
            if (curr.nodeType === 1) matches.push(curr);
            curr = result.iterateNext();
        }
        return matches;
    } catch(_) {
        return [];
    }
};

var operatorHas = function(instruction, element) {
    if (Array.isArray(instruction)) {
        var shouldBeGreedy = !instruction[0] || instruction[0].type !== 'css-selector';
        var inits = shouldBeGreedy ?
            Array.prototype.slice.call(element.querySelectorAll(':scope *')) : [element];
        var res = compileAndApplyProceduralSelector(instruction, inits);
        return res.length === 0 ? [] : [element];
    }
    try {
        return element.matches(instruction) ? [element] : [];
    } catch(_) {
        return [];
    }
};

var operatorNot = function(instruction, element) {
    if (Array.isArray(instruction)) {
        var res = compileAndApplyProceduralSelector(instruction, [element]);
        return res.length === 0 ? [element] : [];
    }
    try {
        return element.matches(instruction) ? [] : [element];
    } catch(_) {
        return [];
    }
};

var ruleTypeToFuncMap = {
    'contains': operatorHasText,
    'css-selector': operatorCssSelector,
    'has': operatorHas,
    'has-text': operatorHasText,
    'matches-attr': operatorMatchesAttr,
    'matches-css': function(arg, el) { return operatorMatchesCSS(null, arg, el); },
    'matches-css-after': function(arg, el) { return operatorMatchesCSS('::after', arg, el); },
    'matches-css-before': function(arg, el) { return operatorMatchesCSS('::before', arg, el); },
    'matches-media': operatorMatchesMedia,
    'matches-path': operatorMatchesPath,
    'matches-property': operatorMatchesProperty,
    'min-text-length': operatorMinTextLength,
    'not': operatorNot,
    'upward': operatorUpward,
    'xpath': operatorXPath
};

var fastPathOperatorTypes = ['matches-media', 'matches-path'];

function compileProceduralSelector(operators) {
    var list = [];
    for (var i = 0; i < operators.length; i++) {
        var op = operators[i];
        var fn = ruleTypeToFuncMap[op.type];
        if (!fn) continue;
        list.push({
            type: op.type,
            func: fn,
            arg: op.arg
        });
    }
    return list;
}

function applyCompiledSelector(compiledOps, initNodes) {
    var nodesToConsider = initNodes ? Array.prototype.slice.call(initNodes) : [];
    for (var i = 0; i < compiledOps.length; i++) {
        if (nodesToConsider.length === 0) break;
        var cop = compiledOps[i];
        if (fastPathOperatorTypes.indexOf(cop.type) !== -1) {
            if (nodesToConsider.length > 0 && cop.func(cop.arg, nodesToConsider[0]).length === 0) {
                nodesToConsider = [];
            }
            continue;
        }
        var next = [];
        for (var n = 0; n < nodesToConsider.length; n++) {
            var matched = cop.func(cop.arg, nodesToConsider[n]);
            if (matched && matched.length > 0) {
                for (var m = 0; m < matched.length; m++) {
                    if (next.indexOf(matched[m]) === -1) next.push(matched[m]);
                }
            }
        }
        nodesToConsider = next;
    }
    return nodesToConsider;
}

function compileAndApplyProceduralSelector(selector, initElements) {
    var compiled = compileProceduralSelector(selector);
    return applyCompiledSelector(compiled, initElements);
}

function runProceduralFilters() {
    try {
        if (!window.OnyxShieldBridge || !window.OnyxShieldBridge.getProceduralActions) return;
        if (proceduralRules === null) {
            var raw = window.OnyxShieldBridge.getProceduralActions(window.location.href);
            if (!raw || raw === '[]') {
                proceduralRules = [];
                return;
            }
            var parsed = JSON.parse(raw);
            proceduralRules = [];
            for (var r = 0; r < parsed.length; r++) {
                var rule = parsed[r];
                if (typeof rule === 'string') {
                    try { rule = JSON.parse(rule); } catch(e) { continue; }
                }
                if (rule && rule.selector && Array.isArray(rule.selector)) {
                    proceduralRules.push(rule);
                }
            }
        }

        if (proceduralRules.length === 0) return;

        for (var i = 0; i < proceduralRules.length; i++) {
            executeProceduralRule(proceduralRules[i]);
        }
    } catch(e) {}
}

function scheduleProceduralRun() {
    if (proceduralTimer) return;
    proceduralTimer = setTimeout(function() {
        proceduralTimer = null;
        runProceduralFilters();
    }, 100);
}

function executeProceduralRule(rule) {
    try {
        var ops = rule.selector;
        if (!ops || !Array.isArray(ops) || ops.length === 0) return;

        var matchingElements = [];
        var startOperator = 0;

        if (ops[0].type === 'css-selector') {
            try {
                matchingElements = Array.prototype.slice.call(document.querySelectorAll(ops[0].arg));
            } catch(_) {
                return;
            }
            startOperator = 1;
        } else if (ops[0].type === 'xpath') {
            matchingElements = operatorXPath(ops[0].arg, document.documentElement || document.body);
            startOperator = 1;
        } else {
            matchingElements = Array.prototype.slice.call(document.querySelectorAll('*'));
        }

        if (startOperator < ops.length) {
            var compiled = compileProceduralSelector(ops.slice(startOperator));
            matchingElements = applyCompiledSelector(compiled, matchingElements);
        }

        var action = rule.action;
        for (var a = 0; a < matchingElements.length; a++) {
            var el = matchingElements[a];
            if (!el || el.nodeType !== 1) continue;

            if (!action || action.type === 'remove') {
                try {
                    el.style.setProperty('display', 'none', 'important');
                    el.style.setProperty('pointer-events', 'none', 'important');
                    el.style.setProperty('visibility', 'hidden', 'important');
                    el.setAttribute('data-onyx-procedural-hidden', 'true');
                    el.remove();
                } catch(_) {}
            } else if (action.type === 'style' && action.arg) {
                el.style.cssText += ';' + action.arg;
            } else if (action.type === 'remove-attr' && action.arg) {
                try {
                    el.removeAttribute(action.arg);
                    delete el[action.arg];
                    el[action.arg] = null;
                } catch(_) {}
            } else if (action.type === 'remove-class' && action.arg) {
                if (el.classList.contains(action.arg)) {
                    el.classList.remove(action.arg);
                }
            }
        }
    } catch(e) {}
}
