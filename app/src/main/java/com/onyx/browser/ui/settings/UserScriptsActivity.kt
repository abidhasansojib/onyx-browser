package com.onyx.browser.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.UserScriptItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityUserscriptsBinding
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.userscript.UpdateResult
import com.onyx.browser.userscript.UserScriptManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class UserScriptsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserscriptsBinding
    private lateinit var adapter: UserScriptAdapter
    private lateinit var manager: UserScriptManager
    private val httpClient = OkHttpClient()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            importFromFileUri(uri)
        }
    }

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

        binding = ActivityUserscriptsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.userScriptsRoot) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.updatePadding(top = statusBarInsets.top)
            binding.rvUserScripts.updatePadding(bottom = navBarInsets.bottom + (16 * resources.displayMetrics.density).toInt())
            insets
        }

        manager = UserScriptManager.getInstance(this)

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

        setupRecyclerView()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        loadScripts()
    }

    private fun setupRecyclerView() {
        adapter = UserScriptAdapter(
            scripts = emptyList(),
            onToggle = { script, isChecked ->
                lifecycleScope.launch {
                    manager.toggleScript(script.id, isChecked)
                }
            },
            onClick = { script ->
                val intent = Intent(this, UserScriptEditorActivity::class.java).apply {
                    putExtra(UserScriptEditorActivity.EXTRA_SCRIPT_ID, script.id)
                }
                startActivity(intent)
            },
            onDelete = { script ->
                showDeleteConfirmDialog(script)
            }
        )

        binding.rvUserScripts.layoutManager = LinearLayoutManager(this)
        binding.rvUserScripts.adapter = adapter
    }

    private fun setupButtons() {
        // Plus button: shows add options bottom sheet (New Script, Import from URL, Import from File)
        binding.btnPlus.setOnClickListener {
            showAddOptions()
        }

        // Update button: checks and updates all installed scripts
        binding.btnUpdate.setOnClickListener {
            performUpdateAll()
        }

        binding.btnEmptyAddScript.setOnClickListener {
            showAddOptions()
        }

        binding.cardScriptsBlockedWarning.setOnClickListener {
            startActivity(Intent(this, ShieldsActivity::class.java))
        }
    }

    private fun showAddOptions() {
        val sheet = AddScriptOptionsBottomSheet(
            onNewScript = {
                startActivity(Intent(this, UserScriptEditorActivity::class.java))
            },
            onImportUrl = {
                showImportUrlSheet()
            },
            onImportFile = {
                filePickerLauncher.launch("*/*")
            }
        )
        sheet.show(supportFragmentManager, AddScriptOptionsBottomSheet.TAG)
    }

    private fun showImportUrlSheet() {
        val sheet = ImportScriptUrlBottomSheet { url ->
            downloadAndInstallFromUrl(url)
        }
        sheet.show(supportFragmentManager, ImportScriptUrlBottomSheet.TAG)
    }

    private fun downloadAndInstallFromUrl(url: String) {
        binding.progressUpdate.visibility = View.VISIBLE
        Toast.makeText(this, R.string.userscripts_installing, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val code = try {
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            } catch (e: Exception) {
                null
            }

            withContext(Dispatchers.Main) {
                binding.progressUpdate.visibility = View.GONE
                if (code.isNullOrBlank()) {
                    Toast.makeText(this@UserScriptsActivity, "Failed to download userscript from URL", Toast.LENGTH_LONG).show()
                    return@withContext
                }

                if (!code.contains("// ==UserScript==") || !code.contains("// ==/UserScript==")) {
                    Toast.makeText(this@UserScriptsActivity, R.string.userscripts_invalid_meta, Toast.LENGTH_LONG).show()
                    return@withContext
                }

                val id = manager.addScript(code, sourceUrl = url)
                val installed = manager.getById(id)
                Toast.makeText(
                    this@UserScriptsActivity,
                    getString(R.string.userscripts_installed_success, installed?.metadata?.name ?: "userscript"),
                    Toast.LENGTH_SHORT
                ).show()
                loadScripts()
            }
        }
    }

    private fun importFromFileUri(uri: Uri) {
        binding.progressUpdate.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val code = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                null
            }

            withContext(Dispatchers.Main) {
                binding.progressUpdate.visibility = View.GONE
                if (code.isNullOrBlank()) {
                    Toast.makeText(this@UserScriptsActivity, "Failed to read file", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                if (!code.contains("// ==UserScript==") || !code.contains("// ==/UserScript==")) {
                    Toast.makeText(this@UserScriptsActivity, R.string.userscripts_invalid_meta, Toast.LENGTH_LONG).show()
                    return@withContext
                }

                val id = manager.addScript(code)
                val installed = manager.getById(id)
                Toast.makeText(
                    this@UserScriptsActivity,
                    getString(R.string.userscripts_installed_success, installed?.metadata?.name ?: "userscript"),
                    Toast.LENGTH_SHORT
                ).show()
                loadScripts()
            }
        }
    }

    private fun performUpdateAll() {
        binding.progressUpdate.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val results = manager.checkAndUpdateAll()
            withContext(Dispatchers.Main) {
                binding.progressUpdate.visibility = View.GONE
                loadScripts()

                if (results.isEmpty()) {
                    Toast.makeText(this@UserScriptsActivity, R.string.userscripts_empty_title, Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val updatedCount = results.count { it.second is UpdateResult.Updated }
                val hasSources = results.any { it.second !is UpdateResult.NoSource }

                when {
                    updatedCount > 0 -> {
                        Toast.makeText(
                            this@UserScriptsActivity,
                            getString(R.string.userscripts_updated_count, updatedCount, results.size),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    hasSources -> {
                        Toast.makeText(this@UserScriptsActivity, R.string.userscripts_up_to_date, Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        Toast.makeText(this@UserScriptsActivity, R.string.userscripts_no_sources, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun loadScripts() {
        val isBlocked = BrowserPreferences.getInstance(this).isGlobalScriptBlockingEnabled
        binding.cardScriptsBlockedWarning.visibility = if (isBlocked) View.VISIBLE else View.GONE
        if (isBlocked) {
            binding.tvToolbarSubtitle.text = getString(R.string.userscripts_disabled_scripts_blocked)
        } else {
            binding.tvToolbarSubtitle.text = getString(R.string.userscripts_toolbar_subtitle)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val items = AppDatabase.getInstance(this@UserScriptsActivity).userScriptDao().getAll()
            withContext(Dispatchers.Main) {
                adapter.updateList(items, isBlocked)
                if (items.isEmpty()) {
                    binding.rvUserScripts.visibility = View.GONE
                    binding.layoutEmptyState.visibility = View.VISIBLE
                } else {
                    binding.rvUserScripts.visibility = View.VISIBLE
                    binding.layoutEmptyState.visibility = View.GONE
                }
            }
        }
    }

    private fun showDeleteConfirmDialog(script: UserScriptItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.userscripts_delete_confirm_title)
            .setMessage(getString(R.string.userscripts_delete_confirm_msg, script.name))
            .setPositiveButton(R.string.userscripts_delete) { _, _ ->
                lifecycleScope.launch {
                    manager.deleteScript(script.id)
                    Toast.makeText(this@UserScriptsActivity, R.string.userscripts_deleted, Toast.LENGTH_SHORT).show()
                    loadScripts()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
