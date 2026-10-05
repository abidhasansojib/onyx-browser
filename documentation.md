# Android WebView Engineering Guide — Onyx Browser

This document provides a technical guide on Android WebView integration, architecture, security policies, navigation, and lifecycle management within Onyx Browser. It consolidates guidance from the official [Android WebView Guide](https://developer.android.com/develop/ui/views/layout/webapps/webview) adapted to the requirements of a standalone privacy browser.

---

## 1. Overview & WebView Fundamentals in Onyx

### 1.1 Full Browser vs. Embedded WebView
In simple utility apps, `WebView` typically displays static content (e.g., terms of service or help pages). In Onyx Browser, `OnyxWebView` functions as a full-fledged browser rendering engine:
- Manages multi-tab navigation, address bar coordination, tab switching, and window management.
- Coordinates between native Android views (XML ViewBinding) and the underlying Chromium Blink engine.
- Implements strict security boundaries, tracking protection, and isolated multi-profile sessions.

### 1.2 AndroidX WebKit Integration (`androidx.webkit:webkit`)
Onyx integrates the modern Jetpack WebKit library (`androidx.webkit:webkit:1.12.0+`):
- Provides feature-checked compat wrappers (`WebViewFeature.isFeatureSupported(...)`).
- Enables modern Chromium features across varied Android OS versions:
  - **Multi-Profile Partitioning**: `ProfileStore` and `WebViewCompat.setProfile` for true incognito isolation.
  - **Document-Start Script Injection**: `WebViewCompat.addDocumentStartJavaScript` to inject polyfills and security guards before any document scripts run.
  - **Client Hint & Header Manipulation**: `WebSettingsCompat.setRequestedWithHeaderOriginAllowList`.
  - **Safe Browsing & Error Handling**: `WebViewCompat.startSafeBrowsing`.

---

## 2. WebSettings & Hardened Configuration Standards

All WebViews in Onyx Browser are configured through `OnyxWebView.configureSettings()`.

### 2.1 Core Settings
```kotlin
with(settings) {
    javaScriptEnabled = true
    domStorageEnabled = true
    databaseEnabled = true
    setSupportMultipleWindows(true)
    javaScriptCanOpenWindowsAutomatically = false

    // Hardware acceleration and viewport scaling
    useWideViewPort = true
    loadWithOverviewMode = true
    setSupportZoom(true)
    builtInZoomControls = true
    displayZoomControls = false

    // Cache policy
    cacheMode = WebSettings.LOAD_DEFAULT

    // Filesystem security: local assets only, file access from URLs strictly blocked
    allowFileAccess = true
    allowContentAccess = true
    allowFileAccessFromFileURLs = false
    allowUniversalAccessFromFileURLs = false

    // Media streaming (audio/video playback)
    mediaPlaybackRequiresUserGesture = false
}
```

### 2.2 Suppressing `X-Requested-With` Header
By default, Android WebView injects the application ID header:
```http
X-Requested-With: com.onyx.browser
```
This identifies the app as an embedded WebView to backend anti-bot systems (Cloudflare, Arkose Labs, Meta Risk Engine). Onyx strips this globally:
```kotlin
if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
    WebSettingsCompat.setRequestedWithHeaderOriginAllowList(this, emptySet())
}
```

### 2.3 Hardware Rasterization & 120Hz Displays
To ensure zero stutter and native 120Hz frame rates:
- Layer type set to `View.LAYER_TYPE_NONE` (relies directly on window hardware acceleration).
- `offscreenPreRaster = true` on Android M+ (API 23+) to rasterize offscreen DOM components ahead of scroll movements.
- Vertical/horizontal fading edges disabled (`isVerticalFadingEdgeEnabled = false`) to reduce composition overhead.

---

## 3. Navigation Architecture & Lifecycle

### 3.1 `shouldOverrideUrlLoading` vs. `shouldInterceptRequest`

| Callback | Execution Point | Primary Responsibility in Onyx |
| :--- | :--- | :--- |
| `shouldOverrideUrlLoading` | User clicks a link or page redirects (top-level navigation) | External app scheme dispatch (`tg://`, `whatsapp://`, `intent:`), HTTPS upgrades, tracking param stripping, AMP canonical resolution. |
| `shouldInterceptRequest` | Network request for any resource (HTML, JS, CSS, images, XHR/Fetch) | Network-level ad & tracker blocking via `AdBlockEngine` (Rust JNI), local archive interception, surrogate script responses, and CAPTCHA/OAuth bypass. |

### 3.2 Loading Pages: `loadUrl` with Custom Headers
- **`loadUrl(url, headers)`**: Primary navigation method used for all direct navigations with custom `DNT: 1` (Do Not Track) header attachment and `javascript:` execution.
- **`loadDataWithBaseURL`**: Used for synthetic content (error pages, HTML data) loaded into the WebView without polluting session history.
- **Error Page Isolation**: When loading synthetic error pages (network down, SSL failure), Onyx uses `loadDataWithBaseURL` with `https://onyx.browser/` base URI — a non-routable origin that prevents CSP leaks, avoids polluting session history, and prevents local asset path exposure. The original failing URL is passed as the history URL parameter so back navigation works correctly.

### 3.3 History Stack Navigation & State Preservation
- **Back/Forward Stack**: Controlled via `webView.canGoBack()` and `webView.goBack()`.
- **State Serialization**:
  ```kotlin
  // Saving tab state across app backgrounding
  val bundle = Bundle()
  webView.saveState(bundle)

  // Restoring saved tab
  webView.restoreState(bundle)
  ```
- **Synthetic Navigation Guards**: When an error page is displayed, `OnyxWebView.reload()` re-requests the actual failing URL (`lastFailingUrl`) instead of reloading the synthetic error document.

---

## 4. Window Management & Popup Protection

### 4.1 `setSupportMultipleWindows(true)` Architecture
To support modern web applications (OAuth popups, payment processors, multi-window apps):
1. `settings.setSupportMultipleWindows(true)` is enabled.
2. `settings.javaScriptCanOpenWindowsAutomatically = false` prevents automatic popups from executing without routing through native callbacks.
3. Link clicks with `target="_blank"` or JavaScript `window.open()` trigger `OnyxWebChromeClient.onCreateWindow`.

### 4.2 User Gesture Gate Enforcement
To prevent malicious ad scripts from spamming background popup tabs:
```kotlin
override fun onCreateWindow(
    view: WebView?,
    isDialog: Boolean,
    isUserGesture: Boolean,
    resultMsg: Message?
): Boolean {
    // Require a recent human touch/click gesture, with a 1500ms touch window
    // fallback for OAuth/payment async flows where Blink clears isUserGesture
    val recentTouch = (view as? OnyxWebView)?.isWithinRecentTouchWindow() ?: false
    if ((!isUserGesture && !recentTouch) || resultMsg == null) {
        return false // Reject automatic ad popups
    }

    // Allocate popup tab in TabManager and pass transport message to the new WebView
    val newTab = tabManager.createNewTab(url = "", isIncognito = isIncognito, parentId = parentTab?.id)
    val newWebView = tabManager.getOrCreateWebView(newTab)
    newWebView.isPopupPendingDisplay = true
    newWebView.isPopupTab = true
    val transport = resultMsg.obj as? WebView.WebViewTransport
    transport?.webView = newWebView
    resultMsg.sendToTarget()
    return true
}
```

---

## 5. JavaScript Bridge & IPC Security

### 5.1 `@JavascriptInterface` Security Rules
Exposing native Java/Kotlin methods to JavaScript via `addJavascriptInterface` introduces security considerations:
1. **Explicit Annotation**: Only methods annotated with `@JavascriptInterface` are accessible to JavaScript.
2. **Thread Safety**: JavaScript interface methods execute on a background thread (`JavaBridge`), not the Android Main thread. UI interactions must post to `Looper.getMainLooper()` or dispatch through coroutines (`Dispatchers.Main`).
3. **Parameter Validation**: All arguments passed from JavaScript must be strictly sanitized and type-checked before processing.

### 5.2 Bridge Property Masking on Sensitive Domains
Security challenge providers (Arkose Labs FunCaptcha, Cloudflare Turnstile, Meta Risk Engine) inspect `window` properties for presence of test automation or WebView wrapper bridges.
- `ChromeEnvironmentBridge.SCRIPT` runs at document-start:
  - Deletes and masks `OnyxShieldBridge`, `OnyxMediaBridge`, `OnyxTouchBridge`, etc. on authentication, CAPTCHA, and Meta domains.
  - Restores `window.chrome = {}` and `navigator.userAgentData` to match standalone Chromium.
  - Enforces `navigator.webdriver = false`.

### 5.3 Asynchronous Script Evaluation
All script injections from the host app use `evaluateJavascript`:
```kotlin
webView.evaluateJavascript(script) { result ->
    // Non-blocking callback on Main thread
}
```
This avoids UI thread blocking and prevents deadlocks associated with legacy synchronous `loadUrl("javascript:...")` patterns.

---

## 6. Memory Management, Resource Cleanup & Multi-Profile

### 6.1 Preventing Activity Leaks & Memory Leaks
Because `WebView` retains native Chromium engine pointers and GPU memory:
1. **Explicit View Hierarchy Removal**: Before destroying a tab, it must be removed from its parent container:
   ```kotlin
   (webView.parent as? ViewGroup)?.removeView(webView)
   ```
2. **Audio/Video Pausing & DOM Cleanup**: All active `<video>` and `<audio>` tags are paused and source pointers cleared before teardown.
3. **Safe Teardown (`destroySafely`)**:
   ```kotlin
   fun destroySafely() {
       stopLoading()
       clearHistory()
       (parent as? ViewGroup)?.removeView(this)
       removeAllViews()
       destroy()
       coroutineScope.cancel()
   }
   ```

### 6.2 Multi-Profile Incognito Isolation
Onyx implements total data separation between Normal and Incognito modes using AndroidX `ProfileStore`:
- **Incognito Partition**:
  ```kotlin
  val store = ProfileStore.getInstance()
  val incognitoProfile = store.getOrCreateProfile("incognito")
  WebViewCompat.setProfile(webView, "incognito")
  ```
- **Profile-Scoped Storage**:
  - `incognitoProfile.cookieManager` maintains a separate, ephemeral cookie jar.
  - `incognitoProfile.webStorage` holds isolated `localStorage` and `IndexedDB`.
  - Cache set to `WebSettings.LOAD_NO_CACHE`.
- **Complete Purge on Session Exit**:
  When all incognito tabs are closed or on app launch, `TabManager.purgeIncognitoProfile()`:
  1. Purges all incognito cookies (`removeAllCookies`).
  2. Deletes all origin storage (`deleteAllData`).
  3. Deletes the incognito profile from `ProfileStore`.
