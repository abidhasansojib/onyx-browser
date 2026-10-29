package com.onyx.browser

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.provider.MediaStore
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import java.io.File
import com.onyx.browser.ui.home.AdjustQuickActionsBottomSheet
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.data.model.TabItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.data.search.SearchSuggestionRepository
import com.onyx.browser.databinding.ActivityMainBinding
import com.onyx.browser.ui.bookmarks.BookmarksActivity
import com.onyx.browser.ui.browser.FindInPageController
import com.onyx.browser.ui.browser.PageExportManager
import com.onyx.browser.ui.browser.PipController
import com.onyx.browser.ui.browser.SearchController
import com.onyx.browser.ui.browser.TabManager
import com.onyx.browser.ui.common.SearchEnginePickerDialog
import com.onyx.browser.ui.common.SearchEnginePopupMenu
import com.onyx.browser.ui.common.SearchEngineIconHelper
import com.onyx.browser.ui.downloads.DownloadsActivity
import com.onyx.browser.ui.history.HistoryActivity
import com.onyx.browser.ui.home.EditShortcutDialog
import com.onyx.browser.ui.home.ManageShortcutsBottomSheet
import com.onyx.browser.ui.home.ShortcutsAdapter
import com.onyx.browser.ui.menu.MenuBottomSheetDialogFragment
import com.onyx.browser.ui.menu.ContextMenuBottomSheet
import com.onyx.browser.ui.menu.LanguageSelectionDialog
import com.onyx.browser.ui.tabs.TabSwitcherBottomSheet
import com.onyx.browser.web.DownloadHandler
import com.onyx.browser.web.FormPostRegistry
import com.onyx.browser.web.LocalFileLoader
import com.onyx.browser.web.OnyxWebChromeClient
import com.onyx.browser.web.OnyxWebView
import com.onyx.browser.web.OnyxWebViewClient
import com.onyx.browser.data.filter.FilterListManager
import com.onyx.browser.download.DownloadNotificationHelper
import com.onyx.browser.media.MediaPlaybackBridge
import com.onyx.browser.media.MediaPlaybackService
import com.onyx.browser.web.DevToolsManager
import com.onyx.browser.web.MediaPlaybackManager
import com.onyx.browser.web.translate.PageTranslateManager
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.web.TabActionCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), TabActionCallback {

    private lateinit var binding: ActivityMainBinding
    lateinit var tabManager: TabManager
    private lateinit var preferences: BrowserPreferences
    private lateinit var pageExportManager: PageExportManager
    private lateinit var findInPageController: FindInPageController
    private lateinit var searchController: SearchController

    fun getActiveWebView(): OnyxWebView? = if (::tabManager.isInitialized) tabManager.getActiveWebView() else null
    private lateinit var shortcutsAdapter: ShortcutsAdapter

    lateinit var pipController: PipController
    val customVideoView: View?
        get() = if (::pipController.isInitialized) pipController.customVideoView else null
    val customViewCallback: WebChromeClient.CustomViewCallback?
        get() = if (::pipController.isInitialized) pipController.customViewCallback else null
    var justExitedPip: Boolean
        get() = if (::pipController.isInitialized) pipController.justExitedPip else false
        set(value) {
            if (::pipController.isInitialized) pipController.justExitedPip = value
        }
    var isCurrentlyInPip: Boolean
        get() = MediaPlaybackBridge.isCurrentlyInPip
        set(value) {
            MediaPlaybackBridge.isCurrentlyInPip = value
            if (::pipController.isInitialized) pipController.isCurrentlyInPip = value
        }
    private var currentDisplayedTabId: String? = null
    private var isTabSwitching = false
    private var isTabsRestored = false
    private var pendingIntent: Intent? = null
    private var lastThemeMode: Int = -1
    private var lastNightMode: Int = -1
    private var lastAccentColor: String = ""
    private var pendingNightModeRecreate: Boolean = false
    private var isLaunchingChildActivity: Boolean = false
    private var isToolbarHidden = false
    private var toolbarAnimator: android.animation.ValueAnimator? = null
    private var lastKnownTopBarHeight: Int = 0
    private var lastStatusBarTopInset: Int = 0

    private fun isInPipMode(): Boolean {
        return isCurrentlyInPip ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ::pipController.isInitialized) {
            pipController.onFullscreenUserInteraction()
        }
        return super.dispatchTouchEvent(ev)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        isLaunchingChildActivity = true
        if (::pipController.isInitialized) {
            pipController.disarmAutoEnter()
        }
        super.startActivityForResult(intent, requestCode, options)
    }

    companion object {
        var currentInstance: java.lang.ref.WeakReference<MainActivity>? = null
        const val ACTION_PIP_PLAY_PAUSE = "com.onyx.browser.action.PIP_PLAY_PAUSE"
        const val ACTION_PIP_REWIND = "com.onyx.browser.action.PIP_REWIND"
        const val ACTION_PIP_FORWARD = "com.onyx.browser.action.PIP_FORWARD"
        const val ACTION_WIDGET_SEARCH = "com.onyx.browser.action.WIDGET_SEARCH"
        const val ACTION_WIDGET_VOICE_SEARCH = "com.onyx.browser.action.WIDGET_VOICE_SEARCH"
        const val ACTION_WIDGET_INCOGNITO_SEARCH = "com.onyx.browser.action.WIDGET_INCOGNITO_SEARCH"
        const val EXTRA_FROM_DOWNLOADS = "com.onyx.browser.extra.FROM_DOWNLOADS"
    }

    private fun getMediaTargetWebView(): OnyxWebView? {
        val playingWv = MediaPlaybackBridge.currentPlayingWebView?.get() as? OnyxWebView
        if (playingWv != null) return playingWv
        val playingId = MediaPlaybackBridge.currentPlayingTabId
        if (!playingId.isNullOrBlank()) {
            val wv = tabManager.getWebView(playingId)
            if (wv != null) return wv
        }
        return tabManager.getActiveWebView()
    }


    private var lastNavBarBottomInset: Int = 0
    // Permission Launchers
    private var pendingStorageAction: (() -> Unit)? = null
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingStorageAction?.invoke()
        } else {
            Toast.makeText(this, "Storage permission required to download files", Toast.LENGTH_SHORT).show()
        }
        pendingStorageAction = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        isSystemDialogOrChooserPending = false
    }

    private val voiceSearchMicLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVoiceSearchIntent()
        } else {
            Toast.makeText(this, "Microphone permission required for voice search", Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoRemember: Boolean = true
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        isSystemDialogOrChooserPending = false
        val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val granted = fine || coarse
        pendingGeoOrigin?.let { origin ->
            val host = try {
                Uri.parse(origin).host?.takeIf { it.isNotBlank() } ?: origin
            } catch (_: Exception) {
                origin
            }
            if (pendingGeoRemember && granted) {
                preferences.setWebLocationAllowed(host, true)
            }
        }
        try {
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, pendingGeoRemember)
        } catch (_: Exception) {}
        pendingGeoOrigin = null
        pendingGeoCallback = null
        pendingGeoRemember = true
    }

    private fun promptWebPermission(
        host: String,
        title: String,
        message: String,
        iconResId: Int = R.drawable.ic_lock,
        isHttps: Boolean = true,
        onAllowedAlways: () -> Unit,
        onAllowedWhileVisiting: () -> Unit,
        onDenied: (permanently: Boolean) -> Unit
    ) {
        if (isFinishing || isDestroyed) {
            onDenied(false)
            return
        }
        val sheet = com.onyx.browser.ui.dialog.WebPermissionBottomSheetDialog(
            host = host,
            permissionTitle = title,
            permissionDesc = message,
            iconResId = iconResId,
            isHttps = isHttps,
            onAllowedAlways = onAllowedAlways,
            onAllowedOnce = onAllowedWhileVisiting,
            onDenied = onDenied
        )
        sheet.show(supportFragmentManager, com.onyx.browser.ui.dialog.WebPermissionBottomSheetDialog.TAG)
    }

    private fun handleGeolocationPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        if (origin == null || callback == null) return
        val host = try {
            Uri.parse(origin).host?.takeIf { it.isNotBlank() } ?: origin
        } catch (_: Exception) {
            origin
        }

        if (!preferences.isLocationEnabledGlobal) {
            try { callback.invoke(origin, false, true) } catch (_: Exception) {}
            return
        }

        val locAllowed = preferences.isWebLocationAllowed(host)
        if (locAllowed == false) {
            try { callback.invoke(origin, false, true) } catch (_: Exception) {}
            return
        }

        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (locAllowed == true && (fine || coarse)) {
            try { callback.invoke(origin, true, true) } catch (_: Exception) {}
            return
        }

        if (isFinishing || isDestroyed) {
            try { callback.invoke(origin, false, false) } catch (_: Exception) {}
            return
        }

        if (locAllowed == null) {
            val isHttps = origin.startsWith("https://", ignoreCase = true)
            promptWebPermission(
                host = host,
                title = "$host wants to access your location",
                message = "Allow $host to access your precise location?",
                iconResId = R.drawable.ic_location,
                isHttps = isHttps,
                onAllowedAlways = {
                    if (fine || coarse) {
                        preferences.setWebLocationAllowed(host, true)
                        try { callback.invoke(origin, true, true) } catch (_: Exception) {}
                    } else {
                        pendingGeoOrigin = origin
                        pendingGeoCallback = callback
                        pendingGeoRemember = true
                        isSystemDialogOrChooserPending = true
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                onAllowedWhileVisiting = {
                    if (fine || coarse) {
                        try { callback.invoke(origin, true, false) } catch (_: Exception) {}
                    } else {
                        pendingGeoOrigin = origin
                        pendingGeoCallback = callback
                        pendingGeoRemember = false
                        isSystemDialogOrChooserPending = true
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                onDenied = { permanently ->
                    if (permanently) {
                        preferences.setWebLocationAllowed(host, false)
                    }
                    try { callback.invoke(origin, false, permanently) } catch (_: Exception) {}
                }
            )
            return
        }

        if (fine || coarse) {
            preferences.setWebLocationAllowed(host, true)
            try { callback.invoke(origin, true, true) } catch (_: Exception) {}
        } else {
            pendingGeoOrigin = origin
            pendingGeoCallback = callback
            isSystemDialogOrChooserPending = true
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private var isSystemDialogOrChooserPending: Boolean = false

    internal fun onCredentialManagerDialogStart() {
        isSystemDialogOrChooserPending = true
        if (::pipController.isInitialized) {
            pipController.disarmAutoEnter()
        }
    }

    internal fun onCredentialManagerDialogEnd() {
        isSystemDialogOrChooserPending = false
        updatePipParams()
    }
    private val handledWebRequests = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<PermissionRequest, Boolean>())
    )
    private var pendingWebMediaRequest: PermissionRequest? = null
    private var pendingWebMediaResources: Array<String>? = null
    private var pendingWebMediaNeedsAudio: Boolean = false
    private var pendingWebMediaNeedsVideo: Boolean = false
    private var pendingWebMediaHost: String? = null
    private var pendingWebMediaRemember: Boolean = true

    private fun safeGrantWebPermission(request: PermissionRequest, resources: Array<String>) {
        if (handledWebRequests.contains(request)) return
        handledWebRequests.add(request)
        if (isFinishing || isDestroyed) {
            try { request.deny() } catch (_: Throwable) {}
            return
        }
        val requestedSet = request.resources?.toSet() ?: emptySet()
        val validResources = resources.filter { requestedSet.contains(it) }.toTypedArray()
        if (validResources.isEmpty()) {
            try { request.deny() } catch (_: Throwable) {}
            return
        }
        runOnUiThread {
            try {
                tabManager.getActiveWebView()?.onResume()
                request.grant(validResources)
            } catch (t: Throwable) {
                android.util.Log.e("OnyxPermissions", "Failed to grant web media permissions", t)
                try { request.deny() } catch (_: Throwable) {}
            }
        }
    }

    private fun safeDenyWebPermission(request: PermissionRequest) {
        if (handledWebRequests.contains(request)) return
        handledWebRequests.add(request)
        runOnUiThread {
            try {
                request.deny()
            } catch (_: Exception) {}
        }
    }

    private val webMediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        isSystemDialogOrChooserPending = false
        val request = pendingWebMediaRequest
        val allResources = pendingWebMediaResources
        val needsAudio = pendingWebMediaNeedsAudio
        val needsVideo = pendingWebMediaNeedsVideo
        val host = pendingWebMediaHost ?: ""
        val remember = pendingWebMediaRemember

        pendingWebMediaRequest = null
        pendingWebMediaResources = null
        pendingWebMediaNeedsAudio = false
        pendingWebMediaNeedsVideo = false
        pendingWebMediaHost = null
        pendingWebMediaRemember = true

        if (request == null || allResources == null) return@registerForActivityResult

        val audioGranted = !needsAudio || (permissions[Manifest.permission.RECORD_AUDIO] == true) ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val videoGranted = !needsVideo || (permissions[Manifest.permission.CAMERA] == true) ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        val grantedList = mutableListOf<String>()
        if (needsAudio && audioGranted) {
            grantedList.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            if (remember && host.isNotBlank()) preferences.setWebMicAllowed(host, true)
        }
        if (needsVideo && videoGranted) {
            grantedList.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            if (remember && host.isNotBlank()) preferences.setWebCameraAllowed(host, true)
        }
        for (res in allResources) {
            if (res != PermissionRequest.RESOURCE_AUDIO_CAPTURE && res != PermissionRequest.RESOURCE_VIDEO_CAPTURE) {
                grantedList.add(res)
            }
        }

        if (grantedList.isNotEmpty()) {
            safeGrantWebPermission(request, grantedList.toTypedArray())
        } else {
            safeDenyWebPermission(request)
        }
    }

    private fun handleWebPermissionRequest(request: PermissionRequest?) {
        if (request == null) return
        if (handledWebRequests.contains(request)) return

        val resources = request.resources ?: emptyArray()
        if (resources.isEmpty()) {
            safeDenyWebPermission(request)
            return
        }

        val needsAudio = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        val needsVideo = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)

        // If no hardware sensors (microphone/camera) requested — check for Protected Media ID (DRM/EME)
        if (!needsAudio && !needsVideo) {
            val needsProtectedMedia = resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
            if (needsProtectedMedia && !preferences.isProtectedContentEnabledGlobal) {
                safeDenyWebPermission(request)
            } else {
                safeGrantWebPermission(request, resources)
            }
            return
        }

        val origin = request.origin?.toString() ?: ""
        val host = try {
            val uri = Uri.parse(origin)
            uri.host?.takeIf { it.isNotBlank() } ?: origin
        } catch (_: Exception) {
            origin
        }

        if ((needsVideo && !preferences.isCameraEnabledGlobal) || (needsAudio && !preferences.isMicEnabledGlobal)) {
            safeDenyWebPermission(request)
            return
        }

        val camAllowed = if (needsVideo) preferences.isWebCameraAllowed(host) else true
        val micAllowed = if (needsAudio) preferences.isWebMicAllowed(host) else true

        if (camAllowed == false && micAllowed == false) {
            safeDenyWebPermission(request)
            return
        }

        if (camAllowed == true && micAllowed == true) {
            grantWebMediaPermission(request, needsAudio, needsVideo, resources, host, remember = false)
            return
        }

        if (camAllowed == true && micAllowed == false) {
            grantWebMediaPermission(request, needsAudio = false, needsVideo = true, arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE), host, remember = false)
            return
        }
        if (camAllowed == false && micAllowed == true) {
            grantWebMediaPermission(request, needsAudio = true, needsVideo = false, arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE), host, remember = false)
            return
        }

        if (isFinishing || isDestroyed) {
            safeDenyWebPermission(request)
            return
        }

        val promptForCam = needsVideo && camAllowed == null
        val promptForMic = needsAudio && micAllowed == null

        val promptItems = mutableListOf<String>()
        if (promptForCam) promptItems.add("Camera")
        if (promptForMic) promptItems.add("Microphone")
        val itemsStr = promptItems.joinToString(" and ")
        val icon = when {
            promptForCam && promptForMic -> R.drawable.ic_camera
            promptForCam -> R.drawable.ic_camera
            else -> R.drawable.ic_mic
        }
        val isHttps = origin.startsWith("https://", ignoreCase = true)

        promptWebPermission(
            host = host,
            title = "$host wants to access your $itemsStr",
            message = "Allow $host to access your $itemsStr?",
            iconResId = icon,
            isHttps = isHttps,
            onAllowedAlways = {
                if (promptForCam) preferences.setWebCameraAllowed(host, true)
                if (promptForMic) preferences.setWebMicAllowed(host, true)
                val grantAudio = (needsAudio && micAllowed == true) || promptForMic
                val grantVideo = (needsVideo && camAllowed == true) || promptForCam
                val toGrant = mutableListOf<String>()
                if (grantAudio) toGrant.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (grantVideo) toGrant.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                grantWebMediaPermission(request, grantAudio, grantVideo, toGrant.toTypedArray(), host, remember = false)
            },
            onAllowedWhileVisiting = {
                val grantAudio = (needsAudio && micAllowed == true) || promptForMic
                val grantVideo = (needsVideo && camAllowed == true) || promptForCam
                val toGrant = mutableListOf<String>()
                if (grantAudio) toGrant.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (grantVideo) toGrant.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                grantWebMediaPermission(request, grantAudio, grantVideo, toGrant.toTypedArray(), host, remember = false)
            },
            onDenied = { permanently ->
                if (permanently) {
                    if (promptForCam) preferences.setWebCameraAllowed(host, false)
                    if (promptForMic) preferences.setWebMicAllowed(host, false)
                }
                val grantAudio = needsAudio && micAllowed == true
                val grantVideo = needsVideo && camAllowed == true
                if (grantAudio || grantVideo) {
                    val toGrant = mutableListOf<String>()
                    if (grantAudio) toGrant.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (grantVideo) toGrant.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    grantWebMediaPermission(request, grantAudio, grantVideo, toGrant.toTypedArray(), host, remember = false)
                } else {
                    safeDenyWebPermission(request)
                }
            }
        )
    }

    private fun grantWebMediaPermission(
        request: PermissionRequest,
        needsAudio: Boolean,
        needsVideo: Boolean,
        allResources: Array<String>,
        host: String,
        remember: Boolean
    ) {
        val hasAudio = !needsAudio || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasVideo = !needsVideo || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        if (hasAudio && hasVideo) {
            if (remember) {
                if (needsVideo) preferences.setWebCameraAllowed(host, true)
                if (needsAudio) preferences.setWebMicAllowed(host, true)
            }
            safeGrantWebPermission(request, allResources)
        } else {
            val neededOs = mutableListOf<String>()
            if (needsAudio && !hasAudio) neededOs.add(Manifest.permission.RECORD_AUDIO)
            if (needsVideo && !hasVideo) neededOs.add(Manifest.permission.CAMERA)

            pendingWebMediaRequest = request
            pendingWebMediaResources = allResources
            pendingWebMediaNeedsAudio = needsAudio
            pendingWebMediaNeedsVideo = needsVideo
            pendingWebMediaHost = host
            pendingWebMediaRemember = remember

            isSystemDialogOrChooserPending = true
            webMediaPermissionLauncher.launch(neededOs.toTypedArray())
        }
    }

    private fun handleWebPermissionRequestCanceled(request: PermissionRequest?) {
        if (request != null) {
            handledWebRequests.add(request)
            if (pendingWebMediaRequest == request) {
                pendingWebMediaRequest = null
                pendingWebMediaResources = null
                pendingWebMediaNeedsAudio = false
                pendingWebMediaNeedsVideo = false
                pendingWebMediaHost = null
            }
        }
    }

    fun requestNotificationPermissionForSite(host: String, callback: (String) -> Unit) {
        if (host.isBlank() || isFinishing || isDestroyed) {
            callback("denied")
            return
        }
        val notifPerm = preferences.isWebNotificationsAllowed(host)
        if (notifPerm == true) {
            callback("granted")
            return
        }
        if (notifPerm == false || !preferences.isNotificationsEnabledGlobal) {
            callback("denied")
            return
        }
        promptWebPermission(
            host = host,
            title = "$host wants to send notifications",
            message = "Allow $host to send you browser notifications?",
            iconResId = R.drawable.ic_bell,
            isHttps = true,
            onAllowedAlways = {
                preferences.setSiteNotificationsPermission(host, com.onyx.browser.data.preferences.SitePermissionState.ALLOWED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    isSystemDialogOrChooserPending = true
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                callback("granted")
            },
            onAllowedWhileVisiting = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    isSystemDialogOrChooserPending = true
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                callback("granted")
            },
            onDenied = { permanently ->
                if (permanently) {
                    preferences.setSiteNotificationsPermission(host, com.onyx.browser.data.preferences.SitePermissionState.BLOCKED)
                }
                callback("denied")
            }
        )
    }

    // ── File Chooser Subsystem (File uploads, Gallery selection & Photo capture) ──
    private class SafeValueCallback<T>(
        private val delegate: ValueCallback<T>?
    ) : ValueCallback<T> {
        private val completed = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun onReceiveValue(value: T?) {
            if (completed.compareAndSet(false, true)) {
                try {
                    delegate?.onReceiveValue(value)
                } catch (t: Throwable) {
                    android.util.Log.e("OnyxFileChooser", "Error delivering callback", t)
                }
            }
        }
    }

    private var pendingFilePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraPhotoUri: Uri? = null
    private var pendingCameraPhotoFile: File? = null
    private var pendingCameraCaptureIntent: Intent? = null

    private val fileChooserCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        isSystemDialogOrChooserPending = false
        val cameraIntent = pendingCameraCaptureIntent
        pendingCameraCaptureIntent = null

        if (granted && cameraIntent != null) {
            try {
                isSystemDialogOrChooserPending = true
                fileChooserLauncher.launch(cameraIntent)
            } catch (t: Throwable) {
                isSystemDialogOrChooserPending = false
                android.util.Log.e("OnyxFileChooser", "Failed to launch camera after permission granted", t)
                pendingCameraPhotoFile?.delete()
                pendingCameraPhotoFile = null
                pendingCameraPhotoUri = null
                try {
                    pendingFilePathCallback?.onReceiveValue(null)
                } catch (_: Throwable) {}
                pendingFilePathCallback = null
            }
        } else {
            // Permission denied or dismissed
            pendingCameraPhotoFile?.delete()
            pendingCameraPhotoFile = null
            pendingCameraPhotoUri = null
            try {
                pendingFilePathCallback?.onReceiveValue(null)
            } catch (_: Throwable) {}
            pendingFilePathCallback = null
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isSystemDialogOrChooserPending = false
        val callback = pendingFilePathCallback
        val photoUri = pendingCameraPhotoUri
        val photoFile = pendingCameraPhotoFile

        pendingFilePathCallback = null
        pendingCameraPhotoUri = null
        pendingCameraPhotoFile = null

        if (callback == null) {
            photoFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
            return@registerForActivityResult
        }

        tabManager.getActiveWebView()?.onResume()

        if (result.resultCode == Activity.RESULT_OK) {
            val results = mutableListOf<Uri>()

            // 1. Check if camera photo was captured to file
            if (photoFile != null && photoFile.exists() && photoFile.length() > 0L && photoUri != null) {
                val webViewPackages = listOf(
                    "com.google.android.webview",
                    "com.android.webview",
                    "com.android.chrome",
                    "com.chrome.beta",
                    "com.chrome.dev",
                    "com.chrome.canary"
                )
                for (pkg in webViewPackages) {
                    try {
                        grantUriPermission(pkg, photoUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Throwable) {}
                }
                results.add(photoUri)
            } else if (photoFile != null && photoUri != null && result.data?.extras?.get("data") is Bitmap) {
                // Safeguard: some camera apps return thumbnail bitmap in 'data' extra instead of writing to EXTRA_OUTPUT
                try {
                    val bitmap = result.data!!.extras!!.get("data") as Bitmap
                    photoFile.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    if (photoFile.length() > 0L) {
                        val webViewPackages = listOf(
                            "com.google.android.webview",
                            "com.android.webview",
                            "com.android.chrome",
                            "com.chrome.beta",
                            "com.chrome.dev",
                            "com.chrome.canary"
                        )
                        for (pkg in webViewPackages) {
                            try {
                                grantUriPermission(pkg, photoUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            } catch (_: Throwable) {}
                        }
                        results.add(photoUri)
                    }
                } catch (_: Throwable) {}
            }

            // 2. If camera file was not used, check intent data from Gallery / Document picker
            if (results.isEmpty()) {
                photoFile?.let { if (it.exists() && it.length() == 0L) it.delete() }

                val data = result.data
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
                android.util.Log.e("OnyxFileChooser", "Failed to deliver file chooser callback", t)
            }
        } else {
            // Cancelled or dismissed
            photoFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
            try {
                callback.onReceiveValue(null)
            } catch (_: Throwable) {}
        }
    }

    private fun handleShowFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?
    ): Boolean {
        if (filePathCallback == null) return false

        // Cancel previous pending callback safely without crashing
        try { pendingFilePathCallback?.onReceiveValue(null) } catch (_: Throwable) {}
        val safeCallback = SafeValueCallback(filePathCallback)
        pendingFilePathCallback = safeCallback
        pendingCameraPhotoFile?.delete()
        pendingCameraPhotoFile = null
        pendingCameraPhotoUri = null
        pendingCameraCaptureIntent = null

        val rawAcceptTypes = fileChooserParams?.acceptTypes?.filter { !it.isNullOrBlank() } ?: emptyList()
        val allowMultiple = fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        // Flatten and clean accept types (handles comma-separated types in single entry e.g. "image/*,video/*")
        val acceptTypes = rawAcceptTypes.flatMap { it.split(",") }.map { it.trim().lowercase() }.filter { it.isNotBlank() }

        // Detect if camera/video capture was requested directly:
        // Chromium sets isCaptureEnabled = true when HTML input contains `capture`, `capture="camera"`, `capture="environment"`, etc.
        // Also check if web content declared capture syntax inside accept types.
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

        // =========================================================================
        // CASE 1: DIRECT CAPTURE (Tapping Camera / Camcorder button on website)
        // Behaves like Chrome/Brave: Directly opens Camera or Camcorder without gallery or chooser prompt!
        // =========================================================================
        if (isExplicitCapture) {
            if (isVideoOnly) {
                // Video capture requested
                val videoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                return try {
                    isSystemDialogOrChooserPending = true
                    fileChooserLauncher.launch(videoIntent)
                    true
                } catch (t: Throwable) {
                    isSystemDialogOrChooserPending = false
                    android.util.Log.e("OnyxFileChooser", "Failed to launch video capture", t)
                    safeCallback.onReceiveValue(null)
                    pendingFilePathCallback = null
                    true
                }
            } else {
                // Photo capture requested (default for image capture or general capture)
                return try {
                    val cameraDir = File(cacheDir, "camera_uploads")
                    if (!cameraDir.exists()) cameraDir.mkdirs()
                    val photoFile = File(cameraDir, "IMG_${System.currentTimeMillis()}.jpg")
                    val photoUri = androidx.core.content.FileProvider.getUriForFile(
                        this,
                        "${applicationContext.packageName}.fileprovider",
                        photoFile
                    )
                    pendingCameraPhotoFile = photoFile
                    pendingCameraPhotoUri = photoUri

                    val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                        clipData = ClipData.newUri(contentResolver, "camera_photo", photoUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    // Grant URI permission to all potential camera packages matching the action
                    val resolvedActivities = packageManager.queryIntentActivities(
                        Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                        0
                    )
                    for (resolved in resolvedActivities) {
                        val packageName = resolved.activityInfo?.packageName
                        if (!packageName.isNullOrBlank()) {
                            try {
                                grantUriPermission(
                                    packageName,
                                    photoUri,
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            } catch (_: Throwable) {}
                        }
                    }

                    // Check runtime camera permission
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        isSystemDialogOrChooserPending = true
                        pendingCameraCaptureIntent = cameraIntent
                        fileChooserCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    } else {
                        isSystemDialogOrChooserPending = true
                        fileChooserLauncher.launch(cameraIntent)
                    }
                    true
                } catch (t: Throwable) {
                    isSystemDialogOrChooserPending = false
                    android.util.Log.e("OnyxFileChooser", "Failed to launch camera capture", t)
                    pendingCameraPhotoFile?.delete()
                    pendingCameraPhotoFile = null
                    pendingCameraPhotoUri = null
                    safeCallback.onReceiveValue(null)
                    pendingFilePathCallback = null
                    true
                }
            }
        }

        // =========================================================================
        // CASE 2: MEDIA / GALLERY UPLOAD (Tapping Gallery / Photo button on website)
        // Behaves like Chrome/Brave: Directly opens Android Photo Picker / Gallery!
        // NEVER shows an action chooser asking "Camera or Gallery" because user tapped Gallery!
        // =========================================================================
        if (isMediaOnly) {
            val photoPickerSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
                    ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)

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
                isSystemDialogOrChooserPending = true
                fileChooserLauncher.launch(galleryIntent)
                true
            } catch (t: Throwable) {
                android.util.Log.w("OnyxFileChooser", "Photo picker launch failed, falling back to GET_CONTENT", t)
                try {
                    val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = if (isImageOnly) "image/*" else if (isVideoOnly) "video/*" else "*/*"
                        if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    fileChooserLauncher.launch(fallbackIntent)
                    true
                } catch (t2: Throwable) {
                    isSystemDialogOrChooserPending = false
                    android.util.Log.e("OnyxFileChooser", "Failed to launch fallback gallery intent", t2)
                    safeCallback.onReceiveValue(null)
                    pendingFilePathCallback = null
                    true
                }
            }
        }

        // =========================================================================
        // CASE 3: GENERIC FILE / DOCUMENT UPLOAD (<input type="file">, pdf, zip, etc.)
        // Behaves like Chrome/Brave: Directly opens Android System File / Document Picker!
        // =========================================================================
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
            isSystemDialogOrChooserPending = true
            fileChooserLauncher.launch(contentIntent)
            true
        } catch (t: Throwable) {
            android.util.Log.w("OnyxFileChooser", "Direct content intent failed, trying chooser", t)
            try {
                val chooserTitle = fileChooserParams?.title?.takeIf { !it.isNullOrBlank() } ?: "Select file"
                val chooserIntent = Intent.createChooser(contentIntent, chooserTitle)
                fileChooserLauncher.launch(chooserIntent)
                true
            } catch (t2: Throwable) {
                isSystemDialogOrChooserPending = false
                android.util.Log.e("OnyxFileChooser", "Failed to launch document chooser", t2)
                safeCallback.onReceiveValue(null)
                pendingFilePathCallback = null
                true
            }
        }
    }

    // Voice Search Result Launcher
    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val query = matches[0]
                binding.etUrl.setText(query)
                performSearchOrLoad(query)
            }
        }
    }

    // Bookmarks URL Result Launcher
    private val bookmarksLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val url = result.data?.getStringExtra(BookmarksActivity.EXTRA_URL)
            val action = result.data?.getStringExtra(BookmarksActivity.EXTRA_ACTION) ?: BookmarksActivity.ACTION_OPEN
            val title = result.data?.getStringExtra(BookmarksActivity.EXTRA_TITLE)
            val isBookmarklet = result.data?.getBooleanExtra(BookmarksActivity.EXTRA_IS_BOOKMARKLET, false) == true ||
                    url?.trim()?.startsWith("javascript:", ignoreCase = true) == true

            if (!url.isNullOrBlank()) {
                when {
                    isBookmarklet || action == BookmarksActivity.ACTION_RUN_BOOKMARKLET -> {
                        executeBookmarklet(url, title)
                    }
                    action == BookmarksActivity.ACTION_OPEN_NEW_TAB -> {
                        val newTab = tabManager.createNewTab(url, isIncognito = false)
                        tabManager.selectTab(newTab)
                        currentDisplayedTabId = newTab.id
                        updateTabBadgeCount()
                        showWebView(newTab, forceUrl = url)
                    }
                    action == BookmarksActivity.ACTION_OPEN_INCOGNITO -> {
                        val newTab = tabManager.createNewTab(url, isIncognito = true)
                        tabManager.selectTab(newTab)
                        currentDisplayedTabId = newTab.id
                        updateTabBadgeCount()
                        showWebView(newTab, forceUrl = url)
                    }
                    else -> {
                        performSearchOrLoad(url)
                    }
                }
            }
        }
    }

    // History URL Result Launcher
    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val url = result.data?.getStringExtra(HistoryActivity.EXTRA_URL)
            if (!url.isNullOrBlank()) {
                performSearchOrLoad(url)
            }
        }
    }

    fun executeBookmarklet(scriptOrUrl: String, title: String? = null) {
        val webView = tabManager.getActiveWebView()
        if (webView == null) {
            Toast.makeText(this, R.string.no_active_page_for_bookmarklet, Toast.LENGTH_SHORT).show()
            return
        }

        var script = if (scriptOrUrl.trim().startsWith("javascript:", ignoreCase = true)) {
            scriptOrUrl.trim().substring(11).trim()
        } else {
            scriptOrUrl.trim()
        }

        if (script.contains("%")) {
            try {
                val protectedScript = script.replace("+", "%2B")
                script = java.net.URLDecoder.decode(protectedScript, "UTF-8")
            } catch (_: Exception) {
                // Fallback to raw script
            }
        }

        if (script.isBlank()) return

        val displayName = if (!title.isNullOrBlank()) title else getString(R.string.bookmarklet_badge)
        Toast.makeText(this, getString(R.string.running_bookmarklet, displayName), Toast.LENGTH_SHORT).show()

        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }

        if (searchController.isSearchMode) {
            searchController.exitSearchMode()
        }
    }

    fun openBookmarks() {
        bookmarksLauncher.launch(Intent(this, BookmarksActivity::class.java))
    }

    fun openHistory() {
        historyLauncher.launch(Intent(this, HistoryActivity::class.java))
    }

    fun openDownloads() {
        startActivity(Intent(this, DownloadsActivity::class.java))
    }

    // QR Scanner Result Launcher
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val value = result.data?.getStringExtra(com.onyx.browser.ui.qr.QrScannerActivity.RESULT_QR_VALUE)
            if (!value.isNullOrBlank()) {
                performSearchOrLoad(value)
            }
        }
    }

    private val previewTabsFromDownloads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val previewTabsFromExternal = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val localFileViewTabs = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun isLocalFileView(tab: TabItem?): Boolean {
        if (tab == null) return false
        val activeWv = tabManager.getActiveWebView()
        val currentWvUrl = activeWv?.url
        if (currentWvUrl != null && (currentWvUrl.startsWith("http://", ignoreCase = true) || currentWvUrl.startsWith("https://", ignoreCase = true))) {
            return false
        }
        val tabUrl = tab.url
        if (tabUrl.startsWith("http://", ignoreCase = true) || tabUrl.startsWith("https://", ignoreCase = true)) {
            return false
        }
        return localFileViewTabs.contains(tab.id) ||
                previewTabsFromDownloads.contains(tab.id) ||
                previewTabsFromExternal.contains(tab.id) ||
                com.onyx.browser.web.LocalFileLoader.isLocalFile(this, tabUrl) ||
                com.onyx.browser.web.LocalFileLoader.isPreviewUrl(tabUrl) ||
                (currentWvUrl != null && (com.onyx.browser.web.LocalFileLoader.isLocalFile(this, currentWvUrl) || com.onyx.browser.web.LocalFileLoader.isPreviewUrl(currentWvUrl)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentColorManager.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)
        currentInstance = java.lang.ref.WeakReference(this)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        enforceHighRefreshRate()
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !isNightMode
        
        preferences = BrowserPreferences.getInstance(this)
        preferences.applyTheme()
        lastThemeMode = preferences.themeMode
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        lastAccentColor = preferences.accentColorHex
        com.onyx.browser.download.OnyxDownloadManager.init(this)
        pageExportManager = PageExportManager(this, lifecycleScope)
        tabManager = TabManager(this, lifecycleScope)
        tabManager.onTabClosedListener = { tab ->
            previewTabsFromDownloads.remove(tab.id)
            previewTabsFromExternal.remove(tab.id)
            localFileViewTabs.remove(tab.id)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val accentTint = AccentColorManager.getAccentColorStateList(this)
        binding.progressBar.progressTintList = accentTint
        binding.ivTranslateIcon.imageTintList = accentTint
        binding.pbTranslateLoading.indeterminateTintList = accentTint

        // Handle Scroll to Top FAB
        binding.fabScrollToTop.setOnClickListener {
            val wv = tabManager.getActiveWebView()
            wv?.scrollTo(0, 0)
            binding.fabScrollToTop.visibility = android.view.View.GONE
            showToolbar(animated = true)
        }
        
        // Handle edge-to-edge system bars (Status Bar & Gesture Nav Bar)
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, windowInsets ->
            if (isInPipMode()) {
                binding.statusBarSpace.visibility = View.GONE
                binding.contentContainer.setPadding(0, 0, 0, 0)
                return@setOnApplyWindowInsetsListener windowInsets
            }

            val statusBarInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            if (customVideoView == null) {
                lastNavBarBottomInset = navBarInsets.bottom
                lastStatusBarTopInset = statusBarInsets.top
            } else {
                if (navBarInsets.bottom > 0) lastNavBarBottomInset = navBarInsets.bottom
                if (statusBarInsets.top > 0) lastStatusBarTopInset = statusBarInsets.top
                binding.statusBarSpace.visibility = View.GONE
                binding.contentContainer.setPadding(0, 0, 0, 0)
                return@setOnApplyWindowInsetsListener windowInsets
            }

            val statusBarHeight = statusBarInsets.top
            val spaceLp = binding.statusBarSpace.layoutParams
            if (spaceLp != null && spaceLp.height != statusBarHeight) {
                spaceLp.height = statusBarHeight
                binding.statusBarSpace.layoutParams = spaceLp
            }
            binding.statusBarSpace.visibility = if (statusBarHeight > 0) View.VISIBLE else View.GONE

            val bottomPaddingPx = (8 * resources.displayMetrics.density).toInt()
            val topPaddingPx = (6 * resources.displayMetrics.density).toInt()
            binding.topBar.setPadding(
                binding.topBar.paddingLeft,
                topPaddingPx,
                binding.topBar.paddingRight,
                bottomPaddingPx
            )
            binding.topBar.post {
                if (binding.topBar.height > 0) {
                    lastKnownTopBarHeight = binding.topBar.height
                }
            }

            binding.contentContainer.setPadding(
                binding.contentContainer.paddingLeft,
                binding.contentContainer.paddingTop,
                binding.contentContainer.paddingRight,
                navBarInsets.bottom
            )
            windowInsets
        }
        
        binding.swipeRefreshLayout.setOnRefreshListener { 
            val activeTab = tabManager.activeTab.value
            val wv = tabManager.getActiveWebView()
            val failingUrl = wv?.currentSyntheticState?.failingUrl ?: wv?.lastFailingUrl
            if (!failingUrl.isNullOrBlank() && wv != null) {
                wv.clearSyntheticState()
                wv.loadUrl(failingUrl)
                binding.swipeRefreshLayout.isRefreshing = false
            } else if (activeTab != null && wv != null && LocalFileLoader.isLocalFile(this, activeTab.url)) {
                LocalFileLoader.loadLocalFile(this, wv, activeTab.url)
                binding.swipeRefreshLayout.isRefreshing = false
            } else {
                wv?.reload() 
            }
        }
        // Reduce pull-to-refresh sensitivity: require a longer drag before triggering.
        // Default trigger distance is ~64dp; 120dp feels deliberate without being sluggish.
        val triggerDp = (120 * resources.displayMetrics.density).toInt()
        binding.swipeRefreshLayout.setDistanceToTriggerSync(triggerDp)

        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            // Block pull-to-refresh if disabled in settings
            if (!preferences.isPullToRefreshEnabled) return@setOnChildScrollUpCallback true
            // Block pull-to-refresh on homepage and when webview can't scroll up
            if (binding.homeLayout.root.visibility == View.VISIBLE) return@setOnChildScrollUpCallback true
            val webView = tabManager.getActiveWebView() ?: return@setOnChildScrollUpCallback true
            if (webView.canScrollVertically(-1) || webView.scrollY > 0) return@setOnChildScrollUpCallback true

            // Block pull-to-refresh on immersive reels, shorts, and video feeds
            val url = (webView.url ?: "").lowercase()
            if (isReelOrFeedUrl(url)) return@setOnChildScrollUpCallback true

            // Block pull-to-refresh if inner container is scrolled, overscroll-behavior is none/contain,
            // or body is overflow-hidden
            if (!webView.touchBridge.isPullToRefreshAllowed) return@setOnChildScrollUpCallback true

            false
        }

        val database = AppDatabase.getInstance(this)
        val suggestionRepository = SearchSuggestionRepository(database.historyDao(), database.bookmarkDao())
        searchController = SearchController(
            activity = this,
            binding = binding,
            preferences = preferences,
            suggestionRepository = suggestionRepository,
            coroutineScope = lifecycleScope,
            getActivePageUrl = { getActivePageUrl() },
            getActiveTab = { tabManager.activeTab.value },
            performSearchOrLoad = { target -> performSearchOrLoad(target) },
            updateAddressBarDisplay = { url -> updateAddressBarDisplay(url) },
            showSoftKeyboard = { showSoftKeyboard() },
            hideSoftKeyboard = { hideSoftKeyboard() },
            isLikelyUrl = { url -> isLikelyUrl(url) }
        )
        searchController.setup()

        setupTopToolbar()
        findInPageController = FindInPageController(this, binding) { tabManager.getActiveWebView() }
        findInPageController.setup()
        setupTranslateBar()
        setupHomepageInteractions()
        setupBackNavigation()
        setupMediaPlaybackListener()

        pipController = PipController(
            activity = this,
            binding = binding,
            preferences = preferences,
            getActiveWebView = { tabManager.getActiveWebView() },
            getMediaTargetWebView = { getMediaTargetWebView() },
            onHideUiForPip = {
                binding.contentContainer.setPadding(0, 0, 0, 0)
                binding.statusBarSpace.visibility = View.GONE
                binding.topBar.visibility = View.GONE
                binding.topBarDivider.visibility = View.GONE
                binding.homeLayout.root.visibility = View.GONE
                binding.fullscreenControlsOverlay.visibility = View.GONE
                binding.progressBar.visibility = View.GONE
                findInPageController.hide()
                binding.searchOverlay.visibility = View.GONE
            },
            onRestoreUiFromPip = { isCustomVideo ->
                if (!isCustomVideo) {
                    binding.contentContainer.setPadding(0, 0, 0, lastNavBarBottomInset)
                    if (lastStatusBarTopInset > 0) {
                        binding.statusBarSpace.visibility = View.VISIBLE
                    }
                    val activeTab = tabManager.activeTab.value
                    if (activeTab != null && activeTab.url.isNotBlank() &&
                        !activeTab.url.startsWith("onyx://") && !activeTab.url.startsWith("about:")) {
                        binding.homeLayout.root.visibility = View.GONE
                        binding.webViewContainer.visibility = View.VISIBLE
                    } else {
                        binding.homeLayout.root.visibility = View.VISIBLE
                        binding.webViewContainer.visibility = View.GONE
                    }
                    ViewCompat.requestApplyInsets(binding.mainRoot)
                }
            }
        )
        pipController.registerReceiver()

        checkNotificationPermissionForDownloads()

        lifecycleScope.launch {
            tabManager.restoreTabs()
            tabManager.hibernateIdleTabs()
            isTabsRestored = true
            observeTabs()
            val intentToHandle = pendingIntent ?: intent
            pendingIntent = null
            val isActionIntent = intentToHandle?.action?.startsWith("com.onyx.browser.action.") == true
            if (savedInstanceState == null || intentToHandle != intent || isActionIntent) {
                handleIncomingIntent(intentToHandle)
            }
        }
    }

    private fun setupTopToolbar() {
        // Back Button in Search Mode (returns to page or homepage)
        binding.btnSearchBack.setOnClickListener {
            searchController.exitSearchMode()
        }

        // Search Engine Icon
        updateSearchEngineIcon()
        binding.btnSearchEngine.setOnClickListener {
            val popup = SearchEnginePopupMenu(
                context = this,
                currentEngine = preferences.searchEngine,
                onEngineSelected = { engine ->
                    preferences.searchEngine = engine
                    updateSearchEngineIcon()
                    com.onyx.browser.ui.widget.SearchWidgetProvider.updateAllWidgets(this)
                    val currentQuery = binding.etUrl.text?.toString()?.trim() ?: ""
                    if (searchController.isSearchMode && currentQuery.isNotEmpty()) {
                        searchController.fetchSearchSuggestions(currentQuery)
                    }
                }
            )
            popup.show(binding.btnSearchEngine)
        }

        // Home / Refresh / Back Button
        updateHomeButtonAppearance()
        binding.btnHome.setOnClickListener {
            val currentTab = tabManager.activeTab.value
            if (isLocalFileView(currentTab)) {
                onBackPressedDispatcher.onBackPressed()
            } else if (preferences.isHomeReplacedWithRefresh) {
                tabManager.getActiveWebView()?.reload()
            } else if (currentTab != null && currentTab.url.isNotBlank() && !currentTab.url.startsWith("about:blank", ignoreCase = true)) {
                val newTab = tabManager.createNewTab(url = "", isIncognito = currentTab.isIncognito)
                displayTab(newTab)
            } else {
                showHomeScreen()
            }
        }

        binding.btnHome.setOnLongClickListener {
            if (preferences.isHomeReplacedWithRefresh) {
                val currentTab = tabManager.activeTab.value
                if (currentTab != null && currentTab.url.isNotBlank() && !currentTab.url.startsWith("about:blank", ignoreCase = true)) {
                    val newTab = tabManager.createNewTab(url = "", isIncognito = currentTab.isIncognito)
                    displayTab(newTab)
                } else {
                    showHomeScreen()
                }
                true
            } else {
                false
            }
        }

        // SSL Lock Icon: opens per-site adblock controls (Brave Shields panel)
        binding.ivSslLock.setOnClickListener {
            val activeTab = tabManager.activeTab.value ?: return@setOnClickListener
            val url = activeTab.url
            if (url.isNotBlank() && !url.startsWith("about:blank", ignoreCase = true)) {
                val activeWv = tabManager.getActiveWebView()
                val blockedCount = activeWv?.pageBlockedCount ?: 0
                val cert = activeWv?.certificate
                val dialog = com.onyx.browser.ui.menu.SiteShieldBottomSheetDialog(
                    currentUrl = url,
                    pageBlockedCount = blockedCount,
                    isIncognito = activeTab.isIncognito,
                    sslCertificate = cert
                ) {
                    tabManager.getActiveWebView()?.reload()
                }
                dialog.show(supportFragmentManager, com.onyx.browser.ui.menu.SiteShieldBottomSheetDialog.TAG)
            }
        }

        // Address Bar Focus & Search Mode
        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !searchController.isSearchMode && !isTabSwitching) {
                searchController.enterSearchMode()
            }
        }

        binding.etUrl.setOnClickListener {
            if (!searchController.isSearchMode) {
                searchController.enterSearchMode()
            }
        }

        binding.searchBarContainer.setOnClickListener {
            if (!searchController.isSearchMode) {
                searchController.enterSearchMode()
            }
        }

        binding.etUrl.doAfterTextChanged { text ->
            if (searchController.isSearchMode) {
                searchController.onQueryTextChanged(text)
            }
        }

        binding.btnClearUrl.setOnClickListener {
            binding.etUrl.setText("")
        }

        binding.btnQrScanner.setOnClickListener {
            startQrScanner()
        }

        binding.btnVoiceSearch.setOnClickListener {
            launchVoiceSearch()
        }

        binding.etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val input = binding.etUrl.text?.toString()?.trim() ?: ""
                if (input.isNotEmpty()) {
                    performSearchOrLoad(input)
                    hideSoftKeyboard()
                    binding.etUrl.clearFocus()
                }
                true
            } else {
                false
            }
        }

        // Tab Switcher Button
        binding.btnTabSwitcher.setOnClickListener {
            val activeTabId = tabManager.activeTab.value?.id
            val activeWebView = tabManager.getActiveWebView()

            val sheet = TabSwitcherBottomSheet(
                tabManager = tabManager,
                coroutineScope = lifecycleScope,
                onTabSelected = { tab ->
                    displayTab(tab)
                },
                onNewTabRequested = { isIncognito ->
                    val newTab = tabManager.createNewTab(isIncognito = isIncognito)
                    displayTab(newTab)
                },
                onSearchRequested = {
                    searchController.enterSearchMode()
                }
            )

            if (activeTabId != null && activeWebView != null) {
                tabManager.captureTabSnapshot(activeTabId, activeWebView) {
                    runOnUiThread {
                        if (sheet.isAdded && !sheet.isHidden) {
                            sheet.refreshTabsList()
                        }
                    }
                }
            }

            sheet.show(supportFragmentManager, TabSwitcherBottomSheet.TAG)
        }

        // Three-Dot Menu Button
        binding.btnMenu.setOnClickListener {
            val activeTab = tabManager.activeTab.value
            val activeWebView = tabManager.getActiveWebView()
            val isHome = (binding.homeLayout.root.visibility == View.VISIBLE) || activeTab?.url.isNullOrBlank()

            val sheet = MenuBottomSheetDialogFragment().apply {
                isHomePage = isHome
                currentUrl = activeTab?.url ?: ""
                currentTitle = activeTab?.title ?: ""
                isDesktopSiteEnabled = activeWebView?.isDesktopModeEnabledForCurrentPage() ?: false
                onDesktopSiteToggled = { _ ->
                    // Toggle desktop mode scoped to the current page's domain only.
                    // Other domains and other tabs remain unaffected.
                    activeWebView?.toggleDesktopModeForPage(activeTab?.url ?: "")
                }
                onFindInPageClicked = {
                    findInPageController.show()
                }
                onTranslateClicked = { langCode ->
                    translateCurrentPage(langCode)
                }
                onSavePageClicked = {
                    showSavePageDialog()
                }
                onAddToHomeScreenClicked = {
                    addCurrentPageToHomeScreen()
                }
                onDeveloperToolsClicked = {
                    injectDeveloperTools()
                }
                onBlockElementClicked = {
                    startElementBlocker()
                }
                onSiteShieldWhitelistChanged = { _ ->
                    activeWebView?.reload()
                }

            }
            sheet.show(supportFragmentManager, MenuBottomSheetDialogFragment.TAG)
        }
    }

    private fun setupHomepageInteractions() {
        val home = binding.homeLayout

        // Dynamic Quick Actions (Clean Non-Box Circular UI, Reorderable via Drag or Dialog, Add in 4th)
        // Dynamic Shortcuts RecyclerView
        shortcutsAdapter = ShortcutsAdapter(
            onShortcutClick = { shortcut ->
                if (shortcut.id == ShortcutItem.ID_ADD_SHORTCUT || shortcut.url == ShortcutItem.URL_ADD_SHORTCUT) {
                    val sheet = ManageShortcutsBottomSheet()
                    sheet.show(supportFragmentManager, ManageShortcutsBottomSheet.TAG)
                } else {
                    performSearchOrLoad(shortcut.url)
                }
            }
        )

        val shortcutSpanCount = if (resources.configuration.screenWidthDp >= 600) 6 else 4
        home.rvShortcuts.layoutManager = GridLayoutManager(this, shortcutSpanCount)
        home.rvShortcuts.adapter = shortcutsAdapter

        var hasDragged = false
        var draggedShortcut: ShortcutItem? = null

        // Drag-and-drop reordering with ItemTouchHelper
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
            0
        ) {
            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= shortcutsAdapter.getItems().size) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            override fun canDropOver(
                recyclerView: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val targetPos = target.bindingAdapterPosition
                return targetPos != RecyclerView.NO_POSITION && targetPos < shortcutsAdapter.getItems().size
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION) {
                    hasDragged = true
                    return shortcutsAdapter.onItemMove(from, to)
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    val pos = viewHolder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                    if (pos != RecyclerView.NO_POSITION && pos < shortcutsAdapter.getItems().size) {
                        draggedShortcut = shortcutsAdapter.getItems()[pos]
                    }
                    viewHolder?.itemView?.animate()?.scaleX(1.10f)?.scaleY(1.10f)?.setDuration(150)?.start()
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                if (hasDragged) {
                    preferences.saveShortcuts(shortcutsAdapter.getItems())
                }
                draggedShortcut = null
                hasDragged = false
            }
        })
        touchHelper.attachToRecyclerView(home.rvShortcuts)

        // Observe shortcuts flow
        lifecycleScope.launch {
            preferences.shortcutsFlow.collectLatest { shortcuts ->
                shortcutsAdapter.submitList(shortcuts)
            }
        }
    }

    private fun observeTabs() {
        lifecycleScope.launch {
            tabManager.activeTab.collectLatest { activeTab ->
                if (activeTab != null) {
                    displayTab(activeTab)
                } else {
                    currentDisplayedTabId = null
                    showHomeScreen()
                    updateTabBadgeCount()
                }
            }
        }

        lifecycleScope.launch {
            tabManager.normalTabs.collectLatest {
                updateTabBadgeCount()
            }
        }

        lifecycleScope.launch {
            tabManager.incognitoTabs.collectLatest { tabs ->
                updateTabBadgeCount()
                // Update (or dismiss) incognito persistent notification
                com.onyx.browser.incognito.IncognitoNotificationHelper.updateNotification(this@MainActivity, tabs.size)
            }
        }
    }

    private fun displayTab(tab: TabItem) {
        updateTabBadgeCount()

        val previousTabId = currentDisplayedTabId
        val tabChanged = (previousTabId != tab.id)
        if (tabChanged) {
            if (previousTabId != null) {
                val outgoingWebView = tabManager.getWebView(previousTabId)
                if (outgoingWebView != null) {
                    val isPlayingMedia = preferences.isBackgroundPlayEnabled && (previousTabId == MediaPlaybackBridge.currentPlayingTabId)
                    if (!isPlayingMedia) {
                        outgoingWebView.onPause()
                    } else {
                        outgoingWebView.resumeTimers()
                        if (!MediaPlaybackService.isServiceRunning && (MediaPlaybackBridge.isAudioOrVideoPlaying || MediaPlaybackBridge.isVideoPlaying)) {
                            MediaPlaybackService.start(
                                this,
                                MediaPlaybackBridge.currentTitle,
                                MediaPlaybackBridge.currentArtist,
                                MediaPlaybackBridge.currentArtworkUrl,
                                MediaPlaybackBridge.currentPositionMs,
                                MediaPlaybackBridge.currentDurationMs,
                                true
                            )
                        }
                    }
                    tabManager.captureTabSnapshot(previousTabId, outgoingWebView)
                    tabManager.saveTabState(previousTabId, outgoingWebView)
                }
            }
            // Suppress etUrl focus-change → enterSearchMode() during the WebView swap.
            // When removeAllViews()/addView(webView) happens, Android may briefly give focus
            // back to etUrl (next focusable view after the removed WebView), which would
            // incorrectly trigger the search overlay on every tab switch.
            isTabSwitching = true
            searchController.exitSearchMode()
            hideTranslateBar(restoreOriginal = false)
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        currentDisplayedTabId = tab.id

        if (tab.url.isBlank() || tab.url == "about:blank") {
            showHomeScreen()
        } else {
            showWebView(tab, reloadIfChanged = tabChanged)
        }

        if (tabChanged) {
            // Clear the guard after the current layout pass so normal user taps on the
            // address bar still open search mode as expected.
            binding.root.post { isTabSwitching = false }
        }
    }

    fun hideToolbar(animated: Boolean = true) {
        if (isToolbarHidden) return
        if (!preferences.isAutoHideToolbarEnabled) return
        if (binding.homeLayout.root.visibility == View.VISIBLE) return
        if (binding.searchOverlay.visibility == View.VISIBLE || binding.etUrl.hasFocus()) return
        if (binding.findInPageBar.visibility == View.VISIBLE) return
        if (binding.translateBar.visibility == View.VISIBLE) return
        if (customVideoView != null) return

        isToolbarHidden = true
        if (binding.topBar.height > 0) {
            lastKnownTopBarHeight = binding.topBar.height
        }
        val barHeight = if (lastKnownTopBarHeight > 0) {
            lastKnownTopBarHeight
        } else {
            (56 * resources.displayMetrics.density).toInt()
        }
        val targetMargin = -barHeight

        toolbarAnimator?.cancel()
        if (animated) {
            val currentMargin = (binding.topBar.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            toolbarAnimator = android.animation.ValueAnimator.ofInt(currentMargin, targetMargin).apply {
                duration = 180L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { animator ->
                    val margin = animator.animatedValue as Int
                    val lp = binding.topBar.layoutParams as? ViewGroup.MarginLayoutParams ?: return@addUpdateListener
                    if (lp.topMargin != margin) {
                        lp.topMargin = margin
                        binding.topBar.layoutParams = lp
                    }
                }
                start()
            }
        } else {
            val lp = binding.topBar.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && lp.topMargin != targetMargin) {
                lp.topMargin = targetMargin
                binding.topBar.layoutParams = lp
            }
        }
    }

    fun showToolbar(animated: Boolean = true) {
        if (isInPipMode() || customVideoView != null) return
        val currentMargin = (binding.topBar.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        if (!isToolbarHidden && currentMargin == 0) return

        isToolbarHidden = false
        toolbarAnimator?.cancel()
        if (animated) {
            toolbarAnimator = android.animation.ValueAnimator.ofInt(currentMargin, 0).apply {
                duration = 180L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { animator ->
                    val margin = animator.animatedValue as Int
                    val lp = binding.topBar.layoutParams as? ViewGroup.MarginLayoutParams ?: return@addUpdateListener
                    if (lp.topMargin != margin) {
                        lp.topMargin = margin
                        binding.topBar.layoutParams = lp
                    }
                }
                start()
            }
        } else {
            val lp = binding.topBar.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && lp.topMargin != 0) {
                lp.topMargin = 0
                binding.topBar.layoutParams = lp
            }
        }
    }

    fun showHomeScreen() {
        val inPip = isInPipMode()
        if (!inPip && customVideoView == null) {
            showToolbar(animated = false)
            binding.topBar.visibility = View.VISIBLE
            binding.topBarDivider.visibility = View.GONE
        }
        binding.homeLayout.root.visibility = if (inPip) View.GONE else View.VISIBLE
        binding.webViewContainer.visibility = View.GONE
        binding.etUrl.setText("")
        binding.ivSslLock.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.swipeRefreshLayout.isEnabled = false
        updateHomeButtonAppearance()
        hideTranslateBar(restoreOriginal = false)

        val isIncognito = tabManager.activeTab.value?.isIncognito == true
        updateIncognitoUI(isIncognito)

        val child = if (binding.webViewContainer.childCount > 0) binding.webViewContainer.getChildAt(0) as? OnyxWebView else null
        val isPlayingChild = preferences.isBackgroundPlayEnabled && (child?.tabId == MediaPlaybackBridge.currentPlayingTabId)
        if (!isPlayingChild) {
            child?.onPause()
        } else {
            child?.resumeTimers()
            if (!MediaPlaybackService.isServiceRunning && (MediaPlaybackBridge.isAudioOrVideoPlaying || MediaPlaybackBridge.isVideoPlaying)) {
                MediaPlaybackService.start(
                    this,
                    MediaPlaybackBridge.currentTitle,
                    MediaPlaybackBridge.currentArtist,
                    MediaPlaybackBridge.currentArtworkUrl,
                    MediaPlaybackBridge.currentPositionMs,
                    MediaPlaybackBridge.currentDurationMs,
                    true
                )
            }
        }
        binding.webViewContainer.removeAllViews()
        if (!inPip) {
            updatePipParams(isVideoPlaying = false, shouldAutoEnter = false)
        }
    }

    private fun updateIncognitoUI(isIncognito: Boolean) {
        val home = binding.homeLayout
        if (isIncognito) {
            home.ivBrandLogo.visibility = View.GONE
            home.tvTagline.visibility = View.GONE
            home.incognitoHeader.visibility = View.VISIBLE
            binding.ivIncognitoIndicator.visibility = View.VISIBLE
            binding.etUrl.hint = getString(R.string.search_privately)
            binding.etUrl.imeOptions = binding.etUrl.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        } else {
            home.ivBrandLogo.visibility = View.VISIBLE
            home.tvTagline.visibility = View.VISIBLE
            home.incognitoHeader.visibility = View.GONE
            binding.ivIncognitoIndicator.visibility = View.GONE
            binding.etUrl.hint = getString(R.string.search_or_type_url)
            binding.etUrl.imeOptions = binding.etUrl.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
        }
    }

    private fun showWebView(tab: TabItem, forceUrl: String? = null, reloadIfChanged: Boolean = false) {
        val inPip = isInPipMode()
        if (!inPip && customVideoView == null) {
            showToolbar(animated = false)
            binding.topBar.visibility = View.VISIBLE
            binding.topBarDivider.visibility = View.GONE
        }
        binding.homeLayout.root.visibility = View.GONE
        binding.webViewContainer.visibility = View.VISIBLE
        binding.swipeRefreshLayout.isEnabled = preferences.isPullToRefreshEnabled

        updateIncognitoUI(tab.isIncognito)

        val webView = tabManager.getOrCreateWebView(tab)
        attachWebViewToContainer(webView)
        if (!inPip) {
            updatePipParams()
        }

        val targetUrl = forceUrl ?: tab.url
        if (targetUrl.isNotBlank() && targetUrl != "about:blank") {
            val isCurrentLoaded = !webView.url.isNullOrBlank() && webView.url != "about:blank"
            val needsLoad = forceUrl != null || !isCurrentLoaded || (reloadIfChanged && webView.url != targetUrl)
            if (needsLoad) {
                try {
                    if (LocalFileLoader.isLocalFile(this, targetUrl)) {
                        LocalFileLoader.loadLocalFile(this, webView, targetUrl) { title ->
                            tabManager.updateActiveTab(targetUrl, title)
                        }
                    } else {
                        var restored = false
                        if (forceUrl == null && !tab.isIncognito && (webView.url.isNullOrBlank() || webView.url == "about:blank")) {
                            restored = tabManager.restoreTabState(tab.id, webView)
                        }
                        if (!restored) {
                            webView.stopLoading()
                            webView.loadUrl(targetUrl)
                        }
                    }
                } catch (t: Throwable) {
                    Toast.makeText(this, "Failed to load URL: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
            updateAddressBarDisplay(if (LocalFileLoader.isLocalFile(this, targetUrl)) targetUrl else (webView.url ?: targetUrl))
            webView.evaluateJavascript(com.onyx.browser.web.MediaPlaybackManager.mediaMonitorScript, null)
        } else {
            showHomeScreen()
        }
    }

    private fun attachWebViewToContainer(webView: OnyxWebView) {
        if (webView.parent != binding.webViewContainer) {
            val currentChild = if (binding.webViewContainer.childCount > 0) {
                binding.webViewContainer.getChildAt(0) as? OnyxWebView
            } else null
            val isPlayingCurrent = preferences.isBackgroundPlayEnabled && (currentChild?.tabId == MediaPlaybackBridge.currentPlayingTabId)
            if (!isPlayingCurrent) {
                currentChild?.onPause()
            } else {
                currentChild?.resumeTimers()
                if (!MediaPlaybackService.isServiceRunning && (MediaPlaybackBridge.isAudioOrVideoPlaying || MediaPlaybackBridge.isVideoPlaying)) {
                    MediaPlaybackService.start(
                        this,
                        MediaPlaybackBridge.currentTitle,
                        MediaPlaybackBridge.currentArtist,
                        MediaPlaybackBridge.currentArtworkUrl,
                        MediaPlaybackBridge.currentPositionMs,
                        MediaPlaybackBridge.currentDurationMs,
                        true
                    )
                }
            }

            (webView.parent as? ViewGroup)?.removeView(webView)
            binding.webViewContainer.removeAllViews()
            binding.webViewContainer.addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            setupWebViewClients(webView)
            webView.onResume()
        }
    }

    private fun setupWebViewClients(webView: OnyxWebView) {
        val tabId = webView.tabId
        webView.onScrollChangedCallback = { _, t, _, oldt ->
            if (preferences.isScrollToTopEnabled && currentDisplayedTabId == tabId && tabManager.activeTab.value?.id == tabId) {
                if (t > 500 && t > oldt) {
                    binding.fabScrollToTop.visibility = android.view.View.VISIBLE
                } else if (t < 10) {
                    binding.fabScrollToTop.visibility = android.view.View.GONE
                }
            }

            if (preferences.isAutoHideToolbarEnabled && currentDisplayedTabId == tabId && tabManager.activeTab.value?.id == tabId && binding.homeLayout.root.visibility != View.VISIBLE) {
                val deltaY = t - oldt
                val threshold = (12 * resources.displayMetrics.density).toInt()
                if (t <= 5) {
                    showToolbar(animated = true)
                } else if (deltaY > threshold && t > (lastKnownTopBarHeight.takeIf { it > 0 } ?: binding.topBar.height)) {
                    hideToolbar(animated = true)
                } else if (deltaY < -threshold) {
                    showToolbar(animated = true)
                }
            }
        }

        val client = OnyxWebViewClient(
            context = this,
            coroutineScope = lifecycleScope,
            onUrlChanged = { newUrl ->
                if (isToolbarHidden && currentDisplayedTabId == tabId) {
                    showToolbar(animated = true)
                }
                val isPreview = LocalFileLoader.isPreviewUrl(newUrl)
                val currentTab = tabManager.getTabById(tabId)
                val realTabUrl = currentTab?.url ?: ""
                val isLocalView = isLocalFileView(currentTab)
                val isDirectoryUrl = newUrl.endsWith("/") || (newUrl.startsWith("file://") && try { File(Uri.parse(newUrl).path ?: "").isDirectory } catch (_: Exception) { false })
                val cleanUrl = if (isPreview) {
                    realTabUrl
                } else if (isLocalView && isDirectoryUrl) {
                    realTabUrl
                } else if (newUrl.startsWith("data:") || newUrl.startsWith("file:///android_asset/") || newUrl.startsWith("file:///android_res/")) {
                    webView.currentSyntheticState?.failingUrl ?: realTabUrl
                } else {
                    newUrl
                }
                if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("about:blank", ignoreCase = true) && !cleanUrl.startsWith("data:") && !cleanUrl.startsWith("file:///android_asset/") && !cleanUrl.startsWith("file:///android_res/")) {
                    val finalTitle = webView.title?.takeIf { it.isNotBlank() }
                        ?: if (isPreview) currentTab?.title?.takeIf { it.isNotBlank() } ?: "Web Archive" else cleanUrl
                    tabManager.updateTabUrlAndTitle(tabId, cleanUrl, finalTitle)
                    if (currentDisplayedTabId == tabId && tabManager.activeTab.value?.id == tabId) {
                        updateAddressBarDisplay(cleanUrl)
                        val activeTab = tabManager.activeTab.value
                        if (activeTab != null && !activeTab.isIncognito) {
                            tabManager.saveTabState(activeTab.id, webView)
                        }
                    }
                }
            },
            onPageFinishedCallback = { finishedUrl ->
                val isPreview = LocalFileLoader.isPreviewUrl(finishedUrl)
                val currentTab = tabManager.getTabById(tabId)
                val realTabUrl = currentTab?.url ?: ""
                val isLocalView = isLocalFileView(currentTab)
                val isDirectoryUrl = finishedUrl.endsWith("/") || (finishedUrl.startsWith("file://") && try { File(Uri.parse(finishedUrl).path ?: "").isDirectory } catch (_: Exception) { false })
                val cleanUrl = if (isPreview) {
                    realTabUrl
                } else if (isLocalView && isDirectoryUrl) {
                    realTabUrl
                } else if (finishedUrl.startsWith("data:") || finishedUrl.startsWith("file:///android_asset/") || finishedUrl.startsWith("file:///android_res/")) {
                    webView.currentSyntheticState?.failingUrl ?: realTabUrl
                } else {
                    finishedUrl
                }
                if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("about:blank", ignoreCase = true) && !cleanUrl.startsWith("data:") && !cleanUrl.startsWith("file:///android_asset/") && !cleanUrl.startsWith("file:///android_res/")) {
                    val finalTitle = webView.title?.takeIf { it.isNotBlank() }
                        ?: if (isPreview) currentTab?.title?.takeIf { it.isNotBlank() } ?: "Web Archive" else cleanUrl
                    tabManager.updateTabUrlAndTitle(tabId, cleanUrl, finalTitle)
                    if (currentDisplayedTabId == tabId && tabManager.activeTab.value?.id == tabId) {
                        updateAddressBarDisplay(cleanUrl)
                    }
                }
                if (currentDisplayedTabId == tabId) {
                    binding.progressBar.visibility = View.GONE
                    val activeTab = tabManager.activeTab.value
                    if (activeTab != null && !activeTab.isIncognito) {
                        tabManager.saveTabState(activeTab.id, webView)
                    }
                    if (activeTab != null && (webView.url == finishedUrl || webView.currentSyntheticState != null)) {
                        webView.postDelayed({
                            if (currentDisplayedTabId == tabId) {
                                tabManager.captureTabSnapshot(activeTab.id, webView)
                            }
                        }, 400)
                    }
                }
            },
            onPageCommitVisibleCallback = { view, _ ->
                if (view is OnyxWebView && view.tabId == currentDisplayedTabId && tabManager.activeTab.value?.id == tabId) {
                    val activeTab = tabManager.activeTab.value
                    if (activeTab != null) {
                        view.postDelayed({
                            if (currentDisplayedTabId == tabId) {
                                tabManager.captureTabSnapshot(activeTab.id, view)
                            }
                        }, 300)
                    }
                }
            },
            tabActionCallback = this
        )
        client.onOpenInAppPrompt = { intent, appName, fallback ->
            if (!isFinishing && !isDestroyed) {
                val promptDialog = com.onyx.browser.ui.dialog.OpenInAppPromptDialog.newInstance(
                    intent = intent,
                    appName = appName,
                    onStayInOnyx = { fallback?.invoke() },
                    onOpenInApp = null
                )
                promptDialog.showSafely(supportFragmentManager)
            }
        }
        webView.webViewClient = client

        webView.webChromeClient = OnyxWebChromeClient(
            onProgressChangedCallback = { progress ->
                if (currentDisplayedTabId != tabId || isInPipMode()) return@OnyxWebChromeClient
                if (binding.progressBar.visibility != android.view.View.VISIBLE && progress < 100) {
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    binding.progressBar.alpha = 1f
                    binding.progressBar.progress = 0
                    if (isToolbarHidden) {
                        showToolbar(animated = true)
                    }
                }
                
                val animator = android.animation.ObjectAnimator.ofInt(binding.progressBar, "progress", binding.progressBar.progress, progress)
                animator.duration = 200
                animator.interpolator = android.view.animation.DecelerateInterpolator()
                animator.start()
                
                if (progress >= 100) {
                    binding.swipeRefreshLayout.isRefreshing = false
                    binding.progressBar.animate()
                        .alpha(0f)
                        .setStartDelay(200)
                        .setDuration(200)
                        .withEndAction {
                            binding.progressBar.visibility = android.view.View.GONE
                            binding.progressBar.progress = 0
                            binding.progressBar.alpha = 1f
                        }
                        .start()
                }
            },
            onTitleReceivedCallback = { title ->
                tabManager.updateTabUrlAndTitle(tabId, webView.url ?: "", title)
            },
            onShowCustomViewCallback = { view, callback ->
                showCustomFullscreenVideo(view, callback)
            },
            onHideCustomViewCallback = {
                hideCustomFullscreenVideo()
            },
            onFileChooserCallback = { callback, params ->
                handleShowFileChooser(callback, params)
            },
            onGeolocationPromptCallback = { origin, callback ->
                handleGeolocationPrompt(origin, callback)
            },
            onGeolocationHidePromptCallback = {
                pendingGeoOrigin = null
                pendingGeoCallback = null
            },
            onPermissionRequestCallback = { request ->
                handleWebPermissionRequest(request)
            },
            onPermissionRequestCanceledCallback = { request ->
                handleWebPermissionRequestCanceled(request)
            },
            onCreateWindowCallback = { sourceWebView, isDialog, isUserGesture, resultMsg ->
                // Check per-domain and global popup blocking preferences
                val parentUrl = (sourceWebView as? OnyxWebView)?.url ?: ""
                val parentDomain = preferences.cleanDomain(parentUrl)
                val popupPerm = if (parentDomain.isNotBlank()) preferences.isWebPopupsAllowed(parentDomain) else null
                val recentTouch = (sourceWebView as? OnyxWebView)?.isWithinRecentTouchWindow() ?: false

                val isStreamingOrMedia = OnyxWebViewClient.isStreamingOrMediaPage(parentUrl) ||
                        OnyxWebViewClient.isMediaOrStreamingHost(parentDomain) ||
                        MediaPlaybackBridge.isVideoAvailableForTab((sourceWebView as? OnyxWebView)?.tabId)

                val isAdBlockActive = preferences.isAdBlockEnabled && !preferences.isDomainWhitelisted(parentDomain)

                val allowPopups = when (popupPerm) {
                    true -> true
                    false -> false
                    null -> {
                        if (isStreamingOrMedia && isAdBlockActive) {
                            // Only real user-gesture popups on media pages. They open hidden and are
                            // vetted by OnyxWebViewClient.isPopupAd, which drops cross-site clickjack
                            // popunders while same-site windows (e.g. download forms) still open.
                            isUserGesture
                        } else {
                            !preferences.isPopupsBlockedGlobal || isUserGesture || recentTouch
                        }
                    }
                }

                if (!allowPopups || resultMsg == null) {
                    false
                } else {
                    val parentTab = (sourceWebView as? OnyxWebView)?.tabId?.let { tabManager.getTabById(it) }
                        ?: tabManager.activeTab.value
                    val isIncognito = parentTab?.isIncognito ?: false
                    val newTab = tabManager.createNewTab(
                        url = "",
                        isIncognito = isIncognito,
                        parentId = parentTab?.id
                    )
                    val newWebView = tabManager.getOrCreateWebView(newTab)
                    newWebView.isPopupPendingDisplay = true
                    newWebView.isPopupTab = true
                    newWebView.lastOAuthInteractionTimestamp = System.currentTimeMillis()
                    (sourceWebView as? OnyxWebView)?.lastOAuthInteractionTimestamp = System.currentTimeMillis()
                    if (!isIncognito) {
                        try {
                            newWebView.cookieManager.setAcceptThirdPartyCookies(newWebView, true)
                        } catch (_: Exception) {}
                    }

                    // Pre-setup the clients before passing it back, so it instantly has download listeners
                    setupWebViewClients(newWebView)

                    val transport = resultMsg.obj as? WebView.WebViewTransport
                    if (transport != null) {
                        transport.webView = newWebView
                        resultMsg.sendToTarget()

                        // NOTE: Do NOT immediately switch tabs or pause the parent tab!
                        // If this window.open was triggered by an ad clickjack on a video player,
                        // switching tabs would call onPause() on the video tab and pause the video!
                        // Instead, keep the parent tab active. When newWebView navigates:
                        // - If it's an ad URL, handleUrlLoading cancels it and closes newTab silently!
                        // - If it's a legitimate URL, displayPopupTab(newTab) switches to it smoothly!
                        val popupOpenedAt = System.currentTimeMillis()
                        lateinit var popupCleanup: Runnable
                        popupCleanup = Runnable {
                            if (newWebView.isPopupPendingDisplay) {
                                val current = tabManager.getTabById(newTab.id)
                                val currentUrl = (newWebView.url ?: "").lowercase()
                                val isAuth = currentUrl.contains("login") || currentUrl.contains("auth") ||
                                        currentUrl.contains("oauth") || currentUrl.contains("facebook") ||
                                        currentUrl.contains("google") || currentUrl.contains("checkpoint") ||
                                        currentUrl.contains("apple") || currentUrl.contains("signin") ||
                                        currentUrl.contains("paypal") || currentUrl.contains("stripe")
                                // A slow server (e.g. a form POST still waiting for its response) keeps the
                                // popup alive for up to 60s instead of silently discarding the request.
                                val stillLoading = newWebView.progress in 1..99 &&
                                        System.currentTimeMillis() - popupOpenedAt < 60_000L
                                if (stillLoading) {
                                    newWebView.postDelayed(popupCleanup, 5000)
                                } else if (!isAuth && current != null && currentDisplayedTabId != newTab.id) {
                                    closeTabById(newTab.id)
                                }
                            }
                        }
                        newWebView.postDelayed(popupCleanup, 12000)

                        true
                    } else {
                        false
                    }
                }
            },
            onCloseWindowCallback = {
                val activeTab = tabManager.activeTab.value
                if (activeTab != null) {
                    tabManager.closeTab(activeTab)
                }
            }
        )

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            if (LocalFileLoader.isLocalFile(this, url)) {
                LocalFileLoader.loadLocalFile(this, webView, url)
                return@setDownloadListener
            }
            val cookies = try {
                webView.cookieManager.getCookie(url) ?: ""
            } catch (_: Exception) {
                ""
            }
            val referer = webView.url ?: ""
            // Downloads produced by POST forms must be replayed with the original POST body.
            FormPostRegistry.claimForDownload(url)
            DownloadHandler.handleDownload(
                activity = this,
                coroutineScope = lifecycleScope,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimetype,
                contentLength = contentLength,
                cookies = cookies,
                referer = referer
            )
            // If this download was triggered in an unpromoted popup tab (e.g. target="_blank" download button),
            // clean up the hidden blank popup window so it doesn't linger as an orphan tab.
            if (webView.isPopupPendingDisplay && currentDisplayedTabId != tabId) {
                closeTabById(tabId)
            }
        }

        fun showContextMenuForElement(
            linkUrl: String? = null,
            imageUrl: String? = null,
            videoUrl: String? = null,
            linkText: String? = null
        ) {
            val cleanLink = linkUrl?.takeIf { it.isNotBlank() }
            val cleanImg = imageUrl?.takeIf { it.isNotBlank() }
            val cleanVid = videoUrl?.takeIf { it.isNotBlank() }
            val cleanText = linkText?.takeIf { it.isNotBlank() }

            val isIncognito = tabManager.getTabById(tabId)?.isIncognito ?: (tabManager.activeTab.value?.isIncognito ?: false)

            val sheet = when {
                cleanLink != null && cleanImg != null ->
                    ContextMenuBottomSheet.forImageLink(cleanLink, cleanImg, cleanText, isIncognito = isIncognito)
                cleanVid != null ->
                    ContextMenuBottomSheet.forVideo(cleanVid, isIncognito = isIncognito)
                cleanImg != null ->
                    ContextMenuBottomSheet.forImage(cleanImg, isIncognito = isIncognito)
                cleanLink != null ->
                    ContextMenuBottomSheet.forLink(cleanLink, cleanText, isIncognito = isIncognito)
                else -> return
            }

            sheet.setOnOpenInNewTab { u -> openUrlInNewTab(u, isIncognito = isIncognito) }
            sheet.setOnOpenInIncognitoTab { u -> openUrlInIncognitoTab(u) }
            sheet.setOnEditUrl { u -> editUrlInSearchBar(u) }
            sheet.setOnDownloadUrl { u ->
                val cookies = webView.cookieManager.getCookie(u) ?: ""
                val userAgent = webView.settings.userAgentString ?: ""
                FormPostRegistry.claimForDownload(u)
                DownloadHandler.handleDownload(
                    activity = this,
                    coroutineScope = lifecycleScope,
                    url = u,
                    userAgent = userAgent,
                    contentDisposition = "",
                    mimeType = "",
                    contentLength = -1L,
                    cookies = cookies,
                    referer = webView.url ?: ""
                )
            }
            sheet.show(supportFragmentManager, ContextMenuBottomSheet.TAG)
        }

        // Long-press context menu for links, images, image links, videos, and media
        webView.setOnLongClickListener {
            val touched = webView.touchBridge.lastTouchedElement
            val hit = webView.hitTestResult
            val hitType = hit.type
            val hitExtra = hit.extra

            // 1. Tier 1: Real-time touch bridge pre-computed element
            if (touched.hasContent) {
                val linkUrl = touched.linkUrl.ifBlank {
                    if (hitType == android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE) hitExtra ?: "" else ""
                }
                val imgUrl = touched.imageUrl.ifBlank {
                    if (hitType == android.webkit.WebView.HitTestResult.IMAGE_TYPE ||
                        hitType == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) hitExtra ?: "" else ""
                }
                showContextMenuForElement(
                    linkUrl = linkUrl,
                    imageUrl = imgUrl,
                    videoUrl = touched.videoUrl,
                    linkText = touched.linkText
                )
                return@setOnLongClickListener true
            }

            // 2. Tier 2: HitTestResult matching
            when (hitType) {
                android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE,
                android.webkit.WebView.HitTestResult.ANCHOR_TYPE -> {
                    val url = hitExtra ?: return@setOnLongClickListener false
                    showContextMenuForElement(linkUrl = url)
                    return@setOnLongClickListener true
                }
                android.webkit.WebView.HitTestResult.IMAGE_TYPE -> {
                    val imgUrl = hitExtra ?: return@setOnLongClickListener false
                    showContextMenuForElement(imageUrl = imgUrl)
                    return@setOnLongClickListener true
                }
                android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val imgUrl = hitExtra ?: ""
                    // requestFocusNodeHref to retrieve the real anchor link URL!
                    val msg = android.os.Handler(android.os.Looper.getMainLooper()) { m ->
                        val linkUrl = m.data.getString("url")
                        val title = m.data.getString("title")
                        val src = m.data.getString("src") ?: imgUrl
                        showContextMenuForElement(
                            linkUrl = linkUrl,
                            imageUrl = src,
                            linkText = title
                        )
                        true
                    }.obtainMessage()
                    webView.requestFocusNodeHref(msg)
                    return@setOnLongClickListener true
                }
                android.webkit.WebView.HitTestResult.EMAIL_TYPE -> {
                    val mail = hitExtra ?: return@setOnLongClickListener false
                    val mailto = if (mail.startsWith("mailto:", ignoreCase = true)) mail else "mailto:$mail"
                    showContextMenuForElement(linkUrl = mailto, linkText = mail)
                    return@setOnLongClickListener true
                }
                android.webkit.WebView.HitTestResult.PHONE_TYPE -> {
                    val phone = hitExtra ?: return@setOnLongClickListener false
                    val tel = if (phone.startsWith("tel:", ignoreCase = true)) phone else "tel:$phone"
                    showContextMenuForElement(linkUrl = tel, linkText = phone)
                    return@setOnLongClickListener true
                }
                android.webkit.WebView.HitTestResult.GEO_TYPE -> {
                    val geo = hitExtra ?: return@setOnLongClickListener false
                    val geoUri = if (geo.startsWith("geo:", ignoreCase = true)) geo else "geo:0,0?q=$geo"
                    showContextMenuForElement(linkUrl = geoUri, linkText = geo)
                    return@setOnLongClickListener true
                }
                android.webkit.WebView.HitTestResult.EDIT_TEXT_TYPE -> {
                    // Let Android native text editing handles work
                    return@setOnLongClickListener false
                }
                else -> {
                    // Tier 3: UNKNOWN_TYPE fallback via elementFromPoint and requestFocusNodeHref
                    val density = resources.displayMetrics.density
                    val cssX = (webView.lastTouchX / density).toInt()
                    val cssY = (webView.lastTouchY / density).toInt()
                    val jsCheck = """
                        (function(x, y) {
                            try {
                                var el = document.elementFromPoint(x, y);
                                if (!el) return null;
                                var a = el.closest('a');
                                var img = el.closest('img') || (el.tagName === 'IMG' ? el : el.querySelector('img'));
                                var video = el.closest('video') || (el.tagName === 'VIDEO' ? el : el.querySelector('video'));
                                if (!video) {
                                    var s = el.querySelector('source');
                                    if (s && s.parentElement && s.parentElement.tagName === 'VIDEO') video = s.parentElement;
                                }
                                if (!a && !img && !video) return null;
                                var linkUrl = a ? (a.href || a.getAttribute('href') || '') : '';
                                var linkText = a ? (a.innerText || a.textContent || '').trim().substring(0, 150) : '';
                                var imageUrl = img ? (img.currentSrc || img.src || '') : '';
                                var videoUrl = video ? (video.currentSrc || video.src || '') : '';
                                return JSON.stringify({
                                    linkUrl: linkUrl,
                                    linkText: linkText,
                                    imageUrl: imageUrl,
                                    videoUrl: videoUrl
                                });
                            } catch(e) { return null; }
                        })($cssX, $cssY);
                    """.trimIndent()

                    webView.evaluateJavascript(jsCheck) { jsonResult ->
                        if (!jsonResult.isNullOrBlank() && jsonResult != "null") {
                            try {
                                val cleanJson = if (jsonResult.startsWith("\"") && jsonResult.endsWith("\"")) {
                                    org.json.JSONTokener(jsonResult).nextValue().toString()
                                } else jsonResult
                                val obj = org.json.JSONObject(cleanJson)
                                val lUrl = obj.optString("linkUrl").takeIf { it.isNotBlank() }
                                val lText = obj.optString("linkText").takeIf { it.isNotBlank() }
                                val iUrl = obj.optString("imageUrl").takeIf { it.isNotBlank() }
                                val vUrl = obj.optString("videoUrl").takeIf { it.isNotBlank() }
                                if (lUrl != null || iUrl != null || vUrl != null) {
                                    showContextMenuForElement(
                                        linkUrl = lUrl,
                                        imageUrl = iUrl,
                                        videoUrl = vUrl,
                                        linkText = lText
                                    )
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    val msg = android.os.Handler(android.os.Looper.getMainLooper()) { m ->
                        val linkUrl = m.data.getString("url")
                        val title = m.data.getString("title")
                        val src = m.data.getString("src")
                        if (!linkUrl.isNullOrBlank() || !src.isNullOrBlank()) {
                            showContextMenuForElement(
                                linkUrl = linkUrl,
                                imageUrl = src,
                                linkText = title
                            )
                        }
                        true
                    }.obtainMessage()
                    webView.requestFocusNodeHref(msg)

                    return@setOnLongClickListener false
                }
            }
        }
    }

    private fun editUrlInSearchBar(url: String) {
        binding.etUrl.setText(url)
        binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
        binding.etUrl.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etUrl, InputMethodManager.SHOW_IMPLICIT)
    }

    fun openUrlInNewTab(url: String, isIncognito: Boolean? = null) {
        val parentTab = tabManager.activeTab.value
        val incognito = isIncognito ?: (parentTab?.isIncognito ?: false)
        val newTab = tabManager.createNewTab(url = url, isIncognito = incognito, parentId = parentTab?.id)
        // Explicitly set as active tab and navigate to it, ensuring currentDisplayedTabId
        // is updated before the StateFlow observer fires to avoid a no-op reload.
        tabManager.selectTab(newTab)
        currentDisplayedTabId = newTab.id
        updateTabBadgeCount()
        showWebView(newTab, forceUrl = url, reloadIfChanged = true)
    }

    private fun openUrlInIncognitoTab(url: String) {
        val parentTab = tabManager.activeTab.value
        val newTab = tabManager.createNewTab(url = url, isIncognito = true, parentId = parentTab?.id)
        tabManager.selectTab(newTab)
        currentDisplayedTabId = newTab.id
        updateTabBadgeCount()
        showWebView(newTab, forceUrl = url, reloadIfChanged = true)
    }

    override fun closeTab(tabId: String) {
        closeTabById(tabId)
    }

    fun closeTabById(tabId: String) {
        val tab = tabManager.getTabById(tabId) ?: return
        tabManager.closeTab(tab)
    }

    override fun getTabById(tabId: String): TabItem? {
        return if (::tabManager.isInitialized) tabManager.getTabById(tabId) else null
    }

    override fun displayPopupTab(tab: TabItem) {
        runOnUiThread {
            val wv = tabManager.getWebView(tab.id)
            if (wv != null && wv.isPopupPendingDisplay) {
                wv.isPopupPendingDisplay = false
                tabManager.selectTab(tab)
                currentDisplayedTabId = tab.id
                updateTabBadgeCount()
                showWebView(tab)
            }
        }
    }

    override fun onRendererCrash(tabId: String) {
        runOnUiThread {
            tabManager.handleRendererCrash(tabId)
            val currentTab = tabManager.activeTab.value
            if (currentTab != null && currentTab.id == tabId) {
                currentDisplayedTabId = null
                displayTab(currentTab)
            }
        }
    }

    private fun startQrScanner() {
        qrScannerLauncher.launch(Intent(this, com.onyx.browser.ui.qr.QrScannerActivity::class.java))
    }

    fun performSearchOrLoad(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        if (trimmed.startsWith("javascript:", ignoreCase = true)) {
            executeBookmarklet(trimmed)
            return
        }

        if (trimmed == "onyx://bookmarks") {
            openBookmarks()
            return
        }
        if (trimmed == "onyx://history") {
            openHistory()
            return
        }
        if (trimmed == "onyx://downloads") {
            openDownloads()
            return
        }
        if (trimmed == "onyx://qr") {
            qrScannerLauncher.launch(Intent(this, com.onyx.browser.ui.qr.QrScannerActivity::class.java))
            return
        }

        val url = when {
            LocalFileLoader.isSensitiveOrRestrictedPath(this, trimmed) -> {
                Toast.makeText(this, "Access to private or restricted files is blocked", Toast.LENGTH_SHORT).show()
                return
            }

            LocalFileLoader.isLocalFile(this, trimmed) -> trimmed

            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true) -> trimmed

            trimmed.startsWith("file://", ignoreCase = true) -> {
                val parsed = try { Uri.parse(trimmed) } catch (_: Exception) { null }
                val path = parsed?.path
                if (path != null && File(path).exists()) {
                    trimmed
                } else {
                    Toast.makeText(this, "File not found: $trimmed", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            isLikelyUrl(trimmed) -> "https://$trimmed"

            else -> {
                val allEngines = preferences.getAllSearchEngines()
                val spaceIndex = trimmed.indexOf(' ')
                if (spaceIndex > 0) {
                    val firstToken = trimmed.substring(0, spaceIndex).trim()
                    val queryAfterKeyword = trimmed.substring(spaceIndex + 1).trim()
                    val matchedEngine = allEngines.firstOrNull {
                        it.keyword.isNotBlank() && it.keyword.equals(firstToken, ignoreCase = true)
                    }
                    if (matchedEngine != null && queryAfterKeyword.isNotEmpty()) {
                        matchedEngine.buildSearchUrl(queryAfterKeyword)
                    } else {
                        preferences.searchEngine.buildSearchUrl(trimmed)
                    }
                } else {
                    preferences.searchEngine.buildSearchUrl(trimmed)
                }
            }
        }

        val activeTab = tabManager.activeTab.value ?: tabManager.createNewTab()
        tabManager.updateActiveTab(url, url)
        showWebView(activeTab, forceUrl = url)
        hideTranslateBar(restoreOriginal = false)
        if (searchController.isSearchMode) {
            searchController.exitSearchMode()
        }
    }

    private fun isLikelyUrl(input: String): Boolean {
        return com.onyx.browser.data.search.SearchSuggestionRepository.isLikelyDomainOrUrl(input)
    }

    private fun isReelOrFeedUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.contains("facebook.com") && (url.contains("/reel") || url.contains("/watch") || url.contains("/videos"))) {
            return true
        }
        if (url.contains("instagram.com") && (url.contains("/reel") || url.contains("/reels") || url.contains("/stories"))) {
            return true
        }
        if (url.contains("youtube.com/shorts") || url.contains("tiktok.com")) {
            return true
        }
        return false
    }

    private fun getActivePageUrl(): String {
        val activeWv = tabManager.getActiveWebView()
        val failingUrl = activeWv?.currentSyntheticState?.failingUrl
        if (!failingUrl.isNullOrBlank()) {
            return failingUrl
        }
        val tabUrl = tabManager.activeTab.value?.url ?: ""
        if (tabUrl.startsWith("data:") || tabUrl.startsWith("file:///android_asset/") || tabUrl.startsWith("file:///android_res/") ||
            LocalFileLoader.isPreviewUrl(tabUrl) || LocalFileLoader.isSensitiveOrRestrictedPath(this, tabUrl)) {
            return ""
        }
        return tabUrl
    }

    private fun updateAddressBarDisplay(url: String) {
        val activeWv = tabManager.getActiveWebView()
        val displayUrl = when {
            url.isBlank() || url.startsWith("about:blank", ignoreCase = true) || url.startsWith("data:") || url.startsWith("file:///android_asset/") || url.startsWith("file:///android_res/") -> {
                activeWv?.currentSyntheticState?.failingUrl ?: tabManager.activeTab.value?.url?.takeIf { !it.startsWith("about:blank", ignoreCase = true) } ?: ""
            }
            LocalFileLoader.isPreviewUrl(url) -> {
                tabManager.activeTab.value?.url ?: ""
            }
            else -> url
        }

        if (displayUrl.isBlank() || displayUrl.startsWith("about:blank", ignoreCase = true) || displayUrl.startsWith("data:") || displayUrl.startsWith("file:///android_asset/") || displayUrl.startsWith("file:///android_res/")) {
            binding.etUrl.setText("")
            binding.ivSslLock.visibility = View.GONE
            updateHomeButtonAppearance(displayUrl)
            return
        }

        val isHttps = displayUrl.startsWith("https://")
        binding.ivSslLock.visibility = if (isHttps) View.VISIBLE else View.GONE

        updateHomeButtonAppearance(displayUrl)

        val isLocalDoc = isLocalFileView(tabManager.activeTab.value) ||
                LocalFileLoader.isLocalFile(this, displayUrl) ||
                LocalFileLoader.isPreviewUrl(displayUrl)

        val host = when {
            isLocalDoc -> {
                val realDocUrl = tabManager.activeTab.value?.url?.takeIf { !it.endsWith("/") && (LocalFileLoader.isLocalFile(this, it) || LocalFileLoader.isPreviewUrl(it)) } ?: displayUrl
                if (LocalFileLoader.isLocalFile(this, realDocUrl)) {
                    LocalFileLoader.getDisplayName(this, LocalFileLoader.parseUri(realDocUrl))
                } else {
                    tabManager.activeTab.value?.title?.takeIf { it.isNotBlank() } ?: "Document Preview"
                }
            }
            LocalFileLoader.isLocalFile(this, displayUrl) -> {
                LocalFileLoader.getDisplayName(this, LocalFileLoader.parseUri(displayUrl))
            }
            LocalFileLoader.isPreviewUrl(displayUrl) -> {
                tabManager.activeTab.value?.title?.takeIf { it.isNotBlank() } ?: "Web Archive"
            }
            else -> try {
                Uri.parse(displayUrl).host ?: displayUrl
            } catch (e: Exception) {
                displayUrl
            }
        }

        if (!searchController.isSearchMode) {
            binding.etUrl.setText(host)
        }
    }

    private fun updateSearchEngineIcon() {
        SearchEngineIconHelper.loadSearchEngineIcon(this, binding.btnSearchEngine, preferences.searchEngine)
    }

    fun updateHomeButtonAppearance(displayUrl: String = "") {
        val currentTab = tabManager.activeTab.value
        val effectiveUrl = displayUrl.ifBlank { currentTab?.url ?: "" }
        val isLocalDoc = isLocalFileView(currentTab) ||
                LocalFileLoader.isLocalFile(this, effectiveUrl) ||
                LocalFileLoader.isPreviewUrl(effectiveUrl)

        if (isLocalDoc) {
            binding.btnHome.setImageResource(R.drawable.ic_arrow_back)
            binding.btnHome.contentDescription = getString(R.string.back)
        } else if (preferences.isHomeReplacedWithRefresh) {
            binding.btnHome.setImageResource(R.drawable.ic_refresh)
            binding.btnHome.contentDescription = getString(R.string.refresh)
        } else {
            binding.btnHome.setImageResource(R.drawable.ic_home)
            binding.btnHome.contentDescription = getString(R.string.home)
        }
    }

    private fun updateTabBadgeCount() {
        val count = tabManager.getOpenTabCount()
        binding.tvTabCount.text = count.toString()
    }

    fun checkNotificationPermissionForDownloads() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                isSystemDialogOrChooserPending = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun checkAndRequestStoragePermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingStorageAction = onGranted
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        onGranted()
    }

    private fun launchVoiceSearch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceSearchIntent()
        } else {
            voiceSearchMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceSearchIntent() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_search))
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice search unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    fun showCustomFullscreenVideo(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (::pipController.isInitialized) {
            pipController.showCustomFullscreenVideo(view, callback)
        } else {
            callback.onCustomViewHidden()
        }
    }

    fun hideCustomFullscreenVideo() {
        if (::pipController.isInitialized) {
            pipController.hideCustomFullscreenVideo()
        }
    }

    fun updatePipParams(
        isVideoPlaying: Boolean = if (::pipController.isInitialized) pipController.isActiveTabVideoPlaying() else MediaPlaybackBridge.isVideoPlaying,
        width: Int = MediaPlaybackBridge.lastVideoWidth,
        height: Int = MediaPlaybackBridge.lastVideoHeight,
        shouldAutoEnter: Boolean = isVideoPlaying
    ) {
        if (::pipController.isInitialized) {
            pipController.updatePipParams(isVideoPlaying, width, height, shouldAutoEnter)
        }
    }

    fun requestInPageVideoPip() {
        if (::pipController.isInitialized) {
            pipController.requestInPageVideoPip()
        }
    }

    fun enterPipMode() {
        if (::pipController.isInitialized) {
            pipController.enterPipMode()
        }
    }

    private fun setupMediaPlaybackListener() {
        MediaPlaybackService.mediaActionListener = object : MediaPlaybackService.MediaActionListener {
            override fun onPlayMedia() {
                runOnUiThread {
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.playAllMediaScript, null)
                }
            }

            override fun onPauseMedia() {
                runOnUiThread {
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
                }
            }

            override fun onSeekMedia(deltaSeconds: Int) {
                runOnUiThread {
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.getSeekMediaScript(deltaSeconds), null)
                }
            }

            override fun onSeekToMedia(positionMs: Long) {
                runOnUiThread {
                    val posSec = positionMs / 1000.0
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.getSeekToPositionScript(posSec), null)
                }
            }

            override fun onSkipNextMedia() {
                runOnUiThread {
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.skipNextMediaScript, null)
                }
            }

            override fun onSkipPreviousMedia() {
                runOnUiThread {
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.skipPreviousMediaScript, null)
                }
            }

            override fun onStopMedia() {
                runOnUiThread {
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
                    MediaPlaybackBridge.resetMediaPlayback(this@MainActivity)
                }
            }
        }

        MediaPlaybackBridge.onMediaPlaybackStartedListener = { playingWebView ->
            runOnUiThread {
                tabManager.normalTabs.value.forEach { tab ->
                    val wv = tabManager.getWebView(tab.id)
                    if (wv != null && wv != playingWebView) {
                        wv.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
                    }
                }
                tabManager.incognitoTabs.value.forEach { tab ->
                    val wv = tabManager.getWebView(tab.id)
                    if (wv != null && wv != playingWebView) {
                        wv.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
                    }
                }
            }
        }

        MediaPlaybackBridge.onMediaStateListener = { isPlaying, isVideo, width, height ->
            runOnUiThread {
                val inPip = isInPipMode()
                val activeVideo = isVideo && isPlaying && (inPip || (::pipController.isInitialized && pipController.isActiveTabVideoPlaying()))
                updatePipParams(activeVideo, width, height)
                if (inPip && isVideo && isPlaying && customVideoView == null) {
                    tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.isolateVideoForPipScript, null)
                }
                // Keep screen on while video is actively playing in-page
                if (isVideo && isPlaying) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else if (!isPlaying && !inPip && customVideoView == null) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        MediaPlaybackBridge.onVideoBoundsListener = { _, _, _, _ ->
            runOnUiThread {
                if (!isInPipMode() && MediaPlaybackBridge.isVideoPlaying && customVideoView == null) {
                    updatePipParams()
                }
            }
        }

        MediaPlaybackBridge.onPipRequestedListener = {
            runOnUiThread {
                if (!isInPipMode() && !justExitedPip) {
                    requestInPageVideoPip()
                }
            }
        }

        MediaPlaybackBridge.onPipExitListener = {
            runOnUiThread {
                if (customVideoView != null) {
                    hideCustomFullscreenVideo()
                }
            }
        }
    }

    private fun showSoftKeyboard() {
        binding.etUrl.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etUrl, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etUrl.windowToken, 0)
    }

    private var currentTranslateTargetCode: String = ""
    private var isUpdatingTranslateUi = false

    fun setTranslateLoading(isLoading: Boolean) {
        runOnUiThread {
            binding.pbTranslateLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun setupTranslateBar() {
        binding.toggleTranslateMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isUpdatingTranslateUi || !isChecked) return@addOnButtonCheckedListener
            val wv = tabManager.getActiveWebView() ?: return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnTranslateOriginal -> {
                    wv.evaluateJavascript(PageTranslateManager.restoreOriginalScript, null)
                    binding.pbTranslateLoading.visibility = View.GONE
                }
                R.id.btnTranslateTarget -> {
                    val code = currentTranslateTargetCode.ifBlank { preferences.targetTranslateLanguage }
                    binding.pbTranslateLoading.visibility = View.VISIBLE
                    wv.evaluateJavascript(PageTranslateManager.toggleTranslatedScript) { result ->
                        val res = result?.trim('"')
                        if (res != "translated") {
                            wv.evaluateJavascript(PageTranslateManager.getTranslateScript(code), null)
                        } else {
                            binding.pbTranslateLoading.visibility = View.GONE
                        }
                    }
                }
            }
        }

        binding.btnChangeTranslateLanguage.setOnClickListener {
            val dialog = LanguageSelectionDialog { code, name ->
                currentTranslateTargetCode = code
                preferences.targetTranslateLanguage = code
                preferences.targetTranslateLanguageName = name
                binding.btnTranslateTarget.text = name
                isUpdatingTranslateUi = true
                try {
                    binding.toggleTranslateMode.check(R.id.btnTranslateTarget)
                } finally {
                    isUpdatingTranslateUi = false
                }
                val wv = tabManager.getActiveWebView() ?: return@LanguageSelectionDialog
                binding.pbTranslateLoading.visibility = View.VISIBLE
                wv.evaluateJavascript(PageTranslateManager.getTranslateScript(code), null)
            }
            dialog.show(supportFragmentManager, "LanguageSelectionDialog")
        }

        binding.btnCloseTranslate.setOnClickListener {
            hideTranslateBar(restoreOriginal = true)
        }
    }

    private fun showTranslateBar(targetCode: String, targetName: String) {
        currentTranslateTargetCode = targetCode
        binding.btnTranslateTarget.text = targetName
        isUpdatingTranslateUi = true
        try {
            binding.toggleTranslateMode.check(R.id.btnTranslateTarget)
        } finally {
            isUpdatingTranslateUi = false
        }
        showToolbar(animated = false)
        binding.translateBar.visibility = View.VISIBLE
    }

    private fun hideTranslateBar(restoreOriginal: Boolean = false) {
        binding.translateBar.visibility = View.GONE
        binding.pbTranslateLoading.visibility = View.GONE
        if (restoreOriginal) {
            tabManager.getActiveWebView()?.evaluateJavascript(PageTranslateManager.restoreOriginalScript, null)
        }
    }

    private fun translateCurrentPage(langCode: String) {
        val activeTab = tabManager.activeTab.value ?: return
        val currentUrl = activeTab.url
        if (currentUrl.isBlank() || currentUrl.startsWith("onyx://") || currentUrl.startsWith("about:") || LocalFileLoader.isLocalFile(this, currentUrl)) {
            Toast.makeText(this, "Cannot translate internal page", Toast.LENGTH_SHORT).show()
            return
        }

        val targetCode = if (langCode.isNotBlank()) langCode else preferences.targetTranslateLanguage
        val targetName = preferences.targetTranslateLanguageName

        val webView = tabManager.getActiveWebView()
        if (webView == null) {
            Toast.makeText(this, "No active webpage to translate", Toast.LENGTH_SHORT).show()
            return
        }

        showTranslateBar(targetCode, targetName)
        binding.pbTranslateLoading.visibility = View.VISIBLE

        webView.evaluateJavascript(PageTranslateManager.getTranslateScript(targetCode), null)
    }

    private fun addCurrentPageToHomeScreen() {
        val activeTab = tabManager.activeTab.value ?: return
        val currentUrl = activeTab.url
        if (currentUrl.isBlank() || currentUrl.startsWith("onyx://") || currentUrl.startsWith("about:")) {
            Toast.makeText(this, "Cannot add internal page to Home screen", Toast.LENGTH_SHORT).show()
            return
        }
        val title = activeTab.title.ifBlank { currentUrl }
        val webView = tabManager.getActiveWebView()
        val favicon = webView?.favicon

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            val shortcutIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(currentUrl)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val iconCompat = if (favicon != null) {
                IconCompat.createWithBitmap(favicon)
            } else {
                IconCompat.createWithResource(this, R.mipmap.ic_launcher)
            }
            val pinShortcutInfo = ShortcutInfoCompat.Builder(this, "onyx_web_${currentUrl.hashCode()}")
                .setShortLabel(title.take(20))
                .setLongLabel(title)
                .setIcon(iconCompat)
                .setIntent(shortcutIntent)
                .build()
            ShortcutManagerCompat.requestPinShortcut(this, pinShortcutInfo, null)
            Toast.makeText(this, getString(R.string.added_to_home_screen), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Pin shortcut not supported by launcher", Toast.LENGTH_SHORT).show()
        }
    }

    private fun injectDeveloperTools() {
        val webView = tabManager.getActiveWebView() ?: return
        val activeTab = tabManager.activeTab.value
        if (activeTab == null || activeTab.url.isBlank() ||
            activeTab.url.startsWith("onyx://") || activeTab.url.startsWith("about:")) {
            Toast.makeText(this, "Developer tools can only run on active web pages", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val isDark = preferences.isDarkMode()
            DevToolsManager.toggleDevTools(this@MainActivity, webView, isDark) { status: String ->
                when (status) {
                    "shown" -> {
                        webView.isDevToolsActive = true
                        // No toast needed — the Eruda panel appearing is its own feedback
                    }
                    "hidden" -> {
                        // Panel is minimized but Eruda icon stays visible on screen
                        // Keep isDevToolsActive = true so auto-reinject fires on navigation
                        webView.isDevToolsActive = true
                    }
                    "failed" -> {
                        webView.isDevToolsActive = false
                        Toast.makeText(this@MainActivity, "Unable to load developer tools", Toast.LENGTH_SHORT).show()
                    }
                    "error" -> {
                        webView.isDevToolsActive = false
                        Toast.makeText(this@MainActivity, "Developer tools encountered an error", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        // Unexpected — do not change state
                    }
                }
            }
        }
    }

    private fun startElementBlocker() {
        val webView = tabManager.getActiveWebView() ?: return
        val activeTab = tabManager.activeTab.value
        if (activeTab == null || activeTab.url.isBlank() ||
            activeTab.url.startsWith("onyx://") || activeTab.url.startsWith("about:")) {
            Toast.makeText(this, "Element blocker can only run on active web pages", Toast.LENGTH_SHORT).show()
            return
        }
        com.onyx.browser.ui.menu.ElementPickerManager.startPicker(webView)
        Toast.makeText(this, R.string.block_element_hint, Toast.LENGTH_SHORT).show()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Don't intercept back in PiP mode — system manages PiP dismissal
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                    return
                }

                if (customVideoView != null || binding.fullscreenCustomViewContainer.visibility == View.VISIBLE) {
                    hideCustomFullscreenVideo()
                    return
                }

                // Cancel element picker if active — back press exits picker, not page
                if (com.onyx.browser.ui.menu.ElementPickerManager.isPickerActive) {
                    val wv = tabManager.getActiveWebView()
                    if (wv != null) com.onyx.browser.ui.menu.ElementPickerManager.cancelPicker(wv)
                    return
                }

                if (binding.topBar.visibility != View.VISIBLE) {
                    binding.topBar.visibility = View.VISIBLE
                    binding.topBarDivider.visibility = View.VISIBLE
                    val activeTab = tabManager.activeTab.value
                    if (activeTab != null && activeTab.url.isNotBlank() &&
                        !activeTab.url.startsWith("onyx://") && !activeTab.url.startsWith("about:")) {
                        binding.webViewContainer.visibility = View.VISIBLE
                        binding.webViewContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        binding.webViewContainer.requestLayout()
                    }
                    tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
                    return
                }

                if (findInPageController.isVisible) {
                    findInPageController.hide()
                    return
                }

                if (binding.translateBar.visibility == View.VISIBLE) {
                    hideTranslateBar(restoreOriginal = true)
                    return
                }

                if (searchController.isSearchMode) {
                    searchController.exitSearchMode()
                    return
                }

                val activeWebView = tabManager.getActiveWebView()
                if (activeWebView != null) {
                    val isErrorPage = (
                        activeWebView.currentSyntheticState != null ||
                        activeWebView.url?.startsWith("https://onyx.browser/", ignoreCase = true) == true ||
                        activeWebView.url?.startsWith("file:///android_asset/error_page.html", ignoreCase = true) == true
                    )
                    if (isErrorPage && handleErrorPageBack(activeWebView)) {
                        return
                    }
                }

                val activeTab = tabManager.activeTab.value
                val isLocalPreview = isLocalFileView(activeTab)

                if (isLocalPreview && activeTab != null) {
                    val anchorStep = activeWebView?.let { findPreviousValidLocalPreviewStep(it, activeTab.url) }
                    if (anchorStep != null) {
                        activeWebView?.goBackOrForward(anchorStep)
                        return
                    }

                    val tabId = activeTab.id
                    val wasFromDownloads = previewTabsFromDownloads.remove(tabId)
                    val wasFromExternal = previewTabsFromExternal.remove(tabId)
                    localFileViewTabs.remove(tabId)

                    // If opened from an external app (ACTION_VIEW / external task): call finish() to return to the file manager
                    if (wasFromExternal) {
                        tabManager.closeTab(activeTab)
                        finish()
                        return
                    }

                    // If opened from within Onyx (with a parent tab or Downloads behind it):
                    // close the preview tab (tabManager.closeTab(activeTab)), restoring the previous tab or Downloads
                    tabManager.closeTab(activeTab)

                    if (wasFromDownloads) {
                        startActivity(Intent(this@MainActivity, DownloadsActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        })
                        return
                    }

                    // Never wipe the tab to "" or trap the user on a blank white page
                    return
                }

                if (activeWebView != null) {
                    val steps = findPreviousValidHistoryStep(activeWebView)
                    if (steps != null) {
                        activeWebView.goBackOrForward(steps)
                        return
                    }
                }

                if (activeTab != null) {
                    val parentId = activeTab.parentId
                    val parentTab = if (!parentId.isNullOrBlank()) {
                        tabManager.getTabById(parentId)
                    } else null

                    if (parentTab != null) {
                        // This tab was opened from another tab (e.g. redirected or opened in new tab).
                        // Since all history steps in this tab are gone, pressing back closes this tab
                        // and returns to the original tab from where the user was redirected.
                        tabManager.closeTab(activeTab)
                        return
                    }

                    if (binding.homeLayout.root.visibility != View.VISIBLE && (activeTab.url.isNotBlank() && !activeTab.url.startsWith("about:blank", ignoreCase = true))) {
                        // All navigation steps in this tab are exhausted.
                        // Standard browser parity (Chrome/Brave/Firefox): Minimize the app to background,
                        // preserving the tab and its URL intact for instant resumption.
                        moveTaskToBack(true)
                        return
                    }
                }

                // Default activity back behavior: minimize to home screen
                moveTaskToBack(true)
            }
        })
    }

    fun handleErrorPageBack(webView: OnyxWebView): Boolean {
        val activeTab = tabManager.activeTab.value
        val steps = findPreviousValidHistoryStep(webView)

        if (steps != null) {
            webView.clearSyntheticState()
            webView.goBackOrForward(steps)
            return true
        }

        // No valid previous history step in this tab — return to home screen or parent tab
        webView.clearSyntheticState()
        webView.stopLoading()
        webView.loadUrl("about:blank")

        if (activeTab != null) {
            val parentId = activeTab.parentId
            val parentTab = if (!parentId.isNullOrBlank()) {
                tabManager.getTabById(parentId)
            } else null

            if (parentTab != null) {
                tabManager.closeTab(activeTab)
                return true
            }

            tabManager.updateActiveTab("", "New Tab")
            showHomeScreen()
            return true
        }

        showHomeScreen()
        return true
    }

    private fun findPreviousValidLocalPreviewStep(webView: OnyxWebView, currentFileUrl: String): Int? {
        val list = try { webView.copyBackForwardList() } catch (_: Throwable) { return null }
        val currentIndex = list.currentIndex
        if (currentIndex <= 0) return null

        val currentNormalized = currentFileUrl.substringBefore('#').trimEnd('/')

        for (i in currentIndex - 1 downTo 0) {
            val item = list.getItemAtIndex(i) ?: continue
            val itemUrl = item.url ?: continue

            // Skip blank, data, or synthetic error page URLs
            if (itemUrl.isBlank() ||
                itemUrl == "about:blank" ||
                itemUrl.startsWith("about:blank", ignoreCase = true) ||
                OnyxWebView.isSyntheticOrDataUrl(itemUrl) ||
                itemUrl.startsWith("https://onyx.browser/", ignoreCase = true) ||
                itemUrl.startsWith("file:///android_asset/error_page.html", ignoreCase = true)
            ) {
                continue
            }

            // Only allow back navigation within the preview if it is an in-document anchor to the same local file
            val itemNormalized = itemUrl.substringBefore('#').trimEnd('/')
            if (itemNormalized.equals(currentNormalized, ignoreCase = true) && itemUrl != webView.url) {
                return i - currentIndex
            }
        }

        return null
    }

    private fun findPreviousValidHistoryStep(webView: OnyxWebView): Int? {
        val list = try { webView.copyBackForwardList() } catch (_: Throwable) { return null }
        val currentIndex = list.currentIndex
        if (currentIndex <= 0) return null

        val failingUrl = webView.currentSyntheticState?.failingUrl ?: webView.lastFailingUrl ?: ""
        val normalizedFailing = failingUrl.trimEnd('/')
        val failingWithoutScheme = normalizedFailing.removePrefix("https://").removePrefix("http://")

        for (i in currentIndex - 1 downTo 0) {
            val item = list.getItemAtIndex(i) ?: continue
            val itemUrl = item.url ?: continue
            val normalizedItem = itemUrl.trimEnd('/')
            val itemWithoutScheme = normalizedItem.removePrefix("https://").removePrefix("http://")

            // Skip blank, data, or synthetic error page URLs
            if (itemUrl.isBlank() ||
                itemUrl == "about:blank" ||
                itemUrl.startsWith("about:blank", ignoreCase = true) ||
                OnyxWebView.isSyntheticOrDataUrl(itemUrl) ||
                itemUrl.startsWith("https://onyx.browser/", ignoreCase = true) ||
                itemUrl.startsWith("file:///android_asset/error_page.html", ignoreCase = true)
            ) {
                continue
            }

            // Skip the failing URL itself (or redirects between http/https)
            if (failingWithoutScheme.isNotBlank() && itemWithoutScheme.equals(failingWithoutScheme, ignoreCase = true)) {
                continue
            }

            // Valid previous webpage found!
            return i - currentIndex
        }

        return null
    }

    override fun onPause() {
        super.onPause()
        val activeTabId = tabManager.activeTab.value?.id
        val activeWebView = tabManager.getActiveWebView()
        if (activeTabId != null && activeWebView != null) {
            tabManager.captureTabSnapshot(activeTabId, activeWebView)
            tabManager.saveTabState(activeTabId, activeWebView)
        }
        if (!preferences.isPipEnabled) {
            updatePipParams(isVideoPlaying = false)
        }
        // Playback is never paused here: onPause also fires for PiP entry, multi-window focus
        // loss and system dialogs. Pause/keep-alive decisions are made in onStop, which is not
        // called while the activity is visible in PiP.
        val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        val isPlayingAnyMedia = MediaPlaybackBridge.isVideoPlaying || MediaPlaybackBridge.isAudioOrVideoPlaying
        if (!isPip && preferences.isBackgroundPlayEnabled && isPlayingAnyMedia) {
            // Promote to a foreground service while still visible (Android 12+ FGS start rules).
            MediaPlaybackService.start(
                this,
                MediaPlaybackBridge.currentTitle,
                MediaPlaybackBridge.currentArtist,
                MediaPlaybackBridge.currentArtworkUrl,
                MediaPlaybackBridge.currentPositionMs,
                MediaPlaybackBridge.currentDurationMs,
                true
            )
        }
    }



    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tabManager.saveAllTabStates()
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkRecoveryCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    val wv = tabManager.getActiveWebView() ?: return@runOnUiThread
                    val state = wv.currentSyntheticState
                    if (state?.category == com.onyx.browser.web.error.SyntheticNavigationState.ErrorCategory.OFFLINE) {
                        val url = state.failingUrl.ifBlank { wv.lastFailingUrl ?: "" }
                        wv.clearSyntheticState()
                        if (url.isNotBlank() && !LocalFileLoader.isLocalFile(this@MainActivity, url)) {
                            wv.loadUrl(url)
                        } else {
                            wv.reload()
                        }
                    }
                }
            }
        }
        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {}
    }

    private fun unregisterNetworkRecoveryCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    override fun onStart() {
        super.onStart()
        MediaPlaybackBridge.isAppInBackground = false
        registerNetworkRecoveryCallback()
        
        val activeTab = tabManager.activeTab.value
        if (activeTab?.isIncognito == true && preferences.isBiometricIncognitoEnabled && !tabManager.isIncognitoUnlocked) {
            // Show overlay to obscure the webview
            binding.incognitoLockedOverlay.visibility = android.view.View.VISIBLE
            
            // Must authenticate
            val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
            val biometricPrompt = androidx.biometric.BiometricPrompt(this, executor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        tabManager.isIncognitoUnlocked = true
                        binding.incognitoLockedOverlay.visibility = android.view.View.GONE
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        binding.incognitoLockedOverlay.visibility = android.view.View.GONE
                        switchToNormalTabOrNew()
                    }
                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        // Allow user retry on biometric prompt UI
                    }
                })

            val promptInfo = com.onyx.browser.ui.common.BiometricAuthHelper.createPromptInfo(
                title = "Unlock Incognito Tab",
                subtitle = "Confirm identity to resume browsing"
            )
            biometricPrompt.authenticate(promptInfo)
        }
    }
    
    private fun switchToNormalTabOrNew() {
        val normalTabs = tabManager.normalTabs.value
        if (normalTabs.isNotEmpty()) {
            tabManager.selectTab(normalTabs.first())
        } else {
            tabManager.clearActiveTab()
            showHomeScreen()
            updateTabBadgeCount()
        }
    }

    override fun onStop() {
        super.onStop()
        tabManager.saveAllTabStates()
        unregisterNetworkRecoveryCallback()
        tabManager.isIncognitoUnlocked = false
        val isPip = isInPipMode()
        val wasDismissedFromPip = ::pipController.isInitialized && pipController.consumePendingPipClose()

        // Deterministic PiP close detection:
        // If onPictureInPictureModeChanged(false) transitioned directly to onStop() without onResume(),
        // and the device screen is interactive (user tapped 'X' on PiP window rather than locking screen),
        // treat it as an explicit user pause and tear down playback cleanly.
        if (wasDismissedFromPip) {
            val screenOff = MediaPlaybackBridge.isScreenOffOrLocked(this)
            if (customVideoView != null) {
                hideCustomFullscreenVideo()
            }
            tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
            if (!screenOff || !preferences.isBackgroundPlayEnabled) {
                MediaPlaybackBridge.isExplicitUserPause = true
                MediaPlaybackBridge.isVideoPlaying = false
                MediaPlaybackBridge.isAudioOrVideoPlaying = false
                val targetWv = getMediaTargetWebView() ?: tabManager.getActiveWebView()
                targetWv?.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
                MediaPlaybackService.stopNotificationOnly(this)
                tabManager.getAllWebViews().forEach { it.onPause() }
                MediaPlaybackBridge.isAppInBackground = true
                return
            }
        }

        // Still visible in PiP (e.g. screen turned off while in PiP): leave playback untouched.
        if (isPip) return

        MediaPlaybackBridge.isAppInBackground = true
        val isPlayingAnyMedia = MediaPlaybackBridge.isVideoPlaying || MediaPlaybackBridge.isAudioOrVideoPlaying
        val mediaTabWv = getMediaTargetWebView() ?: tabManager.getActiveWebView()

        if (preferences.isBackgroundPlayEnabled && isPlayingAnyMedia) {
            // Background play: keep the media tab fully running, suspend every other tab.
            tabManager.getAllWebViews().forEach { wv ->
                if (wv == mediaTabWv) {
                    wv.evaluateJavascript(MediaPlaybackManager.getSetBackgroundStateScript(true), null)
                } else {
                    wv.onPause()
                }
            }
            mediaTabWv?.resumeTimers()
        } else if (!isSystemDialogOrChooserPending) {
            // No background play: WebView.onPause() alone does not stop HTML5 audio, so pause explicitly.
            if (isPlayingAnyMedia) {
                mediaTabWv?.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
            }
            tabManager.getActiveWebView()?.onPause()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Never auto-enter PiP when opening internal Onyx screens, Share sheets, or system dialogs
        if (isLaunchingChildActivity || isSystemDialogOrChooserPending) return
        // On Android 12+ the system auto-enters PiP when setAutoEnterEnabled(true) is armed;
        // calling enterPictureInPictureMode() again would double-trigger the transition.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ::pipController.isInitialized && pipController.isAutoEnterArmed) return
        if (preferences.isPipEnabled && !justExitedPip && !isInPipMode()) {
            if (::pipController.isInitialized && pipController.isActiveTabVideoPlaying()) {
                // Manual PiP entry for Android 8-11, and fallback on 12+ when auto-enter was not armed
                pipController.enterPipMode()
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::pipController.isInitialized) {
            pipController.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        }
    }

    private fun maybeDeferNightModeRecreate(isPip: Boolean) {
        if (isPip) {
            pendingNightModeRecreate = true
        } else {
            pendingNightModeRecreate = false
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        isLaunchingChildActivity = false
        MediaPlaybackBridge.isAppInBackground = false
        if (MediaPlaybackBridge.isMediaPlaying) {
            MediaPlaybackBridge.isExplicitUserPause = false
        }

        val isPip = isInPipMode()
        if (!isPip && ::pipController.isInitialized) {
            pipController.onActivityResumed()
        }

        // 1. Sync theme if changed in Settings or system dark mode toggled
        preferences.applyTheme()
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (!isPip && (pendingNightModeRecreate ||
            (lastThemeMode != -1 && lastThemeMode != preferences.themeMode) ||
            (lastNightMode != -1 && lastNightMode != currentNightMode) ||
            (lastAccentColor.isNotBlank() && lastAccentColor != preferences.accentColorHex))) {
            pendingNightModeRecreate = false
            lastThemeMode = preferences.themeMode
            lastNightMode = currentNightMode
            lastAccentColor = preferences.accentColorHex
            recreate()
            return
        }

        // 2. Sync search engine & update home widgets
        updateSearchEngineIcon()
        updateHomeButtonAppearance()
        com.onyx.browser.ui.widget.SearchWidgetProvider.updateAllWidgets(this)

        // 3. Sync scroll to top FAB visibility & auto-hide toolbar state
        if (!preferences.isScrollToTopEnabled) {
            binding.fabScrollToTop.visibility = View.GONE
        }
        if (!preferences.isAutoHideToolbarEnabled && !isPip && customVideoView == null) {
            showToolbar(animated = false)
        }

        // 4. Sync webview settings
        isSystemDialogOrChooserPending = false
        val wv = tabManager.getActiveWebView()
        wv?.onResume()
        wv?.applyUserAgentForUrl(wv.url ?: "")
        if (wv != null) {
            wv.applyCookieSettings()
            // JS enablement is managed by applySiteSettingsForUrl (called above in applyUserAgentForUrl),
            // which correctly applies both the global isJavaScriptEnabledGlobal toggle and
            // per-site exceptions. Do NOT blindly overwrite it here.
        }

        // 5. Sync pull to refresh, autofill & force dark settings
        binding.swipeRefreshLayout.isEnabled = preferences.isPullToRefreshEnabled
        tabManager.getAllWebViews().forEach {
            it.syncAutofillSettings()
            it.applyForceDark()
        }

        updatePipParams()
        binding.root.post {
            wv?.resumeTimers()
            wv?.requestFocus()
        }
        tabManager.getAllWebViews().forEach { v ->
            v.evaluateJavascript(
                MediaPlaybackManager.getSetBackgroundStateScript(false),
                null
            )
        }

        // Restore screen-on flag if video was still playing when we came back to the app
        if (MediaPlaybackBridge.isVideoPlaying || customVideoView != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        // Guarantee cleanup of custom view container and restoration of toolbar
        if (!isPip) {
            if (customVideoView == null) {
                binding.fullscreenCustomViewContainer.visibility = View.GONE
                binding.fullscreenControlsOverlay.visibility = View.GONE
                if (lastStatusBarTopInset > 0) {
                    binding.statusBarSpace.visibility = View.VISIBLE
                }
                binding.topBar.visibility = View.VISIBLE
                binding.topBarDivider.visibility = View.VISIBLE
                val activeTab = tabManager.activeTab.value
                if (activeTab != null && activeTab.url.isNotBlank() &&
                    !activeTab.url.startsWith("onyx://") && !activeTab.url.startsWith("about:")) {
                    binding.homeLayout.root.visibility = View.GONE
                    binding.webViewContainer.visibility = View.VISIBLE
                    binding.webViewContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
                androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                if (justExitedPip) {
                    wv?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
                }
                binding.topBar.requestLayout()
                binding.webViewContainer.requestLayout()
            } else if (::pipController.isInitialized) {
                pipController.onFullscreenUserInteraction()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isPip = isInPipMode()
        val newNightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (lastNightMode != -1 && lastNightMode != newNightMode) {
            lastNightMode = newNightMode
            maybeDeferNightModeRecreate(isPip)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val wv = tabManager.getActiveWebView()
            wv?.resumeTimers()
            wv?.requestFocus()
            val isPip = isInPipMode()
            if (!isPip && customVideoView == null) {
                if (!isToolbarHidden && binding.topBar.visibility != View.VISIBLE) {
                    binding.topBar.visibility = View.VISIBLE
                    binding.topBarDivider.visibility = View.VISIBLE
                }
                val activeTab = tabManager.activeTab.value
                if (activeTab != null && activeTab.url.isNotBlank() &&
                    !activeTab.url.startsWith("onyx://") && !activeTab.url.startsWith("about:")) {
                    if (binding.webViewContainer.visibility != View.VISIBLE) {
                        binding.webViewContainer.visibility = View.VISIBLE
                        binding.webViewContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        binding.webViewContainer.requestLayout()
                    }
                }
                if (justExitedPip) {
                    wv?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isTabsRestored) {
            handleIncomingIntent(intent)
        } else {
            pendingIntent = intent
        }
    }


    private fun handleShortcuts(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action ?: return false
        when (action) {
            ACTION_WIDGET_SEARCH, "com.onyx.browser.action.SEARCH" -> {
                intent.action = null
                binding.root.post {
                    searchController.enterSearchMode()
                }
                return true
            }
            ACTION_WIDGET_VOICE_SEARCH -> {
                intent.action = null
                binding.root.post {
                    launchVoiceSearch()
                }
                return true
            }
            ACTION_WIDGET_INCOGNITO_SEARCH -> {
                intent.action = null
                val newTab = tabManager.createNewTab(url = "", isIncognito = true)
                displayTab(newTab)
                binding.root.post {
                    searchController.enterSearchMode()
                }
                return true
            }
            "com.onyx.browser.action.NEW_INCOGNITO_TAB" -> {
                intent.action = null
                val newTab = tabManager.createNewTab(url = "", isIncognito = true)
                displayTab(newTab)
                return true
            }
            "com.onyx.browser.action.SCAN_QR" -> {
                intent.action = null
                startQrScanner()
                return true
            }
        }
        return false
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (handleShortcuts(intent)) return
        if (intent == null) return
        val targetUrl = extractUrlFromIntent(intent) ?: return

        val isFromDownloads = intent.getBooleanExtra(EXTRA_FROM_DOWNLOADS, false)
        val isFromExternal = !isFromDownloads && (
            intent.action == Intent.ACTION_VIEW ||
            intent.categories?.contains(Intent.CATEGORY_BROWSABLE) == true ||
            intent.data != null
        )
        val isLocalDoc = com.onyx.browser.web.LocalFileLoader.isLocalFile(this, targetUrl) ||
                com.onyx.browser.web.LocalFileLoader.isPreviewUrl(targetUrl)

        val currentTab = tabManager.activeTab.value
        val targetTab = if (currentTab != null && !currentTab.isIncognito && currentTab.url.isBlank()) {
            // Current tab is an unused blank normal tab: reuse it
            tabManager.updateActiveTab(targetUrl, targetUrl)
            showWebView(currentTab, forceUrl = targetUrl)
            currentTab
        } else {
            // Create a new normal tab for the incoming link so existing tabs remain intact
            val newTab = tabManager.createNewTab(url = targetUrl, isIncognito = false, parentId = currentTab?.id)
            displayTab(newTab)
            newTab
        }

        if (isLocalDoc || intent.action == Intent.ACTION_VIEW) {
            localFileViewTabs.add(targetTab.id)
            if (isFromDownloads) {
                previewTabsFromDownloads.add(targetTab.id)
            } else if (isFromExternal) {
                previewTabsFromExternal.add(targetTab.id)
            }
        }

        if (searchController.isSearchMode) {
            searchController.exitSearchMode()
        }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null

        // Priority 1: Direct data URI from file manager, link click, or download item
        val dataUri = intent.data ?: intent.clipData?.let { if (it.itemCount > 0) it.getItemAt(0).uri else null }
        if (dataUri != null) {
            val uriStr = dataUri.toString().trim()
            if (uriStr.isNotBlank()) {
                return uriStr
            }
        }
        val dataString = intent.dataString?.trim()
        if (!dataString.isNullOrBlank()) {
            return dataString
        }

        val action = intent.action ?: return null

        when (action) {
            Intent.ACTION_VIEW, Intent.ACTION_SEND -> {
                val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!extraText.isNullOrBlank()) {
                    return parseUrlOrExtract(extraText)
                }
            }
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH -> {
                val query = intent.getStringExtra(SearchManager.QUERY)
                    ?: intent.getStringExtra("query")
                if (!query.isNullOrBlank()) {
                    return preferences.searchEngine.buildSearchUrl(query.trim())
                }
            }
        }
        return null
    }

    private fun parseUrlOrExtract(text: String): String {
        val trimmed = text.trim()
        if (LocalFileLoader.isSensitiveOrRestrictedPath(this, trimmed)) {
            return preferences.searchEngine.buildSearchUrl(trimmed)
        }
        if (LocalFileLoader.isLocalFile(this, trimmed) ||
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true)
        ) {
            return trimmed
        }
        val urlRegex = Regex("(https?://[^\\s]+)")
        val match = urlRegex.find(trimmed)
        if (match != null) {
            return match.value
        }
        if (isLikelyUrl(trimmed)) {
            return "https://$trimmed"
        }
        return preferences.searchEngine.buildSearchUrl(trimmed)
    }

    private fun enforceHighRefreshRate() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }
                val modes = display?.supportedModes
                val highestMode = modes?.maxByOrNull { it.refreshRate }
                if (highestMode != null) {
                    val params = window.attributes
                    params.preferredDisplayModeId = highestMode.modeId
                    window.attributes = params
                }
            } catch (_: Exception) {}
        }
    }

    private fun showSavePageDialog() {
        val activeWebView = tabManager.getActiveWebView() ?: return
        val currentTab = tabManager.activeTab.value ?: return
        pageExportManager.showSavePageDialog(activeWebView, currentTab.title)
    }

    fun openNewTab(isIncognito: Boolean = false) {
        val newTab = tabManager.createNewTab(url = "", isIncognito = isIncognito)
        displayTab(newTab)
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingFilePathCallback?.onReceiveValue(null)
        pendingFilePathCallback = null
        pendingCameraPhotoFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
        pendingCameraPhotoFile = null
        pendingCameraPhotoUri = null
        try { pendingWebMediaRequest?.let { safeDenyWebPermission(it) } } catch (_: Exception) {}
        pendingWebMediaRequest = null
        pendingWebMediaResources = null
        tabManager.saveAllTabStates()
        if (::pipController.isInitialized) {
            pipController.unregisterReceiver()
        }
        clipboardListener?.let { listener ->
            (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.removePrimaryClipChangedListener(listener)
            clipboardListener = null
        }
        MediaPlaybackService.mediaActionListener = null
        try {
            MediaPlaybackService.stop(applicationContext)
        } catch (_: Exception) {}
        if (isFinishing) {
            if (preferences.isClearOnExitEnabled) {
                try {
                    val db = AppDatabase.getInstance(applicationContext)
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.NonCancellable).launch {
                        try {
                            db.historyDao().clearAllHistory()
                        } catch (_: Throwable) {}
                    }
                    tabManager.getAllWebViews().forEach { it.clearCache(true) }
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.CookieManager.getInstance().flush()
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    android.webkit.WebViewDatabase.getInstance(applicationContext).clearHttpAuthUsernamePassword()
                    android.webkit.WebViewDatabase.getInstance(applicationContext).clearFormData()
                } catch (_: Throwable) {}
            }
            tabManager.closeAllTabs(incognitoOnly = true)
            com.onyx.browser.incognito.IncognitoNotificationHelper.dismissNotification(this)
            if (TabManager.activeInstance == tabManager) {
                TabManager.activeInstance = null
            }
        }
        tabManager.clearAllWebViews()
        if (currentInstance?.get() == this) {
            currentInstance = null
        }
    }
}
