package com.onyx.browser.ui.downloads

import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.databinding.ItemDownloadBinding

class DownloadsAdapter(
    private val onItemClicked: (DownloadItem) -> Unit,
    private val onItemDeleted: (DownloadItem) -> Unit
) : ListAdapter<DownloadItem, DownloadsAdapter.DownloadViewHolder>(DownloadDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DownloadViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DownloadViewHolder(private val binding: ItemDownloadBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadItem) {
            binding.tvDownloadFileName.text = item.fileName

            val lowerName = item.fileName.lowercase()
            when {
                lowerName.endsWith(".mht") || lowerName.endsWith(".mhtml") ||
                lowerName.endsWith(".html") || lowerName.endsWith(".htm") ||
                item.mimeType.contains("html") || item.mimeType.contains("multipart") -> {
                    binding.ivDownloadIcon.setImageResource(com.onyx.browser.R.drawable.ic_web)
                }
                lowerName.endsWith(".md") || lowerName.endsWith(".markdown") ||
                lowerName.endsWith(".txt") -> {
                    binding.ivDownloadIcon.setImageResource(com.onyx.browser.R.drawable.ic_file)
                }
                else -> {
                    binding.ivDownloadIcon.setImageResource(com.onyx.browser.R.drawable.ic_download)
                }
            }

            val context = binding.root.context
            val sizeFormatted = if (item.fileSize > 0) {
                Formatter.formatFileSize(context, item.fileSize)
            } else ""

            val timeFormatted = DateUtils.getRelativeTimeSpanString(
                item.downloadTime,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )

            val details = listOfNotNull(
                sizeFormatted.ifEmpty { null },
                timeFormatted
            ).joinToString(" • ")

            binding.tvDownloadDetails.text = details

            binding.root.setOnClickListener { onItemClicked(item) }
            binding.btnDeleteDownloadItem.setOnClickListener { onItemDeleted(item) }
        }
    }

    class DownloadDiffCallback : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean =
            oldItem == newItem
    }
}
