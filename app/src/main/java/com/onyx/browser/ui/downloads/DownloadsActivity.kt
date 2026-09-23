package com.onyx.browser.ui.downloads

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.databinding.ActivityDownloadsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var adapter: DownloadsAdapter
    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(this)

        binding.toolbarDownloads.setNavigationOnClickListener { finish() }

        binding.btnOpenSystemDownloads.setOnClickListener {
            openSystemDownloadsFolder()
        }

        setupRecyclerView()
        observeDownloads()
    }

    private fun openSystemDownloadsFolder() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivity(Intent.createChooser(intent, "Open Downloads"))
            } catch (ex: Exception) {
                Toast.makeText(this, "Could not open downloads: ${ex.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = DownloadsAdapter(
            onItemClicked = { item ->
                val snapshot = com.onyx.browser.download.OnyxDownloadManager.snapshotsFlow.value[item.id]
                if (snapshot != null && (snapshot.status == DownloadItem.STATUS_RUNNING || snapshot.status == DownloadItem.STATUS_PAUSED)) {
                    toggleDownloadAction(item)
                } else {
                    openFile(item)
                }
            },
            onItemDeleted = { item -> deleteDownloadItem(item) },
            onActionClicked = { item -> toggleDownloadAction(item) },
            onItemLongClicked = { item -> showDownloadDetailsDialog(item) }
        )

        binding.rvDownloads.layoutManager = LinearLayoutManager(this)
        binding.rvDownloads.adapter = adapter
    }

    private fun toggleDownloadAction(item: DownloadItem) {
        val snapshot = com.onyx.browser.download.OnyxDownloadManager.snapshotsFlow.value[item.id]
        if (snapshot != null) {
            if (snapshot.status == DownloadItem.STATUS_RUNNING) {
                com.onyx.browser.download.OnyxDownloadManager.pauseDownload(item.id)
            } else if (snapshot.status == DownloadItem.STATUS_PAUSED) {
                com.onyx.browser.download.OnyxDownloadManager.resumeDownload(item.id)
            } else if (snapshot.status == DownloadItem.STATUS_FAILED) {
                com.onyx.browser.download.OnyxDownloadManager.retryDownload(item.id)
            }
        } else {
            // Task not currently in memory; check DB status
            if (item.status == DownloadItem.STATUS_PAUSED || item.status == DownloadItem.STATUS_FAILED || item.status == DownloadItem.STATUS_RUNNING) {
                com.onyx.browser.download.OnyxDownloadManager.resumeExistingDownload(this, item)
            }
        }
    }

    private fun observeDownloads() {
        lifecycleScope.launch {
            database.downloadDao().getAllDownloadsFlow().collectLatest { list ->
                adapter.submitList(list)
                binding.emptyDownloadsView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.rvDownloads.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        lifecycleScope.launch {
            com.onyx.browser.download.OnyxDownloadManager.snapshotsFlow.collectLatest { snapshots ->
                adapter.updateActiveSnapshots(snapshots)
            }
        }
    }

    private fun isLocalWebDocument(item: DownloadItem): Boolean {
        val name = item.fileName.lowercase()
        val mime = item.mimeType.lowercase()
        return name.endsWith(".mht") || name.endsWith(".mhtml") ||
                name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xhtml") ||
                name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt") ||
                mime == "multipart/related" || mime == "message/rfc822" ||
                mime == "application/x-mimearchive" || mime == "application/mhtml" ||
                mime == "text/html" || mime == "application/xhtml+xml" ||
                mime == "text/markdown" || mime == "text/x-markdown"
    }

    private fun openFile(item: DownloadItem) {
        if (isLocalWebDocument(item)) {
            val target = item.filePath
            val uri = if (target.startsWith("content://", ignoreCase = true) || target.startsWith("file://", ignoreCase = true)) {
                Uri.parse(target)
            } else {
                Uri.fromFile(File(target))
            }
            val intent = Intent(this, com.onyx.browser.MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            finish()
            return
        }

        // For content URIs
        if (item.filePath.startsWith("content://", ignoreCase = true)) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(item.filePath), item.mimeType.ifEmpty { "*/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val file = File(item.filePath)
        if (!file.exists()) {
            Toast.makeText(this, "File does not exist: ${item.fileName}", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType.ifEmpty { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteDownloadItem(item: DownloadItem) {
        com.onyx.browser.download.OnyxDownloadManager.cancelDownload(item.id)
        lifecycleScope.launch(Dispatchers.IO) {
            database.downloadDao().deleteDownload(item)
        }
    }

    private fun showDownloadDetailsDialog(item: DownloadItem) {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        val primaryTextColor = typedValue.data
        theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
        val secondaryTextColor = typedValue.data

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        fun addRow(label: String, value: String, isCopyable: Boolean = false) {
            if (value.isBlank()) return
            val labelView = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(secondaryTextColor)
                setPadding(0, 12, 0, 2)
            }
            val valView = TextView(this).apply {
                text = value
                textSize = 14f
                setTextColor(primaryTextColor)
                setTextIsSelectable(true)
                if (isCopyable) {
                    setOnClickListener {
                        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText(label, value))
                        Toast.makeText(this@DownloadsActivity, "$label copied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            container.addView(labelView)
            container.addView(valView)
        }

        addRow("File Name", item.fileName)
        if (item.fileSize > 0) {
            addRow("Size", Formatter.formatFileSize(this, item.fileSize))
        }
        addRow("File Path", item.filePath, isCopyable = true)
        addRow("URL", item.url, isCopyable = true)

        if (item.sha256.isNotBlank()) {
            addRow("SHA-256 Checksum", item.sha256, isCopyable = true)
        }
        if (item.md5.isNotBlank()) {
            addRow("MD5 Checksum", item.md5, isCopyable = true)
        }

        // Integrity verification input
        if (item.sha256.isNotBlank() || item.md5.isNotBlank()) {
            val verifyLabel = TextView(this).apply {
                text = "Verify Checksum"
                textSize = 12f
                setTextColor(secondaryTextColor)
                setPadding(0, 16, 0, 4)
            }
            val verifyInput = EditText(this).apply {
                hint = "Paste expected SHA-256 or MD5"
                textSize = 13f
            }
            val resultView = TextView(this).apply {
                textSize = 12f
                setPadding(0, 4, 0, 4)
            }

            verifyInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val input = s?.toString()?.trim() ?: ""
                    if (input.isBlank()) {
                        resultView.text = ""
                    } else if (input.equals(item.sha256, ignoreCase = true) || input.equals(item.md5, ignoreCase = true)) {
                        resultView.text = "✓ Checksum MATCHES successfully"
                        resultView.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                    } else {
                        resultView.text = "✗ Checksum MISMATCH"
                        resultView.setTextColor(android.graphics.Color.parseColor("#F44336"))
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            container.addView(verifyLabel)
            container.addView(verifyInput)
            container.addView(resultView)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(item.fileName)
            .setView(container)
            .setPositiveButton("Close", null)
            .setNeutralButton("Share Link") { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, item.url)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Download Link"))
            }
            .show()
    }
}
