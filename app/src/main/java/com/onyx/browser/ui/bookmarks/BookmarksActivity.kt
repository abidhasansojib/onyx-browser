package com.onyx.browser.ui.bookmarks

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.databinding.ActivityBookmarksBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
            onItemDeleted = { item ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.bookmarkDao().deleteBookmark(item)
                }
            }
        )

        binding.rvBookmarks.layoutManager = LinearLayoutManager(this)
        binding.rvBookmarks.adapter = adapter
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
