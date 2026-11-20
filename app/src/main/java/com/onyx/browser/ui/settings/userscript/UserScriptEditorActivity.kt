package com.onyx.browser.ui.settings.userscript

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.widget.AppCompatEditText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.AppAlertDialog
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.userscript.UserScriptManager
import com.onyx.browser.userscript.UserScriptMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UserScriptEditorActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SCRIPT_ID = "com.onyx.browser.extra.SCRIPT_ID"
        const val EXTRA_SOURCE_URL = "com.onyx.browser.extra.SOURCE_URL"
        const val EXTRA_SCRIPT_CODE = "com.onyx.browser.extra.SCRIPT_CODE"
        @Volatile var pendingCodeToOpen: String? = null

        private const val DEFAULT_TEMPLATE = """// ==UserScript==
// @name         New Userscript
// @namespace    http://tampermonkey.net/
// @version      0.1
// @description  try to take over the world!
// @author       You
// @match        *://*/*
// @grant        none
// ==/UserScript==

(function() {
    'use strict';

    // Your code here...
})();
"""
    }

    private lateinit var manager: UserScriptManager
    private lateinit var preferences: BrowserPreferences
    private var scriptId: Long = -1L
    private var pendingSourceUrl: String? = null

    private var scriptTitle by mutableStateOf("New Userscript")
    private var initialCode by mutableStateOf<String?>(null)
    private var isModified by mutableStateOf(false)
    private var isWordWrap by mutableStateOf(false)

    private var showDiscardDialog by mutableStateOf(false)
    private var showDeleteDialog by mutableStateOf(false)
    private var showGoToLineDialog by mutableStateOf(false)
    private var showFindBar by mutableStateOf(false)

    private var lineCount by mutableIntStateOf(1)
    private var charCount by mutableIntStateOf(0)
    private var cursorLine by mutableIntStateOf(1)
    private var cursorCol by mutableIntStateOf(1)

    private var canUndo by mutableStateOf(false)
    private var canRedo by mutableStateOf(false)
    private var isEditorFocused by mutableStateOf(false)

    private var searchQuery by mutableStateOf("")
    private var replaceText by mutableStateOf("")
    private var searchMatchesList = listOf<IntRange>()
    private var currentMatchIndex by mutableIntStateOf(0)
    private var currentMatchCount by mutableIntStateOf(0)

    private var editorView: ScriptCodeEditorView? = null
    private var statsJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        manager = UserScriptManager.getInstance(this)
        preferences = BrowserPreferences.getInstance(this)
        scriptId = intent.getLongExtra(EXTRA_SCRIPT_ID, -1L)

        loadScript()

        setContent {
            val isDark = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            OnyxTheme(darkTheme = isDark, isAmoled = false) {
                BackHandler(enabled = isModified) {
                    showDiscardDialog = true
                }

                UserScriptEditorScreen(
                    title = scriptTitle,
                    code = initialCode,
                    isExistingScript = scriptId > 0,
                    isModified = isModified,
                    isWordWrap = isWordWrap,
                    lineCount = lineCount,
                    charCount = charCount,
                    cursorLine = cursorLine,
                    cursorCol = cursorCol,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    isEditorFocused = isEditorFocused,
                    showFindBar = showFindBar,
                    searchQuery = searchQuery,
                    replaceText = replaceText,
                    matchIndex = currentMatchIndex,
                    matchCount = currentMatchCount,
                    onBackClick = {
                        if (isModified) {
                            showDiscardDialog = true
                        } else {
                            finish()
                        }
                    },
                    onSaveClick = { saveScript() },
                    onDeleteClick = { showDeleteDialog = true },
                    onToggleFindBar = {
                        showFindBar = !showFindBar
                        if (!showFindBar) {
                            searchQuery = ""
                            searchMatchesList = emptyList()
                            currentMatchIndex = 0
                            currentMatchCount = 0
                        }
                    },
                    onGoToLineClick = { showGoToLineDialog = true },
                    onToggleWordWrap = {
                        isWordWrap = !isWordWrap
                        editorView?.setWordWrap(isWordWrap)
                    },
                    onBindEditor = { view ->
                        editorView = view
                        view.onCursorPositionChanged = { l, c ->
                            cursorLine = l
                            cursorCol = c
                        }
                        view.onEditorFocusChanged = { focused ->
                            isEditorFocused = focused
                        }
                        view.onHistoryStateChanged = { undo, redo ->
                            canUndo = undo
                            canRedo = redo
                        }
                        view.onContentModified = {
                            if (!isModified) {
                                isModified = true
                            }
                            scheduleStatsUpdate()
                        }
                    },
                    onInsertText = { text ->
                        editorView?.insertAtCursor(text)
                    },
                    onMoveCursor = { delta ->
                        editorView?.moveCursor(delta)
                    },
                    onUndo = {
                        editorView?.undo()
                    },
                    onRedo = {
                        editorView?.redo()
                    },
                    onSearchQueryChanged = { query ->
                        searchQuery = query
                        performSearch(query)
                    },
                    onReplacementChanged = { repl ->
                        replaceText = repl
                    },
                    onFindPrev = { findPrevMatch() },
                    onFindNext = { findNextMatch() },
                    onReplace = { replaceCurrentMatch() },
                    onReplaceAll = { replaceAllMatches() }
                )

                if (showDiscardDialog) {
                    AppAlertDialog(
                        title = "Discard unsaved changes?",
                        text = {
                            Text(
                                text = "You have unsaved edits in this script. Are you sure you want to discard them?",
                                textAlign = TextAlign.Center
                            )
                        },
                        confirmButtonText = "Discard",
                        dismissButtonText = stringResource(R.string.cancel),
                        isDestructive = true,
                        onConfirm = {
                            showDiscardDialog = false
                            finish()
                        },
                        onDismissRequest = { showDiscardDialog = false }
                    )
                }

                if (showDeleteDialog) {
                    DeleteUserScriptDialog(
                        scriptName = scriptTitle,
                        onConfirm = {
                            showDeleteDialog = false
                            deleteScript()
                        },
                        onDismissRequest = { showDeleteDialog = false }
                    )
                }

                if (showGoToLineDialog) {
                    var lineInput by remember { mutableStateOf("") }
                    AppAlertDialog(
                        title = "Go to Line",
                        text = {
                            Column {
                                Text(
                                    text = "Enter line number (1 - $lineCount):",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = lineInput,
                                    onValueChange = { lineInput = it.filter { ch -> ch.isDigit() } },
                                    singleLine = true,
                                    placeholder = { Text("Line #") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButtonText = "Jump",
                        onConfirm = {
                            val num = lineInput.toIntOrNull()
                            if (num != null && num > 0) {
                                editorView?.jumpToLine(num)
                            }
                            showGoToLineDialog = false
                        },
                        onDismissRequest = { showGoToLineDialog = false }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        statsJob?.cancel()
    }

    private fun loadScript() {
        val pending = pendingCodeToOpen
        if (pending != null) {
            pendingCodeToOpen = null
            pendingSourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL)
            scriptTitle = try {
                UserScriptMetadata.parse(pending).name.ifBlank { "New Userscript" }
            } catch (_: Exception) {
                "New Userscript"
            }
            initialCode = pending
            computeInitialStats(pending)
            return
        }

        val codeFromIntent = intent.getStringExtra(EXTRA_SCRIPT_CODE)
        if (!codeFromIntent.isNullOrBlank()) {
            pendingSourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL)
            scriptTitle = try {
                UserScriptMetadata.parse(codeFromIntent).name.ifBlank { "New Userscript" }
            } catch (_: Exception) {
                "New Userscript"
            }
            initialCode = codeFromIntent
            computeInitialStats(codeFromIntent)
            return
        }

        if (scriptId > 0) {
            val script = manager.getById(scriptId)
            scriptTitle = script?.metadata?.name ?: script?.script?.name ?: "Edit Userscript"
            lifecycleScope.launch(Dispatchers.IO) {
                val code = manager.readCodeFile(scriptId)
                val resolved = if (code.isNotBlank()) code else DEFAULT_TEMPLATE
                computeInitialStats(resolved)
                withContext(Dispatchers.Main) {
                    initialCode = resolved
                }
            }
        } else {
            scriptTitle = "New Userscript"
            initialCode = DEFAULT_TEMPLATE
            computeInitialStats(DEFAULT_TEMPLATE)
        }
    }

    private fun computeInitialStats(text: String) {
        charCount = text.length
        var lines = 1
        for (i in 0 until text.length) {
            if (text[i] == '\n') lines++
        }
        lineCount = lines
    }

    private fun scheduleStatsUpdate() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch(Dispatchers.Default) {
            delay(400)
            val ed = editorView ?: return@launch
            val raw = ed.text ?: return@launch
            val len = raw.length
            var lines = 1
            for (i in 0 until len) {
                if (raw[i] == '\n') lines++
            }
            withContext(Dispatchers.Main) {
                lineCount = lines
                charCount = len
            }
        }
    }

    private fun performSearch(query: String) {
        val ed = editorView ?: return
        val matches = ed.findMatches(query)
        searchMatchesList = matches
        currentMatchCount = matches.size
        if (matches.isNotEmpty()) {
            currentMatchIndex = 1
            ed.highlightMatch(matches[0])
        } else {
            currentMatchIndex = 0
        }
    }

    private fun findPrevMatch() {
        if (searchMatchesList.isEmpty()) return
        val nextIdx = if (currentMatchIndex <= 1) searchMatchesList.size else currentMatchIndex - 1
        currentMatchIndex = nextIdx
        editorView?.highlightMatch(searchMatchesList[nextIdx - 1])
    }

    private fun findNextMatch() {
        if (searchMatchesList.isEmpty()) return
        val nextIdx = if (currentMatchIndex >= searchMatchesList.size) 1 else currentMatchIndex + 1
        currentMatchIndex = nextIdx
        editorView?.highlightMatch(searchMatchesList[nextIdx - 1])
    }

    private fun replaceCurrentMatch() {
        if (searchMatchesList.isEmpty() || currentMatchIndex <= 0) return
        val range = searchMatchesList[currentMatchIndex - 1]
        editorView?.replaceMatch(range, replaceText)
        performSearch(searchQuery)
    }

    private fun replaceAllMatches() {
        if (searchQuery.isEmpty()) return
        editorView?.replaceAllMatches(searchQuery, replaceText)
        performSearch(searchQuery)
    }

    private fun saveScript() {
        val code = editorView?.text?.toString() ?: initialCode.orEmpty()
        if (code.isBlank()) {
            Toast.makeText(this, "Script cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        val hasHeader = Regex("""//\s*==UserScript==\s*""", RegexOption.IGNORE_CASE).containsMatchIn(code)
        val hasFooter = Regex("""//\s*==/UserScript==\s*""", RegexOption.IGNORE_CASE).containsMatchIn(code)
        if (!hasHeader || !hasFooter) {
            Toast.makeText(this, "Script must contain // ==UserScript== and // ==/UserScript== metadata block", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val meta = UserScriptMetadata.parse(code)
                val sourceUrl = pendingSourceUrl ?: intent.getStringExtra(EXTRA_SOURCE_URL)
                if (scriptId > 0) {
                    manager.updateScript(scriptId, code, sourceUrl)
                } else {
                    manager.addScript(code, sourceUrl)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserScriptEditorActivity, "Saved '${meta.name}'", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserScriptEditorActivity, "Error saving script: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteScript() {
        if (scriptId <= 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                manager.deleteScript(scriptId)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserScriptEditorActivity, "Script deleted", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserScriptEditorActivity, "Error deleting script: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/**
 * High-performance lightweight edit history tracker for Undo and Redo operations.
 */
private class EditHistoryTracker {
    data class Edit(val start: Int, val before: CharSequence?, val after: CharSequence?)

    private val entries = ArrayDeque<Edit>()
    private var position = 0
    private val maxSize = 250

    val canUndo: Boolean get() = position > 0
    val canRedo: Boolean get() = position < entries.size

    fun clear() {
        entries.clear()
        position = 0
    }

    fun record(start: Int, before: CharSequence?, after: CharSequence?) {
        while (entries.size > position) entries.removeLast()
        entries.addLast(Edit(start, before, after))
        position++
        while (entries.size > maxSize) {
            entries.removeFirst()
            position--
        }
    }

    fun undo(): Edit? {
        if (position == 0) return null
        position--
        return entries[position]
    }

    fun redo(): Edit? {
        if (position >= entries.size) return null
        val edit = entries[position]
        position++
        return edit
    }
}

/**
 * Native AppCompatEditText customized for high-speed script code editing on large files.
 * Disables spell checking, auto-fill, and heavy reflow calculations for lag-free performance.
 */
class ScriptCodeEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private val history = EditHistoryTracker()
    private var isApplyingHistory = false
    private var beforeChangeText: CharSequence? = null

    var onCursorPositionChanged: ((line: Int, column: Int) -> Unit)? = null
    var onEditorFocusChanged: ((Boolean) -> Unit)? = null
    var onHistoryStateChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null
    var onContentModified: (() -> Unit)? = null

    init {
        id = R.id.etScriptCode
        background = null
        gravity = Gravity.TOP or Gravity.START
        hint = "// Write or paste your userscript here..."
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setLineSpacing(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.5f, resources.displayMetrics), 1f)
        setPadding(
            (16 * resources.displayMetrics.density).toInt(),
            (12 * resources.displayMetrics.density).toInt(),
            (16 * resources.displayMetrics.density).toInt(),
            (16 * resources.displayMetrics.density).toInt()
        )
        movementMethod = ScrollingMovementMethod.getInstance()
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_ALWAYS
        setHorizontallyScrolling(true)

        inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI

        setOnFocusChangeListener { _, hasFocus ->
            onEditorFocusChanged?.invoke(hasFocus)
        }

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (isApplyingHistory || s == null) return
                if (count < 8000) {
                    beforeChangeText = s.subSequence(start, start + count)
                } else {
                    beforeChangeText = null
                }
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onContentModified?.invoke()
                if (isApplyingHistory || s == null) return
                if (count < 8000 && beforeChangeText != null) {
                    val afterText = s.subSequence(start, start + count)
                    history.record(start, beforeChangeText, afterText)
                    onHistoryStateChanged?.invoke(history.canUndo, history.canRedo)
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    fun setWordWrap(wrap: Boolean) {
        setHorizontallyScrolling(!wrap)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        val l = layout ?: return
        val len = text?.length ?: return
        if (selStart < 0 || selStart > len) return
        val line = l.getLineForOffset(selStart)
        val col = selStart - l.getLineStart(line)
        onCursorPositionChanged?.invoke(line + 1, col + 1)
    }

    fun insertAtCursor(insert: String) {
        val len = text?.length ?: return
        val start = selectionStart.coerceIn(0, len)
        val end = selectionEnd.coerceIn(0, len)
        text?.replace(minOf(start, end), maxOf(start, end), insert)
    }

    fun moveCursor(delta: Int) {
        val len = text?.length ?: return
        setSelection((selectionStart + delta).coerceIn(0, len))
    }

    fun undo() {
        val edit = history.undo() ?: return
        val len = text?.length ?: return
        val end = (edit.start + (edit.after?.length ?: 0)).coerceIn(0, len)
        val start = edit.start.coerceIn(0, len)
        isApplyingHistory = true
        text?.replace(start, end, edit.before ?: "")
        isApplyingHistory = false
        val newLen = text?.length ?: 0
        setSelection((start + (edit.before?.length ?: 0)).coerceIn(0, newLen))
        onHistoryStateChanged?.invoke(history.canUndo, history.canRedo)
    }

    fun redo() {
        val edit = history.redo() ?: return
        val len = text?.length ?: return
        val end = (edit.start + (edit.before?.length ?: 0)).coerceIn(0, len)
        val start = edit.start.coerceIn(0, len)
        isApplyingHistory = true
        text?.replace(start, end, edit.after ?: "")
        isApplyingHistory = false
        val newLen = text?.length ?: 0
        setSelection((start + (edit.after?.length ?: 0)).coerceIn(0, newLen))
        onHistoryStateChanged?.invoke(history.canUndo, history.canRedo)
    }

    fun jumpToLine(lineNumber: Int) {
        val l = layout ?: return
        val targetLine = (lineNumber - 1).coerceIn(0, l.lineCount - 1)
        val offset = l.getLineStart(targetLine)
        setSelection(offset)
        val y = l.getLineTop(targetLine)
        scrollTo(scrollX, (y - height / 3).coerceAtLeast(0))
    }

    fun findMatches(query: String): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val raw = text?.toString() ?: return emptyList()
        val list = mutableListOf<IntRange>()
        var idx = 0
        while (idx < raw.length) {
            val found = raw.indexOf(query, idx, ignoreCase = true)
            if (found == -1) break
            list.add(found until (found + query.length))
            idx = found + maxOf(1, query.length)
        }
        return list
    }

    fun highlightMatch(range: IntRange) {
        val len = text?.length ?: return
        val start = range.first.coerceIn(0, len)
        val end = (range.last + 1).coerceIn(0, len)
        setSelection(start, end)
        val l = layout ?: return
        val line = l.getLineForOffset(start)
        val y = l.getLineTop(line)
        scrollTo(scrollX, (y - height / 3).coerceAtLeast(0))
    }

    fun replaceMatch(range: IntRange, replacement: String) {
        val len = text?.length ?: return
        val start = range.first.coerceIn(0, len)
        val end = (range.last + 1).coerceIn(0, len)
        text?.replace(start, end, replacement)
    }

    fun replaceAllMatches(query: String, replacement: String) {
        val raw = text?.toString() ?: return
        val replaced = raw.replace(query, replacement, ignoreCase = true)
        setText(replaced)
    }
}

/**
 * VisualTransformation compatibility stub.
 */
class JsSyntaxVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(text, OffsetMapping.Identity)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScriptEditorScreen(
    title: String,
    code: String?,
    isExistingScript: Boolean,
    isModified: Boolean,
    isWordWrap: Boolean,
    lineCount: Int,
    charCount: Int,
    cursorLine: Int,
    cursorCol: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    isEditorFocused: Boolean,
    showFindBar: Boolean,
    searchQuery: String,
    replaceText: String,
    matchIndex: Int,
    matchCount: Int,
    onBackClick: () -> Unit,
    onSaveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onToggleFindBar: () -> Unit,
    onGoToLineClick: () -> Unit,
    onToggleWordWrap: () -> Unit,
    onBindEditor: (ScriptCodeEditorView) -> Unit,
    onInsertText: (String) -> Unit,
    onMoveCursor: (Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onReplacementChanged: (String) -> Unit,
    onFindPrev: () -> Unit,
    onFindNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit
) {
    var overflowExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isModified) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onToggleFindBar) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Find & Replace",
                            tint = if (showFindBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                    if (isExistingScript) {
                        IconButton(onClick = onDeleteClick) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Script",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    IconButton(onClick = onSaveClick) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "Save Script",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Box {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Go to Line...") },
                                onClick = {
                                    overflowExpanded = false
                                    onGoToLineClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isWordWrap) "Disable Word Wrap" else "Enable Word Wrap") },
                                onClick = {
                                    overflowExpanded = false
                                    onToggleWordWrap()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (code == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Find & Replace Bar
                AnimatedVisibility(visible = showFindBar, enter = fadeIn(), exit = fadeOut()) {
                    Column {
                        FindReplaceBar(
                            query = searchQuery,
                            onQueryChange = onSearchQueryChanged,
                            replacement = replaceText,
                            onReplacementChange = onReplacementChanged,
                            onFindPrev = onFindPrev,
                            onFindNext = onFindNext,
                            onReplace = onReplace,
                            onReplaceAll = onReplaceAll,
                            matchIndex = matchIndex,
                            matchCount = matchCount,
                            onClose = onToggleFindBar
                        )
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        )
                    }
                }

                val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    AndroidView(
                        factory = { ctx ->
                            ScriptCodeEditorView(ctx).apply {
                                onBindEditor(this)
                                setTextColor(textColor)
                                setHintTextColor(hintColor)
                                setWordWrap(isWordWrap)
                                setText(code)
                                setSelection(0)
                                scrollTo(0, 0)
                            }
                        },
                        update = { view ->
                            if (view.currentTextColor != textColor) {
                                view.setTextColor(textColor)
                            }
                            if (view.currentHintTextColor != hintColor) {
                                view.setHintTextColor(hintColor)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Bottom Status Bar & Keyboard Accessory Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )

                    EditorStatusBar(
                        cursorLine = cursorLine,
                        cursorCol = cursorCol,
                        totalLines = lineCount,
                        totalChars = charCount,
                        isWordWrap = isWordWrap,
                        onToggleWordWrap = onToggleWordWrap
                    )

                    AnimatedVisibility(visible = isEditorFocused, enter = fadeIn(), exit = fadeOut()) {
                        EditorAccessoryBar(
                            canUndo = canUndo,
                            canRedo = canRedo,
                            onInsert = onInsertText,
                            onMoveCursor = onMoveCursor,
                            onUndo = onUndo,
                            onRedo = onRedo
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FindReplaceBar(
    query: String,
    onQueryChange: (String) -> Unit,
    replacement: String,
    onReplacementChange: (String) -> Unit,
    onFindPrev: () -> Unit,
    onFindNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    matchIndex: Int,
    matchCount: Int,
    onClose: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Find...", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                )

                Text(
                    text = if (query.isEmpty()) ""
                    else if (matchCount == 0) "0/0"
                    else "$matchIndex/$matchCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                IconButton(
                    onClick = onFindPrev,
                    enabled = matchCount > 0,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Previous match",
                        tint = if (matchCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onFindNext,
                    enabled = matchCount > 0,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Next match",
                        tint = if (matchCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(36.dp))

                OutlinedTextField(
                    value = replacement,
                    onValueChange = onReplacementChange,
                    placeholder = { Text("Replace with...", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                )

                TextButton(
                    onClick = onReplace,
                    enabled = matchCount > 0,
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Replace", fontSize = 12.sp)
                }

                TextButton(
                    onClick = onReplaceAll,
                    enabled = matchCount > 0 && query.isNotBlank(),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("All", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun EditorStatusBar(
    cursorLine: Int,
    cursorCol: Int,
    totalLines: Int,
    totalChars: Int,
    isWordWrap: Boolean,
    onToggleWordWrap: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "JavaScript",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Lines: $totalLines | Chars: $totalChars",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isWordWrap) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable(onClick = onToggleWordWrap)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (isWordWrap) "Wrap: ON" else "Wrap: OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isWordWrap) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Ln $cursorLine, Col $cursorCol",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun EditorAccessoryBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onInsert: (String) -> Unit,
    onMoveCursor: (Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyCapButton("◀", enabled = true) { onMoveCursor(-1) }
            KeyCapButton("▶", enabled = true) { onMoveCursor(1) }
            AccessoryDivider()
            KeyCapButton("Tab") { onInsert("  ") }
            KeyCapButton("{") { onInsert("{") }
            KeyCapButton("}") { onInsert("}") }
            KeyCapButton("(") { onInsert("(") }
            KeyCapButton(")") { onInsert(")") }
            KeyCapButton("[") { onInsert("[") }
            KeyCapButton("]") { onInsert("]") }
            KeyCapButton(";") { onInsert(";") }
            KeyCapButton("\"") { onInsert("\"") }
            KeyCapButton("'") { onInsert("'") }
            KeyCapButton("=") { onInsert(" = ") }
            KeyCapButton("=>") { onInsert(" => ") }
            KeyCapButton("$") { onInsert("$") }
            AccessoryDivider()
            KeyCapButton("↶", enabled = canUndo) { onUndo() }
            KeyCapButton("↷", enabled = canRedo) { onRedo() }
        }
    }
}

@Composable
private fun KeyCapButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(end = 5.dp)
            .size(width = 38.dp, height = 34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AccessoryDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .width(1.dp)
            .height(20.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}
