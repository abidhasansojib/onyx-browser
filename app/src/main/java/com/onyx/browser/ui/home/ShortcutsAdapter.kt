package com.onyx.browser.ui.home

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.onyx.browser.R
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.databinding.ItemHomeShortcutBinding
import java.util.Collections

class ShortcutsAdapter(
    private val onShortcutClick: (ShortcutItem) -> Unit
) : RecyclerView.Adapter<ShortcutsAdapter.ShortcutViewHolder>() {

    private val items = mutableListOf<ShortcutItem>()

    fun submitList(newItems: List<ShortcutItem>) {
        val filtered = newItems.filter { it.id != ShortcutItem.ID_ADD_SHORTCUT && it.url != ShortcutItem.URL_ADD_SHORTCUT }
        if (getItems() == filtered) {
            return
        }
        items.clear()
        // Add normal shortcuts excluding any existing add sentinel
        items.addAll(filtered)
        // Append the Add shortcut tile beside normal shortcuts
        items.add(
            ShortcutItem(
                id = ShortcutItem.ID_ADD_SHORTCUT,
                title = "Add",
                url = ShortcutItem.URL_ADD_SHORTCUT,
                iconType = ShortcutItem.ICON_ADD
            )
        )
        notifyDataSetChanged()
    }

    fun getItems(): List<ShortcutItem> =
        items.filter { it.id != ShortcutItem.ID_ADD_SHORTCUT && it.url != ShortcutItem.URL_ADD_SHORTCUT }

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        val lastIdx = items.lastIndex
        if (fromPosition < 0 || toPosition < 0 || fromPosition >= lastIdx || toPosition >= lastIdx) {
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
            val isAdd = item.id == ShortcutItem.ID_ADD_SHORTCUT || item.iconType == ShortcutItem.ICON_ADD || item.url == ShortcutItem.URL_ADD_SHORTCUT

            binding.tvShortcutTitle.text = if (isAdd) {
                item.title.ifBlank { "Add" }
            } else {
                item.title.ifBlank {
                    try {
                        java.net.URI(item.url).host?.removePrefix("www.") ?: item.url
                    } catch (_: Exception) {
                        item.url
                    }
                }
            }

            // Reset tint & color filters to prevent recycled view tint bleed
            binding.ivShortcutIcon.clearColorFilter()
            binding.ivShortcutIcon.imageTintList = null

            val textColorPrimary = MaterialColors.getColor(binding.root, android.R.attr.textColorPrimary, Color.WHITE)
            val textColorSecondary = MaterialColors.getColor(binding.root, android.R.attr.textColorSecondary, Color.LTGRAY)

            when (item.iconType) {
                ShortcutItem.ICON_ADD -> {
                    binding.ivShortcutIcon.visibility = View.VISIBLE
                    binding.tvLetterBadge.visibility = View.GONE
                    binding.ivShortcutIcon.setImageResource(R.drawable.ic_add)
                    binding.ivShortcutIcon.imageTintList = ColorStateList.valueOf(textColorPrimary)
                }
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
                    binding.ivShortcutIcon.imageTintList = ColorStateList.valueOf(textColorSecondary)
                }
                ShortcutItem.ICON_HISTORY -> {
                    binding.ivShortcutIcon.visibility = View.VISIBLE
                    binding.tvLetterBadge.visibility = View.GONE
                    binding.ivShortcutIcon.setImageResource(R.drawable.ic_history)
                    binding.ivShortcutIcon.imageTintList = ColorStateList.valueOf(textColorSecondary)
                }
                ShortcutItem.ICON_DOWNLOADS -> {
                    binding.ivShortcutIcon.visibility = View.VISIBLE
                    binding.tvLetterBadge.visibility = View.GONE
                    binding.ivShortcutIcon.setImageResource(R.drawable.ic_download)
                    binding.ivShortcutIcon.imageTintList = ColorStateList.valueOf(textColorSecondary)
                }
                ShortcutItem.ICON_QR_SCAN -> {
                    binding.ivShortcutIcon.visibility = View.VISIBLE
                    binding.tvLetterBadge.visibility = View.GONE
                    binding.ivShortcutIcon.setImageResource(R.drawable.ic_qr_code)
                    binding.ivShortcutIcon.imageTintList = ColorStateList.valueOf(textColorSecondary)
                }
                else -> {
                    val letter = item.title.trim().firstOrNull()?.uppercase()
                        ?: item.url.removePrefix("https://").removePrefix("http://").removePrefix("www.").firstOrNull()?.uppercase()
                    if (letter != null && letter.matches(Regex("[A-Za-z0-9]"))) {
                        binding.tvLetterBadge.text = letter
                        binding.tvLetterBadge.setTextColor(textColorPrimary)
                    } else {
                        binding.tvLetterBadge.text = "•"
                        binding.tvLetterBadge.setTextColor(textColorPrimary)
                    }

                    // Dynamically fetch and display real website favicon / logo with smooth rounded corners
                    com.onyx.browser.data.favicon.FaviconManager.loadFavicon(
                        context = binding.root.context,
                        imageView = binding.ivShortcutIcon,
                        urlOrHost = item.url,
                        fallbackLetterView = binding.tvLetterBadge,
                        isCircular = false,
                        isRounded = true,
                        cornerRadiusRatio = 0.22f
                    )
                }
            }

            itemView.setOnClickListener {
                onShortcutClick(item)
            }
        }
    }
}
