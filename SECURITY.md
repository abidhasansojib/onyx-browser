# Security Policy

Onyx Browser is built with defense-in-depth privacy and security defaults. This document outlines the security architecture and how to report vulnerabilities.

---

## 1. Security Architecture

### SQLCipher AES-256 Database Encryption
- All Room database tables (`AppDatabase` — tabs, bookmarks, history, shortcuts, downloads, userscripts, and site permissions) are encrypted at rest with **SQLCipher AES-256**.
- Master encryption keys are backed by the Android KeyStore (`SecureDatabaseKeyProvider`).

### Chromium Multi-Profile Isolation
- Normal and Incognito sessions are isolated at the engine level via AndroidX WebKit `ProfileStore` (`WebViewCompat.setProfile(webView, "incognito")`).
- Incognito tabs use an ephemeral cookie jar, WebStorage, IndexedDB, and HTTP cache partition purged when all incognito tabs close, with tab state held strictly in memory (`incognitoStateBundles`).
- Private sessions enforce `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` on the omnibox and WebView input connections.

### WebAuthn & Passkey Origin Verification
- `PasskeyWebAuthnBridge` marshals `@JavascriptInterface` calls onto the main UI thread before reading `webView.url`, validates `rpId` domain suffixes and subframe `crossOrigin` bounds, and passes verified web `origin` + `clientDataHash` to Android `CredentialManager` (`CREDENTIAL_MANAGER_SET_ORIGIN`).
- Canonical `clientDataJSON` matching `clientDataHash` is verified and injected so Relying Party SHA-256 signature checks succeed.

### Network Interception, Bridges & Userscript Sandbox
- Network requests pass through `RequestInterceptor` (`HttpsUpgradeInterceptor`, `AuthBypassInterceptor`, `AdBlockRequestInterceptor`, `PrivacyUrlSanitizer`) backed by `adblock-rust` (`libadblock_bridge.so`).
- `$csp` filter directives are injected at document start via `<meta http-equiv="Content-Security-Policy">` with `worker-src` scoped to `blob:`.
- Asynchronous bridges (`TouchBridge`, `FormBridge`, `BlobBridge`) use `WebViewCompat.addWebMessageListener` where supported rather than exposing reflective Java objects on `window`.
- Userscripts support `@inject-into` and `@sandbox` isolation (running isolated scripts inside a `Proxy`-backed sandbox `window` separated from `unsafeWindow`) and enforce a per-script, per-domain first-use user prompt before `GM_xmlhttpRequest` network calls.

### Zero Telemetry
- Onyx Browser includes no analytics SDKs, crash-reporting telemetry, or background tracking. Network requests occur only for user navigation, configured search suggestions, DNS-over-HTTPS, and optional update checks (app, filter lists, userscripts).

---

## 2. Supported Versions

Security fixes apply to the latest release on `main`.

| Version | Supported |
| :--- | :--- |
| Latest Release (`main`) | Yes |
| Older Releases | No |

---

## 3. Reporting a Vulnerability

If you discover a security or privacy vulnerability (such as a bridge/sandbox bypass, origin spoofing, cross-profile data leak, or CSP bypass):

1. **Do not open a public GitHub issue.**
2. **Use GitHub Private Vulnerability Reporting** via the repository's [**Security Advisories**](https://github.com/abidhasansojib/onyx-browser/security/advisories/new) page.
3. Include the affected component (e.g., `PasskeyWebAuthnBridge.kt`, `ExternalNavigationHandler.kt`, `UserScriptBridge.kt`), reproduction steps or proof-of-concept, and impact assessment.
