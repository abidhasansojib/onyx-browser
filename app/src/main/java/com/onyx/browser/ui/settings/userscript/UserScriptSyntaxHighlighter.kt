package com.onyx.browser.ui.settings.userscript

import android.graphics.Color
import android.text.Editable
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

/**
 * Custom ForegroundColorSpan subclass specifically distinguishing userscript syntax spans
 * from IME composition spans and search highlighting spans.
 */
class JsSyntaxSpan(color: Int) : ForegroundColorSpan(color)

/**
 * High-performance, viewport-bounded JavaScript and Userscript syntax highlighter.
 * Uses pre-compiled regex tokenizers and range-restricted scanning to guarantee O(viewport)
 * execution complexity, preventing UI thread stalls on large scripts (50,000+ lines).
 */
object UserScriptSyntaxHighlighter {

    private val METADATA_PATTERN = Pattern.compile(
        """//\s*==/?UserScript==|//\s*@(?:name|namespace|version|description|author|match|include|exclude|exclude-match|require|resource|connect|grant|run-at|noframes|unwrap|inject-into|sandbox|updateURL|downloadURL|icon)\b.*""",
        Pattern.CASE_INSENSITIVE
    )

    private val COMMENT_LINE_PATTERN = Pattern.compile("""//[^\r\n]*""")
    private val COMMENT_BLOCK_PATTERN = Pattern.compile("""/\*[\s\S]*?\*/""")

    private val STRING_PATTERN = Pattern.compile(
        """"(?:[^"\\\r\n]++|\\.)*+"|'(?:[^'\\\r\n]++|\\.)*+'|`(?:[^`\\]++|\\.)*+`"""
    )

    private val NUMBER_PATTERN = Pattern.compile(
        """\b(?:0[xX][0-9a-fA-F]+|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\b"""
    )

    private val KEYWORD_PATTERN = Pattern.compile(
        """\b(?:const|let|var|function|return|if|else|for|while|do|switch|case|break|continue|default|new|delete|typeof|instanceof|try|catch|finally|throw|class|extends|super|import|export|from|as|async|await|yield|this|void|debugger|in|of)\b"""
    )

    private val CONSTANT_PATTERN = Pattern.compile(
        """\b(?:true|false|null|undefined|NaN|Infinity)\b"""
    )

    private val GM_API_PATTERN = Pattern.compile(
        """\b(?:GM|GM_getValue|GM_setValue|GM_deleteValue|GM_listValues|GM_getValues|GM_setValues|GM_deleteValues|GM_getTab|GM_saveTab|GM_getTabs|GM_xmlhttpRequest|GM_xmlHttpRequest|GM_download|GM_openInTab|GM_setClipboard|GM_notification|GM_addStyle|GM_addElement|GM_registerMenuCommand|GM_unregisterMenuCommand|GM_getResourceText|GM_getResourceURL|GM_log|GM_info|unsafeWindow)\b"""
    )

    data class ThemeColors(
        val metadata: Int,
        val comment: Int,
        val string: Int,
        val number: Int,
        val keyword: Int,
        val constant: Int,
        val gmApi: Int
    )

    val darkColors = ThemeColors(
        metadata = 0xFF4EC9B0.toInt(),  // Cyan / Teal
        comment = 0xFF6A9955.toInt(),   // Muted Green
        string = 0xFFCE9178.toInt(),    // Coral / Amber
        number = 0xFFB5CEA8.toInt(),    // Light Green
        keyword = 0xFF569CD6.toInt(),   // Keyword Blue
        constant = 0xFF56B6C2.toInt(),  // Aqua / Constant
        gmApi = 0xFFC586C0.toInt()      // Purple / Violet GM APIs
    )

    val lightColors = ThemeColors(
        metadata = 0xFF008080.toInt(),  // Deep Teal
        comment = 0xFF008000.toInt(),   // Forest Green
        string = 0xFFA31515.toInt(),    // Crimson / Rust
        number = 0xFF098658.toInt(),    // Dark Emerald
        keyword = 0xFF0000FF.toInt(),   // Classic Blue
        constant = 0xFF0184BC.toInt(),  // Cyan
        gmApi = 0xFFAF00DB.toInt()      // Purple
    )

    fun getThemeColors(isDark: Boolean): ThemeColors = if (isDark) darkColors else lightColors

    /**
     * Highlights only the specified range within the editable text.
     * Guaranteed to be O(range length) rather than O(total document size).
     */
    private const val MAX_HIGHLIGHT_CHARS = 16_000

    fun highlightRange(
        editable: Editable,
        rangeStart: Int,
        rangeEnd: Int,
        colors: ThemeColors
    ) {
        val len = editable.length
        if (len == 0) return
        val start = rangeStart.coerceIn(0, len)
        val end = minOf(rangeEnd.coerceIn(start, len), start + MAX_HIGHLIGHT_CHARS)
        if (start >= end) return

        // 1. Clear all existing syntax spans so span count in SpannableStringBuilder never accumulates
        val oldSpans = editable.getSpans(0, len, JsSyntaxSpan::class.java)
        for (span in oldSpans) {
            editable.removeSpan(span)
        }

        val subSequence: CharSequence = android.text.TextUtils.substring(editable, start, end)
        val occupied = BooleanArray(end - start)

        fun applyPattern(pattern: Pattern, color: Int, markOccupied: Boolean = true) {
            val matcher = pattern.matcher(subSequence)
            while (matcher.find()) {
                val matchStart = matcher.start()
                val matchEnd = matcher.end()
                if (matchStart >= matchEnd) continue

                // Avoid re-coloring keywords inside strings or comments
                if (occupied[matchStart] || occupied[matchEnd - 1]) continue

                val absStart = start + matchStart
                val absEnd = start + matchEnd
                editable.setSpan(
                    JsSyntaxSpan(color),
                    absStart,
                    absEnd,
                    Editable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                if (markOccupied) {
                    for (i in matchStart until matchEnd) {
                        occupied[i] = true
                    }
                }
            }
        }

        // Priority application order
        applyPattern(METADATA_PATTERN, colors.metadata)
        applyPattern(COMMENT_BLOCK_PATTERN, colors.comment)
        applyPattern(COMMENT_LINE_PATTERN, colors.comment)
        applyPattern(STRING_PATTERN, colors.string)
        applyPattern(GM_API_PATTERN, colors.gmApi)
        applyPattern(KEYWORD_PATTERN, colors.keyword)
        applyPattern(CONSTANT_PATTERN, colors.constant)
        applyPattern(NUMBER_PATTERN, colors.number)
    }
}
