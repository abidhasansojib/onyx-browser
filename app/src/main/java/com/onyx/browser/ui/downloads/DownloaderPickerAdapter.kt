package com.onyx.browser.ui.downloads

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.R
import com.onyx.browser.databinding.ItemDownloaderPickerBinding

class DownloaderPickerAdapter(
    private val onDownloaderClicked: (ExternalDownloaderInfo) -> Unit
) : ListAdapter<ExternalDownloaderInfo, DownloaderPickerAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloaderPickerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemDownloaderPickerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ExternalDownloaderInfo) {
            binding.tvDownloaderName.text = item.name
            binding.tvDownloaderPackage.text = item.packageName
            if (item.icon != null) {
                binding.ivDownloaderIcon.setImageDrawable(item.icon)
            } else {
                binding.ivDownloaderIcon.setImageResource(R.drawable.ic_download)
            }
            binding.root.setOnClickListener {
                onDownloaderClicked(item)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ExternalDownloaderInfo>() {
        override fun areItemsTheSame(oldItem: ExternalDownloaderInfo, newItem: ExternalDownloaderInfo): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: ExternalDownloaderInfo, newItem: ExternalDownloaderInfo): Boolean {
            return oldItem.id == newItem.id && oldItem.name == newItem.name && oldItem.packageName == newItem.packageName
        }
    }
}
