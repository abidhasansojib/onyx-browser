package com.onyx.browser.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.R
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.databinding.ItemHomeShortcutBinding
import java.util.Collections

class ShortcutsAdapter(
    private val onShortcutClick: (ShortcutItem) -> Unit,
    private val onShortcutLongClick: (ShortcutItem) -> Unit
) : RecyclerView.Adapter<ShortcutsAdapter.ShortcutViewHolder>() {

    private val items = mutableListOf<ShortcutItem>()

    fun submitList(newItems: List<ShortcutItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<ShortcutItem> = items.toList()

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition < 0 || toPosition < 0 || fromPosition >= items.size || toPosition >= items.size) {
            return false
        }
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(items, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(items, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortcutViewHolder {
        val binding = ItemHomeShortcutBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ShortcutViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ShortcutViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ShortcutViewHolder(
        private val binding: ItemHomeShortcutBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ShortcutItem) {
            binding.tvShortcutTitle.text = item.title.ifBlank {
                try {
                    java.net.URI(item.url).host?.removePrefix("www.") ?: item.url
                } catch (_: Exception) {
                    item.url
                }
            }

            when (item.iconType) {
                ShortcutItem.ICON_GOOGLE -> {
                    binding.ivShortcutIcon.visibility = View.VISIBLE
                    binding.tvLetterBadge.visibility = View.GONE
                    binding.ivShortcutIcon.setImageResource(R.drawable.ic_engine_google)
                }
                ShortcutItem.ICON_YOUTUBE -> {
                    binding.ivShortcutIcon.visibility = View.VISIBLE
                    binding.tvLetterBadge.visibility = View.GONE
                    binding.ivShortcutIcon.setImageResource(R.drawable.ic_brand_youtube)
                }
                ShortcutItem.ICON_GITHUB -> {
                    binding.ivShortcutIcon.visibility = View.VISIBLE
                    binding.tvLetterBadge.visibility = View.GONE
                    binding.ivShortcutIcon.setImageResource(R.drawable.ic_brand_github)
                }
                ShortcutItem.ICON_WIKIPEDIA -> {
                    binding.ivShortcutIcon.visibility = View.VISIBLE
                    binding.tvLetterBadge.visibility = View.GONE
                    binding.ivShortcutIcon.setImageResource(R.drawable.ic_brand_wikipedia)
                }
                ShortcutItem.ICON_FACEBOOK -> {
                    binding.ivShortcutIcon.visibility = View.VISIBLE
                    binding.tvLetterBadge.visibility = View.GONE
                    binding.ivShortcutIcon.setImageResource(R.drawable.ic_brand_facebook)
                }
                ShortcutItem.ICON_REDDIT -> {
                    binding.ivShortcutIcon.visibility = View.VISIBLE
                    binding.tvLetterBadge.visibility = View.GONE
                    binding.ivShortcutIcon.setImageResource(R.drawable.ic_brand_reddit)
                }
                ShortcutItem.ICON_BOOKMARKS -> {
                    binding.ivShortcutIcon.visibility = View.VISIBLE
                    binding.tvLetterBadge.visibility = View.GONE
                    binding.ivShortcutIcon.setImageResource(R.drawable.ic_bookmark)
                    binding.ivShortcutIcon.setColorFilter(android.graphics.Color.parseColor("#808080"))
                }
                ShortcutItem.ICON_HISTORY -> {
                    binding.ivShortcutIcon.visibility = View.VISIBLE
                    binding.tvLetterBadge.visibility = View.GONE
                    binding.ivShortcutIcon.setImageResource(R.drawable.ic_history)
                    binding.ivShortcutIcon.setColorFilter(android.graphics.Color.parseColor("#808080"))
                }
                ShortcutItem.ICON_DOWNLOADS -> {
                    binding.ivShortcutIcon.visibility = View.VISIBLE
                    binding.tvLetterBadge.visibility = View.GONE
                    binding.ivShortcutIcon.setImageResource(R.drawable.ic_download)
                    binding.ivShortcutIcon.setColorFilter(android.graphics.Color.parseColor("#808080"))
                }
                ShortcutItem.ICON_QR_SCAN -> {
                    binding.ivShortcutIcon.visibility = View.VISIBLE
                    binding.tvLetterBadge.visibility = View.GONE
                    binding.ivShortcutIcon.setImageResource(R.drawable.ic_qr_code)
                    binding.ivShortcutIcon.setColorFilter(android.graphics.Color.parseColor("#808080"))
                }
                else -> {
                    val letter = item.title.trim().firstOrNull()?.uppercase()
                        ?: item.url.removePrefix("https://").removePrefix("http://").removePrefix("www.").firstOrNull()?.uppercase()
                    if (letter != null && letter.matches(Regex("[A-Za-z0-9]"))) {
                        binding.ivShortcutIcon.visibility = View.GONE
                        binding.tvLetterBadge.visibility = View.VISIBLE
                        binding.tvLetterBadge.text = letter
                    } else {
                        binding.ivShortcutIcon.visibility = View.VISIBLE
                        binding.tvLetterBadge.visibility = View.GONE
                        binding.ivShortcutIcon.setImageResource(R.drawable.ic_web)
                    }
                }
            }

            itemView.setOnClickListener {
                onShortcutClick(item)
            }

            itemView.setOnLongClickListener {
                onShortcutLongClick(item)
                true
            }
        }
    }
}
