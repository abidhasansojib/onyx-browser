package com.onyx.browser.ui.menu

import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.databinding.DialogImagePreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImagePreviewDialog : DialogFragment() {

    private var _binding: DialogImagePreviewBinding? = null
    private val binding get() = _binding!!

    private var imageUrl: String = ""
    private var onOpenInNewTab: ((String) -> Unit)? = null

    fun setOnOpenInNewTab(cb: (String) -> Unit) {
        onOpenInNewTab = cb
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imageUrl = arguments?.getString(ARG_IMAGE_URL) ?: ""
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogImagePreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvPreviewTitle.text = try {
            Uri.parse(imageUrl).lastPathSegment ?: "Image"
        } catch (_: Exception) {
            "Image"
        }

        binding.btnClosePreview.setOnClickListener { dismiss() }

        binding.btnSharePreview.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, imageUrl)
            }
            startActivity(Intent.createChooser(shareIntent, "Share image link"))
        }

        binding.btnDownloadPreview.setOnClickListener {
            downloadImage(imageUrl)
        }

        binding.btnOpenTabPreview.setOnClickListener {
            onOpenInNewTab?.invoke(imageUrl)
            dismiss()
        }

        loadImage()
    }

    private fun loadImage() {
        binding.pbPreviewLoading.visibility = View.VISIBLE

        if (imageUrl.startsWith("data:image")) {
            try {
                val base64Index = imageUrl.indexOf("base64,")
                if (base64Index != -1) {
                    val base64Str = imageUrl.substring(base64Index + 7)
                    val bytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    binding.ivFullPreview.setImageBitmap(bmp)
                    binding.pbPreviewLoading.visibility = View.GONE
                    return
                }
            } catch (_: Exception) {}
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = try {
                val conn = (java.net.URL(imageUrl).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    instanceFollowRedirects = true
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                    )
                    doInput = true
                    connect()
                }
                if (conn.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    conn.inputStream.use { stream ->
                        val bytes = stream.readBytes()
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

                        var inSampleSize = 1
                        while (opts.outWidth / (inSampleSize * 2) >= 2048 &&
                            opts.outHeight / (inSampleSize * 2) >= 2048
                        ) {
                            inSampleSize *= 2
                        }
                        val decodeOpts = BitmapFactory.Options().apply {
                            this.inSampleSize = inSampleSize
                        }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    }
                } else null
            } catch (_: Exception) {
                null
            }

            withContext(Dispatchers.Main) {
                if (isAdded && !isDetached) {
                    binding.pbPreviewLoading.visibility = View.GONE
                    if (bitmap != null) {
                        binding.ivFullPreview.setImageBitmap(bitmap)
                    } else {
                        Toast.makeText(requireContext(), "Failed to load image preview", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun downloadImage(url: String) {
        try {
            val ctx = requireContext()
            val filename = Uri.parse(url).lastPathSegment ?: "image_${System.currentTimeMillis()}.jpg"
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(filename)
                .setDescription("Downloading image")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, filename)
                .setAllowedOverMetered(true)
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(ctx, "Saving image…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ImagePreviewDialog"
        private const val ARG_IMAGE_URL = "image_url"

        fun newInstance(imageUrl: String) = ImagePreviewDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_IMAGE_URL, imageUrl)
            }
        }
    }
}
