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
  - [x] Clear Browsing Data & Tab Switcher Broom Action:
    - Replaced bottom-left bin and painting brush icons with dedicated cleaning broom icon (`ic_broom.xml`).
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
  - [x] Editable Homepage Shortcuts & Drag-to-Reorder System:
    - Quick Action Row Redesign:
      - Replaced `actionIncognito` with Plus button (`actionAddShortcut`, `ic_add.xml`, label "Add").
      - Replaced `actionShortcuts` with direct Bookmarks button (`actionBookmarks`, `ic_bookmark.xml`).
    - Dynamic Top Sites Grid:
      - Replaced static XML table layout with dynamic `RecyclerView` (`rvShortcuts`) and `GridLayoutManager(context, 4)`.
      - Integrated Android `ItemTouchHelper` to support drag-and-drop position swapping with scale animations and immediate persistent order saving.
      - Long-press contextual options menu: Open in new tab, Edit shortcut, Delete shortcut, Share link.
    - Default Prepopulated Shortcuts:
      - YouTube (`https://www.youtube.com`, `ic_brand_youtube.xml`)
      - GitHub (`https://www.github.com`, `ic_brand_github.xml`)
      - Wikipedia (`https://www.wikipedia.org`, `ic_brand_wikipedia.xml`)
      - Facebook (`https://www.facebook.com`, `ic_brand_facebook.xml`)
      - Reddit (`https://www.reddit.com`, `ic_brand_reddit.xml`)
      - Google (`https://www.google.com`, `ic_brand_google.xml`)
      - Dynamic brand icon detection and custom site letter avatars for user-added URLs.
    - Shortcut Management Interface:
      - `ManageShortcutsBottomSheet`: Bottom sheet invoked via quick action plus button containing an Add Shortcut card (Title and URL inputs with scheme validation) and a list of all current shortcuts with Edit and Delete actions.
      - `EditShortcutDialog`: Material 3 dialog for customizing shortcut title and URL.
      - Synchronous reactive persistence via `BrowserPreferences` JSON storage and StateFlow updates.
  - [x] Material Box-Type UI & Pure Google Theme System (System, Google Dark, Google Light):
    - Material 3 Box-Type UI & Responsive Multi-Display Layout:
      - Encapsulated quick action buttons into elevated Material 3 box card container (`bg_material_box.xml`) with rounded corners and subtle outline.
      - Encapsulated dynamic shortcuts grid in a matching Material 3 box card container with elevation.
      - Upgraded individual shortcut buttons to interactive squircle box tiles (`bg_box_tile.xml`) with ripple and border stroke.
      - Responsive multi-display optimization: Centered max-width constraints (`layout_constraintWidth_max="540dp"` for home, `760dp` for tab switcher) ensuring optimal readability on compact phones, foldables, and large tablets.
      - Adaptive grid span counts: 4 columns on phones, 6 columns on tablets/wide screens for shortcuts; 2 columns on phones, 3 columns on tablets for tab switcher.
    - 3-Option Pure Google Theme System (No Material You dynamic tinting):
      - 1. **System** (`THEME_SYSTEM`): Automatically checks system dark mode status via `AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM`. If system dark mode is ON, uses Google Dark; if OFF, uses Google Light.
      - 2. **Dark** (`THEME_DARK`): Manual forced dark mode. Rich, authentic Google Dark Mode matching google.com and Chrome (`#202124` background, `#303134` surface, `#35363A` variant, `#3C4043` outline, `#E8EAED` text, `#8AB4F8` Google Blue 300 accent; avoiding harsh pure black `#000000`).
      - 3. **Light** (`THEME_LIGHT`): Manual forced light mode. Authentic Google Light UI (`#FFFFFF` background & surface, `#DFE1E5` outline/borders, `#F1F3F4` chips/variant, `#202124` text, `#5F6368` secondary text, `#1A73E8` Google Blue 600 accent).
  - [x] Default Browser External Link Intent Handling & Routing:
    - Root Cause Resolved: Previously, `MainActivity` only ran `tabManager.restoreTabs()` on launch and never inspected `intent.data` or handled `onNewIntent`, causing external links from WhatsApp, SMS, Messenger, email, or pinned homescreen shortcuts to launch Onyx to the home screen without opening the target URL.
    - Android Manifest Enhancements:
      - Set `android:launchMode="singleTask"` on `MainActivity` so all incoming links from other apps route directly to the single browser instance rather than duplicating activities across task stacks.
      - Registered comprehensive intent filters: `ACTION_VIEW` (`http`, `https`, `file`, `content` for HTML/XHTML/text), `ACTION_WEB_SEARCH`, `ACTION_SEARCH`, and `ACTION_SEND` (`text/plain` for receiving shared links/text).
    - Lifecycle-Safe Intent Dispatching:
      - Cold Start: Tabs are asynchronously restored from Room DB, after which `handleIncomingIntent(intent)` inspects the launch intent.
      - Warm / Running State: Overrode `onNewIntent(intent)` to immediately handle new links delivered while the app is alive.
      - Tab Routing Policy: If the active tab is an unused blank normal tab (home screen), it reuses that tab; if the active tab is displaying a website or is incognito, it spawns a new normal tab with the incoming URL and displays it immediately.
      - URL & Query Extraction: Robust parsing supporting direct URIs, `EXTRA_TEXT` (direct links or links embedded within message text), and search queries.
  - [x] Dedicated Onyx Downloader Popup Menu & External Downloader Integration:
    - Download Prompt Popup Menu (`DownloadPromptBottomSheet.kt` & `bottom_sheet_download_prompt.xml`):
      - Replaced full-page activity with a bottom sheet modal popup menu matching the 3-dot menu experience, smoothly sliding over the active webpage without disrupting browsing state.
      - Header: "Onyx Downloader" title with download badge and close button.
      - File Details Card: Uppercase extension badge, formatted size, editable file name input with clear text icon, MIME type.
      - Website Details Card: Domain name, full URL with one-tap copy button, and active "Session cookies & headers forwarded" indicator with green shield lock icon.
      - Session & Security Forwarding: Automatically extracts and forwards `Cookie` (from `CookieManager`), `User-Agent`, and `Referer` to internal and external downloaders to ensure authenticated cloud storage, forums, and protected links download successfully.
      - Download Button: Triggers built-in Onyx / Android system download with complete headers and records in Room database.
      - External Downloader Button with Smart Dispatch:
        - Added Android 11+ `<queries>` in `AndroidManifest.xml` for 1DM, 1DM+, 1DM Lite, ADM, ADM Pro, FDM, Download Navi, Aria2.
        - If exactly 1 external downloader is installed (e.g. 1DM): Launches it directly with all forwarded headers, cookies, and parameters without prompting.
        - If multiple external downloaders are installed (e.g. 1DM & ADM): Shows `SelectDownloaderBottomSheet` with app icons and names to select between them.
        - If no external downloader is installed: Shows a helpful prompt offering to open the Play Store or use the default Onyx Downloader.
  - [x] Homepage Quick Action Reordering, Incognito Visual Clarity, and Toolbar Separation:
    - Homepage Quick Actions Redesign (Non-Box UI & Reorderable):
      - Replaced box-type card container with clean, circular flat action buttons (`item_quick_action.xml` and `bg_circle_action.xml`) matching modern mobile browser aesthetics.
      - Default ordering sets Add (`+`) button to the 4th position: Bookmarks, History, Downloads, Add (`DEFAULT_ORDER = [bookmarks, history, downloads, add]`).
      - Full drag-and-drop position swapping via `ItemTouchHelper` directly on the homepage, with instant persistent order saving to `BrowserPreferences`.
      - Added `AdjustQuickActionsBottomSheet` and long-press dialog with Up/Down buttons and "Reset Default" action for effortless manual reordering.
      - Integrated "Adjust Action Buttons" into `ManageShortcutsBottomSheet`.
    - Toolbar Spacing & WebUI Boundary Separation:
      - Added 8dp bottom padding and 4dp top padding to `topBar`, giving the search bar and action icons comfortable breathing room.
      - Introduced a crisp 1dp outline divider line (`topBarDivider`) beneath the toolbar to physically separate the toolbar from web content, completely preventing UI blending.
    - Authentic Incognito Icon & Tab Switcher Segmented Control:
      - Replaced old lightbulb icon with authentic Fedora Hat & Spy Glasses vector drawable (`ic_incognito.xml`).
      - Added `app:tabInlineLabel="true"` and icons (`@drawable/ic_tabs` and `@drawable/ic_incognito`) to the tab switcher segmented bar.
    - Prominent Incognito Homepage Branding & Top Bar Indicator:
      - Swapped normal brand header with dedicated `incognitoHeader` (Fedora Hat & Glasses logo, bold "Incognito" title, and privacy description) whenever the active tab is incognito.
      - Added `ivIncognitoIndicator` in the top search bar and custom `"Search privately or type URL"` hint to provide unmistakable visual feedback that the user is browsing in Incognito mode.
  - [x] Tab Switcher Pill Segmented Control, Proper Bin Icon & Homepage UI Polish (commit `9345a52`):
    - Tab Switcher Segmented Control:
      - Replaced `bg_search_bar` with dedicated `bg_tab_mode_selector` (colorSurfaceVariant pill) for the Normal/Incognito toggle so the background blends seamlessly with the tab switcher surface in both Light and Dark themes.
      - Set `tabIndicatorHeight=36dp` + `tabIndicatorGravity=center` so the active tab indicator fills the pill slot as a proper rounded pill — matching the selected tab visually.
      - Added `@color/tab_mode_icon_tint` color state list: icon tints to `@color/primary` when selected and `?android:attr/textColorSecondary` when not, replacing the flat `?attr/colorControlNormal` which didn't differentiate state.
      - Added top and bottom 1dp `colorOutline` dividers (alpha 0.4) between the top bar, tab grid, and bottom action bar for cleaner structural separation.
    - Bottom Bar Broom → Proper Bin Icon:
      - Replaced the broom icon with the `ic_delete` trash-bin icon (Lucide Trash-2) properly tinted via `app:tint="?attr/colorControlNormal"` so it adapts correctly to both Light (`#202124`) and Dark (`#E8EAED`) themes.
    - Homepage Layout Improvements:
      - Wrapped quick action `RecyclerView` inside a `MaterialCardView` (theme-matching `colorSurface` background, `colorOutline` stroke, 20dp corner radius) for visual grouping.
      - Tightened layout max-width from 540dp → 480dp for better phone-proportioned display.
      - "Top Sites" section label upgraded with `sans-serif-medium` font weight and 0.1 letter spacing for a more polished heading style.
      - Removed elevation shadow from the shortcuts grid box (elevation=0dp) — cleaner flat look consistent with Google's UI language.
      - Consistent 50dp icon sizing across quick action circles and shortcut squircle tiles; both now use 11.5sp label text.
      - Empty tab state updated with secondary hint "Tap + to open a new tab" and reduced logo opacity (0.22).
  - [x] Fix build failure: Restore missing `getQuickActionOrder()` declaration, `saveQuickActionOrder()`, and `companion object {` wrapper in `BrowserPreferences.kt` (commit `9122cbe`).
  - [x] Full Brave-like Shields & Privacy System (commit `4aac01f`):
    - [x] **Homepage Shortcut Tap Fix**: Disabled `SwipeRefreshLayout` on homepage — it was intercepting RecyclerView touch events. `isEnabled = false` in `showHomeScreen()`, re-enabled in `showWebView()`. Also fixed `setOnChildScrollUpCallback` to block pull-to-refresh when homeLayout is visible. No more refresh on homepage/new tab.
    - [x] **BrowserPreferences**: 17 new preference fields, `HTTPS_MODE_*` constants, `COOKIE_BLOCK_*` constants, 17 new key constants, `isFilterListEnabled()`/`setFilterListEnabled()` helpers.
    - [x] **OnyxWebViewClient** full rewrite with:
      - Auto-redirect AMP pages (`resolveAmpUrl()` handles Google AMP cache, `amp.` subdomain, `/amp/` path, `?amp=1`)
      - Auto-redirect tracking URLs (strips 25+ params: utm_*, fbclid, gclid, msclkid, ttclid, li_fat_id, igshid, etc.)
      - HTTPS Upgrade: 3 modes — Disabled / When Possible (fallback to HTTP) / Strict (cancel+error on SSL failure)
      - Global script blocking (`isGlobalScriptBlockingEnabled` gates all script resources)
      - Social media tracker blocking with per-platform allowlists (Facebook logins/embeds, Twitter embeds, LinkedIn embeds)
      - Element blocking in private windows toggle (`isElementBlockingInPrivateEnabled`)
      - Language fingerprint spoofing (`navigator.language = 'en-US'`, `navigator.languages = ['en-US', 'en']`)
      - Block smart app banners (CSS+JS removes apple-itunes-app/google-play-app meta tags and hides banner elements)
      - Open links in app toggle (gates custom scheme dispatcher; `intent://` fallback always works)
    - [x] **OnyxWebView** cookie blocking: 3-mode `CookieManager` integration — Block All / Block Third-Party (`setAcceptThirdPartyCookies`) / Allow All
    - [x] **ShieldsActivity** (336 lines) with 11 sections: Trackers & Ads, Connections, Scripts, Cookies, Fingerprinting, Content Filtering, Element Blocking, Social Media, Links, Secure DNS, Privacy
    - [x] **FilterListsBottomSheet**: 10 Brave/community filter lists (EasyList, EasyPrivacy, uBlock, Brave Default, Fanboy Annoyance, AdGuard Base, AdGuard Mobile, Peter Lowe's, Brave Social, Cookie Consent)
    - [x] **CustomRulesDialog**: Multiline EditText for user-defined Adblock/uBlock rules
    - [x] **Secure DNS**: Toggle + provider picker (Cloudflare 1.1.1.1, Google 8.8.8.8, NextDNS, Custom URL)
    - [x] **Do Not Track**: Send DNT header via OnyxWebView.loadUrl() + spoof `navigator.doNotTrack = '1'`
    - [x] **SettingsActivity**: New "Shields & Privacy" row linking to ShieldsActivity
    - [x] **AndroidManifest**: ShieldsActivity registered
  - [x] Custom Search Engines, Google Password Manager & Passkeys System:
    - [x] **Custom Search Engines (Brave-core Inspired)**:
      - Migrated `SearchEngine` from enum to extensible data class with JSON serialization, maintaining full backward compatibility with built-in engines (Brave, Google, DuckDuckGo, Bing, Startpage, Yahoo).
      - Added custom search engines persistent management in `BrowserPreferences` (`getCustomSearchEngines`, `addCustomSearchEngine`, `updateCustomSearchEngine`, `deleteCustomSearchEngine`, `getAllSearchEngines`).
      - Created `SearchEngineSettingsActivity` with Standard Engines, Custom Engines list, and `AddEditSearchEngineDialog` (name, keyword shortcut, query URL with `%s` validation).
      - Implemented Brave-style keyword address bar shortcuts in `MainActivity.performSearchOrLoad` (e.g. typing `e climate change` searches via Ecosia keyword `e`).
      - Updated `SearchEnginePopupMenu` and `SearchEnginePickerDialog` with custom search engines and direct "Manage search engines…" shortcut.
    - [x] **Google Password Manager & Autofill Services**:
      - Created `AutofillHelper` querying `AutofillManager` and `PackageManager` for installed autofill providers (Google Password Manager, Bitwarden, 1Password, etc.).
      - Created `AutofillSettingsActivity` with active service status card, system Autofill service picker launcher (`ACTION_REQUEST_SET_AUTOFILL_SERVICE`), direct Google Password Manager settings launcher (`com.google.android.gms.credential.manager.PasswordManagerActivity`), and detected provider list.
      - Enabled native Android Autofill in `OnyxWebView` (`importantForAutofill = IMPORTANT_FOR_AUTOFILL_YES`, `saveFormData = true`).
    - [x] **Passkeys Support (WebAuthn / FIDO2 / Credential Manager)**:
      - Integrated Google's official AndroidX `androidx.credentials:credentials:1.3.0` and `androidx.credentials:credentials-play-services-auth:1.3.0`.
      - Created `PasskeyWebAuthnBridge` with `@JavascriptInterface` handling `createPasskey` and `getPasskey` via `CreatePublicKeyCredentialRequest` and `GetPublicKeyCredentialOption`.
      - Injected W3C-compliant WebAuthn polyfill into `OnyxWebView` handling ArrayBuffer <-> Base64URL conversions, `window.PublicKeyCredential`, and `navigator.credentials.create`/`get` interception for biometric and password manager passkey registration and login.
      - Added Passkeys toggle and info card in `AutofillSettingsActivity`.
  - [x] Settings Cleanup & Consolidation:
    - Removed redundant "Ad Blocker & Privacy" row from `SettingsActivity` and `activity_settings.xml`.
    - Consolidated all privacy and adblocking configuration into the single comprehensive "Shields & Privacy" (`ShieldsActivity`) entry.
    - Updated `SiteShieldBottomSheetDialog` "Global Adblocker Settings" button to open `ShieldsActivity`.
    - Converted legacy `SettingsPrivacyActivity` to automatically forward to `ShieldsActivity`.


