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
        INVALID_URL,
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
        errorCode: String = "ERR_INTERNET_DISCONNECTED",
        title: String = "No internet",
        description: String = "Try:",
        checklist: List<String> = listOf(
            "Checking the network cables, modem, and router",
            "Reconnecting to Wi-Fi",
            "Checking mobile network"
        ),
        technicalDetails: String? = null
    ) : SyntheticNavigationState(
        category = ErrorCategory.OFFLINE,
        failingUrl = failingUrl,
        errorCodeString = errorCode,
        title = title,
        description = description,
        checklist = checklist,
        primaryButtonText = "Reload",
        primaryButtonAction = "reload",
        secondaryButtonText = "Details",
        secondaryButtonAction = "details",
        technicalDetails = technicalDetails,
        hasOfflineGame = false
    )

    class Security(
        failingUrl: String,
        errorCode: String = "NET::ERR_CERT_COMMON_NAME_INVALID",
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
        primaryButtonText = "Back to safety",
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
        primaryButtonText = "Reload",
        primaryButtonAction = "reload",
        secondaryButtonText = "Details",
        secondaryButtonAction = "details",
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
        errorCodeString = "ERR_BLOCKED_BY_CLIENT",
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
        errorCode: String = "ERR_FILE_NOT_FOUND",
        title: String = "Your file couldn’t be accessed",
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
        primaryButtonText = "Reload",
        primaryButtonAction = "reload",
        secondaryButtonText = "Details",
        secondaryButtonAction = "details",
        technicalDetails = technicalDetails
    )

    class InvalidUrl(
        failingUrl: String,
        errorCode: String = "ERR_INVALID_URL",
        title: String = "This webpage is not valid",
        description: String = "The webpage address could not be loaded because the URL is invalid or malformed.",
        checklist: List<String> = listOf(
            "Check the web address for typos (such as ww.example.com instead of www.example.com)",
            "Ensure the URL starts with a valid scheme like https:// or http://",
            "Try searching for the page on your search engine"
        ),
        technicalDetails: String? = "Chromium network stack error net::ERR_INVALID_URL: The URL syntax is invalid or contains malformed characters."
    ) : SyntheticNavigationState(
        category = ErrorCategory.INVALID_URL,
        failingUrl = failingUrl,
        errorCodeString = errorCode,
        title = title,
        description = description,
        checklist = checklist,
        primaryButtonText = "Search Web",
        primaryButtonAction = "search",
        secondaryButtonText = "Details",
        secondaryButtonAction = "details",
        technicalDetails = technicalDetails
    )

    class Generic(
        failingUrl: String,
        errorCode: String,
        title: String = "This site can’t be reached",
        description: String = "Could not load page.",
        checklist: List<String> = listOf(
            "Checking the connection",
            "Checking the proxy, firewall, and DNS configuration"
        ),
        technicalDetails: String? = null,
        category: ErrorCategory = ErrorCategory.GENERIC,
        isDanger: Boolean = false,
        primaryButtonText: String = "Reload",
        primaryButtonAction: String = "reload",
        secondaryButtonText: String = "Details",
        secondaryButtonAction: String = "details",
        canCheckWayback: Boolean = false,
        isHstsEnforced: Boolean = false
    ) : SyntheticNavigationState(
        category = category,
        failingUrl = failingUrl,
        errorCodeString = errorCode,
        title = title,
        description = description,
        checklist = checklist,
        isDanger = isDanger,
        primaryButtonText = primaryButtonText,
        primaryButtonAction = primaryButtonAction,
        secondaryButtonText = secondaryButtonText,
        secondaryButtonAction = secondaryButtonAction,
        technicalDetails = technicalDetails,
        canCheckWayback = canCheckWayback,
        isHstsEnforced = isHstsEnforced
    )
}
