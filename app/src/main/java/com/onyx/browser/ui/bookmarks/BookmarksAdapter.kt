package com.onyx.browser.ui.bookmarks

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.databinding.ItemBookmarkBinding

class BookmarksAdapter(
    private val onItemClicked: (BookmarkItem) -> Unit,
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
            binding.tvBookmarkTitle.text = item.title.ifBlank { item.url }
            binding.tvBookmarkUrl.text = item.url

            binding.root.setOnClickListener { onItemClicked(item) }
            binding.btnDeleteBookmarkItem.setOnClickListener { onItemDeleted(item) }
        }
    }

    class BookmarkDiffCallback : DiffUtil.ItemCallback<BookmarkItem>() {
        override fun areItemsTheSame(oldItem: BookmarkItem, newItem: BookmarkItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: BookmarkItem, newItem: BookmarkItem): Boolean =
            oldItem == newItem
    }
}
