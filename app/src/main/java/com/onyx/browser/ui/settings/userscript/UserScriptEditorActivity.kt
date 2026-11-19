package com.onyx.browser.ui.settings.userscript

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.OnyxAlertDialog
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

    private var codeText by mutableStateOf("")
    private var originalCode by mutableStateOf("")
    private var showDiscardDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        manager = UserScriptManager.getInstance(this)
        preferences = BrowserPreferences.getInstance(this)
        scriptId = intent.getLongExtra(EXTRA_SCRIPT_ID, -1L)

        loadScript()

        val isAmoled = preferences.themeMode == BrowserPreferences.THEME_DARK

        setContent {
            val isDark = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            OnyxTheme(darkTheme = isDark, isAmoled = isAmoled) {
                UserScriptEditorScreen(
                    title = if (scriptId > 0) "Edit Userscript" else "New Userscript",
                    code = codeText,
                    onCodeChange = { codeText = it },
                    onBackClick = {
                        if (codeText != originalCode) {
                            showDiscardDialog = true
                        } else {
                            finish()
                        }
                    },
                    onSaveClick = { saveScript() }
                )

                if (showDiscardDialog) {
                    OnyxAlertDialog(
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
            }
        }
    }

    private fun loadScript() {
        val pending = pendingCodeToOpen
        if (pending != null) {
            pendingCodeToOpen = null
            codeText = pending
            originalCode = pending
            return
        }
        val codeFromIntent = intent.getStringExtra(EXTRA_SCRIPT_CODE)
        if (!codeFromIntent.isNullOrBlank()) {
            codeText = codeFromIntent
            originalCode = codeFromIntent
            return
        }
        if (scriptId > 0) {
            lifecycleScope.launch(Dispatchers.IO) {
                val code = manager.readCodeFile(scriptId)
                withContext(Dispatchers.Main) {
                    if (code.isNotBlank()) {
                        codeText = code
                        originalCode = code
                    } else {
                        codeText = DEFAULT_TEMPLATE
                        originalCode = DEFAULT_TEMPLATE
                    }
                }
            }
        } else {
            codeText = DEFAULT_TEMPLATE
            originalCode = DEFAULT_TEMPLATE
        }
    }

    private fun saveScript() {
        if (codeText.isBlank()) {
            Toast.makeText(this, "Script cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val meta = UserScriptMetadata.parse(codeText)
                if (scriptId > 0) {
                    manager.updateScript(scriptId, codeText, sourceUrl)
                } else {
                    manager.addScript(codeText, sourceUrl)
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScriptEditorScreen(
    title: String,
    code: String,
    onCodeChange: (String) -> Unit,
    onBackClick: () -> Unit,
    onSaveClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
        }
    }
}
