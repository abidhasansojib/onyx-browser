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
    private val onTabClosed: (TabItem) -> Unit
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
            binding.tvTabTitle.text = item.title.ifBlank { "New Tab" }

            val domain = try {
                if (item.url.isNotBlank()) Uri.parse(item.url).host ?: item.url else "New Tab"
            } catch (e: Exception) {
                item.url
            }
            binding.tvTabDomain.text = domain

            val isActive = item.id == activeTabId
            val context = binding.root.context
            if (isActive) {
                binding.cardTab.strokeColor = ContextCompat.getColor(context, R.color.primary)
                binding.cardTab.strokeWidth = 3
            } else {
                val typedArray = context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorOutline))
                val outlineColor = typedArray.getColor(0, ContextCompat.getColor(context, R.color.outline_light))
                typedArray.recycle()
                binding.cardTab.strokeColor = outlineColor
                binding.cardTab.strokeWidth = 1
            }

            if (item.isIncognito) {
                binding.ivTabFavicon.setImageResource(R.drawable.ic_incognito)
                binding.ivTabFavicon.setColorFilter(ContextCompat.getColor(context, R.color.incognito_purple))
            } else {
                binding.ivTabFavicon.setImageResource(R.drawable.ic_web)
                binding.ivTabFavicon.clearColorFilter()
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
