package com.onyx.browser.ui.browser

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.core.content.ContextCompat
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.data.preferences.SitePermissionState
import com.onyx.browser.ui.dialog.WebPermissionBottomSheet
import java.util.Collections
import java.util.WeakHashMap

/**
 * Coordinates WebChromeClient permission flows including WebRTC Camera/Microphone/MIDI/EME
 * PermissionRequests, Geolocation prompts, and site Notification permission prompts.
 */
class WebChromeCoordinator(
    private val activity: MainActivity,
    private val preferences: BrowserPreferences
) {
    var pendingGeoOrigin: String? = null
    var pendingGeoCallback: GeolocationPermissions.Callback? = null
    var pendingGeoRemember: Boolean = true

    private val handledWebRequests = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<PermissionRequest, Boolean>())
    )
    var pendingWebMediaRequest: PermissionRequest? = null
    var pendingWebMediaResources: Array<String>? = null
    var pendingWebMediaNeedsAudio: Boolean = false
    var pendingWebMediaNeedsVideo: Boolean = false
    var pendingWebMediaHost: String? = null
    var pendingWebMediaRemember: Boolean = true

    fun showWebPermissionDialog(
        host: String,
        title: String,
        message: String,
        iconResId: Int = R.drawable.ic_lock,
        isHttps: Boolean = true,
        onAllowedAlways: () -> Unit,
        onAllowedWhileVisiting: () -> Unit,
        onDenied: (permanently: Boolean) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onDenied(false)
            return
        }
        val sheet = WebPermissionBottomSheet(
            host = host,
            permissionTitle = title,
            permissionDesc = message,
            iconResId = iconResId,
            isHttps = isHttps,
            onAllowedAlways = onAllowedAlways,
            onAllowedOnce = onAllowedWhileVisiting,
            onDenied = onDenied
        )
        sheet.show(activity.supportFragmentManager, WebPermissionBottomSheet.TAG)
    }

    fun showGeolocationPermissionDialog(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
        requestOsLocationPermissions: (Array<String>) -> Unit
    ) {
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

        val fine = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (locAllowed == true && (fine || coarse)) {
            try { callback.invoke(origin, true, true) } catch (_: Exception) {}
            return
        }

        if (activity.isFinishing || activity.isDestroyed) {
            try { callback.invoke(origin, false, false) } catch (_: Exception) {}
            return
        }

        if (locAllowed == null) {
            val isHttps = origin.startsWith("https://", ignoreCase = true)
            showWebPermissionDialog(
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
                        requestOsLocationPermissions(
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
                        requestOsLocationPermissions(
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
            requestOsLocationPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    fun onGeolocationPermissionsHidePrompt() {
        pendingGeoOrigin = null
        pendingGeoCallback = null
    }

    fun onLocationPermissionsResult(permissions: Map<String, Boolean>) {
        val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
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

    fun safeGrantWebPermission(request: PermissionRequest, resources: Array<String>) {
        if (handledWebRequests.contains(request)) return
        handledWebRequests.add(request)
        if (activity.isFinishing || activity.isDestroyed) {
            try { request.deny() } catch (_: Throwable) {}
            return
        }
        val requestedSet = request.resources?.toSet() ?: emptySet()
        val validResources = resources.filter { requestedSet.contains(it) }.toTypedArray()
        if (validResources.isEmpty()) {
            try { request.deny() } catch (_: Throwable) {}
            return
        }
        activity.runOnUiThread {
            try {
                activity.getActiveWebView()?.onResume()
                request.grant(validResources)
            } catch (t: Throwable) {
                android.util.Log.e("OnyxPermissions", "Failed to grant web media permissions", t)
                try { request.deny() } catch (_: Throwable) {}
            }
        }
    }

    fun safeDenyWebPermission(request: PermissionRequest) {
        if (handledWebRequests.contains(request)) return
        handledWebRequests.add(request)
        activity.runOnUiThread {
            try {
                request.deny()
            } catch (_: Exception) {}
        }
    }

    fun onWebMediaPermissionsResult(permissions: Map<String, Boolean>) {
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

        if (request == null || allResources == null) return

        val audioGranted = !needsAudio || (permissions[Manifest.permission.RECORD_AUDIO] == true) ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val videoGranted = !needsVideo || (permissions[Manifest.permission.CAMERA] == true) ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

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

    fun handleWebPermissionRequest(
        request: PermissionRequest?,
        requestOsMediaPermissions: (Array<String>) -> Unit
    ) {
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
            grantWebMediaPermission(request, needsAudio, needsVideo, resources, host, remember = false, requestOsMediaPermissions)
            return
        }

        if (camAllowed == true && micAllowed == false) {
            grantWebMediaPermission(request, needsAudio = false, needsVideo = true, arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE), host, remember = false, requestOsMediaPermissions)
            return
        }
        if (camAllowed == false && micAllowed == true) {
            grantWebMediaPermission(request, needsAudio = true, needsVideo = false, arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE), host, remember = false, requestOsMediaPermissions)
            return
        }

        if (activity.isFinishing || activity.isDestroyed) {
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

        showWebPermissionDialog(
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
                grantWebMediaPermission(request, grantAudio, grantVideo, toGrant.toTypedArray(), host, remember = false, requestOsMediaPermissions)
            },
            onAllowedWhileVisiting = {
                val grantAudio = (needsAudio && micAllowed == true) || promptForMic
                val grantVideo = (needsVideo && camAllowed == true) || promptForCam
                val toGrant = mutableListOf<String>()
                if (grantAudio) toGrant.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (grantVideo) toGrant.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                grantWebMediaPermission(request, grantAudio, grantVideo, toGrant.toTypedArray(), host, remember = false, requestOsMediaPermissions)
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
                    grantWebMediaPermission(request, grantAudio, grantVideo, toGrant.toTypedArray(), host, remember = false, requestOsMediaPermissions)
                } else {
                    safeDenyWebPermission(request)
                }
            }
        )
    }

    fun grantWebMediaPermission(
        request: PermissionRequest,
        needsAudio: Boolean,
        needsVideo: Boolean,
        allResources: Array<String>,
        host: String,
        remember: Boolean,
        requestOsMediaPermissions: (Array<String>) -> Unit
    ) {
        val hasAudio = !needsAudio || ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasVideo = !needsVideo || ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

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

            requestOsMediaPermissions(neededOs.toTypedArray())
        }
    }

    fun handleWebPermissionRequestCanceled(request: PermissionRequest?) {
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

    fun requestNotificationPermissionForSite(
        host: String,
        requestOsNotificationPermission: () -> Unit,
        callback: (String) -> Unit
    ) {
        if (host.isBlank() || activity.isFinishing || activity.isDestroyed) {
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
        showWebPermissionDialog(
            host = host,
            title = "$host wants to send notifications",
            message = "Allow $host to send you browser notifications?",
            iconResId = R.drawable.ic_bell,
            isHttps = true,
            onAllowedAlways = {
                preferences.setSiteNotificationsPermission(host, SitePermissionState.ALLOWED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestOsNotificationPermission()
                }
                callback("granted")
            },
            onAllowedWhileVisiting = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestOsNotificationPermission()
                }
                callback("granted")
            },
            onDenied = { permanently ->
                if (permanently) {
                    preferences.setSiteNotificationsPermission(host, SitePermissionState.BLOCKED)
                }
                callback("denied")
            }
        )
    }

    fun cleanupOnDestroy() {
        try {
            pendingWebMediaRequest?.let { safeDenyWebPermission(it) }
        } catch (_: Exception) {}
        pendingWebMediaRequest = null
        pendingWebMediaResources = null
        pendingGeoOrigin = null
        pendingGeoCallback = null
    }
}
