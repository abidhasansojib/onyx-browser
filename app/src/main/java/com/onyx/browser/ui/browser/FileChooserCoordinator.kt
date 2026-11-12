package com.onyx.browser.ui.browser

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.onyx.browser.MainActivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates WebView `<input type="file">` uploads, Android Photo Picker / Gallery selection,
 * direct Camera/Camcorder capture, URI permission grants, and lifecycle cleanup.
 */
class FileChooserCoordinator(
    private val activity: MainActivity,
    private val onLaunchFileChooserIntent: (Intent) -> Unit,
    private val onRequestCameraPermission: () -> Unit,
    private val onChooserPendingStateChanged: (Boolean) -> Unit,
    private val onActiveWebViewResume: () -> Unit
) {
    private class SafeValueCallback<T>(
        private val delegate: ValueCallback<T>?
    ) : ValueCallback<T> {
        private val completed = AtomicBoolean(false)

        override fun onReceiveValue(value: T?) {
            if (completed.compareAndSet(false, true)) {
                try {
                    delegate?.onReceiveValue(value)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error delivering callback", t)
                }
            }
        }
    }

    var fileChooserCallback: ValueCallback<Array<Uri>>? = null
        private set
    var pendingFileChooserParams: WebChromeClient.FileChooserParams? = null
        private set
    var cameraImageUri: Uri? = null
        private set
    private var pendingCameraPhotoFile: File? = null
    private var pendingCameraCaptureIntent: Intent? = null

    fun createCameraImageUri(): Uri? {
        val cameraDir = File(activity.cacheDir, "camera_uploads")
        if (!cameraDir.exists()) cameraDir.mkdirs()
        val photoFile = File(cameraDir, "IMG_${System.currentTimeMillis()}.jpg")
        val photoUri = FileProvider.getUriForFile(
            activity,
            "${activity.applicationContext.packageName}.fileprovider",
            photoFile
        )
        pendingCameraPhotoFile = photoFile
        cameraImageUri = photoUri
        return photoUri
    }

    fun handleShowFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?
    ): Boolean {
        if (filePathCallback == null) return false

        try { fileChooserCallback?.onReceiveValue(null) } catch (_: Throwable) {}
        fileChooserCallback = SafeValueCallback(filePathCallback)
        pendingFileChooserParams = fileChooserParams
        pendingCameraPhotoFile?.delete()
        pendingCameraPhotoFile = null
        cameraImageUri = null
        pendingCameraCaptureIntent = null

        return launchFileChooser(fileChooserParams)
    }

    fun launchFileChooser(fileChooserParams: WebChromeClient.FileChooserParams?): Boolean {
        val safeCallback = fileChooserCallback
        val rawAcceptTypes = fileChooserParams?.acceptTypes?.filter { !it.isNullOrBlank() } ?: emptyList()
        val allowMultiple = fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        val acceptTypes = rawAcceptTypes.flatMap { it.split(",") }.map { it.trim().lowercase() }.filter { it.isNotBlank() }

        val isExplicitCapture = fileChooserParams?.isCaptureEnabled == true ||
                rawAcceptTypes.any { it.contains("capture=", ignoreCase = true) }

        val isVideoOnly = acceptTypes.isNotEmpty() && acceptTypes.all { it.contains("video") }
        val isImageOnly = acceptTypes.isNotEmpty() && acceptTypes.all {
            it.startsWith("image/") || it == ".jpg" || it == ".jpeg" || it == ".png" ||
                    it == ".webp" || it == ".gif" || it == ".bmp" || it == ".heic"
        }
        val isMediaOnly = acceptTypes.isNotEmpty() && acceptTypes.all {
            it.startsWith("image/") || it.startsWith("video/") ||
                    it == ".jpg" || it == ".jpeg" || it == ".png" || it == ".webp" ||
                    it == ".gif" || it == ".bmp" || it == ".heic" || it == ".mp4" ||
                    it == ".mkv" || it == ".mov" || it == ".webm" || it == ".3gp"
        }

        // CASE 1: DIRECT CAPTURE (Camera / Camcorder)
        if (isExplicitCapture) {
            if (isVideoOnly) {
                val videoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                return try {
                    onChooserPendingStateChanged(true)
                    onLaunchFileChooserIntent(videoIntent)
                    true
                } catch (t: Throwable) {
                    onChooserPendingStateChanged(false)
                    Log.e(TAG, "Failed to launch video capture", t)
                    safeCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    pendingFileChooserParams = null
                    true
                }
            } else {
                return try {
                    val photoUri = createCameraImageUri() ?: throw IllegalStateException("Unable to create camera image URI")

                    val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                        clipData = ClipData.newUri(activity.contentResolver, "camera_photo", photoUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    val resolvedActivities = activity.packageManager.queryIntentActivities(
                        Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                        0
                    )
                    for (resolved in resolvedActivities) {
                        val packageName = resolved.activityInfo?.packageName
                        if (!packageName.isNullOrBlank()) {
                            try {
                                activity.grantUriPermission(
                                    packageName,
                                    photoUri,
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            } catch (_: Throwable) {}
                        }
                    }

                    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        onChooserPendingStateChanged(true)
                        pendingCameraCaptureIntent = cameraIntent
                        onRequestCameraPermission()
                    } else {
                        onChooserPendingStateChanged(true)
                        onLaunchFileChooserIntent(cameraIntent)
                    }
                    true
                } catch (t: Throwable) {
                    onChooserPendingStateChanged(false)
                    Log.e(TAG, "Failed to launch camera capture", t)
                    pendingCameraPhotoFile?.delete()
                    pendingCameraPhotoFile = null
                    cameraImageUri = null
                    safeCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    pendingFileChooserParams = null
                    true
                }
            }
        }

        // CASE 2: MEDIA / GALLERY UPLOAD
        if (isMediaOnly) {
            val photoPickerSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
                    ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(activity)

            val galleryIntent = if (photoPickerSupported) {
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    if (isImageOnly) {
                        type = "image/*"
                    } else if (isVideoOnly) {
                        type = "video/*"
                    } else {
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    }
                    if (allowMultiple) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
                        } else {
                            putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 100)
                        }
                    }
                }
            } else {
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (isImageOnly) "image/*" else if (isVideoOnly) "video/*" else "*/*"
                    if (!isImageOnly && !isVideoOnly) {
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    }
                    if (allowMultiple) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
            }

            return try {
                onChooserPendingStateChanged(true)
                onLaunchFileChooserIntent(galleryIntent)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "Photo picker launch failed, falling back to GET_CONTENT", t)
                try {
                    val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = if (isImageOnly) "image/*" else if (isVideoOnly) "video/*" else "*/*"
                        if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    onLaunchFileChooserIntent(fallbackIntent)
                    true
                } catch (t2: Throwable) {
                    onChooserPendingStateChanged(false)
                    Log.e(TAG, "Failed to launch fallback gallery intent", t2)
                    safeCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    pendingFileChooserParams = null
                    true
                }
            }
        }

        // CASE 3: GENERIC FILE / DOCUMENT UPLOAD
        val contentIntent = try {
            fileChooserParams?.createIntent()
        } catch (_: Throwable) { null } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (acceptTypes.size == 1) acceptTypes[0] else "*/*"
            if (acceptTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
            }
        }

        if (allowMultiple) {
            try { contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) } catch (_: Throwable) {}
        }

        return try {
            onChooserPendingStateChanged(true)
            onLaunchFileChooserIntent(contentIntent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Direct content intent failed, trying chooser", t)
            try {
                val chooserTitle = fileChooserParams?.title?.takeIf { !it.isNullOrBlank() } ?: "Select file"
                val chooserIntent = Intent.createChooser(contentIntent, chooserTitle)
                onLaunchFileChooserIntent(chooserIntent)
                true
            } catch (t2: Throwable) {
                onChooserPendingStateChanged(false)
                Log.e(TAG, "Failed to launch document chooser", t2)
                safeCallback?.onReceiveValue(null)
                fileChooserCallback = null
                pendingFileChooserParams = null
                true
            }
        }
    }

    fun handleCameraPermissionResult(granted: Boolean) {
        onChooserPendingStateChanged(false)
        val cameraIntent = pendingCameraCaptureIntent
        pendingCameraCaptureIntent = null

        if (granted && cameraIntent != null) {
            try {
                onChooserPendingStateChanged(true)
                onLaunchFileChooserIntent(cameraIntent)
            } catch (t: Throwable) {
                onChooserPendingStateChanged(false)
                Log.e(TAG, "Failed to launch camera after permission granted", t)
                pendingCameraPhotoFile?.delete()
                pendingCameraPhotoFile = null
                cameraImageUri = null
                try {
                    fileChooserCallback?.onReceiveValue(null)
                } catch (_: Throwable) {}
                fileChooserCallback = null
                pendingFileChooserParams = null
            }
        } else {
            pendingCameraPhotoFile?.delete()
            pendingCameraPhotoFile = null
            cameraImageUri = null
            try {
                fileChooserCallback?.onReceiveValue(null)
            } catch (_: Throwable) {}
            fileChooserCallback = null
            pendingFileChooserParams = null
        }
    }

    @Suppress("DEPRECATION")
    fun handleActivityResult(resultCode: Int, data: Intent?) {
        onChooserPendingStateChanged(false)
        val callback = fileChooserCallback
        val photoUri = cameraImageUri
        val photoFile = pendingCameraPhotoFile

        fileChooserCallback = null
        pendingFileChooserParams = null
        cameraImageUri = null
        pendingCameraPhotoFile = null

        if (callback == null) {
            photoFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
            return
        }

        onActiveWebViewResume()

        if (resultCode == Activity.RESULT_OK) {
            val results = mutableListOf<Uri>()

            if (photoFile != null && photoFile.exists() && photoFile.length() > 0L && photoUri != null) {
                grantReadUriToWebViewPackages(photoUri)
                results.add(photoUri)
            } else if (photoFile != null && photoUri != null && data?.extras?.get("data") is Bitmap) {
                try {
                    val bitmap = data.extras!!.get("data") as Bitmap
                    photoFile.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    if (photoFile.length() > 0L) {
                        grantReadUriToWebViewPackages(photoUri)
                        results.add(photoUri)
                    }
                } catch (_: Throwable) {}
            }

            if (results.isEmpty()) {
                photoFile?.let { if (it.exists() && it.length() == 0L) it.delete() }

                if (data != null) {
                    val clipData = data.clipData
                    if (clipData != null && clipData.itemCount > 0) {
                        for (i in 0 until clipData.itemCount) {
                            clipData.getItemAt(i)?.uri?.let { results.add(it) }
                        }
                    } else if (data.data != null) {
                        results.add(data.data!!)
                    }
                }
            }

            try {
                if (results.isNotEmpty()) {
                    callback.onReceiveValue(results.toTypedArray())
                } else {
                    callback.onReceiveValue(null)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to deliver file chooser callback", t)
            }
        } else {
            photoFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
            try {
                callback.onReceiveValue(null)
            } catch (_: Throwable) {}
        }
    }

    fun clearPendingCallbacks() {
        try {
            fileChooserCallback?.onReceiveValue(null)
        } catch (_: Throwable) {}
        fileChooserCallback = null
        pendingFileChooserParams = null
        pendingCameraCaptureIntent = null
        pendingCameraPhotoFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
        pendingCameraPhotoFile = null
        cameraImageUri = null
    }

    private fun grantReadUriToWebViewPackages(uri: Uri) {
        for (pkg in WEBVIEW_PACKAGES) {
            try {
                activity.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "OnyxFileChooser"
        private val WEBVIEW_PACKAGES = listOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary"
        )
    }
}
