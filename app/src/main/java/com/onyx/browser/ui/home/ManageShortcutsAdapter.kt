package com.onyx.browser.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.R
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.databinding.ItemManageShortcutBinding

class ManageShortcutsAdapter(
    private val onEditClick: (ShortcutItem) -> Unit,
    private val onDeleteClick: (ShortcutItem) -> Unit
) : RecyclerView.Adapter<ManageShortcutsAdapter.ManageViewHolder>() {

    private val items = mutableListOf<ShortcutItem>()

    fun submitList(newItems: List<ShortcutItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ManageViewHolder {
        val binding = ItemManageShortcutBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ManageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ManageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ManageViewHolder(
        private val binding: ItemManageShortcutBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ShortcutItem) {
            binding.tvManageTitle.text = item.title.ifBlank {
                try {
                    java.net.URI(item.url).host?.removePrefix("www.") ?: item.url
                } catch (_: Exception) {
                    item.url
                }
            }
            binding.tvManageUrl.text = item.url

            when (item.iconType) {
                ShortcutItem.ICON_GOOGLE -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_engine_google)
                }
                ShortcutItem.ICON_YOUTUBE -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_brand_youtube)
                }
                ShortcutItem.ICON_GITHUB -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_brand_github)
                }
                ShortcutItem.ICON_WIKIPEDIA -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_brand_wikipedia)
                }
                ShortcutItem.ICON_FACEBOOK -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_brand_facebook)
                }
                ShortcutItem.ICON_REDDIT -> {
                    binding.ivManageIcon.visibility = View.VISIBLE
                    binding.tvManageLetter.visibility = View.GONE
                    binding.ivManageIcon.setImageResource(R.drawable.ic_brand_reddit)
                }
                else -> {
                    val letter = item.title.trim().firstOrNull()?.uppercase()
                        ?: item.url.removePrefix("https://").removePrefix("http://").removePrefix("www.").firstOrNull()?.uppercase()
                    if (letter != null && letter.matches(Regex("[A-Za-z0-9]"))) {
                        binding.ivManageIcon.visibility = View.GONE
                        binding.tvManageLetter.visibility = View.VISIBLE
                        binding.tvManageLetter.text = letter
                    } else {
                        binding.ivManageIcon.visibility = View.VISIBLE
                        binding.tvManageLetter.visibility = View.GONE
                        binding.ivManageIcon.setImageResource(R.drawable.ic_web)
                    }
                }
            }

            binding.btnEditShortcut.setOnClickListener {
                onEditClick(item)
            }

            binding.btnDeleteShortcut.setOnClickListener {
                onDeleteClick(item)
            }
        }
    }
}
