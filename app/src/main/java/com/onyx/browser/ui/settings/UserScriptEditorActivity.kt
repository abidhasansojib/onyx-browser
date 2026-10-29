package com.onyx.browser.ui.settings

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.R
import com.onyx.browser.databinding.ActivityUserscriptEditorBinding
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.userscript.UserScriptManager
import com.onyx.browser.userscript.UserScriptMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UserScriptEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserscriptEditorBinding
    private lateinit var manager: UserScriptManager
    private var scriptId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentColorManager.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val canvasBg = ContextCompat.getColor(this, R.color.settings_canvas_bg)
        @Suppress("DEPRECATION")
        window.statusBarColor = canvasBg
        @Suppress("DEPRECATION")
        window.navigationBarColor = canvasBg

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode

        binding = ActivityUserscriptEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.editorRoot) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.updatePadding(top = statusBarInsets.top)
            val bottomInset = if (imeInsets.bottom > 0) imeInsets.bottom else navBarInsets.bottom
            binding.etScriptCode.updatePadding(bottom = bottomInset + (16 * resources.displayMetrics.density).toInt())
            insets
        }

        manager = UserScriptManager.getInstance(this)
        scriptId = intent.getLongExtra(EXTRA_SCRIPT_ID, -1L)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.appBarLayout.apply {
            isLiftOnScroll = false
            setLiftable(false)
            setLifted(false)
            elevation = 0f
            stateListAnimator = null
            outlineProvider = null
            statusBarForeground = null
            setBackgroundColor(canvasBg)
            backgroundTintList = ColorStateList.valueOf(canvasBg)
        }

        binding.toolbar.apply {
            elevation = 0f
            outlineProvider = null
            setBackgroundColor(canvasBg)
            backgroundTintList = ColorStateList.valueOf(canvasBg)
        }

        setupEditor()
    }

    private fun setupEditor() {
        if (scriptId != -1L) {
            val script = manager.getById(scriptId)
            binding.tvEditorTitle.text = script?.metadata?.name ?: getString(R.string.userscripts_editor_title_edit)
            binding.btnDelete.visibility = View.VISIBLE
            binding.btnDelete.setOnClickListener {
                showDeleteConfirm()
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val code = manager.readCodeFile(scriptId)
                withContext(Dispatchers.Main) {
                    binding.etScriptCode.setText(code)
                }
            }
        } else {
            binding.tvEditorTitle.text = getString(R.string.userscripts_editor_title_new)
            binding.btnDelete.visibility = View.GONE
            binding.etScriptCode.setText(DEFAULT_STARTER_TEMPLATE)
        }

        binding.btnSave.setOnClickListener {
            saveScript()
        }
    }

    private fun saveScript() {
        val code = binding.etScriptCode.text?.toString()?.trim().orEmpty()
        if (code.isBlank()) {
            Toast.makeText(this, "Script cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        if (!code.contains("// ==UserScript==") || !code.contains("// ==/UserScript==")) {
            Toast.makeText(this, R.string.userscripts_invalid_meta, Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            if (scriptId != -1L) {
                manager.updateScript(scriptId, code)
            } else {
                manager.addScript(code)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@UserScriptEditorActivity, R.string.userscripts_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun showDeleteConfirm() {
        val script = manager.getById(scriptId)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.userscripts_delete_confirm_title)
            .setMessage(getString(R.string.userscripts_delete_confirm_msg, script?.metadata?.name ?: "script"))
            .setPositiveButton(R.string.userscripts_delete) { _, _ ->
                lifecycleScope.launch {
                    manager.deleteScript(scriptId)
                    Toast.makeText(this@UserScriptEditorActivity, R.string.userscripts_deleted, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_SCRIPT_ID = "extra_script_id"

        private val DEFAULT_STARTER_TEMPLATE = """
            // ==UserScript==
            // @name         New Script
            // @namespace    http://onyx.browser/
            // @version      1.0.0
            // @description  Custom userscript for Onyx Browser
            // @author       You
            // @match        *://*/*
            // @grant        none
            // @run-at       document-end
            // ==/UserScript==

            (function() {
                'use strict';

                // Your code here...
            })();
        """.trimIndent()
    }
}
