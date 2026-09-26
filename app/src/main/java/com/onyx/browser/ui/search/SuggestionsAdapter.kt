package com.onyx.browser.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.R
import com.onyx.browser.data.model.SearchSuggestion
import com.onyx.browser.databinding.ItemSearchSuggestionBinding
import com.onyx.browser.databinding.ItemSearchDomainSuggestionBinding

class SuggestionsAdapter(
    private val onSuggestionClicked: (SearchSuggestion) -> Unit,
    private val onInsertClicked: (SearchSuggestion) -> Unit
) : ListAdapter<SearchSuggestion, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        private const val VIEW_TYPE_NORMAL = 0
        private const val VIEW_TYPE_DOMAIN = 1
        private const val VIEW_TYPE_CLIPBOARD = 2

        val DiffCallback = object : DiffUtil.ItemCallback<SearchSuggestion>() {
            override fun areItemsTheSame(oldItem: SearchSuggestion, newItem: SearchSuggestion): Boolean {
                return oldItem.queryOrUrl == newItem.queryOrUrl && oldItem.isHistory == newItem.isHistory && oldItem.isDomain == newItem.isDomain
            }
            override fun areContentsTheSame(oldItem: SearchSuggestion, newItem: SearchSuggestion): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return if (item.isClipboard) VIEW_TYPE_CLIPBOARD
        else if (item.isDomain || item.isUrl) VIEW_TYPE_DOMAIN
        else VIEW_TYPE_NORMAL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_CLIPBOARD -> ClipboardViewHolder(com.onyx.browser.databinding.ItemSearchClipboardSuggestionBinding.inflate(inflater, parent, false))
            VIEW_TYPE_DOMAIN -> DomainViewHolder(ItemSearchDomainSuggestionBinding.inflate(inflater, parent, false))
            else -> NormalViewHolder(ItemSearchSuggestionBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is DomainViewHolder -> holder.bind(item)
            is NormalViewHolder -> holder.bind(item)
            is ClipboardViewHolder -> holder.bind(item)
        }
    }

    inner class NormalViewHolder(private val binding: ItemSearchSuggestionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SearchSuggestion) {
            binding.tvSuggestionText.text = item.title

            when {
                item.isBookmark -> {
                    binding.ivSuggestionIcon.setImageResource(R.drawable.ic_bookmark)
                    binding.tvSuggestionSubtext.visibility = View.VISIBLE
                    binding.tvSuggestionSubtext.text = item.queryOrUrl
                    binding.btnInsertQuery.visibility = View.VISIBLE
                }
                item.isHistory -> {
                    binding.ivSuggestionIcon.setImageResource(R.drawable.ic_history)
                    if (item.queryOrUrl != item.title) {
                        binding.tvSuggestionSubtext.visibility = View.VISIBLE
                        binding.tvSuggestionSubtext.text = item.queryOrUrl
                    } else {
                        binding.tvSuggestionSubtext.visibility = View.GONE
                    }
                    binding.btnInsertQuery.visibility = View.VISIBLE
                }
                else -> {
                    binding.ivSuggestionIcon.setImageResource(R.drawable.ic_search)
                    binding.tvSuggestionSubtext.visibility = View.GONE
                    binding.btnInsertQuery.visibility = View.VISIBLE
                }
            }

            binding.root.setOnClickListener {
                onSuggestionClicked(item)
            }

            binding.btnInsertQuery.setOnClickListener {
                onInsertClicked(item)
            }
        }
    }

    inner class DomainViewHolder(private val binding: ItemSearchDomainSuggestionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SearchSuggestion) {
            binding.tvSuggestionText.text = item.title

            when {
                item.isBookmark -> {
                    binding.ivSuggestionIcon.setImageResource(R.drawable.ic_bookmark)
                    binding.tvSuggestionSubtext.text = item.queryOrUrl
                }
                item.isHistory -> {
                    binding.ivSuggestionIcon.setImageResource(R.drawable.ic_history)
                    binding.tvSuggestionSubtext.text = item.queryOrUrl
                }
                else -> {
                    binding.ivSuggestionIcon.setImageResource(R.drawable.ic_web)
                    val fullUrl = if (item.queryOrUrl.startsWith("http://", ignoreCase = true) ||
                        item.queryOrUrl.startsWith("https://", ignoreCase = true) ||
                        item.queryOrUrl.startsWith("file://", ignoreCase = true)) {
                        item.queryOrUrl
                    } else {
                        "https://${item.queryOrUrl}"
                    }
                    binding.tvSuggestionSubtext.text = fullUrl
                }
            }

            binding.root.setOnClickListener {
                onSuggestionClicked(item)
            }

            binding.btnInsertQuery.setOnClickListener {
                onInsertClicked(item)
            }
        }
    }

    inner class ClipboardViewHolder(private val binding: com.onyx.browser.databinding.ItemSearchClipboardSuggestionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SearchSuggestion) {
            binding.tvClipboardText.text = item.title
            binding.tvClipboardUrl.text = item.queryOrUrl
            if (item.isUrl || item.isDomain) {
                binding.ivClipboardIcon.setImageResource(R.drawable.ic_link)
            } else {
                binding.ivClipboardIcon.setImageResource(R.drawable.ic_search)
            }
            binding.root.setOnClickListener {
                onSuggestionClicked(item)
            }
        }
    }
}
