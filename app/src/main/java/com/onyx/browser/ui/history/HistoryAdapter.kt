package com.onyx.browser.ui.history

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.R
import com.onyx.browser.data.favicon.FaviconManager
import com.onyx.browser.data.model.HistoryItem
import com.onyx.browser.databinding.ItemHistoryBinding
import com.onyx.browser.databinding.ItemHistoryHeaderBinding

class HistoryAdapter(
    private val onItemClicked: (HistoryItem) -> Unit,
    private val onItemDeleted: (HistoryItem) -> Unit,
    private val onHeaderToggled: (dayKey: String) -> Unit
) : ListAdapter<HistoryListItem, RecyclerView.ViewHolder>(HistoryDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is HistoryListItem.Header -> VIEW_TYPE_HEADER
            is HistoryListItem.Item -> VIEW_TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val binding = ItemHistoryHeaderBinding.inflate(inflater, parent, false)
                HeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemHistoryBinding.inflate(inflater, parent, false)
                HistoryItemViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HistoryListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is HistoryListItem.Item -> (holder as HistoryItemViewHolder).bind(item)
        }
    }

    inner class HeaderViewHolder(private val binding: ItemHistoryHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HistoryListItem.Header) {
            val context = binding.root.context
            binding.tvGroupTitle.text = item.title

            val lp = binding.headerContainer.layoutParams as ViewGroup.MarginLayoutParams
            val dp12 = dpToPx(context, 12)

            if (item.isExpanded) {
                binding.headerContainer.setBackgroundResource(R.drawable.bg_history_card_top)
                binding.ivGroupChevron.setImageResource(R.drawable.ic_chevron_down)
                lp.bottomMargin = 0
            } else {
                binding.headerContainer.setBackgroundResource(R.drawable.bg_history_card_collapsed)
                binding.ivGroupChevron.setImageResource(R.drawable.ic_chevron_right)
                lp.bottomMargin = dp12
            }
            binding.headerContainer.layoutParams = lp

            binding.headerContainer.setOnClickListener {
                onHeaderToggled(item.dayKey)
            }
        }
    }

    inner class HistoryItemViewHolder(private val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HistoryListItem.Item) {
            val historyItem = item.historyItem
            val context = binding.root.context
            val isLocal = com.onyx.browser.web.LocalFileLoader.isLocalFile(context, historyItem.url) ||
                          com.onyx.browser.web.LocalFileLoader.isPreviewUrl(historyItem.url)

            binding.tvHistoryTitle.text = historyItem.title.ifBlank { if (isLocal) "Local Document" else historyItem.url }
            binding.tvHistoryUrl.text = if (isLocal) historyItem.title.ifBlank { "Local Document" } else historyItem.url

            // Set background and margins based on item position in group
            val lp = binding.itemContainer.layoutParams as ViewGroup.MarginLayoutParams
            val dp16 = dpToPx(context, 16)
            val dp12 = dpToPx(context, 12)
            val dp14 = dpToPx(context, 14)
            val dp64 = dpToPx(context, 64)
            val dp68 = dpToPx(context, 68)

            if (item.isLastInGroup) {
                binding.itemContainer.setBackgroundResource(R.drawable.bg_history_card_bottom)
                lp.height = dp68
                lp.bottomMargin = dp14
                binding.itemContainer.setPadding(dp16, 0, dp12, dpToPx(context, 4))
            } else {
                binding.itemContainer.setBackgroundResource(R.drawable.bg_history_card_middle)
                lp.height = dp64
                lp.bottomMargin = 0
                binding.itemContainer.setPadding(dp16, 0, dp12, 0)
            }
            binding.itemContainer.layoutParams = lp

            // Favicon / Logo Loading
            val currentUrl = historyItem.url
            binding.ivHistoryIcon.tag = currentUrl

            val fallbackTint = ContextCompat.getColor(context, R.color.history_icon_placeholder_tint)
            val dp8 = dpToPx(context, 8)

            if (isLocal) {
                binding.ivHistoryIcon.setBackgroundResource(R.drawable.bg_history_icon_placeholder)
                binding.ivHistoryIcon.setImageResource(R.drawable.ic_file)
                binding.ivHistoryIcon.imageTintList = ColorStateList.valueOf(fallbackTint)
                binding.ivHistoryIcon.setPadding(dp8, dp8, dp8, dp8)
            } else {
                val cached = FaviconManager.getCachedFavicon(currentUrl)
                if (cached != null) {
                    val circular = FaviconManager.getCircularBitmap(cached)
                    binding.ivHistoryIcon.background = null
                    binding.ivHistoryIcon.setPadding(0, 0, 0, 0)
                    binding.ivHistoryIcon.clearColorFilter()
                    binding.ivHistoryIcon.imageTintList = null
                    binding.ivHistoryIcon.setImageBitmap(circular)
                } else {
                    binding.ivHistoryIcon.setBackgroundResource(R.drawable.bg_history_icon_placeholder)
                    binding.ivHistoryIcon.setImageResource(R.drawable.ic_web)
                    binding.ivHistoryIcon.imageTintList = ColorStateList.valueOf(fallbackTint)
                    binding.ivHistoryIcon.setPadding(dp8, dp8, dp8, dp8)

                    FaviconManager.loadFavicon(context, currentUrl) { bitmap ->
                        if (binding.ivHistoryIcon.tag == currentUrl && bitmap != null) {
                            val circular = FaviconManager.getCircularBitmap(bitmap)
                            binding.ivHistoryIcon.background = null
                            binding.ivHistoryIcon.setPadding(0, 0, 0, 0)
                            binding.ivHistoryIcon.clearColorFilter()
                            binding.ivHistoryIcon.imageTintList = null
                            binding.ivHistoryIcon.setImageBitmap(circular)
                        }
                    }
                }
            }

            binding.root.setOnClickListener { onItemClicked(historyItem) }
            binding.btnDeleteHistoryItem.setOnClickListener { onItemDeleted(historyItem) }
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    class HistoryDiffCallback : DiffUtil.ItemCallback<HistoryListItem>() {
        override fun areItemsTheSame(oldItem: HistoryListItem, newItem: HistoryListItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: HistoryListItem, newItem: HistoryListItem): Boolean =
            oldItem == newItem
    }
}
