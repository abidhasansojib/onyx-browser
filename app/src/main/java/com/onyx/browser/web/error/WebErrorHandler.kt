package com.onyx.browser.web.error

import android.net.Uri
import android.net.http.SslError
import android.webkit.WebViewClient

/**
 * Universal error classification and resolution engine for Onyx Browser.
 * Converts Chromium WebResourceErrors, HTTP status codes, SSL exceptions,
 * and local file failures into strongly-typed SyntheticNavigationState objects.
 */
object WebErrorHandler {

    typealias ErrorCategory = SyntheticNavigationState.ErrorCategory
    typealias OnyxWebError = SyntheticNavigationState

    /**
     * Resolves a Chromium WebResourceError or legacy errorCode into a SyntheticNavigationState.
     */
    fun resolveNetworkError(
        failingUrl: String,
        errorCode: Int,
        description: String?,
        isNetworkConnected: Boolean
    ): SyntheticNavigationState {
        val desc = description ?: ""
        val domain = extractDomain(failingUrl)

        if (!isNetworkConnected || desc.contains("INTERNET_DISCONNECTED", ignoreCase = true) || desc.contains("NETWORK_CHANGED", ignoreCase = true)) {
            return SyntheticNavigationState.Offline(
                failingUrl = failingUrl,
                errorCode = "net::ERR_INTERNET_DISCONNECTED",
                title = "No internet connection",
                description = "Onyx Browser is unable to connect to the network. Please check your data or Wi-Fi.",
                checklist = listOf(
                    "Check if Wi-Fi or mobile data is turned on",
                    "Check if Airplane mode is turned off",
                    "Restart your wireless router or mobile connection"
                ),
                technicalDetails = "Network interface unavailable or disconnected."
            )
        }

        if (errorCode == WebViewClient.ERROR_HOST_LOOKUP || desc.contains("NAME_NOT_RESOLVED", ignoreCase = true)) {
            return SyntheticNavigationState.Offline(
                failingUrl = failingUrl,
                errorCode = "net::ERR_NAME_NOT_RESOLVED",
                title = "This site can't be reached",
                description = "The server IP address could not be found for $domain.",
                checklist = listOf(
                    "Check the web address for typos (e.g. example.com)",
                    "Test searching for the website name",
                    "Verify DNS settings in Onyx Shields & Privacy"
                ),
                technicalDetails = "DNS query failed: No IP address associated with hostname $domain."
            )
        }

        if (errorCode == WebViewClient.ERROR_CONNECT || desc.contains("CONNECTION_REFUSED", ignoreCase = true)) {
            return SyntheticNavigationState.Offline(
                failingUrl = failingUrl,
                errorCode = "net::ERR_CONNECTION_REFUSED",
                title = "Connection refused",
                description = "$domain refused to connect or actively rejected the connection.",
                checklist = listOf(
                    "The website may be down for maintenance or experiencing an outage",
                    "Check firewall, proxy, or VPN configuration"
                ),
                technicalDetails = "TCP SYN packet rejected by target host $domain."
            )
        }

        if (errorCode == WebViewClient.ERROR_TIMEOUT || desc.contains("TIMED_OUT", ignoreCase = true)) {
            return SyntheticNavigationState.Offline(
                failingUrl = failingUrl,
                errorCode = "net::ERR_TIMED_OUT",
                title = "Connection timed out",
                description = "The server at $domain took too long to respond.",
                checklist = listOf(
                    "The server may be overloaded with high traffic",
                    "Your internet connection might be experiencing slow speeds"
                ),
                technicalDetails = "TCP socket connection timed out waiting for server response."
            )
        }

        if (errorCode == WebViewClient.ERROR_FILE || errorCode == WebViewClient.ERROR_FILE_NOT_FOUND || desc.contains("FILE_NOT_FOUND", ignoreCase = true)) {
            return SyntheticNavigationState.FileError(
                failingUrl = failingUrl,
                errorCode = "net::ERR_FILE_NOT_FOUND",
                title = "File not found",
                description = "The local file or document could not be located at this path.",
                checklist = listOf(
                    "The file may have been moved, renamed, or deleted",
                    "Verify file storage permissions for Onyx Browser"
                ),
                technicalDetails = "File descriptor lookup failed on local filesystem path."
            )
        }

        if (desc.contains("BLOCKED_BY_CLIENT", ignoreCase = true) || desc.contains("UNSAFE_RESOURCE", ignoreCase = true)) {
            return SyntheticNavigationState.ShieldsBlocked(
                failingUrl = failingUrl,
                blockedDomain = domain,
                title = "Blocked by Onyx Shields",
                description = "This site was blocked to protect your privacy and shield you from unwanted trackers, deceptive schemes, or malware.",
                technicalDetails = "Resource matched active adblock filter rule."
            )
        }

        val codeClean = if (desc.startsWith("net::")) desc else if (desc.isNotBlank()) "net::$desc" else "net::ERR_CONNECTION_FAILED"
        return SyntheticNavigationState.Generic(
            failingUrl = failingUrl,
            errorCode = codeClean,
            title = "Web Page Not Available",
            description = "Could not load the page at $domain.",
            checklist = listOf(
                "Check your internet connection and reload the page",
                "If the problem persists, try visiting the page again later"
            ),
            technicalDetails = "General Chromium network stack error ($codeClean)."
        )
    }

    /**
     * Resolves an HTTP status error (4xx or 5xx) on the main frame.
     */
    fun resolveHttpError(failingUrl: String, statusCode: Int, reasonPhrase: String?): SyntheticNavigationState {
        val domain = extractDomain(failingUrl)
        return when (statusCode) {
            404 -> SyntheticNavigationState.ServerDown(
                failingUrl = failingUrl,
                statusCode = 404,
                errorCode = "HTTP 404 Not Found",
                title = "404 - Page Not Found",
                description = "The requested page does not exist on $domain. It may have been moved, deleted, or you might have entered a broken link.",
                checklist = listOf(
                    "Check if there is a spelling mistake in the URL address",
                    "Check the public web archive snapshot on the Wayback Machine",
                    "Return to the website's homepage and navigate from there"
                ),
                canCheckWayback = true,
                technicalDetails = "HTTP status code 404 returned by upstream server."
            )
            403 -> SyntheticNavigationState.ServerDown(
                failingUrl = failingUrl,
                statusCode = 403,
                errorCode = "HTTP 403 Forbidden",
                title = "403 - Access Forbidden",
                description = "You do not have permission to view or access this directory or resource on $domain.",
                checklist = listOf(
                    "The server requires authenticated credentials to access this section",
                    "Access might be restricted by IP or geographic region"
                ),
                canCheckWayback = true,
                technicalDetails = "HTTP status code 403 returned by upstream server."
            )
            401 -> SyntheticNavigationState.ServerDown(
                failingUrl = failingUrl,
                statusCode = 401,
                errorCode = "HTTP 401 Unauthorized",
                title = "401 - Authorization Required",
                description = "This resource requires proper authentication credentials to access.",
                checklist = listOf(
                    "Please check if you are logged in to the service",
                    "Verify your login username and password"
                ),
                canCheckWayback = false,
                technicalDetails = "HTTP status code 401: Authentication header missing or invalid."
            )
            429 -> SyntheticNavigationState.ServerDown(
                failingUrl = failingUrl,
                statusCode = 429,
                errorCode = "HTTP 429 Too Many Requests",
                title = "429 - Rate Limit Exceeded",
                description = "$domain has received too many requests in a short period.",
                checklist = listOf(
                    "Rate limit reached. Please pause and wait a minute before retrying"
                ),
                canCheckWayback = false,
                technicalDetails = "HTTP status code 429: Client rate limit quota exceeded."
            )
            500 -> SyntheticNavigationState.ServerDown(
                failingUrl = failingUrl,
                statusCode = 500,
                errorCode = "HTTP 500 Internal Server Error",
                title = "500 - Internal Server Error",
                description = "The server at $domain encountered an unexpected condition and failed to fulfill the request.",
                checklist = listOf(
                    "This is an issue on the website's backend server, not on your device",
                    "You can view historical snapshots via the Wayback Machine",
                    "Try refreshing the page in a few moments"
                ),
                canCheckWayback = true,
                technicalDetails = "HTTP status code 500 returned by upstream web server."
            )
            502 -> SyntheticNavigationState.ServerDown(
                failingUrl = failingUrl,
                statusCode = 502,
                errorCode = "HTTP 502 Bad Gateway",
                title = "502 - Bad Gateway",
                description = "The proxy or gateway server received an invalid response from the upstream server for $domain.",
                checklist = listOf(
                    "The upstream server is unreachable or failed to respond",
                    "Check the public web archive snapshot on the Wayback Machine",
                    "Try reloading in a few minutes"
                ),
                canCheckWayback = true,
                technicalDetails = "HTTP status code 502 returned by upstream reverse proxy/gateway."
            )
            503 -> SyntheticNavigationState.ServerDown(
                failingUrl = failingUrl,
                statusCode = 503,
                errorCode = "HTTP 503 Service Unavailable",
                title = "503 - Service Unavailable",
                description = "The server at $domain is currently unable to handle the request due to maintenance or capacity overload.",
                checklist = listOf(
                    "The service may be down for scheduled maintenance",
                    "Check the public web archive snapshot on the Wayback Machine",
                    "Try reloading in a few minutes"
                ),
                canCheckWayback = true,
                technicalDetails = "HTTP status code 503 returned: Service overloaded or in maintenance."
            )
            504 -> SyntheticNavigationState.ServerDown(
                failingUrl = failingUrl,
                statusCode = 504,
                errorCode = "HTTP 504 Gateway Timeout",
                title = "504 - Gateway Timeout",
                description = "The gateway server did not receive a timely response from the upstream application server.",
                checklist = listOf(
                    "The upstream database or application server took too long to complete the request",
                    "Check the public web archive snapshot on the Wayback Machine"
                ),
                canCheckWayback = true,
                technicalDetails = "HTTP status code 504 returned by edge proxy/load balancer."
            )
            else -> SyntheticNavigationState.ServerDown(
                failingUrl = failingUrl,
                statusCode = statusCode,
                errorCode = "HTTP $statusCode ${reasonPhrase ?: ""}".trim(),
                title = "HTTP Error $statusCode",
                description = "The server at $domain returned an error ($statusCode).",
                checklist = listOf(
                    "Try refreshing the page or checking the URL address",
                    "Check the public web archive snapshot on the Wayback Machine"
                ),
                canCheckWayback = statusCode >= 400,
                technicalDetails = "HTTP response status code $statusCode."
            )
        }
    }

    /**
     * Resolves an SSL/TLS certificate failure into a SyntheticNavigationState.Security
     * with technical details and HSTS-aware bypass rules.
     */
    fun resolveSslError(
        failingUrl: String,
        sslError: SslError?,
        isHstsEnforced: Boolean = false
    ): SyntheticNavigationState.Security {
        val domain = extractDomain(failingUrl)
        val details = when (sslError?.primaryError) {
            SslError.SSL_EXPIRED -> "The security certificate for $domain expired. The site may be abandoned or unmaintained."
            SslError.SSL_IDMISMATCH -> "The security certificate does not match the domain name $domain. Attackers may be attempting to intercept your connection."
            SslError.SSL_UNTRUSTED -> "The security certificate authority (CA) is not recognized or trusted by your device."
            SslError.SSL_NOTYETVALID -> "The security certificate is not yet valid. Please check that your device's date and time are accurate."
            SslError.SSL_DATE_INVALID -> "The date on the security certificate is invalid."
            else -> "The security certificate could not be verified by Android's cryptographic trust manager."
        }

        return SyntheticNavigationState.Security(
            failingUrl = failingUrl,
            errorCode = "net::ERR_CERT_COMMON_NAME_INVALID",
            title = "Your connection is not private",
            description = "Attackers might be trying to steal your information from $domain (for example, passwords, messages, or credit cards).",
            technicalDetails = details,
            isHstsEnforced = isHstsEnforced
        )
    }

    /**
     * Resolves a client-side shields or malware/phishing block into SyntheticNavigationState.ShieldsBlocked.
     */
    fun resolveShieldsBlocked(failingUrl: String): SyntheticNavigationState.ShieldsBlocked {
        val domain = extractDomain(failingUrl)
        return SyntheticNavigationState.ShieldsBlocked(
            failingUrl = failingUrl,
            blockedDomain = domain
        )
    }

    fun extractDomain(url: String): String =
        try {
            val uri = Uri.parse(url)
            val host = uri.host
            if (!host.isNullOrBlank()) host.removePrefix("www.") else url
        } catch (_: Exception) {
            url
        }
}
