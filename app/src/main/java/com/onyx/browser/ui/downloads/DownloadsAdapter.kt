package com.onyx.browser.ui.downloads

import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.R
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.databinding.ItemDownloadBinding
import com.onyx.browser.download.DownloadTaskSnapshot
import com.onyx.browser.download.FileUtils

class DownloadsAdapter(
    private val onItemClicked: (DownloadItem) -> Unit,
    private val onCancelOngoingClicked: (DownloadItem) -> Unit,
    private val onMoreOptionsClicked: (DownloadItem) -> Unit,
    private val onActionClicked: ((DownloadItem) -> Unit)? = null,
    private val onItemLongClicked: ((DownloadItem) -> Unit)? = null
) : ListAdapter<DownloadItem, DownloadsAdapter.DownloadViewHolder>(DownloadDiffCallback()) {

    private var activeSnapshots: Map<Long, DownloadTaskSnapshot> = emptyMap()

    fun updateActiveSnapshots(snapshots: Map<Long, DownloadTaskSnapshot>) {
        this.activeSnapshots = snapshots
        notifyDataSetChanged()
    }

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
            val context = binding.root.context
            binding.tvDownloadFileName.text = item.fileName

            val lowerName = item.fileName.lowercase()
            when {
                ApkInstallerHelper.isApkFile(item.fileName, item.mimeType) -> {
                    binding.ivDownloadIcon.setImageResource(R.drawable.ic_android)
                }
                lowerName.endsWith(".mht") || lowerName.endsWith(".mhtml") ||
                lowerName.endsWith(".html") || lowerName.endsWith(".htm") ||
                item.mimeType.contains("html") || item.mimeType.contains("multipart") -> {
                    binding.ivDownloadIcon.setImageResource(R.drawable.ic_web)
                }
                lowerName.endsWith(".md") || lowerName.endsWith(".markdown") ||
                lowerName.endsWith(".txt") -> {
                    binding.ivDownloadIcon.setImageResource(R.drawable.ic_file)
                }
                else -> {
                    binding.ivDownloadIcon.setImageResource(R.drawable.ic_download)
                }
            }

            val snapshot = activeSnapshots[item.id]
            val isLiveActive = snapshot != null && (snapshot.status == DownloadItem.STATUS_RUNNING || snapshot.status == DownloadItem.STATUS_PAUSED || snapshot.status == DownloadItem.STATUS_PENDING)
            val isDbPausedOrInterrupted = snapshot == null && (item.status == DownloadItem.STATUS_PAUSED || item.status == DownloadItem.STATUS_RUNNING)

            if (isLiveActive && snapshot != null) {
                binding.tvDownloadFileName.alpha = 1.0f
                binding.ivDownloadIcon.alpha = 1.0f
                binding.tvDownloadDetails.alpha = 1.0f
                binding.progressDownload.visibility = View.VISIBLE
                binding.btnActionDownload.visibility = View.VISIBLE

                if (snapshot.totalBytes > 0L) {
                    binding.progressDownload.isIndeterminate = false
                    binding.progressDownload.progress = snapshot.progressPercent

                    val downloadedStr = Formatter.formatFileSize(context, snapshot.downloadedBytes)
                    val totalStr = Formatter.formatFileSize(context, snapshot.totalBytes)
                    val speedStr = if (snapshot.speedBytesPerSec > 0) "${Formatter.formatFileSize(context, snapshot.speedBytesPerSec)}/s" else "0 B/s"

                    if (snapshot.status == DownloadItem.STATUS_PAUSED) {
                        binding.tvDownloadDetails.text = if (snapshot.isWifiWaiting) {
                            "Paused • Waiting for Wi-Fi ($downloadedStr / $totalStr)"
                        } else {
                            "Paused • $downloadedStr / $totalStr (${snapshot.progressPercent}%)"
                        }
                        binding.btnActionDownload.setImageResource(R.drawable.ic_play_arrow)
                    } else {
                        binding.tvDownloadDetails.text = "$downloadedStr / $totalStr (${snapshot.progressPercent}%) • $speedStr"
                        binding.btnActionDownload.setImageResource(R.drawable.ic_pause)
                    }
                } else {
                    binding.progressDownload.isIndeterminate = true
                    val downloadedStr = Formatter.formatFileSize(context, snapshot.downloadedBytes)
                    val speedStr = if (snapshot.speedBytesPerSec > 0) "${Formatter.formatFileSize(context, snapshot.speedBytesPerSec)}/s" else "0 B/s"

                    if (snapshot.status == DownloadItem.STATUS_PAUSED) {
                        binding.tvDownloadDetails.text = "Paused • $downloadedStr"
                        binding.btnActionDownload.setImageResource(R.drawable.ic_play_arrow)
                    } else {
                        binding.tvDownloadDetails.text = "$downloadedStr • $speedStr"
                        binding.btnActionDownload.setImageResource(R.drawable.ic_pause)
                    }
                }

                binding.btnActionDownload.setOnClickListener {
                    onActionClicked?.invoke(item)
                }

            } else if (isDbPausedOrInterrupted) {
                // Interrupted or paused download from previous session
                binding.btnActionDownload.visibility = View.VISIBLE
                binding.btnActionDownload.setImageResource(R.drawable.ic_play_arrow)
                binding.btnActionDownload.setOnClickListener {
                    onActionClicked?.invoke(item)
                }

                if (item.fileSize > 0L && item.downloadedBytes > 0L) {
                    binding.progressDownload.visibility = View.VISIBLE
                    binding.progressDownload.isIndeterminate = false
                    val pct = ((item.downloadedBytes * 100L) / item.fileSize).toInt().coerceIn(0, 100)
                    binding.progressDownload.progress = pct
                    val downloadedStr = Formatter.formatFileSize(context, item.downloadedBytes)
                    val totalStr = Formatter.formatFileSize(context, item.fileSize)
                    binding.tvDownloadDetails.text = "Paused • $downloadedStr / $totalStr ($pct%)"
                } else if (item.downloadedBytes > 0L) {
                    binding.progressDownload.visibility = View.VISIBLE
                    binding.progressDownload.isIndeterminate = true
                    val downloadedStr = Formatter.formatFileSize(context, item.downloadedBytes)
                    binding.tvDownloadDetails.text = "Paused • $downloadedStr"
                } else {
                    binding.progressDownload.visibility = View.GONE
                    binding.tvDownloadDetails.text = "Paused"
                }

            } else {
                binding.progressDownload.visibility = View.GONE
                binding.btnActionDownload.visibility = View.GONE

                val fileExists = FileUtils.doesFileExist(item.filePath, context)
                val isDeleted = !fileExists && item.status == DownloadItem.STATUS_COMPLETED

                val sizeFormatted = if (item.fileSize > 0) {
                    Formatter.formatFileSize(context, item.fileSize)
                } else ""

                val timeFormatted = DateUtils.getRelativeTimeSpanString(
                    item.downloadTime,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                )

                val statusPrefix = when {
                    isDeleted -> "File deleted • "
                    item.status == DownloadItem.STATUS_FAILED -> "Failed • "
                    item.status == DownloadItem.STATUS_CANCELLED -> "Cancelled • "
                    item.status == DownloadItem.STATUS_PAUSED -> "Paused • "
                    else -> ""
                }

                val details = listOfNotNull(
                    sizeFormatted.ifEmpty { null },
                    timeFormatted
                ).joinToString(" • ")

                binding.tvDownloadDetails.text = "$statusPrefix$details"

                binding.tvDownloadFileName.alpha = if (isDeleted) 0.55f else 1.0f
                binding.ivDownloadIcon.alpha = if (isDeleted) 0.55f else 1.0f
                binding.tvDownloadDetails.alpha = if (isDeleted) 0.75f else 1.0f
            }

            binding.root.setOnClickListener { onItemClicked(item) }
            binding.root.setOnLongClickListener {
                onItemLongClicked?.invoke(item)
                true
            }
            val isOngoing = isLiveActive || isDbPausedOrInterrupted
            if (isOngoing) {
                binding.btnDeleteDownloadItem.setImageResource(R.drawable.ic_close)
                binding.btnDeleteDownloadItem.contentDescription = "Cancel download"
                binding.btnDeleteDownloadItem.setOnClickListener { onCancelOngoingClicked(item) }
            } else {
                binding.btnDeleteDownloadItem.setImageResource(R.drawable.ic_more_vert)
                binding.btnDeleteDownloadItem.contentDescription = "More options"
                binding.btnDeleteDownloadItem.setOnClickListener { onMoreOptionsClicked(item) }
            }
        }
    }

    class DownloadDiffCallback : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean =
            oldItem == newItem
    }
}
