package com.onyx.browser.ui.bookmarks

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.R
import com.onyx.browser.data.favicon.FaviconManager
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.databinding.ItemBookmarkBinding
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.web.LocalFileLoader

class BookmarksAdapter(
    private val onItemClicked: (BookmarkItem) -> Unit,
    private val onItemOpenInNewTab: (BookmarkItem) -> Unit,
    private val onItemOpenInIncognito: (BookmarkItem) -> Unit,
    private val onItemCopy: (BookmarkItem) -> Unit,
    private val onItemShare: (BookmarkItem) -> Unit,
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
            val accentColor = AccentColorManager.getAccentColor(context)
            val isLocal = LocalFileLoader.isLocalFile(context, item.url) ||
                          LocalFileLoader.isPreviewUrl(item.url)

            when {
                item.isBookmarklet -> {
                    binding.tvBookmarkTitle.text = item.title.ifBlank { "Bookmarklet" }
                    binding.tvBookmarkUrl.text = context.getString(R.string.bookmarklet_badge)
                    binding.ivBookmarkIcon.setImageResource(R.drawable.ic_code)
                    binding.ivBookmarkIcon.imageTintList = ColorStateList.valueOf(accentColor)
                }
                isLocal -> {
                    binding.tvBookmarkTitle.text = item.title.ifBlank { "Local Document" }
                    binding.tvBookmarkUrl.text = item.title.ifBlank { "Local Document" }
                    binding.ivBookmarkIcon.setImageResource(R.drawable.ic_file)
                    binding.ivBookmarkIcon.imageTintList = ColorStateList.valueOf(accentColor)
                }
                else -> {
                    binding.tvBookmarkTitle.text = item.title.ifBlank { item.url }
                    binding.tvBookmarkUrl.text = item.url
                    binding.ivBookmarkIcon.setImageResource(R.drawable.ic_bookmark)
                    binding.ivBookmarkIcon.imageTintList = ColorStateList.valueOf(accentColor)
                    if (item.url.isNotBlank()) {
                        FaviconManager.loadFavicon(
                            context = context,
                            imageView = binding.ivBookmarkIcon,
                            urlOrHost = item.url,
                            isCircular = true
                        )
                    }
                }
            }

            binding.root.setOnClickListener { onItemClicked(item) }

            binding.btnMoreBookmarkItem.setOnClickListener { anchor ->
                val popup = PopupMenu(context, anchor)
                popup.menuInflater.inflate(R.menu.menu_bookmark_item, popup.menu)

                val openNewTabItem = popup.menu.findItem(R.id.action_open_in_new_tab)
                val openIncognitoItem = popup.menu.findItem(R.id.action_open_in_incognito)
                val shareItem = popup.menu.findItem(R.id.action_share_bookmark)
                val copyItem = popup.menu.findItem(R.id.action_copy_bookmark)

                if (item.isBookmarklet) {
                    openNewTabItem?.isVisible = false
                    openIncognitoItem?.isVisible = false
                    shareItem?.isVisible = false
                    copyItem?.title = context.getString(R.string.copy_script)
                } else if (isLocal) {
                    openIncognitoItem?.isVisible = false
                    shareItem?.isVisible = false
                    copyItem?.title = context.getString(R.string.copy_link)
                } else {
                    copyItem?.title = context.getString(R.string.copy_link)
                }

                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_open_in_new_tab -> {
                            onItemOpenInNewTab(item)
                            true
                        }
                        R.id.action_open_in_incognito -> {
                            onItemOpenInIncognito(item)
                            true
                        }
                        R.id.action_copy_bookmark -> {
                            onItemCopy(item)
                            true
                        }
                        R.id.action_share_bookmark -> {
                            onItemShare(item)
                            true
                        }
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
