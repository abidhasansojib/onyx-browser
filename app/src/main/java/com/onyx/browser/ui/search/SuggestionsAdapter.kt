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
    private val onInsertClicked: (SearchSuggestion) -> Unit,
    private val onResolveClipboard: (() -> String?)? = null
) : ListAdapter<SearchSuggestion, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        private const val VIEW_TYPE_NORMAL = 0
        private const val VIEW_TYPE_DOMAIN = 1
        private const val VIEW_TYPE_CLIPBOARD = 2

        val DiffCallback = object : DiffUtil.ItemCallback<SearchSuggestion>() {
            override fun areItemsTheSame(oldItem: SearchSuggestion, newItem: SearchSuggestion): Boolean {
                if (oldItem.isClipboard && newItem.isClipboard) return true
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
                    val isBookmarklet = item.queryOrUrl.trim().startsWith("javascript:", ignoreCase = true)
                    if (isBookmarklet) {
                        binding.ivSuggestionIcon.setImageResource(R.drawable.ic_code)
                        binding.tvSuggestionSubtext.visibility = View.VISIBLE
                        binding.tvSuggestionSubtext.text = binding.root.context.getString(R.string.bookmarklet_badge)
                    } else {
                        binding.ivSuggestionIcon.setImageResource(R.drawable.ic_bookmark)
                        binding.tvSuggestionSubtext.visibility = View.VISIBLE
                        binding.tvSuggestionSubtext.text = item.queryOrUrl
                    }
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
                    val isBookmarklet = item.queryOrUrl.trim().startsWith("javascript:", ignoreCase = true)
                    if (isBookmarklet) {
                        binding.ivSuggestionIcon.setImageResource(R.drawable.ic_code)
                        binding.tvSuggestionSubtext.text = binding.root.context.getString(R.string.bookmarklet_badge)
                    } else {
                        binding.ivSuggestionIcon.setImageResource(R.drawable.ic_bookmark)
                        binding.tvSuggestionSubtext.text = item.queryOrUrl
                    }
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
            val isRevealed = !item.isClipboardLazy && item.queryOrUrl.isNotBlank()
            binding.tvClipboardText.text = item.title

            if (isRevealed) {
                val cleanDisplay = item.queryOrUrl.replace("\n", " ").replace("\r", " ").trim()
                binding.tvClipboardUrl.text = cleanDisplay
                binding.btnClipboardReveal.visibility = View.GONE
                if (item.isUrl || item.isDomain) {
                    binding.ivClipboardIcon.setImageResource(R.drawable.ic_link)
                } else {
                    binding.ivClipboardIcon.setImageResource(R.drawable.ic_search)
                }
            } else {
                binding.tvClipboardUrl.text = binding.root.context.getString(R.string.tap_to_search_copied)
                binding.btnClipboardReveal.visibility = View.VISIBLE
                binding.ivClipboardIcon.setImageResource(R.drawable.ic_link)
            }

            binding.btnClipboardReveal.setOnClickListener {
                val resolvedText = onResolveClipboard?.invoke()
                if (!resolvedText.isNullOrBlank()) {
                    val isLink = android.util.Patterns.WEB_URL.matcher(resolvedText).matches() ||
                            resolvedText.startsWith("http://", ignoreCase = true) ||
                            resolvedText.startsWith("https://", ignoreCase = true) ||
                            resolvedText.startsWith("www.", ignoreCase = true)
                    val updatedTitle = if (isLink) binding.root.context.getString(R.string.link_you_copied) else binding.root.context.getString(R.string.text_you_copied)
                    val cleanDisplay = resolvedText.replace("\n", " ").replace("\r", " ").trim()
                    binding.tvClipboardText.text = updatedTitle
                    binding.tvClipboardUrl.text = cleanDisplay
                    binding.ivClipboardIcon.setImageResource(if (isLink) R.drawable.ic_link else R.drawable.ic_search)
                    binding.btnClipboardReveal.visibility = View.GONE
                }
            }

            val handleCardClick = {
                val targetText = if (item.isClipboardLazy || item.queryOrUrl.isBlank()) {
                    onResolveClipboard?.invoke() ?: ""
                } else {
                    item.queryOrUrl
                }
                if (targetText.isNotBlank()) {
                    val isLink = android.util.Patterns.WEB_URL.matcher(targetText).matches() ||
                            targetText.startsWith("http://", ignoreCase = true) ||
                            targetText.startsWith("https://", ignoreCase = true) ||
                            targetText.startsWith("www.", ignoreCase = true)
                    onSuggestionClicked(
                        item.copy(
                            queryOrUrl = targetText,
                            isUrl = isLink,
                            isDomain = isLink && !targetText.startsWith("http://", ignoreCase = true) && !targetText.startsWith("https://", ignoreCase = true),
                            isClipboardLazy = false
                        )
                    )
                }
            }

            binding.root.setOnClickListener { handleCardClick() }
            binding.containerClipboardInfo.setOnClickListener { handleCardClick() }

            binding.btnClipboardInsert.setOnClickListener {
                val targetText = if (item.isClipboardLazy || item.queryOrUrl.isBlank()) {
                    onResolveClipboard?.invoke() ?: ""
                } else {
                    item.queryOrUrl
                }
                if (targetText.isNotBlank()) {
                    onInsertClicked(item.copy(queryOrUrl = targetText, isClipboardLazy = false))
                }
            }
        }
    }
}
