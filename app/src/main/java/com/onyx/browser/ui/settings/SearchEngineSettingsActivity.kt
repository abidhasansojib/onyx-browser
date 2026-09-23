package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import com.onyx.browser.R
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivitySearchEngineSettingsBinding
import com.onyx.browser.databinding.ItemSearchEngineSettingBinding

class SearchEngineSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchEngineSettingsBinding
    private lateinit var preferences: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchEngineSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BrowserPreferences.getInstance(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnAddSearchEngine.setOnClickListener {
            showAddSearchEngineDialog()
        }

        populateAllEngines()
    }

    private fun populateAllEngines() {
        populateStandardEngines()
        populateCustomEngines()
    }

    private fun populateStandardEngines() {
        val container = binding.llStandardEngines
        container.removeAllViews()

        val activeId = preferences.searchEngine.id

        for (engine in SearchEngine.BUILT_IN) {
            val itemBinding = ItemSearchEngineSettingBinding.inflate(
                LayoutInflater.from(this),
                container,
                false
            )

            itemBinding.tvEngineName.text = engine.displayName
            itemBinding.tvEngineUrl.text = engine.homeUrl
            itemBinding.ivEngineIcon.setImageResource(engine.iconResId)
            itemBinding.ivEngineIcon.visibility = View.VISIBLE
            itemBinding.tvLetterAvatar.visibility = View.GONE

            if (engine.keyword.isNotBlank()) {
                itemBinding.tvKeywordBadge.text = engine.keyword
                itemBinding.tvKeywordBadge.visibility = View.VISIBLE
            } else {
                itemBinding.tvKeywordBadge.visibility = View.GONE
            }

            itemBinding.rbSelected.isChecked = (engine.id == activeId)
            itemBinding.llCustomActions.visibility = View.GONE

            itemBinding.root.setOnClickListener {
                preferences.searchEngine = engine
                populateAllEngines()
                com.onyx.browser.ui.widget.SearchWidgetProvider.updateAllWidgets(this)
                Toast.makeText(this, "Default search engine set to ${engine.displayName}", Toast.LENGTH_SHORT).show()
            }

            container.addView(itemBinding.root)
        }
    }

    private fun populateCustomEngines() {
        val container = binding.llCustomEngines
        container.removeAllViews()

        val customEngines = preferences.getCustomSearchEngines()
        val activeId = preferences.searchEngine.id

        if (customEngines.isEmpty()) {
            binding.llEmptyCustomEngines.visibility = View.VISIBLE
        } else {
            binding.llEmptyCustomEngines.visibility = View.GONE

            for (engine in customEngines) {
                val itemBinding = ItemSearchEngineSettingBinding.inflate(
                    LayoutInflater.from(this),
                    container,
                    false
                )

                itemBinding.tvEngineName.text = engine.displayName
                itemBinding.tvEngineUrl.text = engine.queryUrl

                val firstLetter = engine.displayName.trim().firstOrNull()?.uppercase() ?: "S"
                itemBinding.ivEngineIcon.visibility = View.GONE
                itemBinding.tvLetterAvatar.visibility = View.VISIBLE
                itemBinding.tvLetterAvatar.text = firstLetter

                if (engine.keyword.isNotBlank()) {
                    itemBinding.tvKeywordBadge.text = engine.keyword
                    itemBinding.tvKeywordBadge.visibility = View.VISIBLE
                } else {
                    itemBinding.tvKeywordBadge.visibility = View.GONE
                }

                itemBinding.rbSelected.isChecked = (engine.id == activeId)
                itemBinding.llCustomActions.visibility = View.VISIBLE

                itemBinding.root.setOnClickListener {
                    preferences.searchEngine = engine
                    populateAllEngines()
                    com.onyx.browser.ui.widget.SearchWidgetProvider.updateAllWidgets(this)
                    Toast.makeText(this, "Default search engine set to ${engine.displayName}", Toast.LENGTH_SHORT).show()
                }

                itemBinding.btnEdit.setOnClickListener {
                    showEditSearchEngineDialog(engine)
                }

                itemBinding.btnDelete.setOnClickListener {
                    confirmDeleteCustomEngine(engine)
                }

                container.addView(itemBinding.root)
            }
        }
    }

    private fun showAddSearchEngineDialog() {
        val dialog = AddEditSearchEngineDialog(existingEngine = null) { newEngine ->
            preferences.addCustomSearchEngine(newEngine)
            populateAllEngines()
            Toast.makeText(this, "Added ${newEngine.displayName}", Toast.LENGTH_SHORT).show()
        }
        dialog.show(supportFragmentManager, AddEditSearchEngineDialog.TAG)
    }

    private fun showEditSearchEngineDialog(engine: SearchEngine) {
        val dialog = AddEditSearchEngineDialog(existingEngine = engine) { updated ->
            preferences.updateCustomSearchEngine(updated)
            // If the updated engine is currently active, re-set it
            if (preferences.searchEngine.id == updated.id) {
                preferences.searchEngine = updated
            }
            populateAllEngines()
            Toast.makeText(this, "Updated ${updated.displayName}", Toast.LENGTH_SHORT).show()
        }
        dialog.show(supportFragmentManager, AddEditSearchEngineDialog.TAG)
    }

    private fun confirmDeleteCustomEngine(engine: SearchEngine) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete search engine?")
            .setMessage("Are you sure you want to delete '${engine.displayName}'?")
            .setPositiveButton("Delete") { _, _ ->
                preferences.deleteCustomSearchEngine(engine.id)
                populateAllEngines()
                Toast.makeText(this, "Deleted ${engine.displayName}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
