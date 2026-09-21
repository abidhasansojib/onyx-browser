package com.onyx.browser.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.data.model.QuickActionItem
import com.onyx.browser.databinding.ItemQuickActionBinding
import java.util.Collections

class QuickActionsAdapter(
    private val onActionClick: (QuickActionItem) -> Unit,
    private val onActionLongClick: ((QuickActionItem) -> Unit)? = null
) : RecyclerView.Adapter<QuickActionsAdapter.ViewHolder>() {

    private val items = mutableListOf<QuickActionItem>()

    fun submitList(newItems: List<QuickActionItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<QuickActionItem> = items.toList()

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQuickActionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemQuickActionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: QuickActionItem) {
            binding.ivActionIcon.setImageResource(item.iconRes)
            binding.tvActionTitle.setText(item.titleRes)
            binding.root.setOnClickListener {
                onActionClick(item)
            }
            binding.root.setOnLongClickListener {
                onActionLongClick?.invoke(item)
                true
            }
        }
    }
}
