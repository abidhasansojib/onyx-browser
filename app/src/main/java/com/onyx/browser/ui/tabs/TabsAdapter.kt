package com.onyx.browser.ui.tabs

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.R
import com.onyx.browser.data.model.TabItem
import com.onyx.browser.databinding.ItemTabBinding

class TabsAdapter(
    private val onTabClicked: (TabItem) -> Unit,
    private val onTabClosed: (TabItem) -> Unit,
    private val getSnapshot: (String) -> android.graphics.Bitmap?,
    private val isTabLocked: ((TabItem) -> Boolean)? = null
) : ListAdapter<TabItem, TabsAdapter.TabViewHolder>(TabDiffCallback()) {

    var activeTabId: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val binding = ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TabViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TabViewHolder(private val binding: ItemTabBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TabItem) {
            val locked = isTabLocked?.invoke(item) == true

            if (locked) {
                binding.tvTabTitle.text = "Protected Tab"
                binding.tvTabDomain.text = "Locked"
            } else {
                binding.tvTabTitle.text = item.title.ifBlank { "New Tab" }

                val domain = try {
                    if (item.url.isNotBlank()) Uri.parse(item.url).host ?: item.url else "New Tab"
                } catch (e: Exception) {
                    item.url
                }
                binding.tvTabDomain.text = domain
            }

            val isActive = item.id == activeTabId
            val context = binding.root.context
            val density = context.resources.displayMetrics.density

            if (isActive) {
                val accentColor = ContextCompat.getColor(context, R.color.tab_switcher_accent)
                binding.cardTab.strokeColor = accentColor
                binding.cardTab.strokeWidth = (2.5f * density).toInt().coerceAtLeast(3)

                binding.tabHeader.setBackgroundColor(accentColor)
                binding.tvTabTitle.setTextColor(android.graphics.Color.WHITE)
                binding.btnTabClose.setColorFilter(android.graphics.Color.WHITE)
            } else {
                val strokeColor = ContextCompat.getColor(context, R.color.tab_card_stroke)
                binding.cardTab.strokeColor = strokeColor
                binding.cardTab.strokeWidth = (1f * density).toInt().coerceAtLeast(1)

                val headerBgColor = ContextCompat.getColor(context, R.color.tab_card_header_bg)
                binding.tabHeader.setBackgroundColor(headerBgColor)

                val textColor = ContextCompat.getColor(context, R.color.tab_text_primary)
                binding.tvTabTitle.setTextColor(textColor)

                val closeColor = ContextCompat.getColor(context, R.color.tab_close_icon)
                binding.btnTabClose.setColorFilter(closeColor)
            }

            if (locked) {
                binding.ivTabFavicon.setImageResource(R.drawable.ic_lock)
                binding.ivTabFavicon.setColorFilter(if (isActive) android.graphics.Color.WHITE else ContextCompat.getColor(context, R.color.primary))
            } else if (item.isIncognito) {
                binding.ivTabFavicon.setImageResource(R.drawable.ic_incognito)
                binding.ivTabFavicon.setColorFilter(if (isActive) android.graphics.Color.WHITE else ContextCompat.getColor(context, R.color.incognito_purple))
            } else {
                binding.ivTabFavicon.setImageResource(R.drawable.ic_web)
                binding.ivTabFavicon.clearColorFilter()
                if (item.url.isNotBlank()) {
                    com.onyx.browser.data.favicon.FaviconManager.loadFavicon(
                        context = context,
                        imageView = binding.ivTabFavicon,
                        urlOrHost = item.url,
                        isCircular = true
                    )
                }
            }
            
            val snapshot = if (locked) null else getSnapshot(item.id)
            if (snapshot != null) {
                binding.ivTabSnapshot.setImageBitmap(snapshot)
                binding.ivTabSnapshot.visibility = android.view.View.VISIBLE
                binding.fallbackPreview.visibility = android.view.View.GONE
            } else {
                binding.ivTabSnapshot.visibility = android.view.View.GONE
                binding.fallbackPreview.visibility = android.view.View.VISIBLE
            }

            binding.cardTab.setOnClickListener { onTabClicked(item) }
            binding.btnTabClose.setOnClickListener { onTabClosed(item) }
        }
    }

    class TabDiffCallback : DiffUtil.ItemCallback<TabItem>() {
        override fun areItemsTheSame(oldItem: TabItem, newItem: TabItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: TabItem, newItem: TabItem): Boolean =
            oldItem == newItem
    }
}
