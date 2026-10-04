package com.onyx.browser.ui.bookmarks

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.R
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.databinding.ItemBookmarkBinding

class BookmarksAdapter(
    private val onItemClicked: (BookmarkItem) -> Unit,
    private val onItemEdit: (BookmarkItem) -> Unit,
    private val onItemDeleted: (BookmarkItem) -> Unit
) : ListAdapter<BookmarkItem, BookmarksAdapter.BookmarkViewHolder>(BookmarkDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val binding = ItemBookmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BookmarkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BookmarkViewHolder(private val binding: ItemBookmarkBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BookmarkItem) {
            val context = binding.root.context
            val isLocal = com.onyx.browser.web.LocalFileLoader.isLocalFile(context, item.url) ||
                          com.onyx.browser.web.LocalFileLoader.isPreviewUrl(item.url)
            binding.tvBookmarkTitle.text = item.title.ifBlank { if (isLocal) "Local Document" else item.url }
            binding.tvBookmarkUrl.text = if (isLocal) item.title.ifBlank { "Local Document" } else item.url

            binding.root.setOnClickListener { onItemClicked(item) }

            binding.btnMoreBookmarkItem.setOnClickListener { anchor ->
                val popup = PopupMenu(context, anchor)
                popup.menuInflater.inflate(R.menu.menu_bookmark_item, popup.menu)
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_edit_bookmark -> {
                            onItemEdit(item)
                            true
                        }
                        R.id.action_delete_bookmark -> {
                            onItemDeleted(item)
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        }
    }

    class BookmarkDiffCallback : DiffUtil.ItemCallback<BookmarkItem>() {
        override fun areItemsTheSame(oldItem: BookmarkItem, newItem: BookmarkItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: BookmarkItem, newItem: BookmarkItem): Boolean =
            oldItem == newItem
    }
}
