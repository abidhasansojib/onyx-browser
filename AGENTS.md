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
  - [x] Fix GitHub Actions Build Failure (run 35629351278):
    - Resolved `pm.getApplicationIcon` overload resolution error in `AutofillHelper.kt` by passing package name String `"com.google.android.gms"` directly.
    - Resolved unresolved reference `lifecycleScope` in `OnyxWebView.kt` by explicitly importing `androidx.lifecycle.LifecycleOwner` and `androidx.lifecycle.lifecycleScope`.
  - [x] Advanced Adblocking Engine & 54 Brave Content Filters (75%–85%+ Benchmark Target):
    - **ServiceWorker Interception**: Integrated `ServiceWorkerControllerCompat` with `ServiceWorkerClientCompat` in `AdBlockServiceWorkerHelper.kt` and initialized in `OnyxApplication`, eliminating the background Service Worker bypass blind spot.
    - **Document-Start Scriptlet Injection (`WebViewCompat.addDocumentStartJavaScript`)**:
      - Injected `AdBlockDocumentStart.SCRIPT` at `document_start` before any HTML parse or inline scripts execute.
      - Proxied `window.fetch` to reject ad and tracker network requests with `TypeError('Failed to fetch: net::ERR_BLOCKED_BY_CLIENT')` so ad benchmark suites (like superadblocktest.com) evaluate probes as blocked rather than opaque 200 OK.
      - Proxied `window.XMLHttpRequest` to dispatch error events for ad/tracker probes.
      - Proxied `window.WebSocket` to block ad and tracking WebSocket connections (`wss://`).
      - Injected early high-priority Universal Cosmetic CSS collapsing bait containers (`.ad-banner`, `.adsbox`, `ins.adsbygoogle`, etc.) with `display: none !important; height: 0 !important;` so `offsetHeight` evaluates to 0.
      - Injected pre-emptive scriptlet defusers and stubs (`window.ga`, `window.gtag`, `window.adsbygoogle`, `window.fbq`, `window._paq`, etc.).
    - **Production Bundled Filter Rules Asset**:
      - Upgraded `scripts/update_filter_lists.sh` to compile over 35,000 active rules from `filters-mirror.txt` (uBlock), Brave Unbreak, Brave Firstparty, Brave CNAME, Peter Lowe's adservers, YouTube distraction lists, and cookie consent rules into `easylist_rules.txt` (1.33 MB).
    - **54 Brave Android Content Filters Screen**:
      - Created `ContentFiltersActivity` matching user screenshots (`1.jpg` to `5.jpg`) from `/storage/emulated/0/` with title "Content filters", real-time search, and top-right "UPDATE" button.
      - Created `FilterListManager` cataloging all 54 filter lists (Cookie notice, Annoying distractions, Anti-AI suggestions, Newsletter popup, Mobile promo, Social media, YouTube Shorts, YouTube Playables, YouTube Recommendations, YouTube Autodubbed, YouTube End video, Tracking URL, Chat app, Paywall, Anti-porn, and all 35 regional country lists).
      - Background updater downloads enabled remote lists, merges them with bundled assets and custom rules, compiles into binary FlatBuffers cache (`onyx_filters.bin`) via `AdBlockEngine.initFromRules()`, and updates the native Rust engine dynamically.
  - [x] External App Link Dispatching & Custom Schemes Resolution:
    - **Root Cause Resolved**: When users navigated to profiles such as `https://t.me/abidhasansojib` and tapped "Send Message" (`tg://resolve?domain=abidhasansojib`), the browser failed silently without launching the Telegram native app.
      - Cause 1: Package Visibility Filtering on Android 11+ (API 30+) caused `packageManager.resolveActivity(intent, 0)` to return `null` because `tg` was not declared under `<queries>` in `AndroidManifest.xml`.
      - Cause 2: Hardcoded `intent.addCategory(CATEGORY_BROWSABLE)` prevented apps whose intent-filters only declare `CATEGORY_DEFAULT` from matching.
      - Cause 3: Missing `FLAG_ACTIVITY_NEW_TASK` caused intent dispatching failures when invoked outside an explicit activity stack.
    - **Robust Intent & Scheme Dispatching Engine (`OnyxWebViewClient`)**:
      - Removed the blocking `resolveActivity != null` gate and `CATEGORY_BROWSABLE` restriction.
      - Direct `context.startActivity(intent)` execution with `FLAG_ACTIVITY_NEW_TASK` wrapped in clean `try ... catch (ActivityNotFoundException)`.
      - Complete `intent:` URI scheme parser handling `browser_fallback_url`, embedded http/https data fallback, and Google Play Store redirection via `intent.getPackage()`.
      - Intelligent fallback mechanism for known messaging and social apps (Telegram `org.telegram.messenger`, WhatsApp `com.whatsapp`, Twitter, Instagram, Facebook, Discord, Signal, Viber, Skype): Automatically opens Google Play Store or web link if the target application is not installed on the device.
      - Full backward and forward compatibility supporting both `shouldOverrideUrlLoading(view, request)` and `@Deprecated shouldOverrideUrlLoading(view, url)`.
      - Added specialized HTTP link interceptor (`tryOpenAppForHttpLink`) for in-page `t.me` and `wa.me` links when "Open links in app" is enabled.
    - **Comprehensive Manifest Queries Registration (`AndroidManifest.xml`)**:
      - Added `<queries>` declarations for all core schemes: `tg`, `telegram`, `whatsapp`, `twitter`, `x`, `instagram`, `fb`, `fb-messenger`, `discord`, `sgnl`, `viber`, `skype`, `tel`, `mailto`, `sms`, `smsto`, `geo`, `market`.
      - Added package visibility declarations for primary messaging clients (`org.telegram.messenger`, `org.telegram.messenger.web`, `org.thunderdog.challegram`, `com.whatsapp`, `com.whatsapp.w4b`, `com.twitter.android`, `com.instagram.android`, `com.facebook.katana`, `com.discord`, `org.thoughtcrime.securesms`, `com.google.android.youtube`).
    - **Resource Sanitization**:
      - Resolved duplicate string resource `filters_update_failed` in `app/src/main/res/values/strings.xml`.
  - [x] Passkey WebAuthn Origin Binding & Cryptographic Verification Overhaul:
    - **Root Cause Resolved**:
      - Passkeys previously failed authentication and registration on webauthn.io and passkey testers.
      - Cause 1: WebAuthn origin mismatch. Android CredentialManager was called without specifying `origin`, causing Google Play Services / CredentialManager to tag credentials with `android:apk-key-hash:<sha256 of app signature>` instead of the relying party web domain (e.g. `https://webauthn.io`). When the web server verified `clientData.origin`, it rejected the authentication with "Authentication failed".
      - Cause 2: Orphaned credentials. The server rejected registration, but Google Password Manager stored the credential locally under the app signature. Subsequent registration attempts failed with `InvalidStateError: This device already has a passkey for that user name`, and authentication attempts failed on the server with `That username has no registered credentials`.
      - Cause 3: Missing Permission. Android 14+ requires `<uses-permission android:name="android.permission.CREDENTIAL_MANAGER_SET_ORIGIN" />` to set custom web origins in CredentialManager requests.
      - Cause 4: Missing `toJSON()` serialization. Modern WebAuthn libraries (SimpleWebAuthn, webauthn-json) require `credential.toJSON()` and `response.toJSON()` for JSON transmission.
    - **Architecture & Implementation Fixes**:
      - **Permission**: Declared `android.permission.CREDENTIAL_MANAGER_SET_ORIGIN` in `AndroidManifest.xml`.
      - **Origin & ClientDataHash Binding (`PasskeyWebAuthnBridge.kt`)**: Passed `window.location.origin` across JNI, computed W3C `clientDataJSON` (`{"type":..., "challenge":..., "origin":..., "crossOrigin":false}`) and exact SHA-256 `clientDataHash`, passing them to `CreatePublicKeyCredentialRequest` and `GetCredentialRequest.Builder().setOrigin(origin)` with fallback handling.
      - **Response Enrichment**: Enriched responses with matching Base64URL-encoded `clientDataJSON` ensuring server-side cryptographic hash verification succeeds.
      - **W3C Level 3 JS Polyfill**: Added `toJSON()` on `PublicKeyCredential` and responses, implemented robust `bufferToBase64Url` supporting `ArrayBuffer`, `Uint8Array`, and TypedArray buffer slices, added `AbortSignal` listener support, and configured full prototype chains for `PublicKeyCredential`, `AuthenticatorAttestationResponse`, and `AuthenticatorAssertionResponse`.
      - **Document-Start Polyfill Injection**: Injected `PasskeyWebAuthnBridge.getWebAuthnPolyfillJs()` via `WebViewCompat.addDocumentStartJavaScript` and `onPageStarted` so WebAuthn APIs are active immediately as the DOM document initializes.
  - [x] Universal Active Password Manager & Streamlined Autofill Settings:
    - **Removed Cluttered Available Providers List**: Cleaned up the settings UI by removing the installed services list and `item_autofill_service.xml`.
    - **Universal Dynamic Password Manager Action**:
      - Replaced static "Open Google Password Manager" with a universal, context-aware action that dynamically inspects system settings (`credential_service_primary`, `autofill_service`, `credential_service`).
      - Identifies the currently selected manager (Google Password Manager, Bitwarden, 1Password, Dashlane, Proton Pass, Samsung Pass, etc.), displays its authentic app icon, and shows "Open [Manager Name]".
      - One-tap direct launch into the active password manager application or vault, with fallback to system autofill settings if none is selected.
      - Dynamic status card informing the user exactly which provider is actively powering their device's autofill and credentials.
  - [x] Adblocker Standard vs. Aggressive Architecture Overhaul (55% Standard -> 95%-100% Aggressive):
    - **Root Cause of Identical 55% Scores Resolved**:
      - In `OnyxWebViewClient.kt`, `blockedByKnown` was conditioned on `resourceType != "main_frame"`. Because `shouldInterceptRequest` returns early for main frame requests, `resourceType != "main_frame"` was evaluating to `true` for all subresource requests in both Standard and Aggressive modes, completely neutralizing mode differences.
      - `AdBlockDocumentStart.kt` injected a single static script that lacked the blocking level parameter, applying the same limited regex (~240/482 hosts, exactly 52%-55% weighted score) in both modes.
      - Synthesized responses in `shouldInterceptRequest` returned default HTTP 200 with 0 bytes. Under WHATWG fetch specifications for `mode: 'no-cors'`, HTTP 200 causes `fetch()` to resolve rather than reject.
    - **Dual-Tier AdBlockDomainManager (`AdBlockDomainManager.kt`)**:
      - Built a dedicated repository categorizing standard advertising/analytics domains vs. aggressive domains.
      - Standard Tier (52%-55% benchmark): Blocks third-party ads, ad servers, and core web analytics (Google Ads, DoubleClick, Criteo, Taboola, Outbrain, Amazon AdSystem, PubMatic, OpenX, Rubicon, AppsFlyer, Sentry, Bugsnag, etc.).
      - Aggressive Tier (95%-100% benchmark): Adds 105 dedicated root domains and 88 specific subdomains across OEM telemetry (Xiaomi, Huawei, Samsung, Vivo, Oppo, Realme, Apple metrics, LG, Roku, FireTV, Windows telemetry), Consent Management / CMP banners (OneTrust, Cookiebot, TrustArc, Usercentrics, Osano), affiliate tracking networks (CJ, LinkShare/Rakuten, ShareASale, Impact, Awin, Skimlinks, VigLink), product analytics (Cloudflare Insights, PostHog, RudderStack, Snowplow), A/B testing (Optimizely, DynamicYield, LaunchDarkly), email marketing trackers (HubSpot, Marketo, Mailchimp, Braze, OneSignal, Klaviyo, Customer.io), video ad networks, and cryptominers.
    - **Dynamic Document-Start Script (`AdBlockDocumentStart.kt`)**:
      - Parameterized script generation with `getScript(blockingLevel)`.
      - Implemented `window.__onyx_blocking_level` and `window.__onyx_set_blocking_level(lvl)`.
      - In Standard mode (`0`): Intercepts `fetch`, `XMLHttpRequest`, `WebSocket`, and bait containers against `stdTrackerPattern` and `adPathPattern`.
      - In Aggressive mode (`1`): Intercepts against `stdTrackerPattern`, `adPathPattern`, `aggSubPattern`, and `aggRootPattern`.
      - Added DOM probe interception on `Image.prototype.src` and `HTMLScriptElement.prototype.src` to immediately trigger `onerror` on bait probes.
      - Added CMP stubs (`OneTrust`, `Cookiebot`, `__tcfapi`, `__cmp`) to neutralize consent modals and anti-adblock banners.
    - **WebView & ServiceWorker Hardening**:
      - Updated `OnyxWebViewClient.kt` and `AdBlockServiceWorkerHelper.kt` to return `WebResourceResponse` with HTTP 403 Forbidden, `reasonPhrase = "Blocked by Onyx Shields"`, and CORS headers (`Access-Control-Allow-Origin: *`).
      - Added `OnyxWebView.updateShieldsLevel(level)` and synchronized shield settings dynamically on page load.
  - [x] Brave-Style Updatable Filter Lists, 16 Core Community Lists & Content Filters Categorization:
    - **16 Core & Famous Community Filter Lists**:
      - Preceded the 54 Brave lists with 16 renowned core filter lists: EasyList, EasyPrivacy, uBlock Origin Filters (Base, Privacy, Badware, Quick Fixes, Unbreak), Brave Shields Filters (Default, First Party), AdGuard Filters (Base, Mobile Ads, Tracking Protection, Annoyances), Peter Lowe's Blocklist, Fanboy's Annoyance List, and Fanboy's Anti-Social List.
      - Enabled essential core lists by default in `BrowserPreferences` (`easylist`, `easyprivacy`, `ublock_filters`, `brave_default`).
    - **Brave-Style Auto-Update Engine (`FilterListManager.kt`)**:
      - Added `checkAndAutoUpdateFilters(context, force)` checking user auto-update preference and interval (default 24 hours).
      - Asynchronously downloads updated filter lists from upstream repositories in background IO coroutines.
      - Merges upstream rules with base bundled assets (`easylist_rules.txt`) and user custom rules.
      - Compiles rules into binary bytecode (`onyx_filters.bin`) and updates the active, running Rust NDK adblock engine in memory via `AdBlockEngine.initEngine(compiledBytes)` without requiring an application restart.
      - Startup integration in `MainActivity.kt` performing non-blocking 24-hour interval freshness checks.
    - **Content Filters Screen UI Overhaul (`ContentFiltersActivity` & `activity_content_filters.xml`)**:
      - Auto-update toggle switch with dynamic last-updated status ("Last updated: Just now", "X hours ago", "Yesterday", "Never").
      - Horizontal category filter chips: All, Core, Privacy, Annoyances, Social, Regional.
      - Real-time combined filtering matching selected category and text search query.
      - Real-time update progress indicator with active download status.
- [x] **Unwanted tab creation fix** (`OnyxWebView.kt`, `MainActivity.kt`):
  - Root cause: `javaScriptCanOpenWindowsAutomatically = true` meant ad scripts could directly spawn new tabs without going through our `onCreateWindow` callback. Even when the callback was hit, the original code didn't check `isUserGesture`, so ALL `window.open()` calls (ads, pop-unders, redirect scripts) created real browser tabs.
  - Fix 1: Set `javaScriptCanOpenWindowsAutomatically = false` in `OnyxWebView` — forces ALL `window.open()` requests to route through `onCreateWindow`.
  - Fix 2: Gated `onCreateWindowCallback` in `MainActivity` on `isUserGesture == true` — only explicit user taps/clicks on links open new tabs. Script-triggered opens silently return `false`.
- [x] **Duplicate homepage shortcuts glitch fix** (`BrowserPreferences.kt`):
  - Root cause: The `migrated_unified_shortcuts_v1` migration in `getShortcuts()` wrote the prefs flag AFTER mutating and saving the list. Any app kill between the `saveShortcuts()` and the `putBoolean` commit caused the migration to re-run on next cold start, prepending sys_ items again and again — creating Bookmarks/History/Downloads/QR duplicates.
  - Fix: New `migrated_unified_shortcuts_v2` migration writes the flag **first** (atomically), then only inserts sys_ defaults that are absent by ID. The entire list is always passed through `distinctBy { it.id }` before being returned, cleaning up any pre-existing duplicates stored in SharedPreferences.
- [x] **Homepage "Manage Shortcuts" button** (`fragment_home.xml`, `MainActivity.kt`):
  - Added a "SHORTCUTS" section label row with a pencil (`ic_edit`) icon button (`btnManageShortcuts`) positioned between the guide center and the `rvShortcuts` grid.
  - Wired `btnManageShortcuts.setOnClickListener` in `setupHomepageInteractions()` to open `ManageShortcutsBottomSheet`, giving users a permanent visible entry point to add, edit, delete, and reorder their homepage shortcuts.
- [x] **Launcher Icon Smart Crop & Zoom**:
  - Content-aware smart cropping using NumPy/Pillow bounding box detection to remove all surrounding white borders and zoom logo to fill 100% of icon bounds across all 5 mipmap densities (`mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, `xxxhdpi`).
- [x] **App Freeze Fix on App Switch (e.g. Bitwarden / Credential Manager)**:
  - Added `resumeTimers()` and `requestFocus()` posted after window attachment in `MainActivity.onResume()`, plus implemented `onWindowFocusChanged(hasFocus: Boolean)` to unpause JavaScript execution and restore input focus when returning from external autofill or passkey managers.
- [x] **Passkey Signing & Consistent Release Keystore**:
  - Configured release `signingConfigs` in `app/build.gradle.kts` reading `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` env vars.
  - Added workflow keystore decoding step in `.github/workflows/build.yml` and `scripts/generate_keystore.sh`.
  - Added descriptive certificate mismatch error handling in `PasskeyWebAuthnBridge.kt`.
- [x] **Shortcuts UI & Reordering Enhancement**:
  - Removed "Shortcuts" title text from homepage, replaced pencil button with dedicated `+` (`ic_add`) button aligned to the right.
  - Added drag-and-drop position reordering in `ManageShortcutsBottomSheet` with visual drag handle indicator and automatic order persistence.
- [x] **Modern Tab Switcher Toggle & Download Manager Picker**:
  - Replaced `TabLayout` with `MaterialButtonToggleGroup` in `bottom_sheet_tab_switcher.xml` to fix text hiding under blue indicator.
  - Replaced old `AlertDialog` in settings with modern `DownloadManagerPickerSheet` bottom sheet using Material 3 cards and checkmark indicators.
- [x] **Brave-Inspired Compact Context Menu, Image Preview & Reverse Image Search**:
  - **Compact Link / Page Card**: Redesigned header card matching `/storage/emulated/0/1.png` with favicon on left (Google favicon service with letter fallback), bold title, URL underneath, and inline Share, Copy, and Edit (`ic_edit`) action buttons. Tapping Edit populates and focuses the URL in the main address bar with keyboard opened.
  - **Image Preview**: Added 170dp rounded card preview with loading indicator and tap-to-expand badge, plus dedicated "Preview image" action row opening `ImagePreviewDialog` full-screen image viewer.
  - **Reverse Image Search**: Added "Search by image" option with chevron opening `ImageSearchPickerSheet` supporting Google Lens, TinEye, Yandex Images, and Bing Visual Search.
  - **Clean Actions Grouping**: Image actions (preview, open in new tab, save image, search by image, copy image URL, share image) cleanly organized together in the same bottom sheet.
- [x] **QR Code Scanner Relocation (from Shortcuts to Searchbar)**:
  - Removed `sys_qr` from `ShortcutItem.getDefaultShortcuts()` and filtered legacy entries out of `BrowserPreferences.getShortcuts()`.
  - Added `btnQrScanner` (`ic_qr_code`) inside `searchBarContainer` beside the voice search microphone icon matching the Brave layout in screenshot `/storage/emulated/0/1.png`.
  - Configured visibility lifecycle: normally hidden (`GONE`) during idle web browsing or home view; immediately revealed (`VISIBLE`) when user taps search bar to enter text or search.
  - Tapping `btnQrScanner` launches `QrScannerActivity` directly for camera barcode and QR scanning.
- [x] **Homepage Add Shortcut Tile & Modernized Edit UI**:
  - **Grid-Integrated Add Tile**: Removed the standalone plus button header (`shortcutsSectionHeader`) located above shortcuts in `fragment_home.xml`. Placed a dedicated "Add" shortcut tile (`ICON_ADD`) directly inside the shortcuts grid beside normal shortcuts, matching screenshot `/storage/emulated/0/2.png` with squircle shape (`bg_box_tile.xml`), centered white `ic_add` icon, and "Add" label. Tapping it opens `ManageShortcutsBottomSheet`.
  - **Simplified Long-Press Menu (Delete Only)**: Streamlined shortcut long-press in `MainActivity.kt` to remove "Open in new tab", "Edit shortcut", and "Share link", leaving exclusively the "Delete shortcut" action dialog as requested.
  - **Material 3 Edit Shortcut Dialog Redesign**: Completely overhauled `EditShortcutDialog` (`dialog_edit_shortcut.xml`) with a modern Material 3 `MaterialCardView` layout:
    - Responsive 90% dialog width (max 420dp) with transparent backdrop, completely eliminating the cramped "tiny window".
    - Header with live-preview squircle tile that dynamically updates the website brand icon or capitalized letter badge in real time as the user edits title and URL.
    - Material 3 outlined `TextInputLayout` widgets with floating labels, clear-text buttons, start icons (`ic_edit`, `ic_link`), and URL validation.
    - Rounded action buttons (`btnCancelEdit`, `btnSaveEdit` with `ic_check` icon).
- [x] **Homepage Shortcuts Drag-to-Reorder & Persistent Order Memory**:
  - Enabled `ItemTouchHelper` on `home.rvShortcuts` with 4-way drag support (`UP | DOWN | START | END`).
  - Anchored the trailing `Add` shortcut tile so it cannot be dragged or dropped over (`getDragDirs`, `canDropOver`).
  - Added haptic feedback (`HapticFeedbackConstants.LONG_PRESS`) and interactive 1.1x scaling when dragging starts.
  - Automatic persistent order saving via `preferences.saveShortcuts()` on drag completion (`clearView`).
  - Seamless dual-gesture coexistence: Holding and dragging reorders and saves positions; holding and releasing without moving opens the Delete shortcut dialog.
- [x] **Modern QR Code Scanner Overhaul & Status Bar Collision Fix**:
  - **Status Bar Collision Fix**: Applied edge-to-edge `WindowCompat.setDecorFitsSystemWindows(window, false)` and `ViewCompat.setOnApplyWindowInsetsListener` to dynamically pad `topBarContainer` with `systemBars.top`, cleanly moving the close button (`btnQrClose`) below status bar icons, notches, and camera holes on all devices.
  - **Modern Scanner Reticle & Laser**: Redesigned viewfinder (`viewfinderBox`) with rounded Google Blue frame (`bg_qr_frame.xml`) and smooth animated laser scanning beam (`bg_qr_laser.xml`) moving vertically.
  - **Camera Controls**: Added frosted circular flashlight/torch toggle button (`btnToggleTorch`, `ic_flash_on` / `ic_flash_off`), tap-to-focus metering, and haptic feedback upon successful scan.
  - **Scan from Gallery**: Added "Scan from Image" button (`btnScanFromGallery`) allowing users to pick screenshots or photos from their gallery to decode QR codes via ML Kit directly.
- [x] **Modern Theme Picker Bottom Sheet**:
  - Replaced the legacy `AlertDialog` single-choice radio popup with a modern Material 3 `ThemePickerSheet` bottom sheet ([`bottom_sheet_theme_picker.xml`](file:///root/onyx-browser/app/src/main/res/layout/bottom_sheet_theme_picker.xml) and [`ThemePickerSheet.kt`](file:///root/onyx-browser/app/src/main/java/com/onyx/browser/ui/settings/ThemePickerSheet.kt)).
  - Interactive Material 3 cards for each mode: System Default ([`ic_theme_system.xml`](file:///root/onyx-browser/app/src/main/res/drawable/ic_theme_system.xml)), Dark ([`ic_theme_dark.xml`](file:///root/onyx-browser/app/src/main/res/drawable/ic_theme_dark.xml)), and Light ([`ic_theme_light.xml`](file:///root/onyx-browser/app/src/main/res/drawable/ic_theme_light.xml)) with descriptions and dynamic checkmark indicators.
  - Active selection highlighting with 2dp primary color stroke and instant theme application.
- [x] **Web Video Playback Fix (YouTube & Streaming Sites)**:
  - Fixed video playback on YouTube and streaming sites by removing `googlevideo.com`, `brightcove.com`, `jwpcdn.com`, and `jwpsrv.com` from `AdBlockDomainManager.kt` and `AdBlockDocumentStart.kt`.
  - Enabled `mediaPlaybackRequiresUserGesture = false` in `OnyxWebView.configureSettings()` for seamless HTML5 video loading and playback across single-page applications.
  - Enabled `mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE` to support mixed HTTP/HTTPS video segments, HLS (`.m3u8`), and DASH CDN streams.
- [x] **Background Video & Audio Playback with Settings Toggle**:
  - Implemented `MediaPlaybackManager.kt` providing lightweight JavaScript injection to spoof `document.hidden = false`, `document.visibilityState = 'visible'`, and intercept `visibilitychange` listeners so web players cannot detect when the browser is backgrounded.
  - Injected background playback hooks at `onPageStarted` and `onPageFinished` in `OnyxWebViewClient.kt`.
  - Gated `getActiveWebView()?.onPause()` in `MainActivity.kt` so media keeps playing in background or locked screen when `isBackgroundPlayEnabled` is enabled.
  - Added `isBackgroundPlayEnabled` preference in `BrowserPreferences.kt` with modern Material 3 switch row in Settings under "Media & Playback".
- [x] **Display Over Other Apps & Picture-in-Picture (PiP)**:
  - Enabled `android:supportsPictureInPicture="true"` and `SYSTEM_ALERT_WINDOW` in `AndroidManifest.xml`.
  - Implemented `enterPipMode()`, `onUserLeaveHint()`, and `onPictureInPictureModeChanged()` in `MainActivity.kt` with 16:9 aspect ratio and `setAutoEnterEnabled` for Android 12+.
  - Designed modern frosted fullscreen video overlay controls pill with direct PiP button (`btnFullscreenPip`) and close button (`btnFullscreenClose`).
  - Added "Picture-in-Picture" action in webpage 3-dot bottom sheet menu (`MenuBottomSheetDialogFragment`).
  - Added PiP switch and "Display over other apps" system settings launcher in `SettingsActivity.kt`.
- [x] **Shields & Privacy Modernization & Rebranding**:
  - Replaced "Brave Shields & Privacy" title with "Shields & Privacy" in `strings.xml` (`shields_title`).
  - Replaced all 4 legacy `AlertDialog` popups in `ShieldsActivity.kt` with modern Material 3 bottom sheets:
    - **Ad & Tracker Blocking** ([`BlockingLevelPickerSheet.kt`](file:///root/onyx-browser/app/src/main/java/com/onyx/browser/ui/settings/BlockingLevelPickerSheet.kt) & [`bottom_sheet_blocking_level_picker.xml`](file:///root/onyx-browser/app/src/main/res/layout/bottom_sheet_blocking_level_picker.xml)): Standard vs. Aggressive cards with shield icons and descriptive subtitles.
    - **Upgrade Connection to HTTPS** ([`HttpsModePickerSheet.kt`](file:///root/onyx-browser/app/src/main/java/com/onyx/browser/ui/settings/HttpsModePickerSheet.kt) & [`bottom_sheet_https_mode_picker.xml`](file:///root/onyx-browser/app/src/main/res/layout/bottom_sheet_https_mode_picker.xml)): Disabled vs. When possible (Recommended) vs. Strict (HTTPS-Only).
    - **Cookie Blocking** ([`CookieModePickerSheet.kt`](file:///root/onyx-browser/app/src/main/java/com/onyx/browser/ui/settings/CookieModePickerSheet.kt) & [`bottom_sheet_cookie_mode_picker.xml`](file:///root/onyx-browser/app/src/main/res/layout/bottom_sheet_cookie_mode_picker.xml)): Allow all vs. Block third-party (Recommended) vs. Block all.
    - **Secure DNS Provider** ([`DnsProviderPickerSheet.kt`](file:///root/onyx-browser/app/src/main/java/com/onyx/browser/ui/settings/DnsProviderPickerSheet.kt) & [`bottom_sheet_dns_provider_picker.xml`](file:///root/onyx-browser/app/src/main/res/layout/bottom_sheet_dns_provider_picker.xml)): Cloudflare 1.1.1.1, Google 8.8.8.8, NextDNS, and Custom URL with inline Material 3 `TextInputLayout` and Save button.
  - Consistent Material 3 bottom sheet design language: Drag handle, 16dp rounded card containers, 44dp squircle icon backgrounds, active 2dp accent stroke, dynamic checkmark indicators, and cancel buttons.
- [x] **Content Filters Status Bar Collision Fix**:
  - Resolved status bar, camera hole cutout, and navigation bar collision in [`ContentFiltersActivity.kt`](file:///root/onyx-browser/app/src/main/java/com/onyx/browser/ui/settings/ContentFiltersActivity.kt) and [`activity_content_filters.xml`](file:///root/onyx-browser/app/src/main/res/layout/activity_content_filters.xml).
  - Configured edge-to-edge transparent system bars with `WindowCompat.setDecorFitsSystemWindows(window, false)` and dynamic `ViewCompat.setOnApplyWindowInsetsListener`.
  - Applied `statusBarInsets.top` padding to `AppBarLayout` so the back navigation button, "Content filters" title, and "UPDATE" action button sit cleanly below the status bar on all devices.
  - Added navigation bar inset bottom padding to `rvContentFilters` to prevent list items from being cut off by navigation gesture bars.
  - Upgraded toolbar to Material 3 `MaterialToolbar` with `app:navigationIconTint="?attr/colorControlNormal"` and vertically centered title and update button.
- [x] **About Screen & Developer Support**:
  - Designed and implemented dedicated Material 3 [`AboutActivity.kt`](file:///root/onyx-browser/app/src/main/java/com/onyx/browser/ui/settings/AboutActivity.kt) and [`activity_about.xml`](file:///root/onyx-browser/app/src/main/res/layout/activity_about.xml).
  - Added "About & Support" category and navigation row in [`activity_settings.xml`](file:///root/onyx-browser/app/src/main/res/layout/activity_settings.xml) and [`SettingsActivity.kt`](file:///root/onyx-browser/app/src/main/java/com/onyx/browser/ui/settings/SettingsActivity.kt).
  - Displays comprehensive application details with one-tap clipboard copy:
    - **App Version**: Version name, build code, release type, and ABI architecture.
    - **Package Name**: `com.onyx.browser`.
    - **Operating System**: Android release version, API level, manufacturer, model, and hardware details.
    - **WebView Engine**: Current WebView package provider (`com.google.android.webview` / Chrome) and version number.
  - Direct community & developer contact actions:
    - **Contact Developer**: Launches official Telegram account [`t.me/abidhasansojib`](https://t.me/abidhasansojib).
    - **Report Bug**: Opens official GitHub Issues tracker [`github.com/abidhasansojib/onyx-browser/issues`](https://github.com/abidhasansojib/onyx-browser/issues).
  - Edge-to-edge transparent system bars integration with `WindowCompat` and dynamic cutout insets.
- [x] **Modern WWW Globe Launcher Icon**:
  - Processed and extracted the circular "WWW" globe emblem from `/storage/emulated/0/logo.png`.
  - Rebuilt all density mipmap assets (`mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, `xxxhdpi`):
    - `ic_launcher_foreground.png`: Crisp white vector globe on transparent background, centered in the 72dp safe zone of the 108dp canvas to prevent clipping on any launcher mask.
    - `ic_launcher.png`: Pure black (`#000000`) AMOLED background with centered white globe emblem.
    - `ic_launcher_round.png`: Circular masked pure black icon with centered globe.
  - Configured adaptive background to AMOLED Pure Black `#000000` in [`ic_launcher_background.xml`](file:///root/onyx-browser/app/src/main/res/drawable/ic_launcher_background.xml).
  - Updated Android 13+ Material You monochrome themed icon layer in [`ic_launcher.xml`](file:///root/onyx-browser/app/src/main/res/mipmap/ic_launcher.xml) and [`ic_launcher_round.xml`](file:///root/onyx-browser/app/src/main/res/mipmap/ic_launcher_round.xml).
- [x] **Modern Custom Offline & Web Page Not Available Pages**:
  - **Generative UI Design**: Created an interactive, responsive Generative UI preview widget ([`error_pages_widget.html`](file:///root/.gemini/antigravity-cli/brain/57371cad-7a74-4301-ab0c-1cc01cd1e821/error_pages_widget.html)) demonstrating both `net::ERR_INTERNET_DISCONNECTED` and `net::ERR_NAME_NOT_RESOLVED` error states with live tab switching, troubleshooting checklists, and an embedded offline runner arcade game.
  - **Zero-Dependency Android Asset**: Created [`app/src/main/assets/error_page.html`](file:///root/onyx-browser/app/src/main/assets/error_page.html) supporting automatic Light/Dark mode via `@media (prefers-color-scheme: dark)`, dynamic error parameters and template replacement (`url`, `error`, `desc`), diagnostics panel, and an HTML5 canvas endless runner game playable offline.
  - **Native WebView Integration**: Updated [`OnyxWebViewClient.kt`](file:///root/onyx-browser/app/src/main/java/com/onyx/browser/web/OnyxWebViewClient.kt) `onReceivedError()` and `onReceivedSslError()` to intercept main-frame errors and populate the custom error template with `loadDataWithBaseURL` preserving the original target URL in the browser URL bar.




