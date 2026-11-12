# Security Policy

Onyx Browser is engineered with defense-in-depth privacy and security defaults. This document outlines the browser's security architecture and how to report vulnerabilities responsibly.

---

## 1. Security Architecture Overview

### On-Device Database Encryption (SQLCipher AES-256)
- All persistent Room database tables (`OnyxDatabase` — tabs, bookmarks, browsing history, homepage shortcuts, download metadata, and per-site permissions in `SitePermissionEntity`) are encrypted at rest using **SQLCipher AES-256**.
- Database encryption keys are generated and protected by the hardware-backed **Android KeyStore** (`KeyStoreManager`) and `EncryptedSharedPreferences`.

### Chromium Multi-Profile Isolation
- Normal and Incognito browsing sessions are isolated at the engine level using AndroidX WebKit `ProfileStore` (`ProfileStore.getOrCreateProfile("incognito")` and `WebViewCompat.setProfile()`).
- Incognito tabs use an isolated cookie jar, WebStorage, IndexedDB, and HTTP cache partition that is purged immediately when all incognito tabs are closed (`TabManager.purgeIncognitoProfile()`), with in-memory-only bundle hibernation (`incognitoStateBundles`) so private tab state never touches disk.
- Private sessions enforce `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` across both the Omnibox and in-page WebView input connections to prevent keyboard personalized learning and clipboard history retention.

### WebAuthn & Passkey Origin Verification
- `PasskeyWebAuthnBridge` marshals all `@JavascriptInterface` calls onto the Main UI thread before reading `webView.url`, validates `rpId` domain suffixes and subframe `crossOrigin` restrictions, and passes verified web `origin` + `clientDataHash` to Android `CredentialManager` (`CREDENTIAL_MANAGER_SET_ORIGIN`), never falling back to `origin = null` native app credentials.
- Canonical `clientDataJSON` matching `clientDataHash` is verified and injected so Relying Party SHA-256 signature checks succeed against hardware/provider authenticators.

### Network Interception, CSP Injection & Bridge Hardening
- Network requests traverse the `RequestInterceptor` pipeline (`HttpsUpgradeInterceptor`, `AuthBypassInterceptor`, `AdBlockRequestInterceptor`, and `PrivacyUrlSanitizer`) backed by Brave's memory-safe Rust engine (`adblock-rust` / `libadblock_bridge.so`).
- `$csp` filter directives are injected at document start via `<meta http-equiv="Content-Security-Policy">` after stripping `<meta>`-invalid directives and scoping `worker-src` to `blob:`.
- Modern asynchronous bridges (`OnyxTouchBridge`, `OnyxFormBridge`, `OnyxBlobBridge`) use `WebViewCompat.addWebMessageListener` where supported rather than exposing reflective Java objects on `window`.
- UserScripts execute inside isolated IIFEs with capability tokens and enforce explicit `@connect` domain review before `GM_xmlhttpRequest` cross-origin calls.

### Zero Telemetry
- Onyx Browser contains no analytics SDKs, crash-reporting telemetry, or background phoning home. Network connections are made exclusively for user navigation, configured search suggestions, DNS-over-HTTPS, and filter list updates.

---

## 2. Supported Versions

Security fixes are applied to the latest release on the `main` branch.

| Version | Supported |
| :--- | :--- |
| Latest Release (`main`) | Yes |
| Older Releases | No (upgrade to latest release) |

---

## 3. Reporting a Vulnerability

If you discover a security or privacy vulnerability in Onyx Browser (such as a sandbox/bridge bypass, credential origin spoofing, cross-profile data leak, or filter/CSP bypass):

1. **Do NOT open a public GitHub issue** for unpatched security vulnerabilities.
2. **Use GitHub Private Vulnerability Reporting**:
   - Navigate to the repository's [**Security Advisories**](https://github.com/abidhasansojib/onyx-browser/security/advisories/new) page and click **"Report a vulnerability"**.
3. **Include the following details in your report**:
   - Affected component or file (e.g., `PasskeyWebAuthnBridge.kt`, `ExternalNavigationHandler.kt`, `OnyxUserScriptBridge.kt`).
   - Step-by-step reproduction instructions or a minimal proof-of-concept (HTML/JS snippet or URL).
   - Impact assessment (what data or boundary is affected).

We review all private vulnerability reports promptly and will coordinate a fix and advisory disclosure with credit to the reporter.
