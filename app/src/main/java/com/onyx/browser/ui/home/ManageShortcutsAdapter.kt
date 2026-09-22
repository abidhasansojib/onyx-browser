package com.onyx.browser.ui.home

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.R
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.databinding.ItemManageShortcutBinding

class ManageShortcutsAdapter(
    private val onEditClick: (ShortcutItem) -> Unit,
    private val onDeleteClick: (ShortcutItem) -> Unit,
    private val onOrderChanged: (List<ShortcutItem>) -> Unit = {}
) : ListAdapter<ShortcutItem, ManageShortcutsAdapter.ManageViewHolder>(DIFF_CALLBACK) {

    /** Attached by ManageShortcutsBottomSheet so drag handles can start drags. */
    var touchHelper: ItemTouchHelper? = null

    // ── Mutable working copy kept in sync with ListAdapter's current list ──
    private val items = mutableListOf<ShortcutItem>()

    override fun submitList(list: List<ShortcutItem>?) {
        val newList = list ?: emptyList()
        items.clear()
        items.addAll(newList)
        super.submitList(newList.toList())
    }

    /** Returns a snapshot of the current ordered list. */
    fun getItems(): List<ShortcutItem> = items.toList()

    /**
     * Moves an item from [from] to [to] position and notifies the adapter.
     * Called by ItemTouchHelper during an active drag.
     */
    fun moveItem(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ManageViewHolder {
        val binding = ItemManageShortcutBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ManageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ManageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    @SuppressLint("ClickableViewAccessibility")
    inner class ManageViewHolder(
        private val binding: ItemManageShortcutBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // Touch listener on the drag handle triggers the ItemTouchHelper drag
            binding.ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper?.startDrag(this)
                }
                false
            }
        }

        fun bind(item: ShortcutItem) {
            binding.tvManageTitle.text = item.title.ifBlank {
                try {
                    java.net.URI(item.url).host?.removePrefix("www.") ?: item.url
                } catch (_: Exception) {
                    item.url
                }
            }
            binding.tvManageUrl.text = item.url

            when (item.iconType) {
                ShortcutItem.ICON_GOOGLE -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_engine_google)
                }
                ShortcutItem.ICON_YOUTUBE -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_brand_youtube)
                }
                ShortcutItem.ICON_GITHUB -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_brand_github)
                }
                ShortcutItem.ICON_WIKIPEDIA -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_brand_wikipedia)
                }
                ShortcutItem.ICON_FACEBOOK -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_brand_facebook)
                }
                ShortcutItem.ICON_REDDIT -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_brand_reddit)
                }
                ShortcutItem.ICON_BOOKMARKS -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_bookmark)
                }
                ShortcutItem.ICON_HISTORY -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_history)
                }
                ShortcutItem.ICON_DOWNLOADS -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_download)
                }
                else -> {
                    val letter = item.title.trim().firstOrNull()?.uppercase()
                        ?: item.url.removePrefix("https://").removePrefix("http://").removePrefix("www.").firstOrNull()?.uppercase()
                    if (letter != null && letter.matches(Regex("[A-Za-z0-9]"))) {
                        binding.ivManageIcon.visibility = View.GONE
                        binding.tvManageLetter.visibility = View.VISIBLE
                        binding.tvManageLetter.text = letter
                    } else {
                        binding.ivManageIcon.visibility = View.VISIBLE
                        binding.tvManageLetter.visibility = View.GONE
                        binding.ivManageIcon.setImageResource(R.drawable.ic_web)
                    }
                }
            }

            binding.btnEditShortcut.setOnClickListener {
                onEditClick(item)
            }

            binding.btnDeleteShortcut.setOnClickListener {
                onDeleteClick(item)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ShortcutItem>() {
            override fun areItemsTheSame(old: ShortcutItem, new: ShortcutItem): Boolean =
                old.id == new.id

            override fun areContentsTheSame(old: ShortcutItem, new: ShortcutItem): Boolean =
                old == new
        }
    }
}
