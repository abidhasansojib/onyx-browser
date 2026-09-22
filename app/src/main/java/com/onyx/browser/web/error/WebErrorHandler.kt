package com.onyx.browser.web.error

import android.net.Uri
import android.net.http.SslError
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient

/**
 * Universal error classification and resolution engine for Onyx Browser.
 * Converts Chromium WebResourceErrors, HTTP status codes, SSL exceptions,
 * and local file failures into strongly-typed, beautifully structured OnyxWebError payloads.
 */
object WebErrorHandler {

    enum class ErrorCategory {
        OFFLINE,
        DNS_NOT_FOUND,
        CONNECTION_REFUSED,
        CONNECTION_RESET,
        TIMED_OUT,
        SSL_SECURITY,
        HTTP_CLIENT_ERROR,
        HTTP_NOT_FOUND,
        HTTP_FORBIDDEN,
        HTTP_UNAUTHORIZED,
        HTTP_SERVER_ERROR,
        HTTP_TOO_MANY_REQUESTS,
        FILE_ERROR,
        BLOCKED_BY_SHIELDS,
        INVALID_URL,
        GENERIC
    }

    data class OnyxWebError(
        val category: ErrorCategory,
        val errorCodeString: String,
        val title: String,
        val description: String,
        val failingUrl: String,
        val checklist: List<String>,
        val primaryButtonText: String = "Try Again",
        val primaryButtonAction: String = "reload",
        val secondaryButtonText: String = "Search Web",
        val secondaryButtonAction: String = "search",
        val isDanger: Boolean = false,
        val sslDetails: String? = null
    ) {
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
            val escapedPrimary = escapeJson(primaryButtonText)
            val escapedSecondary = escapeJson(secondaryButtonText)
            val escapedSsl = sslDetails?.let { escapeJson(it) } ?: ""

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
                    "primaryButtonText": "$escapedPrimary",
                    "primaryButtonAction": "$primaryButtonAction",
                    "secondaryButtonText": "$escapedSecondary",
                    "secondaryButtonAction": "$secondaryButtonAction",
                    "isDanger": $isDanger,
                    "sslDetails": "$escapedSsl"
                }
            """.trimIndent()
        }

        private fun escapeJson(str: String): String =
            str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t")
    }

    /**
     * Resolves a Chromium WebResourceError or legacy errorCode into an OnyxWebError.
     */
    fun resolveNetworkError(
        failingUrl: String,
        errorCode: Int,
        description: String?,
        isNetworkConnected: Boolean
    ): OnyxWebError {
        val desc = description ?: ""
        val domain = extractDomain(failingUrl)

        if (!isNetworkConnected || desc.contains("INTERNET_DISCONNECTED", ignoreCase = true) || desc.contains("NETWORK_CHANGED", ignoreCase = true)) {
            return OnyxWebError(
                category = ErrorCategory.OFFLINE,
                errorCodeString = "net::ERR_INTERNET_DISCONNECTED",
                title = "No internet connection",
                description = "Onyx Browser is unable to connect to the network. Please check your data or Wi-Fi.",
                failingUrl = failingUrl,
                checklist = listOf(
                    "Check if Wi-Fi or mobile data is turned on",
                    "Check if Airplane mode is turned off",
                    "Restart your wireless router or mobile connection"
                ),
                primaryButtonText = "Try Again",
                primaryButtonAction = "reload",
                secondaryButtonText = "Network Settings",
                secondaryButtonAction = "settings",
                isDanger = false
            )
        }

        if (errorCode == WebViewClient.ERROR_HOST_LOOKUP || desc.contains("NAME_NOT_RESOLVED", ignoreCase = true)) {
            return OnyxWebError(
                category = ErrorCategory.DNS_NOT_FOUND,
                errorCodeString = "net::ERR_NAME_NOT_RESOLVED",
                title = "This site can't be reached",
                description = "The server IP address could not be found for $domain.",
                failingUrl = failingUrl,
                checklist = listOf(
                    "Check the web address for typos (e.g. example.com)",
                    "Test searching for the website name",
                    "Verify DNS settings in Onyx Shields & Privacy"
                ),
                primaryButtonText = "Try Again",
                primaryButtonAction = "reload",
                secondaryButtonText = "Search Web",
                secondaryButtonAction = "search",
                isDanger = false
            )
        }

        if (errorCode == WebViewClient.ERROR_CONNECT || desc.contains("CONNECTION_REFUSED", ignoreCase = true)) {
            return OnyxWebError(
                category = ErrorCategory.CONNECTION_REFUSED,
                errorCodeString = "net::ERR_CONNECTION_REFUSED",
                title = "Connection refused",
                description = "$domain refused to connect or actively rejected the connection.",
                failingUrl = failingUrl,
                checklist = listOf(
                    "The website may be down for maintenance or experiencing an outage",
                    "Check firewall, proxy, or VPN configuration"
                ),
                primaryButtonText = "Try Again",
                primaryButtonAction = "reload",
                secondaryButtonText = "Search Web",
                secondaryButtonAction = "search"
            )
        }

        if (errorCode == WebViewClient.ERROR_TIMEOUT || desc.contains("TIMED_OUT", ignoreCase = true)) {
            return OnyxWebError(
                category = ErrorCategory.TIMED_OUT,
                errorCodeString = "net::ERR_TIMED_OUT",
                title = "Connection timed out",
                description = "The server at $domain took too long to respond.",
                failingUrl = failingUrl,
                checklist = listOf(
                    "The server may be overloaded with high traffic",
                    "Your internet connection might be experiencing slow speeds"
                ),
                primaryButtonText = "Try Again",
                primaryButtonAction = "reload",
                secondaryButtonText = "Search Web",
                secondaryButtonAction = "search"
            )
        }

        if (errorCode == WebViewClient.ERROR_FILE || errorCode == WebViewClient.ERROR_FILE_NOT_FOUND || desc.contains("FILE_NOT_FOUND", ignoreCase = true)) {
            return OnyxWebError(
                category = ErrorCategory.FILE_ERROR,
                errorCodeString = "net::ERR_FILE_NOT_FOUND",
                title = "File not found",
                description = "The local file or document could not be located at this path.",
                failingUrl = failingUrl,
                checklist = listOf(
                    "The file may have been moved, renamed, or deleted",
                    "Verify file storage permissions for Onyx Browser"
                ),
                primaryButtonText = "Try Again",
                primaryButtonAction = "reload",
                secondaryButtonText = "Open Downloads",
                secondaryButtonAction = "downloads"
            )
        }

        if (desc.contains("BLOCKED_BY_CLIENT", ignoreCase = true) || desc.contains("UNSAFE_RESOURCE", ignoreCase = true)) {
            return OnyxWebError(
                category = ErrorCategory.BLOCKED_BY_SHIELDS,
                errorCodeString = "net::ERR_BLOCKED_BY_CLIENT",
                title = "Blocked by Onyx Shields",
                description = "This resource was blocked to protect your privacy and shield you from unwanted trackers or malware.",
                failingUrl = failingUrl,
                checklist = listOf(
                    "Identified as cross-site tracker, adware, or malicious domain",
                    "You can allow this domain in Shields & Privacy Settings"
                ),
                primaryButtonText = "Go to Homepage",
                primaryButtonAction = "home",
                secondaryButtonText = "Shields Settings",
                secondaryButtonAction = "shields"
            )
        }

        val codeClean = if (desc.startsWith("net::")) desc else if (desc.isNotBlank()) "net::$desc" else "net::ERR_CONNECTION_FAILED"
        return OnyxWebError(
            category = ErrorCategory.GENERIC,
            errorCodeString = codeClean,
            title = "Web Page Not Available",
            description = "Could not load the page at $domain.",
            failingUrl = failingUrl,
            checklist = listOf(
                "Check your internet connection and reload the page",
                "If the problem persists, try visiting the page again later"
            ),
            primaryButtonText = "Try Again",
            primaryButtonAction = "reload",
            secondaryButtonText = "Search Web",
            secondaryButtonAction = "search"
        )
    }

    /**
     * Resolves an HTTP status error (4xx or 5xx) on the main frame.
     */
    fun resolveHttpError(failingUrl: String, statusCode: Int, reasonPhrase: String?): OnyxWebError {
        val domain = extractDomain(failingUrl)
        return when (statusCode) {
            404 -> OnyxWebError(
                category = ErrorCategory.HTTP_NOT_FOUND,
                errorCodeString = "HTTP 404 Not Found",
                title = "404 - Page Not Found",
                description = "The requested page does not exist on $domain. It may have been moved, deleted, or you might have a broken link.",
                failingUrl = failingUrl,
                checklist = listOf(
                    "Check if there is a spelling mistake in the URL address",
                    "Return to the website's homepage and navigate from there"
                ),
                primaryButtonText = "Go to Homepage",
                primaryButtonAction = "home",
                secondaryButtonText = "Search Web",
                secondaryButtonAction = "search"
            )
            403 -> OnyxWebError(
                category = ErrorCategory.HTTP_FORBIDDEN,
                errorCodeString = "HTTP 403 Forbidden",
                title = "403 - Access Forbidden",
                description = "You do not have permission to view or access this directory or resource on $domain.",
                failingUrl = failingUrl,
                checklist = listOf(
                    "The server requires authenticated credentials to access this section",
                    "Access might be restricted by IP or geographic region"
                ),
                primaryButtonText = "Try Again",
                primaryButtonAction = "reload",
                secondaryButtonText = "Go to Homepage",
                secondaryButtonAction = "home"
            )
            401 -> OnyxWebError(
                category = ErrorCategory.HTTP_UNAUTHORIZED,
                errorCodeString = "HTTP 401 Unauthorized",
                title = "401 - Authorization Required",
                description = "This resource requires proper authentication credentials to access.",
                failingUrl = failingUrl,
                checklist = listOf(
                    "Please check if you are logged in to the service",
                    "Verify your login username and password"
                ),
                primaryButtonText = "Try Again",
                primaryButtonAction = "reload",
                secondaryButtonText = "Go to Homepage",
                secondaryButtonAction = "home"
            )
            429 -> OnyxWebError(
                category = ErrorCategory.HTTP_TOO_MANY_REQUESTS,
                errorCodeString = "HTTP 429 Too Many Requests",
                title = "429 - Rate Limit Exceeded",
                description = "$domain has received too many requests in a short period.",
                failingUrl = failingUrl,
                checklist = listOf(
                    "Rate limit reached. Please pause and wait a minute before retrying"
                ),
                primaryButtonText = "Try Again",
                primaryButtonAction = "reload",
                secondaryButtonText = "Search Web",
                secondaryButtonAction = "search"
            )
            500 -> OnyxWebError(
                category = ErrorCategory.HTTP_SERVER_ERROR,
                errorCodeString = "HTTP 500 Internal Server Error",
                title = "500 - Internal Server Error",
                description = "The server at $domain encountered an unexpected condition and failed to fulfill the request.",
                failingUrl = failingUrl,
                checklist = listOf(
                    "This is an issue on the website's backend server, not on your device",
                    "Try refreshing the page in a few moments"
                ),
                primaryButtonText = "Try Again",
                primaryButtonAction = "reload",
                secondaryButtonText = "Search Web",
                secondaryButtonAction = "search"
            )
            502 -> OnyxWebError(
                category = ErrorCategory.HTTP_SERVER_ERROR,
                errorCodeString = "HTTP 502 Bad Gateway",
                title = "502 - Bad Gateway",
                description = "The proxy or gateway server received an invalid response from the upstream server for $domain.",
                failingUrl = failingUrl,
                checklist = listOf(
                    "The upstream server is unreachable or failed to respond",
                    "Try reloading in a few minutes"
                ),
                primaryButtonText = "Try Again",
                primaryButtonAction = "reload",
                secondaryButtonText = "Search Web",
                secondaryButtonAction = "search"
            )
            503 -> OnyxWebError(
                category = ErrorCategory.HTTP_SERVER_ERROR,
                errorCodeString = "HTTP 503 Service Unavailable",
                title = "503 - Service Unavailable",
                description = "The server at $domain is currently unable to handle the request due to maintenance or capacity overload.",
                failingUrl = failingUrl,
                checklist = listOf(
                    "The service may be down for scheduled maintenance",
                    "Try reloading in a few minutes"
                ),
                primaryButtonText = "Try Again",
                primaryButtonAction = "reload",
                secondaryButtonText = "Search Web",
                secondaryButtonAction = "search"
            )
            504 -> OnyxWebError(
                category = ErrorCategory.HTTP_SERVER_ERROR,
                errorCodeString = "HTTP 504 Gateway Timeout",
                title = "504 - Gateway Timeout",
                description = "The gateway server did not receive a timely response from the upstream server.",
                failingUrl = failingUrl,
                checklist = listOf(
                    "The upstream database or application server took too long to complete the request"
                ),
                primaryButtonText = "Try Again",
                primaryButtonAction = "reload",
                secondaryButtonText = "Search Web",
                secondaryButtonAction = "search"
            )
            else -> OnyxWebError(
                category = if (statusCode >= 500) ErrorCategory.HTTP_SERVER_ERROR else ErrorCategory.HTTP_CLIENT_ERROR,
                errorCodeString = "HTTP $statusCode ${reasonPhrase ?: ""}".trim(),
                title = "HTTP Error $statusCode",
                description = "The server at $domain returned an error ($statusCode).",
                failingUrl = failingUrl,
                checklist = listOf(
                    "Try refreshing the page or checking the URL address"
                ),
                primaryButtonText = "Try Again",
                primaryButtonAction = "reload",
                secondaryButtonText = "Search Web",
                secondaryButtonAction = "search"
            )
        }
    }

    /**
     * Resolves an SSL/TLS certificate failure into an OnyxWebError with technical details and bypass option.
     */
    fun resolveSslError(failingUrl: String, sslError: SslError?): OnyxWebError {
        val domain = extractDomain(failingUrl)
        val details = when (sslError?.primaryError) {
            SslError.SSL_EXPIRED -> "The security certificate for $domain expired. The site may be abandoned or unmaintained."
            SslError.SSL_IDMISMATCH -> "The security certificate does not match the domain name $domain. Attackers may be attempting to intercept your connection."
            SslError.SSL_UNTRUSTED -> "The security certificate authority (CA) is not trusted by your device."
            SslError.SSL_NOTYETVALID -> "The security certificate is not yet valid. Please check that your device's date and time are accurate."
            SslError.SSL_DATE_INVALID -> "The date on the security certificate is invalid."
            else -> "The security certificate could not be verified by Android."
        }

        return OnyxWebError(
            category = ErrorCategory.SSL_SECURITY,
            errorCodeString = "net::ERR_CERT_COMMON_NAME_INVALID",
            title = "Your connection is not private",
            description = "Attackers might be trying to steal your information from $domain (for example, passwords, messages, or credit cards).",
            failingUrl = failingUrl,
            checklist = listOf(
                "Do not enter passwords, credit card numbers, or personal information on this site",
                "Check that your device's system clock and date are set accurately"
            ),
            primaryButtonText = "Back to Safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            isDanger = true,
            sslDetails = details
        )
    }

    private fun extractDomain(url: String): String =
        try {
            val uri = Uri.parse(url)
            val host = uri.host
            if (!host.isNullOrBlank()) host.removePrefix("www.") else url
        } catch (_: Exception) {
            url
        }
}
