package com.onyx.browser.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.data.model.UserScriptItem
import com.onyx.browser.databinding.ItemUserscriptBinding

class UserScriptAdapter(
    private var scripts: List<UserScriptItem>,
    private val onToggle: (UserScriptItem, Boolean) -> Unit,
    private val onClick: (UserScriptItem) -> Unit,
    private val onDelete: (UserScriptItem) -> Unit
) : RecyclerView.Adapter<UserScriptAdapter.ViewHolder>() {

    private var isGloballyBlocked: Boolean = false

    fun updateList(newScripts: List<UserScriptItem>, isGloballyBlocked: Boolean = false) {
        scripts = newScripts
        this.isGloballyBlocked = isGloballyBlocked
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemUserscriptBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(scripts[position])
    }

    override fun getItemCount(): Int = scripts.size

    inner class ViewHolder(private val binding: ItemUserscriptBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: UserScriptItem) {
            binding.tvScriptName.text = item.name.ifBlank { "Unnamed script" }

            val metaParts = mutableListOf<String>()
            if (item.version.isNotBlank()) metaParts.add("v${item.version}")
            if (item.author.isNotBlank()) metaParts.add("by ${item.author}")
            binding.tvScriptMeta.text = if (metaParts.isNotEmpty()) metaParts.joinToString(" • ") else "Local script"

            if (item.description.isNotBlank()) {
                binding.tvScriptDescription.text = item.description
                binding.tvScriptDescription.visibility = View.VISIBLE
            } else {
                binding.tvScriptDescription.visibility = View.GONE
            }

            binding.tvScriptMatches.text = item.matchSummary.ifBlank { "All sites" }

            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = item.enabled
            binding.switchEnabled.isEnabled = !isGloballyBlocked
            binding.root.alpha = if (isGloballyBlocked) 0.65f else 1.0f
            binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(item, isChecked)
            }

            binding.root.setOnClickListener {
                onClick(item)
            }

            binding.btnDeleteScript.setOnClickListener {
                onDelete(item)
            }
        }
    }
}
