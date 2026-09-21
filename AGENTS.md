# AGENTS.md - Onyx Browser Project Operational Guide & Architecture

## 1. Project Summary & Purpose
**Onyx Browser** (`com.onyx.browser`) is a production-grade, ultra-lightweight, high-performance Android web browser engineered from scratch for modern Android devices (Min SDK 26, Target/Compile SDK 35).

### Core Goals & Tech Stack
- **Language**: Kotlin 2.x (Android App) & Rust (Ad-blocking Engine via JNI).
- **UI Paradigm**: Classic Android XML Views with ViewBinding. Strictly NO Jetpack Compose to preserve instantaneous cold starts, minimize memory consumption, and ensure optimal hardware-accelerated WebView compositing.
- **Native Ad-Blocker**: Brave's `adblock-rust` (linked via submodule and symlinked) compiled to `.so` shared libraries (`libadblock_bridge.so`) across target Android ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`) via `cargo-ndk`.
- **Adblock Lists Integration**: Official Brave `adblock-lists` repository integrated via submodule and symlink, with automated aggregation into mobile assets.
- **Continuous Upstream Synchronization**: Automated GitHub Actions cron workflow (`.github/workflows/sync_upstream.yml`) running every 6 hours to pull upstream changes, refresh filter lists, and rebuild APKs.
- **Database**: Room Database for history, bookmarks, tabs, and downloads persistence.
- **Preferences**: AndroidX Jetpack Preferences with AMOLED Pure Black `#000000`, Light, and Material You dynamic color themes.
- **CI/CD**: Fully autonomous GitHub Actions workflow to cross-compile Rust NDK shared libraries and build Android release/debug APKs.

---

## 2. Architecture & Directory Layout
```text
onyx-browser/
├── .github/
│   └── workflows/
│       ├── build.yml             # Native Rust NDK & Gradle build pipeline
│       └── sync_upstream.yml     # Automated upstream Brave sync pipeline
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── assets/
│   │       │   ├── easylist_rules.txt  # Bundled compiled Brave adblock rules
│   │       │   └── brave-lists/        # Symlink -> external/adblock-lists/brave-lists
│   │       ├── java/com/onyx/browser/
│   │       │   ├── data/ (Room DB & Preferences)
│   │       │   ├── nativebridge/ (AdBlockEngine.kt JNI bridge)
│   │       │   ├── ui/ (Classic XML ViewBinding controllers)
│   │       │   └── web/ (WebViewClient, ChromeClient, DownloadHandler)
│   │       ├── res/
│   │       ├── jniLibs/ (arm64-v8a, armeabi-v7a, x86_64)
│   │       └── AndroidManifest.xml
│   └── build.gradle.kts
├── external/
│   ├── adblock-rust/             # Submodule: https://github.com/brave/adblock-rust.git
│   └── adblock-lists/            # Submodule: https://github.com/brave/adblock-lists.git
├── rust_engine/
│   ├── adblock-rust/             # Symlink -> ../external/adblock-rust
│   ├── Cargo.toml
│   └── src/lib.rs
├── scripts/
│   ├── fetch_icons.sh            # Automated Lucide icon acquisition
│   ├── svg_to_vector.py          # SVG -> Android Vector Drawable converter
│   ├── sync_upstream.sh          # Upstream submodule & symlink sync
│   └── update_filter_lists.sh    # Bundles official Brave lists into assets
├── .gitmodules
├── build.gradle.kts
├── settings.gradle.kts
└── AGENTS.md
```

---

## 3. Established Project Rules & Coding Standards

### Environment & Capability Constraints
- **Local Building Strictly Forbidden**: The local host environment is NOT permitted or capable of building Android Gradle projects, compiling NDK/Rust binaries, or running heavy compilation pipelines.
- **Remote CI/CD Execution**: All compilation of Rust native libraries (`libadblock_bridge.so`) and Android APK builds (`./gradlew assembleDebug` / `assembleRelease`) MUST be executed remotely via GitHub Actions.
- **Code Quality**:
  - Null-safe, idiomatic Kotlin code with lifecycle-aware ViewBinding binding inflation and clearing.
  - Strict WebView memory leak prevention: Detach WebViews from parent layout, destroy properly in `onDestroyView()` / tab closure, remove callbacks.
  - Coroutines with `Dispatchers.IO` for disk and database access, `Dispatchers.Main` for UI updates.
  - Rust memory safety: Clean JNI boundary error handling with `catch_unwind` and fallback to unblocked if any JNI error occurs.

---

## 4. Active Tasks & Milestones
- [x] Create project structure and `AGENTS.md`.
- [x] Implement Rust native crate (`rust_engine/Cargo.toml`, `rust_engine/src/lib.rs`).
- [x] Configure root and app `build.gradle.kts`, `settings.gradle.kts`, and Gradle wrapper.
- [x] Implement Room Database, Entities, and DAOs (`data/local`, `data/model`).
- [x] Implement SharedPreferences manager (`BrowserPreferences`).
- [x] Implement Native JNI bridge (`nativebridge/AdBlockEngine.kt`).
- [x] Implement WebView client and chrome client with adblocking & cosmetic CSS injection (`web/`).
- [x] Implement UI: Top toolbar, Home body, Tab switcher, Quick menu, Downloads dialog, Settings, History, Bookmarks.
- [x] Provide high-tech vector drawables and AMOLED themes via automated Lucide icon acquisition.
- [x] Implement GitHub Actions CI/CD workflow (`.github/workflows/build.yml`).
- [x] Initialize Git repo, push to GitHub (`abidhasansojib/onyx-browser`), trigger workflow, and verify successful build.
- [x] Monitor remote CI/CD execution and verify artifact generation.
- [x] Implement monochrome Chrome silhouette adaptive app icon matching user reference image on AMOLED Pure Black `#000000` with Android 13+ themed icon support.
- [x] Configure complete runtime permissions: Notifications (Android 13+ download alerts/progress), Storage (Android 8-9 download saves), Microphone (Voice search & WebRTC), Geolocation (Web maps/weather), and Camera (Web uploads/calls).
- [x] Fix runtime search and restart crash: Remove `panic = "abort"` in Rust NDK release profile, harden JNI native bridge (`checkUrl`, `getCosmeticResources`), and fail open safely in `OnyxWebViewClient`.
- [x] Resolve status bar collision: Apply WindowInsetsCompat systemBars top padding to toolbar and navigationBars bottom padding to containers across all activities.
- [x] Transform Tab Switcher into a full-page DialogFragment with reactive StateFlow collection and swipe-to-dismiss gesture support.
- [x] Fix homepage quick actions: Make History, Downloads, Bookmarks, and Incognito buttons clickable with ripple feedback and system downloads folder access.
- [x] Fetch official brand search engine SVG vectors (Brave, Google, DuckDuckGo, Bing, Startpage, Yahoo) from simple-icons CDN and implement compact dropdown `SearchEnginePopupMenu`.
- [x] Verified full end-to-end GitHub Actions build run (#35543759711), packaging native 64/32-bit Rust `libadblock_bridge.so` libraries, assets, and producing verified `app-debug.apk` and `app-release.apk` artifacts.
- [x] Fix app launch crash / instant closure: Add `vectorDrawables.useSupportLibrary = true`, enable `setCompatVectorFromResourcesEnabled(true)`, add `colorControlNormal` and `colorControlHighlight` attributes to Material3 themes, sanitize all 22 vector drawables from dynamic theme references to rock-solid `#FFFFFFFF`, and migrate all layouts to `AppCompatImageButton`/`AppCompatImageView` with `app:srcCompat`.
- [x] Fix PathParser IllegalArgumentException on `ic_engine_brave.xml` & `ic_engine_bing.xml`: Implement strict SVG path tokenizer and normalizer to unpack concatenated flags (e.g. `0 01-4.293` -> `0 0 1 -4.293`) across all vector drawables.
- [x] Fix ThreadPoolForeg crash on search & startup: Remove `view.url` access from `shouldInterceptRequest` (which runs on Chromium's background thread), implement thread-safe `@Volatile currentPageUrl` tracking with Referer header inspection, wrap interception in fail-open try-catch, implement robust `shouldOverrideUrlLoading` for external intent schemes, and track `currentDisplayedTabId` in `MainActivity` to eliminate tab churn.
- [x] Via Browser Architectural Logic & Algorithms:
  - Smart Search & URL routing: Regex-based domain, IPv4, localhost, and custom scheme parser.
  - Multi-window & popup window lifecycle: Implemented `onCreateWindow` (spawns new tab with `WebViewTransport`) and `onCloseWindow` in `OnyxWebChromeClient`.
  - Battery & CPU lifecycle throttling: Background tabs and activities invoke `webView.onPause()`; active tab invokes `webView.onResume()`.
  - Hardware-accelerated rendering & privacy: `LAYER_TYPE_HARDWARE` enabled, third-party cookies blocked, deprecated render priority cleaned.
- [x] UI Refinement & Modernization Phase (Completed & Built):
  - [x] Verified full end-to-end GitHub Actions build run (#35573934472), packaging native Rust `libadblock_bridge.so`, offline `eruda.min.js` assets, context-aware menu, Find in Page bar, and producing verified [`app-release.apk`](file:///root/onyx-browser/release/app-release.apk) (14MB) and [`app-debug.apk`](file:///root/onyx-browser/release/app-debug.apk) (16MB).
  - [x] Remove diamond gemstone emblem from homepage logo and vector drawable (`ic_logo_onyx.xml`), center the clean geometric ONYX wordmark, and update subtitle to "Fast and Private" (`tagline_fast_and_private`).
  - [x] Dedicated Full-Page Search Mode & Webpage Action Card:
    - Tap search bar opens full-page search overlay with back button and clean input ready for fresh search.
    - Current Webpage Card displayed beneath search bar with Favicon, Title, URL, and 3 quick action buttons: Share (`ic_share`), Copy (`ic_copy`), and Edit (`ic_edit` populates the clean search bar with current URL for customization).
  - [x] Universal Real-time Search Suggestions Engine (`SearchSuggestionRepository` & `SuggestionsAdapter`):
    - Full OpenSearch and JSON API support across all 6 search engines: Brave, Google, DuckDuckGo, Bing, Startpage, Yahoo.
    - Blended local browsing history suggestions (`HistoryDao.searchHistory`).
    - Diagonal insert arrow button (`ic_insert_query`) on each suggestion to append/customize query without immediate submission.
  - [x] Fixed "Set as Default Browser":
    - Added `<category android:name="android.intent.category.APP_BROWSER" />` and `WEB_SEARCH` action in `AndroidManifest.xml` to qualify for system browser role.
    - Replaced unhandled `startActivity` with `registerForActivityResult(StartActivityForResult())` on `RoleManager.createRequestRoleIntent(ROLE_BROWSER)` and robust multi-step OEM fallback intents (`ACTION_MANAGE_DEFAULT_APPS_SETTINGS`, application details, and general settings).
  - [x] Tab Switcher Top Bar Redesign:
    - Replaced top-left cross button with Search button (`ic_search`) to access search mode directly from the tab menu.
    - Centered the "Normal" and "Incognito" segmented buttons properly in the middle top.
  - [x] Clear Browsing Data & Tab Switcher Brush Action:
    - Replaced bottom-left bin icon with Brush icon (`ic_brush.xml`).
    - Implemented `ClearBrowsingDataDialog` with Chrome-style time range selection: Last 15 mins, Last hour, Last 24 hours, Last 7 days, Last 4 weeks, All time.
    - Dynamic preview calculating browsing history site count with domain examples, open tab count with tab title examples that will be closed, and cookies/cache warning.
    - Integrated Room DB time-range history deletion, tab closure (`closeTabsCreatedSince`), and WebView cookie/cache/storage clearing.
  - [x] Tab Switcher Close All Tabs Icon & Dialog Redesign:
    - Replaced generic cross icon (`ic_close`) with dedicated tab-close icon (`ic_tab_close.xml`: tab window outline with centered 'X').
    - Redesigned "Close all tabs" confirmation prompt with modern Material 3 dialog layout (`dialog_confirm_close_all_tabs.xml` and `CloseAllTabsDialog.kt`):
      - Prominent danger icon badge (`bg_circle_danger.xml` with `ic_tab_close` tinted `@color/red_danger`).
      - Dynamic contextual title and warning message distinguishing between normal and incognito mode and single vs. multiple tabs.
      - Tab count summary pill displaying exact tab count to be closed.
      - Styled Material 3 action buttons: Tonal rounded "Cancel" button and filled red danger "Close All" button.
      - Empty tab list protection displaying an instant toast message.
  - [x] Context-Aware 3-Dot Menu & Webpage Toolset Redesign:
    - Homepage / New Tab Mode:
      - Displays quick shortcuts (Bookmarks, History, Downloads, Share).
      - Displays "Set as default browser" banner only when Onyx is not yet the system default.
      - Displays Settings button directly underneath.
      - Suppresses extra web-only navigation options (new tab, incognito tab, desktop site, bookmark, find in page).
    - Webpage Mode:
      - 1st Header Box-Type UI Card: Clean website domain, centered Shield with Lock button (`ic_shield_lock.xml`), and direct Share button (`ic_share.xml`).
      - Site Shield & Privacy Dialog (`SiteShieldBottomSheetDialog.kt`): Real-time protection status, "Disable adblocker for this site only" toggle with persistent domain whitelist in `BrowserPreferences` and immediate tab reload.
      - 2nd Translate to [Language] (`ic_translate.xml`): Contextual target language display, right gear icon opening `LanguageSelectionDialog.kt` (20 languages supported), and instant Google Web Translate loading.
      - 3rd Find in Page: Interactive toolbar (`findInPageBar`) with real-time match counter (`X/Y`), previous/next navigation, and back-press handling.
      - 4th Desktop Site: MaterialSwitch toggle switching desktop user agent and reloading.
      - 5th Add to Home Screen (`ic_add_to_home_screen.xml`): Native Android launcher shortcut pinning with website title and favicon via `ShortcutManagerCompat`.
      - 6th Developer Tools (`ic_terminal_outline.xml`): Bundled offline `eruda.min.js` in assets, injected dynamically to provide full mobile DevTools console (DOM, console, network, resources).
      - 7th Settings: Direct access to global browser configuration.
