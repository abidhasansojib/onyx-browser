package com.onyx.browser.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.data.model.QuickActionItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.DialogAdjustQuickActionsBinding
import com.onyx.browser.databinding.ItemAdjustQuickActionBinding
import java.util.Collections

class AdjustQuickActionsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogAdjustQuickActionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: BrowserPreferences
    private val currentItems = mutableListOf<QuickActionItem>()
    private lateinit var adapter: AdjustAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAdjustQuickActionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferences = BrowserPreferences.getInstance(requireContext())

        val currentOrder = preferences.getQuickActionOrder()
        currentItems.clear()
        currentItems.addAll(QuickActionItem.getOrderedItems(currentOrder))

        adapter = AdjustAdapter()
        binding.rvAdjustActions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAdjustActions.adapter = adapter

        binding.btnClose.setOnClickListener {
            dismiss()
        }

        binding.btnDone.setOnClickListener {
            saveAndDismiss()
        }

        binding.btnResetDefault.setOnClickListener {
            currentItems.clear()
            currentItems.addAll(QuickActionItem.getOrderedItems(QuickActionItem.DEFAULT_ORDER))
            adapter.notifyDataSetChanged()
            saveAndDismiss()
        }
    }

    private fun saveAndDismiss() {
        val newOrder = currentItems.map { it.id }
        preferences.saveQuickActionOrder(newOrder)
        dismiss()
    }

    inner class AdjustAdapter : RecyclerView.Adapter<AdjustAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemAdjustQuickActionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = currentItems[position]
            holder.binding.ivIcon.setImageResource(item.iconRes)
            holder.binding.tvTitle.setText(item.titleRes)

            // Disable up arrow for first item
            holder.binding.btnMoveUp.isEnabled = position > 0
            holder.binding.btnMoveUp.alpha = if (position > 0) 1.0f else 0.3f

            // Disable down arrow for last item
            holder.binding.btnMoveDown.isEnabled = position < currentItems.size - 1
            holder.binding.btnMoveDown.alpha = if (position < currentItems.size - 1) 1.0f else 0.3f

            holder.binding.btnMoveUp.setOnClickListener {
                if (position > 0) {
                    Collections.swap(currentItems, position, position - 1)
                    notifyItemMoved(position, position - 1)
                    notifyItemRangeChanged(position - 1, 2)
                    preferences.saveQuickActionOrder(currentItems.map { it.id })
                }
            }

            holder.binding.btnMoveDown.setOnClickListener {
                if (position < currentItems.size - 1) {
                    Collections.swap(currentItems, position, position + 1)
                    notifyItemMoved(position, position + 1)
                    notifyItemRangeChanged(position, 2)
                    preferences.saveQuickActionOrder(currentItems.map { it.id })
                }
            }
        }

        override fun getItemCount(): Int = currentItems.size

        inner class ViewHolder(val binding: ItemAdjustQuickActionBinding) : RecyclerView.ViewHolder(binding.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AdjustQuickActionsBottomSheet"
    }
}
