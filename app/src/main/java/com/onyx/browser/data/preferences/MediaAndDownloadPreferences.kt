package com.onyx.browser.data.preferences

/**
 * Domain-scoped preference store for media playback, Picture-in-Picture,
 * video gestures, and multi-threaded download behavior.
 */
class MediaAndDownloadPreferences(private val browserPrefs: BrowserPreferences) {

    var isBackgroundPlayEnabled: Boolean
        get() = browserPrefs.isBackgroundPlayEnabled
        set(value) {
            browserPrefs.isBackgroundPlayEnabled = value
        }

    var isAutoPipEnabled: Boolean
        get() = browserPrefs.isAutoPipEnabled
        set(value) {
            browserPrefs.isAutoPipEnabled = value
        }

    var isPipEnabled: Boolean
        get() = browserPrefs.isPipEnabled
        set(value) {
            browserPrefs.isPipEnabled = value
        }

    var isSwipeGestureVideoEnabled: Boolean
        get() = browserPrefs.isSwipeGestureVideoEnabled
        set(value) {
            browserPrefs.isSwipeGestureVideoEnabled = value
        }

    var isDoubleTapSeekEnabled: Boolean
        get() = browserPrefs.isDoubleTapSeekEnabled
        set(value) {
            browserPrefs.isDoubleTapSeekEnabled = value
        }

    var selectedDownloaderPackage: String
        get() = browserPrefs.selectedDownloaderPackage
        set(value) {
            browserPrefs.selectedDownloaderPackage = value
        }

    var isAskBeforeDownload: Boolean
        get() = browserPrefs.isAskBeforeDownload
        set(value) {
            browserPrefs.isAskBeforeDownload = value
        }

    var downloadManagerBehavior: Int
        get() = browserPrefs.downloadManagerBehavior
        set(value) {
            browserPrefs.downloadManagerBehavior = value
        }

    var downloadThreadsCount: Int
        get() = browserPrefs.downloadThreadsCount
        set(value) {
            browserPrefs.downloadThreadsCount = value
        }

    var downloadSpeedLimitKbps: Int
        get() = browserPrefs.downloadSpeedLimitKbps
        set(value) {
            browserPrefs.downloadSpeedLimitKbps = value
        }

    var downloadSpeedLimit: Long
        get() = browserPrefs.downloadSpeedLimit
        set(value) {
            browserPrefs.downloadSpeedLimit = value
        }

    var isWifiOnlyDownload: Boolean
        get() = browserPrefs.isWifiOnlyDownload
        set(value) {
            browserPrefs.isWifiOnlyDownload = value
        }

    var isDownloadWifiOnly: Boolean
        get() = browserPrefs.isDownloadWifiOnly
        set(value) {
            browserPrefs.isDownloadWifiOnly = value
        }
}
