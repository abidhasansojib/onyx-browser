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

class SuggestionsAdapter(
    private val onSuggestionClicked: (SearchSuggestion) -> Unit,
    private val onInsertClicked: (SearchSuggestion) -> Unit
) : ListAdapter<SearchSuggestion, SuggestionsAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchSuggestionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemSearchSuggestionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SearchSuggestion) {
            binding.tvSuggestionText.text = item.title

            if (item.isHistory) {
                binding.ivSuggestionIcon.setImageResource(R.drawable.ic_history)
                if (item.queryOrUrl != item.title) {
                    binding.tvSuggestionSubtext.visibility = View.VISIBLE
                    binding.tvSuggestionSubtext.text = item.queryOrUrl
                } else {
                    binding.tvSuggestionSubtext.visibility = View.GONE
                }
            } else {
                binding.ivSuggestionIcon.setImageResource(R.drawable.ic_search)
                binding.tvSuggestionSubtext.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                onSuggestionClicked(item)
            }

            binding.btnInsertQuery.setOnClickListener {
                onInsertClicked(item)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SearchSuggestion>() {
        override fun areItemsTheSame(oldItem: SearchSuggestion, newItem: SearchSuggestion): Boolean {
            return oldItem.queryOrUrl == newItem.queryOrUrl && oldItem.isHistory == newItem.isHistory
        }

        override fun areContentsTheSame(oldItem: SearchSuggestion, newItem: SearchSuggestion): Boolean {
            return oldItem == newItem
        }
    }
}
