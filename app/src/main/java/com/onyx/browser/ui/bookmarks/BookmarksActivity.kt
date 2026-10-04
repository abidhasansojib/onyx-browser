package com.onyx.browser.ui.bookmarks

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.databinding.ActivityBookmarksBinding
import com.onyx.browser.databinding.DialogEditBookmarkBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookmarksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookmarksBinding
    private lateinit var adapter: BookmarksAdapter
    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookmarksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(this)

        binding.toolbarBookmarks.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        observeBookmarks()
    }

    private fun setupRecyclerView() {
        adapter = BookmarksAdapter(
            onItemClicked = { item ->
                val resultIntent = Intent().apply { putExtra(EXTRA_URL, item.url) }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            },
            onItemEdit = { item ->
                showEditDialog(item)
            },
            onItemDeleted = { item ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.bookmarkDao().deleteBookmark(item)
                }
            }
        )

        binding.rvBookmarks.layoutManager = LinearLayoutManager(this)
        binding.rvBookmarks.adapter = adapter
    }

    private fun showEditDialog(item: BookmarkItem) {
        val dialog = BottomSheetDialog(this, R.style.Theme_OnyxBrowser_BottomSheetDialog)
        val dialogBinding = DialogEditBookmarkBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        // Pre-fill existing values
        dialogBinding.etBookmarkName.setText(item.title)
        dialogBinding.etBookmarkUrl.setText(item.url)

        // Move cursor to end
        dialogBinding.etBookmarkName.setSelection(dialogBinding.etBookmarkName.text?.length ?: 0)

        dialogBinding.btnCancelEdit.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSaveBookmark.setOnClickListener {
            val newTitle = dialogBinding.etBookmarkName.text?.toString()?.trim() ?: ""
            val newUrl = dialogBinding.etBookmarkUrl.text?.toString()?.trim() ?: ""

            if (newUrl.isBlank()) {
                dialogBinding.tilBookmarkUrl.error = getString(R.string.bookmark_url)
                return@setOnClickListener
            }
            dialogBinding.tilBookmarkUrl.error = null
            dialogBinding.tilBookmarkName.error = null

            // Dismiss keyboard
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(dialogBinding.etBookmarkUrl.windowToken, 0)

            lifecycleScope.launch(Dispatchers.IO) {
                database.bookmarkDao().updateBookmark(
                    id = item.id,
                    title = newTitle.ifBlank { newUrl },
                    url = newUrl
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BookmarksActivity, R.string.bookmark_saved, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun observeBookmarks() {
        lifecycleScope.launch {
            database.bookmarkDao().getAllBookmarksFlow().collectLatest { list ->
                adapter.submitList(list)
                binding.emptyBookmarksView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.rvBookmarks.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
    }
}
