package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.data.preferences.SitePermissionState
import com.onyx.browser.databinding.BottomSheetSitePermissionCategoryBinding

class SitePermissionCategorySheet(
    private val categoryType: Int,
    private val onPermissionChanged: () -> Unit
) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "SitePermissionCategorySheet"
        const val CATEGORY_CAMERA = 1
        const val CATEGORY_MIC = 2
        const val CATEGORY_LOCATION = 3
        const val CATEGORY_NOTIFICATIONS = 4
        const val CATEGORY_JAVASCRIPT = 5
        const val CATEGORY_POPUPS = 6
        const val CATEGORY_AUTOPLAY = 7
        const val CATEGORY_IMAGES = 8
        const val CATEGORY_ZOOM = 9
        const val CATEGORY_MIXED_CONTENT = 10
        const val CATEGORY_DESKTOP = 11
        const val CATEGORY_GOOGLE_SIGNIN = 12
        const val CATEGORY_AUTO_SHRED = 13
        const val CATEGORY_SENSORS = 14
        const val CATEGORY_CLIPBOARD = 15
        const val CATEGORY_SOUND = 16
        const val CATEGORY_AUTO_DOWNLOADS = 17
        const val CATEGORY_USB = 18
        const val CATEGORY_SERIAL = 19
        const val CATEGORY_HID = 20
        const val CATEGORY_MIDI = 21
        const val CATEGORY_HAND_TRACKING = 22
        const val CATEGORY_IDLE = 23
        const val CATEGORY_FONTS = 24
        const val CATEGORY_BLUETOOTH = 25
        const val CATEGORY_WAKE_LOCK = 26
        const val CATEGORY_PAYMENT = 27
        const val CATEGORY_FILE_EDITING = 28
        const val CATEGORY_VR = 29
        const val CATEGORY_NFC = 30
        const val CATEGORY_WINDOW_MANAGEMENT = 31
        const val CATEGORY_STORAGE_ACCESS = 32
        const val CATEGORY_WEBRTC = 33
    }

    private var _binding: BottomSheetSitePermissionCategoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: BrowserPreferences

    override fun getTheme(): Int = R.style.Theme_OnyxBrowser_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSitePermissionCategoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { d ->
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = BrowserPreferences.getInstance(requireContext())
        setupHeader()
        setupGlobalToggle()
        populateSiteLists()
        binding.btnAddSiteException.setOnClickListener { showAddExceptionDialog() }
    }

    private fun setupHeader() {
        val (iconRes, titleRes) = when (categoryType) {
            CATEGORY_CAMERA -> Pair(R.drawable.ic_camera, R.string.camera_settings_title)
            CATEGORY_MIC -> Pair(R.drawable.ic_mic, R.string.mic_settings_title)
            CATEGORY_LOCATION -> Pair(R.drawable.ic_location, R.string.location_settings_title)
            CATEGORY_NOTIFICATIONS -> Pair(R.drawable.ic_bell, R.string.notifications_settings_title)
            CATEGORY_JAVASCRIPT -> Pair(R.drawable.ic_code, R.string.javascript_settings_title)
            CATEGORY_POPUPS -> Pair(R.drawable.ic_open_in_new, R.string.popups_settings_title)
            CATEGORY_AUTOPLAY -> Pair(R.drawable.ic_play_circle, R.string.autoplay_settings_title)
            CATEGORY_IMAGES -> Pair(R.drawable.ic_image, R.string.images_settings_title)
            CATEGORY_ZOOM -> Pair(R.drawable.ic_aspect_ratio, R.string.zoom_settings_title)
            CATEGORY_MIXED_CONTENT -> Pair(R.drawable.ic_lock, R.string.mixed_content_settings_title)
            CATEGORY_DESKTOP -> Pair(R.drawable.ic_desktop, R.string.desktop_site_settings_title)
            CATEGORY_GOOGLE_SIGNIN -> Pair(R.drawable.ic_brand_google, R.string.google_signin_settings_title)
            CATEGORY_AUTO_SHRED -> Pair(R.drawable.ic_broom, R.string.auto_shred_settings_title)
            CATEGORY_SENSORS -> Pair(R.drawable.ic_sensors, R.string.sensors_settings_title)
            CATEGORY_CLIPBOARD -> Pair(R.drawable.ic_clipboard, R.string.clipboard_settings_title)
            CATEGORY_SOUND -> Pair(R.drawable.ic_volume, R.string.sound_settings_title)
            CATEGORY_AUTO_DOWNLOADS -> Pair(R.drawable.ic_download, R.string.auto_downloads_settings_title)
            CATEGORY_USB -> Pair(R.drawable.ic_usb, R.string.usb_settings_title)
            CATEGORY_SERIAL -> Pair(R.drawable.ic_cpu, R.string.serial_settings_title)
            CATEGORY_HID -> Pair(R.drawable.ic_gamepad, R.string.hid_settings_title)
            CATEGORY_MIDI -> Pair(R.drawable.ic_music, R.string.midi_settings_title)
            CATEGORY_HAND_TRACKING -> Pair(R.drawable.ic_hand, R.string.hand_tracking_settings_title)
            CATEGORY_IDLE -> Pair(R.drawable.ic_idle, R.string.idle_settings_title)
            CATEGORY_FONTS -> Pair(R.drawable.ic_fonts, R.string.fonts_settings_title)
            CATEGORY_BLUETOOTH -> Pair(R.drawable.ic_bluetooth, R.string.bluetooth_settings_title)
            CATEGORY_WAKE_LOCK -> Pair(R.drawable.ic_sun, R.string.wake_lock_settings_title)
            CATEGORY_PAYMENT -> Pair(R.drawable.ic_credit_card, R.string.payment_settings_title)
            CATEGORY_FILE_EDITING -> Pair(R.drawable.ic_file_edit, R.string.file_editing_settings_title)
            CATEGORY_VR -> Pair(R.drawable.ic_vr, R.string.vr_settings_title)
            CATEGORY_NFC -> Pair(R.drawable.ic_nfc, R.string.nfc_settings_title)
            CATEGORY_WINDOW_MANAGEMENT -> Pair(R.drawable.ic_window_management, R.string.window_management_settings_title)
            CATEGORY_STORAGE_ACCESS -> Pair(R.drawable.ic_storage_access, R.string.storage_access_settings_title)
            CATEGORY_WEBRTC -> Pair(R.drawable.ic_network, R.string.webrtc_settings_title)
            else -> Pair(R.drawable.ic_web, R.string.site_settings_title)
        }
        binding.ivCategoryIcon.setImageResource(iconRes)
        binding.tvCategoryTitle.text = getString(titleRes)
        updateSubtitle()
    }

    private fun updateSubtitle() {
        val allowed = getAllowedDomains()
        val blocked = getBlockedDomains()
        val parts = mutableListOf<String>()
        val allowedWord = if (categoryType == CATEGORY_AUTO_SHRED) "to forget" else if (categoryType == CATEGORY_DESKTOP) "desktop" else "allowed"
        val blockedWord = if (categoryType == CATEGORY_AUTO_SHRED) "kept" else if (categoryType == CATEGORY_DESKTOP) "mobile" else "blocked"
        if (allowed.isNotEmpty()) parts.add("${allowed.size} $allowedWord")
        if (blocked.isNotEmpty()) parts.add("${blocked.size} $blockedWord")
        binding.tvCategorySubtitle.text = if (parts.isEmpty()) getString(R.string.no_exceptions_set) else parts.joinToString(" · ")
    }

    private fun setupGlobalToggle() {
        val isEnabled = getGlobalEnabled()
        updateGlobalToggleUi(isEnabled)

        binding.layoutGlobalToggle.setOnClickListener {
            val newState = !binding.switchGlobalToggle.isChecked
            setGlobalEnabled(newState)
            updateGlobalToggleUi(newState)
            onPermissionChanged()
        }
    }

    private fun getGlobalEnabled(): Boolean = when (categoryType) {
        CATEGORY_CAMERA -> prefs.isCameraEnabledGlobal
        CATEGORY_MIC -> prefs.isMicEnabledGlobal
        CATEGORY_LOCATION -> prefs.isLocationEnabledGlobal
        CATEGORY_NOTIFICATIONS -> prefs.isNotificationsEnabledGlobal
        CATEGORY_JAVASCRIPT -> prefs.isJavaScriptEnabledGlobal
        CATEGORY_POPUPS -> !prefs.isPopupsBlockedGlobal   // toggled: true = allowed
        CATEGORY_AUTOPLAY -> !prefs.isAutoplayBlockedGlobal
        CATEGORY_IMAGES -> prefs.isImagesEnabledGlobal
        CATEGORY_ZOOM -> prefs.isZoomEnabledGlobal
        CATEGORY_MIXED_CONTENT -> !prefs.isMixedContentBlockedGlobal
        CATEGORY_DESKTOP -> prefs.isDesktopMode
        CATEGORY_GOOGLE_SIGNIN -> !prefs.isGoogleSignInBlockedGlobal
        CATEGORY_AUTO_SHRED -> prefs.isCookieAutoclearOnCloseEnabled
        CATEGORY_SENSORS -> !prefs.isSensorsBlockedGlobal
        CATEGORY_CLIPBOARD -> !prefs.isClipboardBlockedGlobal
        CATEGORY_SOUND -> !prefs.isSoundMutedGlobal
        CATEGORY_AUTO_DOWNLOADS -> !prefs.isAutoDownloadsBlockedGlobal
        CATEGORY_USB -> !prefs.isUsbBlockedGlobal
        CATEGORY_SERIAL -> !prefs.isSerialBlockedGlobal
        CATEGORY_HID -> !prefs.isHidBlockedGlobal
        CATEGORY_MIDI -> !prefs.isMidiBlockedGlobal
        CATEGORY_HAND_TRACKING -> !prefs.isHandTrackingBlockedGlobal
        CATEGORY_IDLE -> !prefs.isIdleBlockedGlobal
        CATEGORY_FONTS -> !prefs.isFontsBlockedGlobal
        CATEGORY_BLUETOOTH -> !prefs.isBluetoothBlockedGlobal
        CATEGORY_WAKE_LOCK -> !prefs.isWakeLockBlockedGlobal
        CATEGORY_PAYMENT -> !prefs.isPaymentBlockedGlobal
        CATEGORY_FILE_EDITING -> !prefs.isFileEditingBlockedGlobal
        CATEGORY_VR -> !prefs.isVrBlockedGlobal
        CATEGORY_NFC -> !prefs.isNfcBlockedGlobal
        CATEGORY_WINDOW_MANAGEMENT -> !prefs.isWindowManagementBlockedGlobal
        CATEGORY_STORAGE_ACCESS -> !prefs.isStorageAccessBlockedGlobal
        CATEGORY_WEBRTC -> prefs.isWebRtcProtectedGlobal
        else -> true
    }

    private fun setGlobalEnabled(value: Boolean) = when (categoryType) {
        CATEGORY_CAMERA -> prefs.isCameraEnabledGlobal = value
        CATEGORY_MIC -> prefs.isMicEnabledGlobal = value
        CATEGORY_LOCATION -> prefs.isLocationEnabledGlobal = value
        CATEGORY_NOTIFICATIONS -> prefs.isNotificationsEnabledGlobal = value
        CATEGORY_JAVASCRIPT -> prefs.isJavaScriptEnabledGlobal = value
        CATEGORY_POPUPS -> prefs.isPopupsBlockedGlobal = !value
        CATEGORY_AUTOPLAY -> prefs.isAutoplayBlockedGlobal = !value
        CATEGORY_IMAGES -> prefs.isImagesEnabledGlobal = value
        CATEGORY_ZOOM -> prefs.isZoomEnabledGlobal = value
        CATEGORY_MIXED_CONTENT -> prefs.isMixedContentBlockedGlobal = !value
        CATEGORY_DESKTOP -> prefs.isDesktopMode = value
        CATEGORY_GOOGLE_SIGNIN -> prefs.isGoogleSignInBlockedGlobal = !value
        CATEGORY_AUTO_SHRED -> prefs.isCookieAutoclearOnCloseEnabled = value
        CATEGORY_SENSORS -> prefs.isSensorsBlockedGlobal = !value
        CATEGORY_CLIPBOARD -> prefs.isClipboardBlockedGlobal = !value
        CATEGORY_SOUND -> prefs.isSoundMutedGlobal = !value
        CATEGORY_AUTO_DOWNLOADS -> prefs.isAutoDownloadsBlockedGlobal = !value
        CATEGORY_USB -> prefs.isUsbBlockedGlobal = !value
        CATEGORY_SERIAL -> prefs.isSerialBlockedGlobal = !value
        CATEGORY_HID -> prefs.isHidBlockedGlobal = !value
        CATEGORY_MIDI -> prefs.isMidiBlockedGlobal = !value
        CATEGORY_HAND_TRACKING -> prefs.isHandTrackingBlockedGlobal = !value
        CATEGORY_IDLE -> prefs.isIdleBlockedGlobal = !value
        CATEGORY_FONTS -> prefs.isFontsBlockedGlobal = !value
        CATEGORY_BLUETOOTH -> prefs.isBluetoothBlockedGlobal = !value
        CATEGORY_WAKE_LOCK -> prefs.isWakeLockBlockedGlobal = !value
        CATEGORY_PAYMENT -> prefs.isPaymentBlockedGlobal = !value
        CATEGORY_FILE_EDITING -> prefs.isFileEditingBlockedGlobal = !value
        CATEGORY_VR -> prefs.isVrBlockedGlobal = !value
        CATEGORY_NFC -> prefs.isNfcBlockedGlobal = !value
        CATEGORY_WINDOW_MANAGEMENT -> prefs.isWindowManagementBlockedGlobal = !value
        CATEGORY_STORAGE_ACCESS -> prefs.isStorageAccessBlockedGlobal = !value
        CATEGORY_WEBRTC -> prefs.isWebRtcProtectedGlobal = value
        else -> {}
    }

    private fun updateGlobalToggleUi(isEnabled: Boolean) {
        binding.switchGlobalToggle.isChecked = isEnabled
        val (titleText, descText) = when (categoryType) {
            CATEGORY_JAVASCRIPT -> if (isEnabled)
                Pair("JavaScript enabled", "Sites can run JavaScript (recommended)")
            else
                Pair("JavaScript blocked", "All sites are blocked from running JavaScript")
            CATEGORY_POPUPS -> if (isEnabled)
                Pair("Pop-ups allowed", "Sites can open new windows and tabs")
            else
                Pair(getString(R.string.blocked_globally), "All sites are blocked from opening pop-ups")
            CATEGORY_AUTOPLAY -> if (isEnabled)
                Pair("Autoplay allowed", "Sites can autoplay audio and video")
            else
                Pair("Autoplay blocked", "Sites cannot autoplay media without interaction")
            CATEGORY_IMAGES -> if (isEnabled)
                Pair("Images shown", "Sites can display images (recommended)")
            else
                Pair("Images blocked", "All sites are blocked from loading images")
            CATEGORY_ZOOM -> if (isEnabled)
                Pair("Zoom allowed", "You can pinch-to-zoom on all pages")
            else
                Pair("Zoom blocked", "Sites control whether pinch-to-zoom is allowed")
            CATEGORY_MIXED_CONTENT -> if (isEnabled)
                Pair("Insecure content allowed", "Sites can load HTTP resources on HTTPS pages")
            else
                Pair("Insecure content blocked", "Sites cannot load HTTP resources on HTTPS pages (recommended)")
            CATEGORY_NOTIFICATIONS -> if (isEnabled)
                Pair(getString(R.string.ask_before_accessing), "Sites must ask before sending notifications")
            else
                Pair(getString(R.string.blocked_globally), "All sites are blocked from sending notifications")
            CATEGORY_DESKTOP -> if (isEnabled)
                Pair("Desktop site on", "Pages show the desktop version of websites")
            else
                Pair("Desktop site off", "Pages show the mobile version of websites")
            CATEGORY_GOOGLE_SIGNIN -> if (isEnabled)
                Pair("Google Sign-In allowed", "Sites can display third-party Google Sign-In prompts")
            else
                Pair("Google Sign-In blocked", "Third-party Google Sign-In prompts are blocked (recommended)")
            CATEGORY_AUTO_SHRED -> if (isEnabled)
                Pair("Forget all sites on close", "Cookies and site data are cleared when tabs close")
            else
                Pair("Forget configured sites on close", "Only configured sites below are cleared when closed")
            CATEGORY_SENSORS -> if (isEnabled)
                Pair("Motion sensors allowed", "Sites can access motion and light sensors")
            else
                Pair("Motion sensors blocked", "Sensor APIs are disabled to prevent fingerprinting (recommended)")
            CATEGORY_CLIPBOARD -> if (isEnabled)
                Pair("Ask before clipboard access", "Sites must ask before reading clipboard contents")
            else
                Pair("Clipboard blocked", "All sites are blocked from reading clipboard")
            CATEGORY_SOUND -> if (isEnabled)
                Pair("Sound allowed", "Sites can play audio and sound")
            else
                Pair("Sound muted", "All sites are muted by default")
            CATEGORY_AUTO_DOWNLOADS -> if (isEnabled)
                Pair("Automatic downloads allowed", "Sites can download multiple files automatically")
            else
                Pair("Automatic downloads blocked", "Sites cannot download multiple files in succession")
            CATEGORY_USB -> if (isEnabled)
                Pair("USB devices allowed", "Sites can ask to connect to USB devices")
            else
                Pair("USB devices blocked", "All sites are blocked from connecting to USB devices (recommended)")
            CATEGORY_SERIAL -> if (isEnabled)
                Pair("Serial ports allowed", "Sites can ask to connect to serial ports")
            else
                Pair("Serial ports blocked", "All sites are blocked from connecting to serial ports (recommended)")
            CATEGORY_HID -> if (isEnabled)
                Pair("HID devices allowed", "Sites can ask to connect to human interface devices")
            else
                Pair("HID devices blocked", "All sites are blocked from connecting to HID devices (recommended)")
            CATEGORY_MIDI -> if (isEnabled)
                Pair("MIDI devices allowed", "Sites can access MIDI musical instruments and send SysEx")
            else
                Pair("MIDI devices blocked", "All sites are blocked from accessing MIDI devices (recommended)")
            CATEGORY_HAND_TRACKING -> if (isEnabled)
                Pair("Hand tracking allowed", "Sites can track hand pose and gestures in WebXR")
            else
                Pair("Hand tracking blocked", "Hand tracking sensors are disabled (recommended)")
            CATEGORY_IDLE -> if (isEnabled)
                Pair("Idle detection allowed", "Sites can detect when you are actively using your device")
            else
                Pair("Idle detection blocked", "All sites are blocked from monitoring user idle status (recommended)")
            CATEGORY_FONTS -> if (isEnabled)
                Pair("Local fonts allowed", "Sites can query fonts installed on your device")
            else
                Pair("Local fonts blocked", "Local font enumeration is blocked to prevent fingerprinting (recommended)")
            CATEGORY_BLUETOOTH -> if (isEnabled)
                Pair("Bluetooth allowed", "Sites can ask to connect to nearby Bluetooth devices")
            else
                Pair("Bluetooth blocked", "All sites are blocked from connecting to Bluetooth devices (recommended)")
            CATEGORY_WAKE_LOCK -> if (isEnabled)
                Pair("Screen wake lock allowed", "Sites can prevent your screen from turning off (recommended)")
            else
                Pair("Screen wake lock blocked", "All sites are prevented from keeping your screen awake")
            CATEGORY_PAYMENT -> if (isEnabled)
                Pair("Payment methods allowed", "Sites can query and request payment methods (recommended)")
            else
                Pair("Payment methods blocked", "All sites are blocked from querying payment methods")
            CATEGORY_FILE_EDITING -> if (isEnabled)
                Pair("File editing allowed", "Sites can ask to edit files and folders")
            else
                Pair("File editing blocked", "All sites are blocked from editing files (recommended)")
            CATEGORY_VR -> if (isEnabled)
                Pair("Virtual reality allowed", "Sites can access VR/XR hardware and headsets")
            else
                Pair("Virtual reality blocked", "All sites are blocked from using VR/XR hardware (recommended)")
            CATEGORY_NFC -> if (isEnabled)
                Pair("NFC allowed", "Sites can ask to scan and write NFC tags")
            else
                Pair("NFC blocked", "All sites are blocked from using NFC (recommended)")
            CATEGORY_WINDOW_MANAGEMENT -> if (isEnabled)
                Pair("Window management allowed", "Sites can detect screen configuration and place windows")
            else
                Pair("Window management blocked", "Multi-screen enumeration is blocked to prevent fingerprinting (recommended)")
            CATEGORY_STORAGE_ACCESS -> if (isEnabled)
                Pair("Storage access allowed", "Sites can request cross-site storage access")
            else
                Pair("Storage access blocked", "Cross-site storage access is blocked (recommended)")
            CATEGORY_WEBRTC -> if (isEnabled)
                Pair("WebRTC IP protection enabled", "Local LAN IP leaks are masked and stripped during peer connections (recommended)")
            else
                Pair("WebRTC IP protection disabled", "Local IP addresses are exposed during WebRTC connections")
            else -> if (isEnabled)
                Pair(getString(R.string.ask_before_accessing), "Sites must ask for permission before using this device")
            else
                Pair(getString(R.string.blocked_globally), "All sites are blocked from using this device")
        }
        binding.tvGlobalToggleTitle.text = titleText
        binding.tvGlobalToggleDesc.text = descText
    }

    private fun getAllowedDomains(): Set<String> = when (categoryType) {
        CATEGORY_CAMERA -> prefs.getAllowedCameraDomains()
        CATEGORY_MIC -> prefs.getAllowedMicDomains()
        CATEGORY_LOCATION -> prefs.getAllowedLocationDomains()
        CATEGORY_NOTIFICATIONS -> prefs.getAllowedNotifDomains()
        CATEGORY_JAVASCRIPT -> prefs.getAllowedJsDomains()
        CATEGORY_POPUPS -> prefs.getAllowedPopupDomains()
        CATEGORY_AUTOPLAY -> prefs.getAllowedAutoplayDomains()
        CATEGORY_IMAGES -> prefs.getAllowedImagesDomains()
        CATEGORY_ZOOM -> prefs.getAllowedZoomDomains()
        CATEGORY_MIXED_CONTENT -> prefs.getAllowedMixedDomains()
        CATEGORY_DESKTOP -> prefs.desktopDomains
        CATEGORY_GOOGLE_SIGNIN -> prefs.getAllowedGoogleSignInDomains()
        CATEGORY_AUTO_SHRED -> prefs.autoShredDomains
        CATEGORY_SENSORS -> prefs.getAllowedSensorsDomains()
        CATEGORY_CLIPBOARD -> prefs.getAllowedClipboardDomains()
        CATEGORY_SOUND -> prefs.getAllowedSoundDomains()
        CATEGORY_AUTO_DOWNLOADS -> prefs.getAllowedAutoDownloadsDomains()
        CATEGORY_USB -> prefs.getAllowedUsbDomains()
        CATEGORY_SERIAL -> prefs.getAllowedSerialDomains()
        CATEGORY_HID -> prefs.getAllowedHidDomains()
        CATEGORY_MIDI -> prefs.getAllowedMidiDomains()
        CATEGORY_HAND_TRACKING -> prefs.getAllowedHandTrackingDomains()
        CATEGORY_IDLE -> prefs.getAllowedIdleDomains()
        CATEGORY_FONTS -> prefs.getAllowedFontsDomains()
        CATEGORY_BLUETOOTH -> prefs.getAllowedBluetoothDomains()
        CATEGORY_WAKE_LOCK -> prefs.getAllowedWakeLockDomains()
        CATEGORY_PAYMENT -> prefs.getAllowedPaymentDomains()
        CATEGORY_FILE_EDITING -> prefs.getAllowedFileEditingDomains()
        CATEGORY_VR -> prefs.getAllowedVrDomains()
        CATEGORY_NFC -> prefs.getAllowedNfcDomains()
        CATEGORY_WINDOW_MANAGEMENT -> prefs.getAllowedWindowManagementDomains()
        CATEGORY_STORAGE_ACCESS -> prefs.getAllowedStorageAccessDomains()
        CATEGORY_WEBRTC -> prefs.getAllowedWebRtcDomains()
        else -> emptySet()
    }

    private fun getBlockedDomains(): Set<String> = when (categoryType) {
        CATEGORY_CAMERA -> prefs.getBlockedCameraDomains()
        CATEGORY_MIC -> prefs.getBlockedMicDomains()
        CATEGORY_LOCATION -> prefs.getBlockedLocationDomains()
        CATEGORY_NOTIFICATIONS -> prefs.getBlockedNotifDomains()
        CATEGORY_JAVASCRIPT -> prefs.getBlockedJsDomains()
        CATEGORY_POPUPS -> prefs.getBlockedPopupDomains()
        CATEGORY_AUTOPLAY -> prefs.getBlockedAutoplayDomains()
        CATEGORY_IMAGES -> prefs.getBlockedImagesDomains()
        CATEGORY_ZOOM -> prefs.getBlockedZoomDomains()
        CATEGORY_MIXED_CONTENT -> prefs.getBlockedMixedDomains()
        CATEGORY_DESKTOP -> prefs.mobileDomains
        CATEGORY_GOOGLE_SIGNIN -> prefs.getBlockedGoogleSignInDomains()
        CATEGORY_AUTO_SHRED -> prefs.autoKeepDomains
        CATEGORY_SENSORS -> prefs.getBlockedSensorsDomains()
        CATEGORY_CLIPBOARD -> prefs.getBlockedClipboardDomains()
        CATEGORY_SOUND -> prefs.getBlockedSoundDomains()
        CATEGORY_AUTO_DOWNLOADS -> prefs.getBlockedAutoDownloadsDomains()
        CATEGORY_USB -> prefs.getBlockedUsbDomains()
        CATEGORY_SERIAL -> prefs.getBlockedSerialDomains()
        CATEGORY_HID -> prefs.getBlockedHidDomains()
        CATEGORY_MIDI -> prefs.getBlockedMidiDomains()
        CATEGORY_HAND_TRACKING -> prefs.getBlockedHandTrackingDomains()
        CATEGORY_IDLE -> prefs.getBlockedIdleDomains()
        CATEGORY_FONTS -> prefs.getBlockedFontsDomains()
        CATEGORY_BLUETOOTH -> prefs.getBlockedBluetoothDomains()
        CATEGORY_WAKE_LOCK -> prefs.getBlockedWakeLockDomains()
        CATEGORY_PAYMENT -> prefs.getBlockedPaymentDomains()
        CATEGORY_FILE_EDITING -> prefs.getBlockedFileEditingDomains()
        CATEGORY_VR -> prefs.getBlockedVrDomains()
        CATEGORY_NFC -> prefs.getBlockedNfcDomains()
        CATEGORY_WINDOW_MANAGEMENT -> prefs.getBlockedWindowManagementDomains()
        CATEGORY_STORAGE_ACCESS -> prefs.getBlockedStorageAccessDomains()
        CATEGORY_WEBRTC -> prefs.getBlockedWebRtcDomains()
        else -> emptySet()
    }

    private fun clearSitePermission(domain: String) = when (categoryType) {
        CATEGORY_CAMERA -> prefs.setSiteCameraPermission(domain, SitePermissionState.ASK)
        CATEGORY_MIC -> prefs.setSiteMicPermission(domain, SitePermissionState.ASK)
        CATEGORY_LOCATION -> prefs.setSiteLocationPermission(domain, SitePermissionState.ASK)
        CATEGORY_NOTIFICATIONS -> prefs.setSiteNotificationsPermission(domain, SitePermissionState.ASK)
        CATEGORY_JAVASCRIPT -> prefs.setSiteJavaScriptPermission(domain, SitePermissionState.ASK)
        CATEGORY_POPUPS -> prefs.setSitePopupsPermission(domain, SitePermissionState.ASK)
        CATEGORY_AUTOPLAY -> prefs.setSiteAutoplayPermission(domain, SitePermissionState.ASK)
        CATEGORY_IMAGES -> prefs.setSiteImagesPermission(domain, SitePermissionState.ASK)
        CATEGORY_ZOOM -> prefs.setSiteZoomPermission(domain, SitePermissionState.ASK)
        CATEGORY_MIXED_CONTENT -> prefs.setSiteMixedContentPermission(domain, SitePermissionState.ASK)
        CATEGORY_DESKTOP -> prefs.setSiteDesktopModeState(domain, SitePermissionState.ASK)
        CATEGORY_GOOGLE_SIGNIN -> prefs.setSiteGoogleSignInPermission(domain, SitePermissionState.ASK)
        CATEGORY_AUTO_SHRED -> prefs.setSiteAutoShredState(domain, SitePermissionState.ASK)
        CATEGORY_SENSORS -> prefs.setSiteSensorsPermission(domain, SitePermissionState.ASK)
        CATEGORY_CLIPBOARD -> prefs.setSiteClipboardPermission(domain, SitePermissionState.ASK)
        CATEGORY_SOUND -> prefs.setSiteSoundPermission(domain, SitePermissionState.ASK)
        CATEGORY_AUTO_DOWNLOADS -> prefs.setSiteAutoDownloadsPermission(domain, SitePermissionState.ASK)
        CATEGORY_USB -> prefs.setSiteUsbPermission(domain, SitePermissionState.ASK)
        CATEGORY_SERIAL -> prefs.setSiteSerialPermission(domain, SitePermissionState.ASK)
        CATEGORY_HID -> prefs.setSiteHidPermission(domain, SitePermissionState.ASK)
        CATEGORY_MIDI -> prefs.setSiteMidiPermission(domain, SitePermissionState.ASK)
        CATEGORY_HAND_TRACKING -> prefs.setSiteHandTrackingPermission(domain, SitePermissionState.ASK)
        CATEGORY_IDLE -> prefs.setSiteIdlePermission(domain, SitePermissionState.ASK)
        CATEGORY_FONTS -> prefs.setSiteFontsPermission(domain, SitePermissionState.ASK)
        CATEGORY_BLUETOOTH -> prefs.setSiteBluetoothPermission(domain, SitePermissionState.ASK)
        CATEGORY_WAKE_LOCK -> prefs.setSiteWakeLockPermission(domain, SitePermissionState.ASK)
        CATEGORY_PAYMENT -> prefs.setSitePaymentPermission(domain, SitePermissionState.ASK)
        CATEGORY_FILE_EDITING -> prefs.setSiteFileEditingPermission(domain, SitePermissionState.ASK)
        CATEGORY_VR -> prefs.setSiteVrPermission(domain, SitePermissionState.ASK)
        CATEGORY_NFC -> prefs.setSiteNfcPermission(domain, SitePermissionState.ASK)
        CATEGORY_WINDOW_MANAGEMENT -> prefs.setSiteWindowManagementPermission(domain, SitePermissionState.ASK)
        CATEGORY_STORAGE_ACCESS -> prefs.setSiteStorageAccessPermission(domain, SitePermissionState.ASK)
        CATEGORY_WEBRTC -> prefs.setSiteWebRtcPermission(domain, SitePermissionState.ASK)
        else -> {}
    }

    private fun setSitePermissionAllowed(domain: String) = when (categoryType) {
        CATEGORY_CAMERA -> prefs.setSiteCameraPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_MIC -> prefs.setSiteMicPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_LOCATION -> prefs.setSiteLocationPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_NOTIFICATIONS -> prefs.setSiteNotificationsPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_JAVASCRIPT -> prefs.setSiteJavaScriptPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_POPUPS -> prefs.setSitePopupsPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_AUTOPLAY -> prefs.setSiteAutoplayPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_IMAGES -> prefs.setSiteImagesPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_ZOOM -> prefs.setSiteZoomPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_MIXED_CONTENT -> prefs.setSiteMixedContentPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_DESKTOP -> prefs.setSiteDesktopModeState(domain, SitePermissionState.ALLOWED)
        CATEGORY_GOOGLE_SIGNIN -> prefs.setSiteGoogleSignInPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_AUTO_SHRED -> prefs.setSiteAutoShredState(domain, SitePermissionState.ALLOWED)
        CATEGORY_SENSORS -> prefs.setSiteSensorsPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_CLIPBOARD -> prefs.setSiteClipboardPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_SOUND -> prefs.setSiteSoundPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_AUTO_DOWNLOADS -> prefs.setSiteAutoDownloadsPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_USB -> prefs.setSiteUsbPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_SERIAL -> prefs.setSiteSerialPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_HID -> prefs.setSiteHidPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_MIDI -> prefs.setSiteMidiPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_HAND_TRACKING -> prefs.setSiteHandTrackingPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_IDLE -> prefs.setSiteIdlePermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_FONTS -> prefs.setSiteFontsPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_BLUETOOTH -> prefs.setSiteBluetoothPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_WAKE_LOCK -> prefs.setSiteWakeLockPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_PAYMENT -> prefs.setSitePaymentPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_FILE_EDITING -> prefs.setSiteFileEditingPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_VR -> prefs.setSiteVrPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_NFC -> prefs.setSiteNfcPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_WINDOW_MANAGEMENT -> prefs.setSiteWindowManagementPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_STORAGE_ACCESS -> prefs.setSiteStorageAccessPermission(domain, SitePermissionState.ALLOWED)
        CATEGORY_WEBRTC -> prefs.setSiteWebRtcPermission(domain, SitePermissionState.ALLOWED)
        else -> {}
    }

    private fun setSitePermissionBlocked(domain: String) = when (categoryType) {
        CATEGORY_CAMERA -> prefs.setSiteCameraPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_MIC -> prefs.setSiteMicPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_LOCATION -> prefs.setSiteLocationPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_NOTIFICATIONS -> prefs.setSiteNotificationsPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_JAVASCRIPT -> prefs.setSiteJavaScriptPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_POPUPS -> prefs.setSitePopupsPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_AUTOPLAY -> prefs.setSiteAutoplayPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_IMAGES -> prefs.setSiteImagesPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_ZOOM -> prefs.setSiteZoomPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_MIXED_CONTENT -> prefs.setSiteMixedContentPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_DESKTOP -> prefs.setSiteDesktopModeState(domain, SitePermissionState.BLOCKED)
        CATEGORY_GOOGLE_SIGNIN -> prefs.setSiteGoogleSignInPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_AUTO_SHRED -> prefs.setSiteAutoShredState(domain, SitePermissionState.BLOCKED)
        CATEGORY_SENSORS -> prefs.setSiteSensorsPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_CLIPBOARD -> prefs.setSiteClipboardPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_SOUND -> prefs.setSiteSoundPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_AUTO_DOWNLOADS -> prefs.setSiteAutoDownloadsPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_USB -> prefs.setSiteUsbPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_SERIAL -> prefs.setSiteSerialPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_HID -> prefs.setSiteHidPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_MIDI -> prefs.setSiteMidiPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_HAND_TRACKING -> prefs.setSiteHandTrackingPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_IDLE -> prefs.setSiteIdlePermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_FONTS -> prefs.setSiteFontsPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_BLUETOOTH -> prefs.setSiteBluetoothPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_WAKE_LOCK -> prefs.setSiteWakeLockPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_PAYMENT -> prefs.setSitePaymentPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_FILE_EDITING -> prefs.setSiteFileEditingPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_VR -> prefs.setSiteVrPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_NFC -> prefs.setSiteNfcPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_WINDOW_MANAGEMENT -> prefs.setSiteWindowManagementPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_STORAGE_ACCESS -> prefs.setSiteStorageAccessPermission(domain, SitePermissionState.BLOCKED)
        CATEGORY_WEBRTC -> prefs.setSiteWebRtcPermission(domain, SitePermissionState.BLOCKED)
        else -> {}
    }

    private fun showAddExceptionDialog() {
        val inputET = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.add_site_exception_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(requireContext()).apply {
            setPadding(padding, 0, padding, 0)
            addView(inputET)
        }
        val posBtnText = when (categoryType) {
            CATEGORY_DESKTOP -> getString(R.string.desktop_site)
            CATEGORY_AUTO_SHRED -> "Forget on close"
            CATEGORY_WEBRTC -> "Protect IP"
            else -> getString(R.string.permission_allowed)
        }
        val negBtnText = when (categoryType) {
            CATEGORY_DESKTOP -> "Mobile"
            CATEGORY_AUTO_SHRED -> "Keep data"
            CATEGORY_WEBRTC -> "Allow local IP"
            else -> getString(R.string.permission_blocked)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_site_exception)
            .setView(container)
            .setNeutralButton(R.string.cancel, null)
            .setPositiveButton(posBtnText) { _, _ ->
                val domain = prefs.cleanDomain(inputET.text.toString().trim())
                if (domain.isNotBlank() && (domain.contains(".") || domain == "localhost")) {
                    clearSitePermission(domain)
                    setSitePermissionAllowed(domain)
                    populateSiteLists()
                    onPermissionChanged()
                }
            }
            .setNegativeButton(negBtnText) { _, _ ->
                val domain = prefs.cleanDomain(inputET.text.toString().trim())
                if (domain.isNotBlank() && (domain.contains(".") || domain == "localhost")) {
                    clearSitePermission(domain)
                    setSitePermissionBlocked(domain)
                    populateSiteLists()
                    onPermissionChanged()
                }
            }
            .show()
    }

    private fun populateSiteLists() {
        binding.containerAllowedSites.removeAllViews()
        binding.containerBlockedSites.removeAllViews()

        val allowedDomains = getAllowedDomains()
        val blockedDomains = getBlockedDomains()

        val hasAllowed = allowedDomains.isNotEmpty()
        val hasBlocked = blockedDomains.isNotEmpty()

        binding.tvAllowedHeader.text = when (categoryType) {
            CATEGORY_DESKTOP -> "ALWAYS DESKTOP"
            CATEGORY_AUTO_SHRED -> "ALWAYS FORGET ON CLOSE"
            CATEGORY_WEBRTC -> "ALWAYS PROTECT IP"
            else -> getString(R.string.allowed_sites_header)
        }
        binding.tvBlockedHeader.text = when (categoryType) {
            CATEGORY_DESKTOP -> "ALWAYS MOBILE"
            CATEGORY_AUTO_SHRED -> "NEVER FORGET (KEEP DATA)"
            CATEGORY_WEBRTC -> "ALLOW LOCAL IP (UNPROTECTED)"
            else -> getString(R.string.blocked_sites_header)
        }

        binding.tvAllowedHeader.visibility = if (hasAllowed) View.VISIBLE else View.GONE
        binding.cardAllowedSites.visibility = if (hasAllowed) View.VISIBLE else View.GONE
        binding.tvBlockedHeader.visibility = if (hasBlocked) View.VISIBLE else View.GONE
        binding.cardBlockedSites.visibility = if (hasBlocked) View.VISIBLE else View.GONE
        binding.layoutEmptySites.visibility = if (!hasAllowed && !hasBlocked) View.VISIBLE else View.GONE

        val allowedList = allowedDomains.sorted()
        for ((index, domain) in allowedList.withIndex()) {
            addDomainRow(binding.containerAllowedSites, domain, true, index < allowedList.size - 1)
        }
        val blockedList = blockedDomains.sorted()
        for ((index, domain) in blockedList.withIndex()) {
            addDomainRow(binding.containerBlockedSites, domain, false, index < blockedList.size - 1)
        }
        updateSubtitle()
    }

    private fun addDomainRow(
        container: LinearLayout,
        domain: String,
        isAllowed: Boolean,
        showDivider: Boolean
    ) {
        val density = resources.displayMetrics.density

        val rowWrapper = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
            setBackgroundResource(typedValue.resourceId)
        }

        val ivGlobe = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_web)
            val iconColor = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorSecondary, iconColor, true)
            setColorFilter(ContextCompat.getColor(context, iconColor.resourceId))
            val iconSize = (18 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = (12 * density).toInt() }
        }

        val tvDomain = TextView(requireContext()).apply {
            text = domain
            textSize = 14.5f
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            setTextColor(ContextCompat.getColor(context, typedValue.resourceId))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvStatus = TextView(requireContext()).apply {
            text = when {
                categoryType == CATEGORY_AUTO_SHRED && isAllowed -> "Forget"
                categoryType == CATEGORY_AUTO_SHRED && !isAllowed -> "Keep data"
                categoryType == CATEGORY_DESKTOP && isAllowed -> getString(R.string.desktop_site)
                categoryType == CATEGORY_DESKTOP && !isAllowed -> "Mobile"
                categoryType == CATEGORY_WEBRTC && isAllowed -> "Protected"
                categoryType == CATEGORY_WEBRTC && !isAllowed -> "Unprotected"
                isAllowed -> getString(R.string.permission_allowed)
                else -> getString(R.string.permission_blocked)
            }
            textSize = 11.5f
            setTextColor(
                if (isAllowed) ContextCompat.getColor(context, R.color.green_success)
                else if (categoryType == CATEGORY_AUTO_SHRED && !isAllowed) ContextCompat.getColor(context, R.color.settings_subtitle_text)
                else ContextCompat.getColor(context, R.color.red_danger)
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (10 * density).toInt()
            }
        }

        val btnDelete = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_delete)
            setColorFilter(ContextCompat.getColor(context, R.color.settings_subtitle_text))
            val pad = (5 * density).toInt()
            setPadding(pad, pad, pad, pad)
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
            setBackgroundResource(typedValue.resourceId)
            val iconSize = (32 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setOnClickListener {
                clearSitePermission(domain)
                populateSiteLists()
                onPermissionChanged()
            }
        }

        row.addView(ivGlobe)
        row.addView(tvDomain)
        row.addView(tvStatus)
        row.addView(btnDelete)
        rowWrapper.addView(row)

        if (showDivider) {
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = (46 * density).toInt()
                }
                setBackgroundColor(ContextCompat.getColor(context, R.color.settings_card_stroke))
            }
            rowWrapper.addView(divider)
        }

        container.addView(rowWrapper)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

