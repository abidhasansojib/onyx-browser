package com.onyx.browser.ui.settings.userscript

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.widget.AppCompatEditText
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    private var showDiscardDialog by mutableStateOf(false)
    private var showDeleteDialog by mutableStateOf(false)

    private var lineCount by mutableIntStateOf(1)
    private var charCount by mutableIntStateOf(0)

    private var editorEditText: AppCompatEditText? = null

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
                    lineCount = lineCount,
                    charCount = charCount,
                    onBackClick = {
                        if (isModified) {
                            showDiscardDialog = true
                        } else {
                            finish()
                        }
                    },
                    onSaveClick = { saveScript() },
                    onDeleteClick = { showDeleteDialog = true },
                    onStatsChanged = { lines, chars ->
                        lineCount = lines
                        charCount = chars
                    },
                    onModifiedChanged = { modified ->
                        isModified = modified
                    },
                    onBindEditText = { editText ->
                        editorEditText = editText
                    }
                )

                if (showDiscardDialog) {
                    AppAlertDialog(
                        title = "Discard unsaved changes?",
                        text = { Text("You have unsaved edits in this script.") },
                        confirmButtonText = "Discard",
                        isDestructive = true,
                        onConfirm = {
                            showDiscardDialog = false
                            finish()
                        },
                        onDismissRequest = { showDiscardDialog = false }
                    )
                }

                if (showDeleteDialog) {
                    AppAlertDialog(
                        title = "Delete userscript?",
                        text = { Text("Are you sure you want to permanently delete '$scriptTitle'?") },
                        confirmButtonText = "Delete",
                        isDestructive = true,
                        onConfirm = {
                            showDeleteDialog = false
                            deleteScript()
                        },
                        onDismissRequest = { showDeleteDialog = false }
                    )
                }
            }
        }
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
            countStats(pending)
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
            countStats(codeFromIntent)
            return
        }

        if (scriptId > 0) {
            val script = manager.getById(scriptId)
            scriptTitle = script?.metadata?.name ?: script?.script?.name ?: "Edit Userscript"
            lifecycleScope.launch(Dispatchers.IO) {
                val code = manager.readCodeFile(scriptId)
                val resolved = if (code.isNotBlank()) code else DEFAULT_TEMPLATE
                withContext(Dispatchers.Main) {
                    initialCode = resolved
                    countStats(resolved)
                }
            }
        } else {
            scriptTitle = "New Userscript"
            initialCode = DEFAULT_TEMPLATE
            countStats(DEFAULT_TEMPLATE)
        }
    }

    private fun countStats(text: String) {
        charCount = text.length
        var lines = 1
        for (i in 0 until text.length) {
            if (text[i] == '\n') lines++
        }
        lineCount = lines
    }

    private fun saveScript() {
        val code = editorEditText?.text?.toString() ?: initialCode.orEmpty()
        if (code.isBlank()) {
            Toast.makeText(this, "Script cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        if (!code.contains("// ==UserScript==") || !code.contains("// ==/UserScript==")) {
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
 * Lightweight VisualTransformation fallback for compatibility.
 */
class JsSyntaxVisualTransformation(
    private val keywordColor: Color,
    private val gmColor: Color = Color(0xFFAB47BC),
    private val stringColor: Color = Color(0xFF4CAF50),
    private val commentColor: Color = Color(0xFF888888),
    private val metaTagColor: Color = Color(0xFFFF9800)
) : VisualTransformation {
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
    lineCount: Int,
    charCount: Int,
    onBackClick: () -> Unit,
    onSaveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onStatsChanged: (lines: Int, chars: Int) -> Unit,
    onModifiedChanged: (Boolean) -> Unit,
    onBindEditText: (AppCompatEditText) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                    .imePadding()
            ) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )

                val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

                AndroidView(
                    factory = { ctx ->
                        AppCompatEditText(ctx).apply {
                            id = R.id.etScriptCode
                            onBindEditText(this)
                            background = null
                            gravity = Gravity.TOP or Gravity.START
                            hint = "// Write or paste your userscript here..."
                            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                            inputType = InputType.TYPE_CLASS_TEXT or
                                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            movementMethod = ScrollingMovementMethod.getInstance()
                            isVerticalScrollBarEnabled = true
                            overScrollMode = View.OVER_SCROLL_ALWAYS
                            setHorizontallyScrolling(false)
                            typeface = Typeface.MONOSPACE
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                            setLineSpacing(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics), 1f)
                            setPadding(
                                (16 * resources.displayMetrics.density).toInt(),
                                (12 * resources.displayMetrics.density).toInt(),
                                (16 * resources.displayMetrics.density).toInt(),
                                (16 * resources.displayMetrics.density).toInt()
                            )
                            setTextColor(textColor)
                            setHintTextColor(hintColor)
                            setText(code)
                            setSelection(0)
                            scrollTo(0, 0)

                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                    val len = s?.length ?: 0
                                    var lines = 1
                                    if (s != null) {
                                        for (i in 0 until s.length) {
                                            if (s[i] == '\n') lines++
                                        }
                                    }
                                    onStatsChanged(lines, len)
                                    val current = s?.toString().orEmpty()
                                    onModifiedChanged(current != code)
                                }
                                override fun afterTextChanged(s: Editable?) {}
                            })
                        }
                    },
                    update = { view ->
                        view.setTextColor(textColor)
                        view.setHintTextColor(hintColor)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )

                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Lines: $lineCount | Characters: $charCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
