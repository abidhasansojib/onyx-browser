package com.onyx.browser.data.model

import androidx.room.Entity
import androidx.room.Index

enum class SitePermissionType {
    CAMERA,
    MICROPHONE,
    LOCATION,
    NOTIFICATIONS,
    JAVASCRIPT,
    POPUPS,
    ADS,
    CLIPBOARD,
    AUTOPLAY,
    SOUND,
    USB,
    MIDI,
    VR_AR,
    SENSORS,
    IDLE_DETECTION,
    SERIAL,
    HID,
    FILE_EDITING,
    FONT_ACCESS,
    STORAGE_ACCESS,
    WINDOW_MANAGEMENT,
    PAYMENT_HANDLER,
    AUTO_DOWNLOADS,
    WAKE_LOCK,
    WEBRTC,
    IMAGES,
    ZOOM,
    MIXED_CONTENT,
    GOOGLE_SIGNIN,
    AUTO_SHRED,
    DESKTOP_MODE
}

@Entity(
    tableName = "site_permissions",
    primaryKeys = ["domain", "permissionType"],
    indices = [
        Index(value = ["permissionType"]),
        Index(value = ["domain"])
    ]
)
data class SitePermissionEntity(
    val domain: String,
    val permissionType: String,
    val state: String, // "ALLOWED" or "BLOCKED"
    val updatedAt: Long = System.currentTimeMillis()
)
