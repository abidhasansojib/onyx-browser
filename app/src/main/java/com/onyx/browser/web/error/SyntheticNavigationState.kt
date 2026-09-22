package com.onyx.browser.web.error

import android.net.Uri

/**
 * Strongly-typed semantic representation of synthetic navigation error states
 * in Onyx Browser, implementing Chromium/Brave/Safari-grade closed-loop error modeling.
 */
sealed class SyntheticNavigationState(
    val category: ErrorCategory,
    val failingUrl: String,
    val errorCodeString: String,
    val title: String,
    val description: String,
    val checklist: List<String> = emptyList(),
    val isDanger: Boolean = false,
    val primaryButtonText: String = "Try Again",
    val primaryButtonAction: String = "reload",
    val secondaryButtonText: String = "Search Web",
    val secondaryButtonAction: String = "search",
    val technicalDetails: String? = null,
    val isHstsEnforced: Boolean = false,
    val canCheckWayback: Boolean = false,
    val hasOfflineGame: Boolean = false
) {

    enum class ErrorCategory {
        OFFLINE,
        SECURITY,
        SERVER_DOWN,
        SHIELDS_BLOCKED,
        FILE_ERROR,
        GENERIC
    }

    val domain: String = try {
        val host = Uri.parse(failingUrl).host
        if (!host.isNullOrBlank()) host.removePrefix("www.") else failingUrl
    } catch (_: Exception) {
        failingUrl
    }

    fun toJson(): String {
        val escapedTitle = escapeJson(title)
        val escapedDesc = escapeJson(description)
        val escapedCode = escapeJson(errorCodeString)
        val escapedUrl = escapeJson(failingUrl)
        val escapedDomain = escapeJson(domain)
        val escapedPrimaryText = escapeJson(primaryButtonText)
        val escapedSecondaryText = escapeJson(secondaryButtonText)
        val escapedTech = technicalDetails?.let { escapeJson(it) } ?: ""

        val checklistJson = checklist.joinToString(prefix = "[", postfix = "]") {
            "\"${escapeJson(it)}\""
        }

        return """
            {
                "category": "${category.name}",
                "errorCode": "$escapedCode",
                "title": "$escapedTitle",
                "description": "$escapedDesc",
                "url": "$escapedUrl",
                "domain": "$escapedDomain",
                "checklist": $checklistJson,
                "primaryButtonText": "$escapedPrimaryText",
                "primaryButtonAction": "$primaryButtonAction",
                "secondaryButtonText": "$escapedSecondaryText",
                "secondaryButtonAction": "$secondaryButtonAction",
                "isDanger": $isDanger,
                "technicalDetails": "$escapedTech",
                "isHstsEnforced": $isHstsEnforced,
                "canCheckWayback": $canCheckWayback,
                "hasOfflineGame": $hasOfflineGame
            }
        """.trimIndent()
    }

    private fun escapeJson(str: String): String =
        str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")

    fun toBase64Json(): String {
        return android.util.Base64.encodeToString(
            toJson().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
    }

    class Offline(
        failingUrl: String,
        errorCode: String = "net::ERR_INTERNET_DISCONNECTED",
        title: String = "No internet connection",
        description: String = "Onyx Browser is unable to connect to the network. Please check your data or Wi-Fi.",
        checklist: List<String> = listOf(
            "Check if Wi-Fi or mobile data is turned on",
            "Check if Airplane mode is turned off",
            "Restart your wireless router or mobile connection"
        ),
        technicalDetails: String? = null
    ) : SyntheticNavigationState(
        category = ErrorCategory.OFFLINE,
        failingUrl = failingUrl,
        errorCodeString = errorCode,
        title = title,
        description = description,
        checklist = checklist,
        primaryButtonText = "Try Again",
        primaryButtonAction = "reload",
        secondaryButtonText = "Network Settings",
        secondaryButtonAction = "network_settings",
        technicalDetails = technicalDetails,
        hasOfflineGame = false
    )

    class Security(
        failingUrl: String,
        errorCode: String = "net::ERR_CERT_COMMON_NAME_INVALID",
        title: String = "Your connection is not private",
        description: String,
        technicalDetails: String,
        isHstsEnforced: Boolean = false
    ) : SyntheticNavigationState(
        category = ErrorCategory.SECURITY,
        failingUrl = failingUrl,
        errorCodeString = errorCode,
        title = title,
        description = description,
        checklist = listOf(
            "Do not enter passwords, credit card numbers, or personal information on this site",
            "Check that your device's system clock and date are set accurately"
        ),
        isDanger = true,
        primaryButtonText = "Back to Safety",
        primaryButtonAction = "back",
        secondaryButtonText = "Advanced",
        secondaryButtonAction = "advanced_ssl",
        technicalDetails = technicalDetails,
        isHstsEnforced = isHstsEnforced
    )

    class ServerDown(
        failingUrl: String,
        val statusCode: Int,
        errorCode: String,
        title: String,
        description: String,
        checklist: List<String>,
        canCheckWayback: Boolean = true,
        technicalDetails: String? = null
    ) : SyntheticNavigationState(
        category = ErrorCategory.SERVER_DOWN,
        failingUrl = failingUrl,
        errorCodeString = errorCode,
        title = title,
        description = description,
        checklist = checklist,
        primaryButtonText = "Try Again",
        primaryButtonAction = "reload",
        secondaryButtonText = if (canCheckWayback) "Check Wayback Machine" else "Search Web",
        secondaryButtonAction = if (canCheckWayback) "wayback_machine" else "search",
        canCheckWayback = canCheckWayback,
        technicalDetails = technicalDetails
    )

    class ShieldsBlocked(
        failingUrl: String,
        val blockedDomain: String,
        title: String = "Blocked by Onyx Shields",
        description: String = "This site was blocked to protect your privacy and shield you from malicious trackers or deceptive content.",
        technicalDetails: String? = "Blocked by active Onyx Shields filter rule."
    ) : SyntheticNavigationState(
        category = ErrorCategory.SHIELDS_BLOCKED,
        failingUrl = failingUrl,
        errorCodeString = "net::ERR_BLOCKED_BY_CLIENT",
        title = title,
        description = description,
        checklist = listOf(
            "Identified as cross-site tracker, deceptive site, or malicious domain",
            "You can temporarily allow this domain or adjust Shields & Privacy Settings"
        ),
        primaryButtonText = "Go to Homepage",
        primaryButtonAction = "home",
        secondaryButtonText = "Shields Settings",
        secondaryButtonAction = "shields",
        technicalDetails = technicalDetails
    )

    class FileError(
        failingUrl: String,
        errorCode: String = "net::ERR_FILE_NOT_FOUND",
        title: String = "File not found",
        description: String = "The local file or document could not be located at this path.",
        checklist: List<String> = listOf(
            "The file may have been moved, renamed, or deleted",
            "Verify file storage permissions for Onyx Browser"
        ),
        technicalDetails: String? = null
    ) : SyntheticNavigationState(
        category = ErrorCategory.FILE_ERROR,
        failingUrl = failingUrl,
        errorCodeString = errorCode,
        title = title,
        description = description,
        checklist = checklist,
        primaryButtonText = "Try Again",
        primaryButtonAction = "reload",
        secondaryButtonText = "Open Downloads",
        secondaryButtonAction = "downloads",
        technicalDetails = technicalDetails
    )

    class Generic(
        failingUrl: String,
        errorCode: String,
        title: String = "Web Page Not Available",
        description: String = "Could not load page.",
        checklist: List<String> = listOf(
            "Check your internet connection and reload the page",
            "If the problem persists, try visiting the page again later"
        ),
        technicalDetails: String? = null
    ) : SyntheticNavigationState(
        category = ErrorCategory.GENERIC,
        failingUrl = failingUrl,
        errorCodeString = errorCode,
        title = title,
        description = description,
        checklist = checklist,
        primaryButtonText = "Try Again",
        primaryButtonAction = "reload",
        secondaryButtonText = "Search Web",
        secondaryButtonAction = "search",
        technicalDetails = technicalDetails
    )
}
