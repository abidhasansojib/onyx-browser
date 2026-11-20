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
        """//\s*==/?UserScript==|//\s*@(?:name|namespace|version|description|author|match|include|exclude|exclude-match|require|resource|connect|grant|run-at|noframes|unwrap|updateURL|downloadURL|icon)\b.*""",
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
        """\b(?:GM|GM_getValue|GM_setValue|GM_deleteValue|GM_listValues|GM_xmlhttpRequest|GM_xmlHttpRequest|GM_download|GM_openInTab|GM_setClipboard|GM_notification|GM_addStyle|GM_addElement|GM_registerMenuCommand|GM_unregisterMenuCommand|GM_getResourceText|GM_getResourceURL|GM_log|GM_info|unsafeWindow)\b"""
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
        metadata = Color.parseColor("#4EC9B0"),  // Cyan / Teal
        comment = Color.parseColor("#6A9955"),   // Muted Green
        string = Color.parseColor("#CE9178"),    // Coral / Amber
        number = Color.parseColor("#B5CEA8"),    // Light Green
        keyword = Color.parseColor("#569CD6"),   // Keyword Blue
        constant = Color.parseColor("#56B6C2"),  // Aqua / Constant
        gmApi = Color.parseColor("#C586C0")      // Purple / Violet GM APIs
    )

    val lightColors = ThemeColors(
        metadata = Color.parseColor("#008080"),  // Deep Teal
        comment = Color.parseColor("#008000"),   // Forest Green
        string = Color.parseColor("#A31515"),    // Crimson / Rust
        number = Color.parseColor("#098658"),    // Dark Emerald
        keyword = Color.parseColor("#0000FF"),   // Classic Blue
        constant = Color.parseColor("#0184BC"),  // Cyan
        gmApi = Color.parseColor("#AF00DB")      // Purple
    )

    fun getThemeColors(isDark: Boolean): ThemeColors = if (isDark) darkColors else lightColors

    /**
     * Highlights only the specified range within the editable text.
     * Guaranteed to be O(range length) rather than O(total document size).
     */
    private const val MAX_HIGHLIGHT_CHARS = 120_000

    fun highlightRange(
        editable: Editable,
        rangeStart: Int,
        rangeEnd: Int,
        colors: ThemeColors
    ) {
        if (rangeEnd - rangeStart > MAX_HIGHLIGHT_CHARS) return
        val len = editable.length
        if (len == 0) return
        val start = rangeStart.coerceIn(0, len)
        val end = rangeEnd.coerceIn(start, len)
        if (start >= end) return

        // 1. Clear existing syntax spans in this range
        val oldSpans = editable.getSpans(start, end, JsSyntaxSpan::class.java)
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
