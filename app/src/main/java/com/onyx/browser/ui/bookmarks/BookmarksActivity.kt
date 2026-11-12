package com.onyx.browser.ui.bookmarks

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.onyx.browser.R
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.databinding.ActivityBookmarksBinding
import com.onyx.browser.databinding.DialogEditBookmarkBinding
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.ui.viewmodel.BookmarksViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BookmarksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookmarksBinding
    private lateinit var adapter: BookmarksAdapter
    private lateinit var viewModel: BookmarksViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentColorManager.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)
        binding = ActivityBookmarksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[BookmarksViewModel::class.java]

        binding.toolbarBookmarks.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        setupSearch()
        setupAddBookmarkFab()
        observeBookmarks()
    }

    private fun setupRecyclerView() {
        adapter = BookmarksAdapter(
            onItemClicked = { item ->
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_URL, item.url)
                    putExtra(EXTRA_TITLE, item.title)
                    putExtra(EXTRA_IS_BOOKMARKLET, item.isBookmarklet)
                    putExtra(EXTRA_ACTION, if (item.isBookmarklet) ACTION_RUN_BOOKMARKLET else ACTION_OPEN)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            },
            onItemOpenInNewTab = { item ->
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_URL, item.url)
                    putExtra(EXTRA_TITLE, item.title)
                    putExtra(EXTRA_IS_BOOKMARKLET, false)
                    putExtra(EXTRA_ACTION, ACTION_OPEN_NEW_TAB)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            },
            onItemOpenInIncognito = { item ->
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_URL, item.url)
                    putExtra(EXTRA_TITLE, item.title)
                    putExtra(EXTRA_IS_BOOKMARKLET, false)
                    putExtra(EXTRA_ACTION, ACTION_OPEN_INCOGNITO)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            },
            onItemCopy = { item ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val label = if (item.isBookmarklet) "Bookmarklet Code" else "Bookmark URL"
                val clip = ClipData.newPlainText(label, item.url)
                clipboard.setPrimaryClip(clip)
                val msg = if (item.isBookmarklet) getString(R.string.script_copied) else getString(R.string.link_copied)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            },
            onItemShare = { item ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, item.title)
                    putExtra(Intent.EXTRA_TEXT, item.url)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
            },
            onItemEdit = { item ->
                showEditBookmarkDialog(item)
            },
            onItemDeleted = { item ->
                viewModel.deleteBookmark(item)
            }
        )

        binding.rvBookmarks.layoutManager = LinearLayoutManager(this)
        binding.rvBookmarks.adapter = adapter
    }

    private fun setupSearch() {
        val existingQuery = viewModel.searchQuery.value
        if (existingQuery.isNotEmpty() && binding.etSearchBookmarks.text?.toString() != existingQuery) {
            binding.etSearchBookmarks.setText(existingQuery)
            binding.etSearchBookmarks.setSelection(existingQuery.length)
        }
        binding.btnClearBookmarksSearch.visibility = if (existingQuery.isNotEmpty()) View.VISIBLE else View.GONE

        binding.etSearchBookmarks.doAfterTextChanged { text ->
            val query = text?.toString()?.trim() ?: ""
            binding.btnClearBookmarksSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            viewModel.setSearchQuery(query)
        }

        binding.btnClearBookmarksSearch.setOnClickListener {
            binding.etSearchBookmarks.setText("")
        }
    }

    private fun setupAddBookmarkFab() {
        binding.fabAddBookmark.setOnClickListener {
            showAddBookmarkDialog()
        }
    }

    private fun showAddBookmarkDialog() {
        val dialog = BottomSheetDialog(this, R.style.Theme_OnyxBrowser_BottomSheetDialog)
        val dialogBinding = DialogEditBookmarkBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.tvBookmarkDialogTitle.text = getString(R.string.add_bookmark_title)

        // Try reading clipboard if it has a URL or javascript:
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clipItem = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (!clipItem.isNullOrBlank() && (clipItem.startsWith("http://", ignoreCase = true) ||
            clipItem.startsWith("https://", ignoreCase = true) ||
            clipItem.startsWith("javascript:", ignoreCase = true))) {
            dialogBinding.etBookmarkUrl.setText(clipItem)
        }

        dialogBinding.btnCancelEdit.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSaveBookmark.setOnClickListener {
            val title = dialogBinding.etBookmarkName.text?.toString()?.trim() ?: ""
            val url = dialogBinding.etBookmarkUrl.text?.toString()?.trim() ?: ""

            if (url.isBlank()) {
                dialogBinding.tilBookmarkUrl.error = getString(R.string.bookmark_url)
                return@setOnClickListener
            }
            dialogBinding.tilBookmarkUrl.error = null
            dialogBinding.tilBookmarkName.error = null

            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(dialogBinding.etBookmarkUrl.windowToken, 0)

            viewModel.addBookmark(title, url) {
                Toast.makeText(this@BookmarksActivity, R.string.bookmark_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showEditBookmarkDialog(item: BookmarkItem) {
        val dialog = BottomSheetDialog(this, R.style.Theme_OnyxBrowser_BottomSheetDialog)
        val dialogBinding = DialogEditBookmarkBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.tvBookmarkDialogTitle.text = getString(R.string.edit_bookmark)
        dialogBinding.etBookmarkName.setText(item.title)
        dialogBinding.etBookmarkUrl.setText(item.url)
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

            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(dialogBinding.etBookmarkUrl.windowToken, 0)

            viewModel.updateBookmark(item.id, newTitle, newUrl) {
                Toast.makeText(this@BookmarksActivity, R.string.bookmark_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun observeBookmarks() {
        lifecycleScope.launch {
            viewModel.bookmarks.collectLatest { list ->
                adapter.submitList(list)
                binding.emptyBookmarksView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.rvBookmarks.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ACTION = "extra_action"
        const val EXTRA_IS_BOOKMARKLET = "extra_is_bookmarklet"

        const val ACTION_OPEN = "action_open"
        const val ACTION_OPEN_NEW_TAB = "action_open_new_tab"
        const val ACTION_OPEN_INCOGNITO = "action_open_incognito"
        const val ACTION_RUN_BOOKMARKLET = "action_run_bookmarklet"
    }
}
