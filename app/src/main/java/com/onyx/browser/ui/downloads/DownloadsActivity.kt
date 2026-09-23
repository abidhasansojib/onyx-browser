package com.onyx.browser.ui.downloads

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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
            onItemClicked = { item -> openFile(item) },
            onItemDeleted = { item -> deleteDownloadItem(item) }
        )

        binding.rvDownloads.layoutManager = LinearLayoutManager(this)
        binding.rvDownloads.adapter = adapter
    }

    private fun observeDownloads() {
        lifecycleScope.launch {
            database.downloadDao().getAllDownloadsFlow().collectLatest { list ->
                adapter.submitList(list)
                binding.emptyDownloadsView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.rvDownloads.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
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
        lifecycleScope.launch(Dispatchers.IO) {
            database.downloadDao().deleteDownload(item)
        }
    }
}
