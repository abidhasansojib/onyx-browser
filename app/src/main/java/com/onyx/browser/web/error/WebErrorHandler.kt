package com.onyx.browser.web.error

import android.net.Uri
import android.net.http.SslError
import android.webkit.WebViewClient

typealias ErrorCategory = SyntheticNavigationState.ErrorCategory
typealias OnyxWebError = SyntheticNavigationState

/**
 * Universal error classification and resolution engine for Onyx Browser.
 * Converts Chromium WebResourceErrors, HTTP status codes, SSL exceptions,
 * and local file failures into strongly-typed SyntheticNavigationState objects.
 * Handles all 105 Chromium net error codes with rich contextual recommendations.
 */
object WebErrorHandler {

    data class ErrorDefinition(
        val category: SyntheticNavigationState.ErrorCategory,
        val title: String,
        val descriptionTemplate: String,
        val checklist: List<String>,
        val isDanger: Boolean = false,
        val primaryButtonText: String = "Reload",
        val primaryButtonAction: String = "reload",
        val secondaryButtonText: String = "Details",
        val secondaryButtonAction: String = "details",
        val technicalDetails: String? = null,
        val canCheckWayback: Boolean = false,
        val isHstsEnforced: Boolean = false
    )

    private val ERR_CODE_REGEX = Regex("""ERR_[A-Z0-9_]+""")

    val CHROMIUM_NET_ERRORS: Map<String, ErrorDefinition> = mapOf(
        // ====================================================================
        // --- ANDROID-SPECIFIC & CAPTIVE PORTAL ERRORS (3) ---
        // ====================================================================
        "ERR_CLEARTEXT_NOT_PERMITTED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Cleartext HTTP traffic not permitted",
            descriptionTemplate = "The connection to %s is unencrypted (HTTP) and was restricted by Android system network security policy.",
            checklist = listOf(
                "Try loading the site over secure HTTPS: https://%s",
                "Check if the site or local network supports secure connections"
            ),
            isDanger = true,
            primaryButtonText = "Try HTTPS",
            primaryButtonAction = "reload_https",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Android network security config blocks unencrypted cleartext HTTP traffic to protect sensitive data."
        ),
        "ERR_CAPTIVE_PORTAL_DETECTED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.OFFLINE,
            title = "Wi-Fi login required",
            descriptionTemplate = "The Wi-Fi network you are using requires you to sign in before you can connect to %s.",
            checklist = listOf(
                "Sign in or accept the terms of the local Wi-Fi hotspot",
                "Open network settings to complete login"
            ),
            primaryButtonText = "Sign in to Network",
            primaryButtonAction = "network_settings",
            secondaryButtonText = "Reload",
            secondaryButtonAction = "reload",
            technicalDetails = "Chromium captive portal probe detected HTTP redirect on local gateway."
        ),
        "ERR_NETWORK_ACCESS_DENIED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.OFFLINE,
            title = "Network access denied",
            descriptionTemplate = "Onyx Browser was blocked from accessing the network.",
            checklist = listOf(
                "Check if Airplane Mode is on",
                "Check mobile data or Wi-Fi permissions in Android Settings",
                "Check if a firewall or VPN app is blocking network traffic"
            ),
            primaryButtonText = "Network Settings",
            primaryButtonAction = "network_settings",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "OS socket creation failed with EACCES or network permissions denied."
        ),

        // ====================================================================
        // --- CLIENT-SIDE, SECURITY POLICY & AD-BLOCKING (7) ---
        // ====================================================================
        "ERR_BLOCKED_BY_CLIENT" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SHIELDS_BLOCKED,
            title = "Blocked by Onyx Shields",
            descriptionTemplate = "Onyx Shields blocked %s to protect your privacy and shield you from unwanted trackers, deceptive ads, or malware.",
            checklist = listOf(
                "Identified as cross-site tracker, deceptive site, or intrusive ad domain",
                "You can temporarily allow this domain or adjust Shields & Privacy Settings"
            ),
            primaryButtonText = "Go to Homepage",
            primaryButtonAction = "home",
            secondaryButtonText = "Shields Settings",
            secondaryButtonAction = "shields",
            technicalDetails = "Resource matched active adblock filter rule."
        ),
        "ERR_BLOCKED_BY_RESPONSE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Blocked by security response policy",
            descriptionTemplate = "The response from %s was blocked by the browser due to cross-origin security restrictions.",
            checklist = listOf(
                "The site responded with headers restricting cross-origin framing or isolation",
                "The server operator needs to adjust their Cross-Origin-Resource-Policy headers"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Resource blocked by Cross-Origin-Resource-Policy (CORP) or cross-origin embedder policy."
        ),
        "ERR_BLOCKED_BY_ORB" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Blocked by Opaque Resource Blocking",
            descriptionTemplate = "Onyx Browser blocked a cross-origin resource from %s to protect against side-channel data leaks.",
            checklist = listOf(
                "The requested resource was blocked by Chromium's Opaque Resource Blocking (ORB) security standard",
                "This protects your browsing session against cross-site data disclosure"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Chromium ORB (Opaque Resource Blocking) blocked non-opaque cross-origin resource."
        ),
        "ERR_BLOCKED_BY_CSP" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Blocked by Content Security Policy",
            descriptionTemplate = "The webpage at %s refused to load because it violates the site's Content Security Policy.",
            checklist = listOf(
                "The website has strict security rules defining which scripts and frames can run",
                "This protects you against cross-site scripting (XSS) attacks"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Content Security Policy directive violation prevented document loading."
        ),
        "ERR_INSECURE_RESPONSE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Insecure response blocked",
            descriptionTemplate = "%s returned an insecure or unauthenticated response.",
            checklist = listOf(
                "Attackers may be attempting to tamper with your connection",
                "Do not enter passwords or personal details on this page"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "The server response failed certificate validation or cryptographic integrity checks."
        ),
        "ERR_UNSAFE_PORT" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Access to unsafe port blocked",
            descriptionTemplate = "The address %s uses a network port that is restricted for security reasons.",
            checklist = listOf(
                "The requested URL specifies a port reserved for dangerous or sensitive non-HTTP protocols",
                "Check the port number in the address bar"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Port is on Chromium's blocked port list to prevent cross-protocol attacks."
        ),
        "ERR_UNSAFE_REDIRECT" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Unsafe redirect blocked",
            descriptionTemplate = "%s attempted to redirect you to an unsafe or restricted address.",
            checklist = listOf(
                "The server attempted to redirect from a secure context to an insecure or forbidden protocol",
                "The redirect was blocked to protect your device"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Unsafe protocol or scheme transition in HTTP redirect chain."
        ),

        // ====================================================================
        // --- CONNECTION & NETWORK ISSUES (24) ---
        // ====================================================================
        "ERR_INTERNET_DISCONNECTED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.OFFLINE,
            title = "No internet",
            descriptionTemplate = "Try:",
            checklist = listOf(
                "Checking the network cables, modem, and router",
                "Reconnecting to Wi-Fi",
                "Checking mobile network settings"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Default network interface is disconnected."
        ),
        "ERR_NETWORK_CHANGED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.OFFLINE,
            title = "Network change detected",
            descriptionTemplate = "Your connection was interrupted because the network configuration changed.",
            checklist = listOf(
                "Your device switched between Wi-Fi and mobile data",
                "Reload the page to reconnect over the active network"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Chromium network observer reported IP route or network adapter change."
        ),
        "ERR_CONNECTION_TIMED_OUT" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "This site can’t be reached",
            descriptionTemplate = "%s took too long to respond.",
            checklist = listOf(
                "Checking the connection",
                "Checking the proxy and the firewall",
                "Running network diagnostics"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "TCP connection attempt timed out waiting for server response."
        ),
        "ERR_CONNECTION_CLOSED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "This site can’t be reached",
            descriptionTemplate = "The server at %s unexpectedly closed the connection.",
            checklist = listOf(
                "Checking the connection",
                "Checking the proxy and firewall configuration",
                "Try reloading the page in a few moments"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "TCP socket closed unexpectedly by remote peer."
        ),
        "ERR_CONNECTION_RESET" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "This site can’t be reached",
            descriptionTemplate = "The connection to %s was reset.",
            checklist = listOf(
                "Checking the connection",
                "Checking the proxy and the firewall",
                "The host or intermediate firewall sent a TCP reset"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "TCP connection reset (RST packet received from remote host or firewall)."
        ),
        "ERR_CONNECTION_REFUSED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "This site can’t be reached",
            descriptionTemplate = "%s refused to connect.",
            checklist = listOf(
                "Checking the connection",
                "Checking the proxy and the firewall",
                "Ensure the web server is running and listening on the port"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "TCP connection refused: No server is listening on the target port."
        ),
        "ERR_CONNECTION_ABORTED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Connection aborted",
            descriptionTemplate = "The connection to %s was aborted by your device or network software.",
            checklist = listOf(
                "Checking the connection",
                "Checking antivirus, VPN, or firewall software",
                "Try reloading the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "TCP socket connection was locally aborted."
        ),
        "ERR_SOCKET_NOT_CONNECTED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Socket not connected",
            descriptionTemplate = "The socket connection to %s is not connected.",
            checklist = listOf(
                "The underlying network connection dropped during transmission",
                "Try reloading the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Socket operation attempted on an unconnected network socket."
        ),
        "ERR_NAME_NOT_RESOLVED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "This site can’t be reached",
            descriptionTemplate = "%s’s server IP address could not be found.",
            checklist = listOf(
                "Checking the connection",
                "Checking the proxy, firewall, and DNS configuration",
                "Checking the web address for spelling errors"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "DNS lookup failed: Hostname does not exist or DNS server failed to answer."
        ),
        "ERR_NAME_RESOLUTION_FAILED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "DNS resolution failed",
            descriptionTemplate = "The DNS resolution for %s failed.",
            checklist = listOf(
                "Verify DNS settings on your network or Wi-Fi router",
                "Check if the domain name is typed correctly"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Chromium asynchronous DNS resolver failed to resolve the host."
        ),
        "ERR_DNS_TIMED_OUT" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "DNS query timed out",
            descriptionTemplate = "The DNS server took too long to answer queries for %s.",
            checklist = listOf(
                "Check your DNS provider or router DNS configuration",
                "Switch to an alternate DNS (such as 1.1.1.1 or 8.8.8.8)"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "DNS query timed out waiting for UDP/TCP response from nameserver."
        ),
        "ERR_DNS_SERVER_FAILED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "DNS server failure",
            descriptionTemplate = "The DNS server returned an error while resolving %s.",
            checklist = listOf(
                "The nameserver for this domain is misconfigured or malfunctioning",
                "Try refreshing in a few moments"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "DNS server responded with SERVFAIL status code."
        ),
        "ERR_DNS_MALFORMED_RESPONSE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Malformed DNS response",
            descriptionTemplate = "The DNS server returned a malformed response for %s.",
            checklist = listOf(
                "Your router or ISP DNS sent corrupted DNS response packets",
                "Try flushing DNS cache or restarting network"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "DNS response parser failed to decode nameserver record payload."
        ),
        "ERR_DNS_SERVER_REQUIRES_TCP" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "DNS requires TCP",
            descriptionTemplate = "The DNS server requires TCP to resolve %s.",
            checklist = listOf(
                "The DNS response was truncated over UDP and TCP fallback is required",
                "Verify TCP port 53 is not blocked by local firewall"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "DNS server indicated truncation (TC bit set) requiring TCP retry."
        ),
        "ERR_ADDRESS_UNREACHABLE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Address unreachable",
            descriptionTemplate = "The IP address for %s is unreachable.",
            checklist = listOf(
                "Checking the connection",
                "The server may be powered down or disconnected from the internet",
                "Check the IP address routing"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Host or network route unreachable (ICMP Destination Unreachable)."
        ),
        "ERR_ADDRESS_IN_USE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Network address in use",
            descriptionTemplate = "The local network address or port is already in use.",
            checklist = listOf(
                "Another process or network socket is using the required local port",
                "Restart network connection or your device"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Socket bind operation failed with EADDRINUSE."
        ),
        "ERR_ADDRESS_INVALID" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.INVALID_URL,
            title = "Invalid network address",
            descriptionTemplate = "The IP address specified for %s is invalid.",
            checklist = listOf(
                "Check the address for typing mistakes",
                "Verify whether the target IP address is properly formatted"
            ),
            primaryButtonText = "Search Web",
            primaryButtonAction = "search",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Target IP address is not a valid IPv4 or IPv6 unicast address."
        ),
        "ERR_HOST_RESOLVER_QUEUE_TOO_LARGE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "DNS resolver queue full",
            descriptionTemplate = "Too many concurrent DNS requests are pending.",
            checklist = listOf(
                "The browser's host resolver queue is congested",
                "Wait a few seconds and reload the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Internal Chromium host resolver queue capacity exceeded."
        ),
        "ERR_EMPTY_RESPONSE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Empty response",
            descriptionTemplate = "%s didn’t send any data.",
            checklist = listOf(
                "The server closed the connection without returning any data",
                "Try reloading the page in a few minutes"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Server sent 0 bytes of response data before closing connection."
        ),
        "ERR_RESPONSE_HEADERS_TOO_BIG" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Response headers too large",
            descriptionTemplate = "The server at %s returned response headers that are too large.",
            checklist = listOf(
                "The website sent excessively large HTTP response headers or cookie headers",
                "Try clearing cookies for this site"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "HTTP response headers exceed maximum buffer size."
        ),
        "ERR_RESPONSE_HEADERS_MULTIPLE_CONTENT_LENGTH" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Conflicting response headers",
            descriptionTemplate = "The server sent multiple conflicting Content-Length headers.",
            checklist = listOf(
                "This may be a sign of HTTP request smuggling or a misconfigured proxy",
                "The page was blocked for your security"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Multiple disparate Content-Length header values detected in HTTP response."
        ),
        "ERR_RESPONSE_HEADERS_MULTIPLE_CONTENT_DISPOSITION" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Conflicting Content-Disposition headers",
            descriptionTemplate = "The server sent multiple conflicting Content-Disposition headers.",
            checklist = listOf(
                "Conflicting download headers detected",
                "The page was blocked to prevent file download spoofing attacks"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Duplicate or malformed Content-Disposition headers in HTTP response."
        ),
        "ERR_RESPONSE_HEADERS_MULTIPLE_LOCATION" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Conflicting redirect headers",
            descriptionTemplate = "The server sent multiple conflicting Location redirect headers.",
            checklist = listOf(
                "The server or proxy sent multiple different redirect targets",
                "The response was blocked for your protection"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Multiple Location headers found in HTTP 3xx redirect response."
        ),
        "ERR_TOO_MANY_REDIRECTS" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "This page isn’t working",
            descriptionTemplate = "%s redirected you too many times.",
            checklist = listOf(
                "Try clearing your cookies for this site",
                "The website has a misconfigured redirect loop"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Exceeded maximum allowed HTTP redirect limit (redirect loop)."
        ),

        // ====================================================================
        // --- SSL / TLS & CERTIFICATE ERRORS (26) ---
        // ====================================================================
        "ERR_CERT_COMMON_NAME_INVALID" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "Attackers might be trying to steal your information from %s (for example, passwords, messages, or credit cards).",
            checklist = listOf(
                "The security certificate belongs to a different domain name",
                "Check that the URL is spelled correctly"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Certificate subject alternative name (SAN) does not match requested hostname %s."
        ),
        "ERR_CERT_DATE_INVALID" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your clock is ahead or behind",
            descriptionTemplate = "A private connection to %s can't be established because your device's date and time are incorrect, or the certificate has expired.",
            checklist = listOf(
                "Check that your device's date and time are accurate",
                "The certificate may have expired or is not yet valid"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Certificate validity period has expired or is in the future."
        ),
        "ERR_CERT_AUTHORITY_INVALID" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "The security certificate for %s is not trusted by your device's operating system.",
            checklist = listOf(
                "The certificate issuer is unknown or self-signed",
                "Do not enter passwords or sensitive personal data"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Certificate authority (CA) root certificate is not in the system trust store."
        ),
        "ERR_CERT_CONTAINS_ERRORS" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "The security certificate for %s contains decoding or syntax errors.",
            checklist = listOf(
                "The certificate format is corrupted or malformed",
                "Do not submit sensitive credentials on this site"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "X.509 certificate decoding error: ASN.1 parser encountered invalid structure."
        ),
        "ERR_CERT_NO_REVOCATION_MECHANISM" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "The certificate for %s does not specify a revocation mechanism.",
            checklist = listOf(
                "The certificate lacks OCSP or CRL revocation endpoints",
                "Connection was terminated according to security policy"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Certificate does not contain CRL distribution points or OCSP AIA extension."
        ),
        "ERR_CERT_UNABLE_TO_CHECK_REVOCATION" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "Onyx Browser was unable to check if the security certificate for %s has been revoked.",
            checklist = listOf(
                "The certificate revocation server (OCSP/CRL) is unreachable",
                "Check your internet connection and try again"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "OCSP/CRL revocation server check failed or timed out."
        ),
        "ERR_CERT_REVOKED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "The security certificate for %s has been explicitly revoked by its issuer.",
            checklist = listOf(
                "This site’s certificate was compromised or revoked",
                "Do NOT proceed to this website"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Certificate serial number listed on CRL or OCSP responder returned REVOKED status."
        ),
        "ERR_CERT_INVALID" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "The security certificate presented by %s is invalid.",
            checklist = listOf(
                "The certificate failed cryptographic verification",
                "Attackers may be attempting to intercept your connection"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "General X.509 certificate validation failure."
        ),
        "ERR_CERT_WEAK_SIGNATURE_ALGORITHM" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "The security certificate for %s was signed using an insecure or obsolete algorithm.",
            checklist = listOf(
                "The certificate uses a weak signature algorithm (such as MD5 or SHA-1)",
                "The website owner must reissue the certificate using SHA-256 or better"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Certificate signature uses deprecated cryptographic algorithm."
        ),
        "ERR_CERT_NON_UNIQUE_NAME" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "The certificate for %s contains a non-unique hostname.",
            checklist = listOf(
                "Public certificates cannot be issued for internal domain names or reserved TLDs",
                "Attackers could impersonate this server"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Certificate SAN contains internal or non-globally unique name."
        ),
        "ERR_CERT_WEAK_KEY" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "The security certificate for %s uses a weak cryptographic key.",
            checklist = listOf(
                "The public key size is too small to ensure secure encryption",
                "The website owner must upgrade their key length to at least RSA 2048-bit"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Public key length falls below acceptable cryptographic security threshold."
        ),
        "ERR_CERT_NAME_CONSTRAINT_VIOLATION" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "The certificate for %s violates certificate authority name constraints.",
            checklist = listOf(
                "The certificate was issued outside of the intermediate CA's permitted domain space",
                "The connection was refused for your security"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "X.509 Name Constraints extension violated by certificate SAN."
        ),
        "ERR_CERT_VALIDITY_TOO_LONG" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "The certificate for %s has a validity period that exceeds modern security standards.",
            checklist = listOf(
                "Public certificates may not exceed a validity period of 398 days",
                "The website owner must obtain a newer compliant certificate"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Certificate validity period exceeds CAB Forum baseline requirement limit."
        ),
        "ERR_CERT_KNOWN_INTERCEPTION_BLOCKED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "A known network interception attempt was blocked for %s.",
            checklist = listOf(
                "Your network or an attacker is attempting to intercept and decrypt your traffic",
                "Disconnect from this network immediately"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Certificate matches fingerprint of known TLS interception or MITM proxy."
        ),
        "ERR_CERT_END" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "The certificate chain for %s could not be completed.",
            checklist = listOf(
                "The server failed to supply required intermediate certificates",
                "The trust chain cannot be verified"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "End of certificate trust chain reached without finding trusted root."
        ),
        "ERR_BAD_SSL_CLIENT_AUTH_CERT" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Client certificate error",
            descriptionTemplate = "The server at %s rejected your client authentication certificate.",
            checklist = listOf(
                "The client certificate installed on your device was rejected or invalid",
                "Contact your network administrator for an updated client certificate"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Server rejected TLS client certificate during mutual authentication handshake."
        ),
        "ERR_SSL_CLIENT_AUTH_CERT_NEEDED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Client certificate required",
            descriptionTemplate = "%s requires a client certificate to log in.",
            checklist = listOf(
                "This private or enterprise service requires a personal client certificate",
                "Install a valid client certificate on your device to access this site"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Server requested TLS client authentication certificate (CertificateRequest message)."
        ),
        "ERR_SSL_CLIENT_AUTH_PRIVATE_KEY_ACCESS_DENIED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Client certificate access denied",
            descriptionTemplate = "Onyx Browser was denied access to the private key for %s.",
            checklist = listOf(
                "Permission to access the Android KeyStore credential was denied",
                "Grant access to the requested client key"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Android KeyStore private key access failed with permission error."
        ),
        "ERR_SSL_PROTOCOL_ERROR" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "SSL protocol error",
            descriptionTemplate = "Onyx Browser and the server at %s could not establish a secure connection.",
            checklist = listOf(
                "The server sent invalid or malformed SSL/TLS handshake data",
                "The server may be running outdated or unsupported encryption software"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Cryptographic protocol error during TLS negotiation."
        ),
        "ERR_SSL_VERSION_OR_CIPHER_MISMATCH" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Unsupported protocol or cipher",
            descriptionTemplate = "%s uses an unsupported protocol or cipher suite.",
            checklist = listOf(
                "The client and server don't support a common SSL protocol version or cipher suite",
                "The server may be using obsolete SSLv3 or TLS 1.0/1.1"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "No overlapping cipher suites or TLS protocol versions between client and server."
        ),
        "ERR_SSL_FALLBACK_BEYOND_MINIMUM_VERSION" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "SSL fallback beyond minimum version",
            descriptionTemplate = "The server at %s attempted an insecure TLS protocol downgrade.",
            checklist = listOf(
                "An attacker or misconfigured server attempted to force an insecure downgrade",
                "The connection was terminated to protect you"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "TLS fallback SCSV detected downgrade below minimum permitted TLS version."
        ),
        "ERR_SSL_PINNED_KEY_NOT_IN_CERT_CHAIN" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "The server at %s presented a certificate that does not match its pinned public key.",
            checklist = listOf(
                "Strict key pinning detected a mismatched public key",
                "Attackers may be actively intercepting your connection"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Public key pinning (HPKP) check failed: Pinned fingerprint not found in chain."
        ),
        "ERR_CERT_SYMANTEC_LEGACY" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "The certificate for %s was issued by an untrusted legacy Symantec CA.",
            checklist = listOf(
                "Symantec root certificates were untrusted by major browser vendors",
                "The website operator must replace their certificate"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Distrusted legacy Symantec/GeoTrust/Thawte root certificate authority."
        ),
        "ERR_SSL_SERVER_CERT_BAD_FORMAT" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Your connection is not private",
            descriptionTemplate = "The SSL certificate sent by %s has an invalid encoding format.",
            checklist = listOf(
                "The server certificate bytes could not be parsed",
                "Do not proceed to this website"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Server TLS certificate payload failed DER/BER decoding."
        ),
        "ERR_SSL_UNRECOGNIZED_NAME_ALERT" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "SSL unrecognized name alert",
            descriptionTemplate = "The server at %s does not recognize the requested hostname (SNI).",
            checklist = listOf(
                "The server has not configured a certificate for this virtual host",
                "Check the website address"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Server sent TLS unrecognized_name fatal alert during SNI negotiation."
        ),
        "ERR_SSL_OBSOLETE_VERSION" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Obsolete security protocol",
            descriptionTemplate = "The website at %s uses an outdated version of TLS that is no longer secure.",
            checklist = listOf(
                "The site uses TLS 1.0 or TLS 1.1, which have known security flaws",
                "The website operator must upgrade to TLS 1.2 or TLS 1.3"
            ),
            isDanger = true,
            primaryButtonText = "Back to safety",
            primaryButtonAction = "back",
            secondaryButtonText = "Advanced",
            secondaryButtonAction = "advanced_ssl",
            technicalDetails = "Connection attempted over deprecated TLS 1.0 or 1.1 protocol."
        ),

        // ====================================================================
        // --- URL, CACHE, & REQUEST ERRORS (14) ---
        // ====================================================================
        "ERR_INVALID_URL" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.INVALID_URL,
            title = "The webpage is not valid",
            descriptionTemplate = "The web address <strong>%s</strong> is invalid.",
            checklist = listOf(
                "Check the web address for typos (such as ww.example.com instead of www.example.com)",
                "Ensure the URL starts with a valid scheme like https:// or http://",
                "Try searching for the page on your search engine"
            ),
            primaryButtonText = "Search Web",
            primaryButtonAction = "search",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Chromium network stack error net::ERR_INVALID_URL: The URL syntax is invalid or contains malformed characters."
        ),
        "ERR_DISALLOWED_URL_SCHEME" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.INVALID_URL,
            title = "URL scheme not allowed",
            descriptionTemplate = "The web address uses a scheme that is disallowed in this context.",
            checklist = listOf(
                "Onyx Browser restricts loading certain internal or restricted URI protocols",
                "Check the URL address prefix"
            ),
            primaryButtonText = "Search Web",
            primaryButtonAction = "search",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Navigation rejected: URL scheme is blocked by browser security policy."
        ),
        "ERR_UNKNOWN_URL_SCHEME" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.INVALID_URL,
            title = "Unknown URL scheme",
            descriptionTemplate = "Onyx Browser does not know how to handle the address protocol in %s.",
            checklist = listOf(
                "The link requires an external application that is not installed on your device",
                "Verify the link address"
            ),
            primaryButtonText = "Search Web",
            primaryButtonAction = "search",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "No registered protocol handler or application for this URL scheme."
        ),
        "ERR_INVALID_REDIRECT" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Invalid redirect",
            descriptionTemplate = "%s sent an invalid redirect address.",
            checklist = listOf(
                "The server attempted to redirect to an unparseable or forbidden destination",
                "Try reloading the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "HTTP redirect target URL failed validation or protocol verification."
        ),
        "ERR_CACHE_MISS" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Document expired",
            descriptionTemplate = "This webpage requires data that was previously submitted and is no longer in cache.",
            checklist = listOf(
                "To reload this page, data will need to be resubmitted",
                "Tap Reload to send the data again"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "HTTP request had ONLY_IF_CACHED policy set and entry was not present in cache."
        ),
        "ERR_CACHE_READ_FAILURE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Cache read failure",
            descriptionTemplate = "Failed to read data from the local browser disk cache.",
            checklist = listOf(
                "Disk storage may be full or temporarily unavailable",
                "Try reloading to fetch fresh data from the server"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Chromium disk cache failed while reading cached HTTP entry."
        ),
        "ERR_CACHE_WRITE_FAILURE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Cache write failure",
            descriptionTemplate = "Failed to write data into the browser disk cache.",
            checklist = listOf(
                "Check available storage space on your device",
                "Try reloading the webpage"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Chromium disk cache failed to write HTTP transaction data."
        ),
        "ERR_CACHE_OPERATION_NOT_SUPPORTED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Cache operation not supported",
            descriptionTemplate = "The requested cache operation is not supported.",
            checklist = listOf(
                "Try refreshing the webpage directly from the network",
                "Clear browser cache if problem persists"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Disk cache backend returned unsupported operation."
        ),
        "ERR_CACHE_LOCK_TIMEOUT" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Cache lock timeout",
            descriptionTemplate = "Timed out waiting for disk cache entry lock.",
            checklist = listOf(
                "Another browser thread is reading or writing this cache entry",
                "Try reloading the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Chromium sparse cache entry lock acquisition timed out."
        ),
        "ERR_CONTENT_DECODING_FAILED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Content decoding failed",
            descriptionTemplate = "The webpage at %s could not be decoded.",
            checklist = listOf(
                "The server sent compressed data (such as gzip, brotli, or zstd) that is corrupted",
                "Try reloading the webpage"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Decompression filter failed to unpack Content-Encoding stream."
        ),
        "ERR_CONTENT_LENGTH_MISMATCH" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Content length mismatch",
            descriptionTemplate = "The server at %s sent fewer bytes than declared in the Content-Length header.",
            checklist = listOf(
                "The download was cut off before all data could be received",
                "Try reloading the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Connection closed before declared Content-Length bytes were fully read."
        ),
        "ERR_INCOMPLETE_CHUNKED_ENCODING" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Incomplete chunked transfer",
            descriptionTemplate = "The server at %s closed the connection before completing the chunked transfer.",
            checklist = listOf(
                "The network stream terminated prematurely",
                "Try reloading the page in a few moments"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "HTTP chunked transfer terminated without receiving the terminal chunk."
        ),
        "ERR_REQUEST_RANGE_NOT_SATISFIABLE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Range request not satisfiable",
            descriptionTemplate = "The byte range requested from %s is invalid.",
            checklist = listOf(
                "The file on the server may have changed size or been modified",
                "Reload to fetch the complete resource from the beginning"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "HTTP 416 Range Not Satisfiable: Requested byte range out of bounds."
        ),
        "ERR_UPLOAD_FILE_CHANGED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Uploaded file changed",
            descriptionTemplate = "A file selected for upload was modified or deleted during upload.",
            checklist = listOf(
                "Reselect the file you wish to upload and try again",
                "Ensure the file is not being modified by another app"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Local file timestamp or length changed during HTTP POST/PUT stream."
        ),

        // ====================================================================
        // --- PROXY, TUNNEL, & FIREWALL ERRORS (9) ---
        // ====================================================================
        "ERR_PROXY_CONNECTION_FAILED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Proxy connection failed",
            descriptionTemplate = "Onyx Browser cannot connect to the configured proxy server.",
            checklist = listOf(
                "Check your proxy server address and port in Settings",
                "Verify that the proxy server is online and running",
                "Check your Wi-Fi proxy configuration"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "TCP connection to HTTP/HTTPS/SOCKS proxy server failed."
        ),
        "ERR_TUNNEL_CONNECTION_FAILED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Proxy tunnel failed",
            descriptionTemplate = "The proxy server refused to establish a secure tunnel to %s.",
            checklist = listOf(
                "The proxy rejected the HTTP CONNECT tunnel request",
                "Contact your network or proxy administrator"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "HTTP CONNECT proxy tunnel negotiation returned non-200 status code."
        ),
        "ERR_SOCKS_CONNECTION_FAILED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "SOCKS connection failed",
            descriptionTemplate = "Failed to establish a connection through the SOCKS proxy.",
            checklist = listOf(
                "Check your SOCKS proxy host and port",
                "Ensure the SOCKS proxy server is accepting connections"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "SOCKS protocol handshake negotiation failed."
        ),
        "ERR_SOCKS_CONNECTION_HOST_UNREACHABLE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Host unreachable via SOCKS",
            descriptionTemplate = "The SOCKS proxy reported that %s is unreachable.",
            checklist = listOf(
                "The remote host is unreachable from the proxy network",
                "Verify the destination address"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "SOCKS proxy returned host unreachable response code."
        ),
        "ERR_NO_SUPPORTED_PROXIES" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "No supported proxies",
            descriptionTemplate = "None of the configured proxy servers could be used.",
            checklist = listOf(
                "Check your proxy configuration rules or PAC script",
                "Check proxy protocol compatibility"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "PAC script or proxy resolution returned no supported proxy schemes."
        ),
        "ERR_MANDATORY_PROXY_CONFIGURATION_FAILED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Mandatory proxy failed",
            descriptionTemplate = "Mandatory proxy configuration could not be applied.",
            checklist = listOf(
                "Your network requires a mandatory proxy that is unreachable",
                "Contact your system or network administrator"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Enforced proxy configuration could not be initialized."
        ),
        "ERR_PROXY_AUTH_REQUESTED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Proxy authentication required",
            descriptionTemplate = "The proxy server requires authentication.",
            checklist = listOf(
                "Provide valid proxy login credentials",
                "Check your proxy authentication settings"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "HTTP 407 Proxy Authentication Required received."
        ),
        "ERR_PROXY_AUTH_UNSUPPORTED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Proxy authentication unsupported",
            descriptionTemplate = "The authentication scheme requested by the proxy server is unsupported.",
            checklist = listOf(
                "The proxy requested an authentication protocol not supported by the browser",
                "Use a standard Basic or Digest proxy"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Unsupported Proxy-Authenticate challenge mechanism."
        ),
        "ERR_PAC_SCRIPT_FAILED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Proxy auto-config script error",
            descriptionTemplate = "Failed to execute or download the proxy auto-config (PAC) script.",
            checklist = listOf(
                "Check the PAC URL in your network settings",
                "Check if the PAC script server is accessible"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "PAC execution engine failed to parse or execute FindProxyForURL script."
        ),

        // ====================================================================
        // --- HTTP, PROTOCOL & STREAMING ERRORS (11) ---
        // ====================================================================
        "ERR_INVALID_HTTP_RESPONSE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Invalid HTTP response",
            descriptionTemplate = "The server at %s sent a response that could not be parsed.",
            checklist = listOf(
                "The web server responded with non-HTTP data or corrupted bytes",
                "Try reloading the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "HTTP parser failed on status line or header parsing."
        ),
        "ERR_HTTP_RESPONSE_CODE_FAILURE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SERVER_DOWN,
            title = "HTTP response code failure",
            descriptionTemplate = "The server at %s returned an HTTP error response code.",
            checklist = listOf(
                "Check if the website is undergoing maintenance",
                "You can check for saved versions on the Wayback Machine"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            canCheckWayback = true,
            technicalDetails = "Upstream server returned an unhandled HTTP 4xx or 5xx status response."
        ),
        "ERR_H2_OR_QUIC_REQUIRED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "HTTP/2 or QUIC required",
            descriptionTemplate = "%s requires HTTP/2 or QUIC protocol support.",
            checklist = listOf(
                "The server rejected standard HTTP/1.1 connections",
                "Try reloading the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Server requires modern multiplexed transport (HTTP/2 or HTTP/3 QUIC)."
        ),
        "ERR_HTTP2_PROTOCOL_ERROR" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "HTTP/2 protocol error",
            descriptionTemplate = "The webpage at %s experienced an HTTP/2 protocol violation.",
            checklist = listOf(
                "The server sent an invalid HTTP/2 frame or stream state",
                "Try reloading the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "HTTP/2 session terminated due to stream error or PROTOCOL_ERROR frame."
        ),
        "ERR_HTTP2_STREAM_ERROR" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "HTTP/2 stream error",
            descriptionTemplate = "An error occurred on an HTTP/2 data stream from %s.",
            checklist = listOf(
                "The multiplexed stream was reset by the server",
                "Try reloading the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "RST_STREAM received on HTTP/2 multiplexed stream."
        ),
        "ERR_HTTP2_FRAME_SIZE_ERROR" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "HTTP/2 frame size error",
            descriptionTemplate = "The server sent an HTTP/2 frame that exceeded the maximum allowed size.",
            checklist = listOf(
                "The server sent an oversize binary frame",
                "Try reloading the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "HTTP/2 frame exceeded maximum negotiated frame size limit."
        ),
        "ERR_HTTP2_COMPRESSION_ERROR" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "HTTP/2 header compression error",
            descriptionTemplate = "Failed to decompress HPACK HTTP/2 headers from %s.",
            checklist = listOf(
                "HPACK header decompression failed",
                "Try reloading the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "HPACK decoding error: Dynamic table corruption or index out of range."
        ),
        "ERR_HTTP2_FLOW_CONTROL_ERROR" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "HTTP/2 flow control error",
            descriptionTemplate = "HTTP/2 stream flow control window exceeded on %s.",
            checklist = listOf(
                "The server violated flow control limits",
                "Try reloading the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Flow control window size violation in HTTP/2 session."
        ),
        "ERR_QUIC_PROTOCOL_ERROR" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "QUIC protocol error",
            descriptionTemplate = "An error occurred in the QUIC (HTTP/3) transport to %s.",
            checklist = listOf(
                "QUIC UDP transport packet error",
                "The browser will fallback to standard TCP"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "QUIC session terminated with transport or application protocol error."
        ),
        "ERR_QUIC_HANDSHAKE_FAILED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "QUIC handshake failed",
            descriptionTemplate = "The cryptographic QUIC handshake with %s failed.",
            checklist = listOf(
                "UDP packets may be blocked or throttled by your local network",
                "Try reloading to connect over standard TLS/TCP"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "QUIC crypto handshake failed or timed out over UDP transport."
        ),
        "ERR_ENCODING_CONVERSION_FAILED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Character encoding failed",
            descriptionTemplate = "The character encoding of %s could not be converted.",
            checklist = listOf(
                "The document contains characters incompatible with the declared charset",
                "Try reloading the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Character set transcoder failed to convert byte stream to Unicode."
        ),

        // ====================================================================
        // --- FILE & LOCAL SYSTEM ERRORS (11) ---
        // ====================================================================
        "ERR_FILE_NOT_FOUND" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.FILE_ERROR,
            title = "Your file couldn’t be accessed",
            descriptionTemplate = "The local file or document could not be located at this path.",
            checklist = listOf(
                "The file may have been moved, renamed, or deleted",
                "Verify file storage permissions for Onyx Browser"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "File descriptor lookup failed: File does not exist on local filesystem."
        ),
        "ERR_FILE_ACCESS_FAILED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.FILE_ERROR,
            title = "File access failed",
            descriptionTemplate = "Onyx Browser was unable to read the local file.",
            checklist = listOf(
                "Check storage permissions in Android Settings",
                "The file may be locked by another application"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "OS file read failed (EACCES or EPERM)."
        ),
        "ERR_FILE_TOO_LARGE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.FILE_ERROR,
            title = "File too large",
            descriptionTemplate = "The requested local file exceeds the maximum displayable size.",
            checklist = listOf(
                "The file is too large to display directly inside the browser",
                "Use a dedicated file viewer or document reader"
            ),
            primaryButtonText = "Go Home",
            primaryButtonAction = "home",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "File size exceeds WebView in-memory rendering threshold."
        ),
        "ERR_FILE_NO_SPACE" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.FILE_ERROR,
            title = "No space left on device",
            descriptionTemplate = "Your device is out of storage space.",
            checklist = listOf(
                "Free up space on your device by deleting unwanted files",
                "Empty your downloads folder or clear cache"
            ),
            primaryButtonText = "Open Downloads",
            primaryButtonAction = "downloads",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Filesystem write failed with ENOSPC (No space left on device)."
        ),
        "ERR_ACCESS_DENIED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.SECURITY,
            title = "Access denied",
            descriptionTemplate = "Access to the requested resource on %s was denied.",
            checklist = listOf(
                "You do not have permission to view this file or page",
                "Check access rights and authentication"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Operating system or server denied access permissions."
        ),
        "ERR_NOT_IMPLEMENTED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Not implemented",
            descriptionTemplate = "The requested feature or protocol is not implemented by the server.",
            checklist = listOf(
                "The server does not support the action requested",
                "Try reloading the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "HTTP 501 Not Implemented or unsupported protocol feature."
        ),
        "ERR_INSUFFICIENT_RESOURCES" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Insufficient system resources",
            descriptionTemplate = "Onyx Browser ran out of system resources while loading %s.",
            checklist = listOf(
                "Close other open tabs or background apps",
                "Reload the page to try again"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Operating system reported resource exhaustion (file descriptors or sockets)."
        ),
        "ERR_OUT_OF_MEMORY" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Out of memory",
            descriptionTemplate = "Onyx Browser ran out of memory while displaying this webpage.",
            checklist = listOf(
                "Close other browser tabs to free up device memory",
                "Restart the browser if the page continues to crash"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Chromium renderer or WebView process encountered OOM exception."
        ),
        "ERR_TIMED_OUT" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Operation timed out",
            descriptionTemplate = "The network operation to %s timed out.",
            checklist = listOf(
                "Check your internet connection",
                "The server may be overloaded; try refreshing in a moment"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Generic socket or I/O operation timed out."
        ),
        "ERR_ABORTED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "Request aborted",
            descriptionTemplate = "The loading of %s was aborted.",
            checklist = listOf(
                "The navigation was stopped or replaced by another request",
                "Tap Reload to load this page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Navigation request was cancelled or aborted before completion."
        ),
        "ERR_FAILED" to ErrorDefinition(
            category = SyntheticNavigationState.ErrorCategory.GENERIC,
            title = "This site can’t be reached",
            descriptionTemplate = "The webpage at %s might be temporarily down or moved permanently.",
            checklist = listOf(
                "Checking the connection",
                "Checking the proxy and the firewall",
                "Reload the page"
            ),
            primaryButtonText = "Reload",
            primaryButtonAction = "reload",
            secondaryButtonText = "Details",
            secondaryButtonAction = "details",
            technicalDetails = "Chromium net stack general failure (net::ERR_FAILED)."
        )
    )

    /**
     * Extracts canonical Chromium net error code from error description or legacy error code.
     */
    fun extractErrorCode(desc: String, errorCode: Int, isNetworkConnected: Boolean): String {
        val match = ERR_CODE_REGEX.find(desc)
        if (match != null) {
            val code = match.value
            if (CHROMIUM_NET_ERRORS.containsKey(code)) {
                return code
            }
        }

        val cleanDesc = desc.removePrefix("net::").trim().uppercase()
        if (CHROMIUM_NET_ERRORS.containsKey(cleanDesc)) {
            return cleanDesc
        }

        for (key in CHROMIUM_NET_ERRORS.keys) {
            if (desc.contains(key, ignoreCase = true)) {
                return key
            }
        }

        if (!isNetworkConnected) {
            return "ERR_INTERNET_DISCONNECTED"
        }

        return when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP -> "ERR_NAME_NOT_RESOLVED"
            WebViewClient.ERROR_CONNECT -> "ERR_CONNECTION_REFUSED"
            WebViewClient.ERROR_TIMEOUT -> "ERR_CONNECTION_TIMED_OUT"
            WebViewClient.ERROR_REDIRECT_LOOP -> "ERR_TOO_MANY_REDIRECTS"
            WebViewClient.ERROR_UNSUPPORTED_SCHEME -> "ERR_UNKNOWN_URL_SCHEME"
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "ERR_SSL_PROTOCOL_ERROR"
            WebViewClient.ERROR_BAD_URL -> "ERR_INVALID_URL"
            WebViewClient.ERROR_FILE_NOT_FOUND -> "ERR_FILE_NOT_FOUND"
            WebViewClient.ERROR_FILE -> "ERR_FILE_ACCESS_FAILED"
            WebViewClient.ERROR_PROXY_AUTHENTICATION -> "ERR_PROXY_AUTH_REQUESTED"
            WebViewClient.ERROR_AUTHENTICATION -> "ERR_ACCESS_DENIED"
            WebViewClient.ERROR_IO -> "ERR_CONNECTION_RESET"
            WebViewClient.ERROR_TOO_MANY_REQUESTS -> "ERR_INSUFFICIENT_RESOURCES"
            else -> "ERR_FAILED"
        }
    }

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

        val errCode = extractErrorCode(desc, errorCode, isNetworkConnected)
        val def = CHROMIUM_NET_ERRORS[errCode] ?: CHROMIUM_NET_ERRORS["ERR_FAILED"]!!

        val title = def.title
        val displayTarget = if (failingUrl.length > 60) failingUrl.take(57) + "…" else (if (domain.isNotBlank()) domain else failingUrl)
        val descText = if (def.descriptionTemplate.contains("%s")) {
            try {
                String.format(def.descriptionTemplate, displayTarget)
            } catch (_: Exception) {
                def.descriptionTemplate.replace("%s", displayTarget)
            }
        } else {
            def.descriptionTemplate
        }

        val formattedChecklist = def.checklist.map { item ->
            if (item.contains("%s")) {
                try {
                    String.format(item, displayTarget)
                } catch (_: Exception) {
                    item.replace("%s", displayTarget)
                }
            } else {
                item
            }
        }

        val techDetails = def.technicalDetails?.let {
            if (it.contains("%s")) {
                try {
                    String.format(it, displayTarget)
                } catch (_: Exception) {
                    it.replace("%s", displayTarget)
                }
            } else {
                it
            }
        } ?: "Chromium network stack error net::$errCode."

        val formattedErrorCode = if (desc.startsWith("net::")) desc else "net::$errCode"

        return when (def.category) {
            SyntheticNavigationState.ErrorCategory.OFFLINE -> SyntheticNavigationState.Offline(
                failingUrl = failingUrl,
                errorCode = formattedErrorCode,
                title = title,
                description = descText,
                checklist = formattedChecklist,
                technicalDetails = techDetails
            )
            SyntheticNavigationState.ErrorCategory.SECURITY -> SyntheticNavigationState.Security(
                failingUrl = failingUrl,
                errorCode = formattedErrorCode,
                title = title,
                description = descText,
                technicalDetails = techDetails,
                isHstsEnforced = def.isHstsEnforced
            )
            SyntheticNavigationState.ErrorCategory.SHIELDS_BLOCKED -> SyntheticNavigationState.ShieldsBlocked(
                failingUrl = failingUrl,
                blockedDomain = domain,
                title = title,
                description = descText,
                technicalDetails = techDetails
            )
            SyntheticNavigationState.ErrorCategory.FILE_ERROR -> SyntheticNavigationState.FileError(
                failingUrl = failingUrl,
                errorCode = formattedErrorCode,
                title = title,
                description = descText,
                checklist = formattedChecklist,
                technicalDetails = techDetails
            )
            SyntheticNavigationState.ErrorCategory.INVALID_URL -> SyntheticNavigationState.InvalidUrl(
                failingUrl = failingUrl,
                errorCode = formattedErrorCode,
                title = title,
                description = descText,
                checklist = formattedChecklist,
                technicalDetails = techDetails
            )
            else -> SyntheticNavigationState.Generic(
                failingUrl = failingUrl,
                errorCode = formattedErrorCode,
                title = title,
                description = descText,
                checklist = formattedChecklist,
                technicalDetails = techDetails,
                category = def.category,
                isDanger = def.isDanger,
                primaryButtonText = def.primaryButtonText,
                primaryButtonAction = def.primaryButtonAction,
                secondaryButtonText = def.secondaryButtonText,
                secondaryButtonAction = def.secondaryButtonAction,
                canCheckWayback = def.canCheckWayback,
                isHstsEnforced = def.isHstsEnforced
            )
        }
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
        val code = when (sslError?.primaryError) {
            SslError.SSL_EXPIRED -> "ERR_CERT_DATE_INVALID"
            SslError.SSL_IDMISMATCH -> "ERR_CERT_COMMON_NAME_INVALID"
            SslError.SSL_UNTRUSTED -> "ERR_CERT_AUTHORITY_INVALID"
            SslError.SSL_NOTYETVALID -> "ERR_CERT_DATE_INVALID"
            SslError.SSL_DATE_INVALID -> "ERR_CERT_DATE_INVALID"
            else -> "ERR_CERT_INVALID"
        }

        val def = CHROMIUM_NET_ERRORS[code]
        val descText = def?.descriptionTemplate?.replace("%s", domain)
            ?: "Attackers might be trying to steal your information from $domain (for example, passwords, messages, or credit cards)."
        val tech = def?.technicalDetails?.replace("%s", domain)
            ?: "The security certificate could not be verified by Android's cryptographic trust manager."

        return SyntheticNavigationState.Security(
            failingUrl = failingUrl,
            errorCode = "net::$code",
            title = def?.title ?: "Your connection is not private",
            description = descText,
            technicalDetails = tech,
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
