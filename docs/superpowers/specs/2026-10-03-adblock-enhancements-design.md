# Specification: Adblocking Subsystem & Engine Enhancements

- **Date:** 2026-10-03
- **Status:** Proposed / Under Review
- **Target Version:** v1.0.211+

---

## 1. Overview & Objectives

This specification details architectural enhancements across Onyx Browser's adblocking stack:
1. **Engine & JNI Optimizations**: Eliminate redundant JNI boundary transitions and thread lock contention, passing primitive integer resource types and utilizing lock-free atomic engine pointer reads.
2. **Rule Execution & Engine Capabilities**: Support Brave/uBlock `$redirect` surrogate scripts with HTTP 200 OK stubs to prevent site crashes, execute procedural cosmetic filters (`:has()`, `:has-text()`, `:upward()`), and uncloak first-party CNAME tracking aliases.
3. **DOM & Anti-Adblock Defusers**: Implement client-side query deduplication to prevent repetitive bridge calls on dynamic single-page applications and extend honeypot defusers to cover `getBoundingClientRect()`.
4. **Background Sync & User Tools**: Add silent daily background filter updates via AndroidX WorkManager and provide an interactive in-page visual element blocker ("Zapper") for custom cosmetic rule creation.

---

## 2. Architecture & Data Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          1. Presentation & UI Layer                         │
│  [ Browser Overflow Menu ] ──> "Block Element" (Visual Zapper Mode)         │
│  [ OnyxShieldBridge ] <──────> In-Page JS Picker Overlay & Rule Storage     │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                    2. WebView & Script Injection Layer                      │
│                                                                             │
│  AdBlockDocumentStart.kt:                                                   │
│   ├── Procedural Filter Runner (:has, :has-text, :remove, :remove-class)   │
│   ├── deduplicated Set<String> avoids querying already-checked identifiers │
│   └── honeypot defuser: offsetHeight/Width + getBoundingClientRect()       │
│                                                                             │
│  OnyxWebViewClient.kt:                                                      │
│   ├── Fast-path in-memory check: AdBlockDomainManager (Kotlin memory FIRST) │
│   ├── Static CNAME Uncloaking Map: resolves first-party tracking aliases   │
│   └── Surrogate script handler: serves $redirect payload with HTTP 200 OK   │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ JNI checkUrl(url, sourceUrl, resTypeInt)
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                   3. Native Rust Engine (libadblock_bridge)                 │
│                                                                             │
│  rust_engine/src/lib.rs:                                                    │
│   ├── Lockless Concurrent Reads: arc_swap::ArcSwapOption<Engine>           │
│   ├── Integer Request Type Mapping (0..7) without UTF-8 String allocations  │
│   ├── BlockerResult::redirect extraction (data URI / script payload)       │
│   └── Procedural actions export in getCosmeticResources JSON               │
└─────────────────────────────────────────────────────────────────────────────┘
                                       ▲
┌──────────────────────────────────────┴──────────────────────────────────────┐
│                     4. AndroidX WorkManager Background Layer                │
│  FilterUpdateWorker: CoroutineWorker (every 24h on Wi-Fi + battery not low) │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Detailed Component Specifications

### 3.1. Section 1: Engine & JNI Bridge Optimizations

#### A. Fast Kotlin-Layer Short-Circuiting
- **Current Behavior**: `OnyxWebViewClient.shouldInterceptRequest` executes `AdBlockEngine.shouldBlock(url, pageUrl, resourceType)` *before* checking `AdBlockDomainManager.isBlockedInStandard(reqDomain)`. Every subresource crosses the JNI boundary to Rust.
- **Enhanced Design**:
  In `OnyxWebViewClient.kt` line 508:
  ```kotlin
  // 1. Fast in-memory Kotlin check: known trackers short-circuit in <0.05µs
  val blockedByStandard = AdBlockDomainManager.isBlockedInStandard(reqDomain)
  val blockedByAggressive = isAggressive && AdBlockDomainManager.isBlockedInAggressive(reqDomain)
  val blockedByDomain = blockedByStandard || blockedByAggressive

  // 2. Native Rust engine query only executed if domain is not already known
  val engineResult = if (!blockedByDomain && !isAdTestResource) {
      AdBlockEngine.checkRequest(url, pageUrl, resourceTypeInt)
  } else null

  val isBlocked = blockedByDomain || isAdTestResource || (engineResult?.shouldBlock == true)
  ```
- **Benefit**: Resolves 70%+ of network tracker requests directly in Kotlin memory without JNI overhead.

#### B. Integer Resource Types Across JNI
- **Constants Definition** in `AdBlockEngine.kt`:
  ```kotlin
  const val RESOURCE_TYPE_OTHER = 0
  const val RESOURCE_TYPE_SCRIPT = 1
  const val RESOURCE_TYPE_IMAGE = 2
  const val RESOURCE_TYPE_STYLESHEET = 3
  const val RESOURCE_TYPE_SUB_FRAME = 4
  const val RESOURCE_TYPE_XHR = 5
  const val RESOURCE_TYPE_MEDIA = 6
  const val RESOURCE_TYPE_MAIN_FRAME = 7
  ```
- **Helper Method** in `OnyxWebViewClient.kt`:
  ```kotlin
  private fun detectResourceTypeInt(request: WebResourceRequest): Int = when (detectResourceType(request)) {
      "script" -> AdBlockEngine.RESOURCE_TYPE_SCRIPT
      "image" -> AdBlockEngine.RESOURCE_TYPE_IMAGE
      "stylesheet" -> AdBlockEngine.RESOURCE_TYPE_STYLESHEET
      "sub_frame" -> AdBlockEngine.RESOURCE_TYPE_SUB_FRAME
      "xhr" -> AdBlockEngine.RESOURCE_TYPE_XHR
      "media" -> AdBlockEngine.RESOURCE_TYPE_MEDIA
      "main_frame" -> AdBlockEngine.RESOURCE_TYPE_MAIN_FRAME
      else -> AdBlockEngine.RESOURCE_TYPE_OTHER
  }
  ```
- **Rust NDK Implementation** in `rust_engine/src/lib.rs`:
  ```rust
  #[no_mangle]
  pub extern "system" fn Java_com_onyx_browser_nativebridge_AdBlockEngine_checkRequestNative(
      mut env: JNIEnv,
      _class: JClass,
      url: JString,
      source_url: JString,
      resource_type: jint,
  ) -> jstring { ... }
  ```
  Maps `resource_type` directly to a string literal slice (`match resource_type { 1 => "script", ... }`) without calling `env.get_string()` for the request type.

#### C. Lockless Atomic Reads via `arc-swap`
- **Dependency**: Add `arc-swap = "1.7"` to `rust_engine/Cargo.toml`.
- **Rust Engine Storage**:
  ```rust
  use arc_swap::ArcSwapOption;
  use std::sync::Arc;

  static ENGINE: ArcSwapOption<Engine> = ArcSwapOption::const_empty();
  ```
- **Read Operations**:
  ```rust
  let engine_guard = ENGINE.load();
  if let Some(ref engine) = *engine_guard {
      // Direct lock-free read access across arbitrary parallel threads
  }
  ```
- **Write Operations (Initialization / Recompilation)**:
  ```rust
  ENGINE.store(Some(Arc::new(engine)));
  ```
  Eliminates `RwLock` reader contention across concurrent WebView worker threads.

---

### 3.2. Section 2: Adblocking Engine & Rule Capabilities

#### A. Surrogate Script & `$redirect` Rule Support
- **Rust Engine Output**:
  When `engine.check_network_request(&request)` matches a rule with `$redirect` or `$redirect-rule`, `blocker_result.redirect` contains the surrogate body / data URI (e.g. `data:application/javascript;base64,...`).
  `checkRequestNative` returns:
  - `null`: Request allowed.
  - `"blocked"`: Blocked with default HTTP 403 or empty stub.
  - `"redirect:<payload>"`: Matched surrogate script / redirect.
- **Kotlin Integration**:
  In `OnyxWebViewClient.kt`, when `engineResult.redirectData` is present:
  Serve an HTTP 200 OK `WebResourceResponse` with permissive CORS headers and the decoded surrogate script or placeholder, keeping e-commerce checkouts and analytics-dependent sites functional without tracking.

#### B. Procedural Cosmetic Filtering
- **Rust Export**:
  In `getCosmeticResources(url)` in `rust_engine/src/lib.rs`:
  ```rust
  let procedural: Vec<String> = resources.procedural_actions.into_iter().collect();
  ```
  Exposed in the JSON response as `"procedural": [...]`.
- **In-Page Procedural Runner** in `AdBlockDocumentStart.kt`:
  Injected at `document_start` and triggered on `DOMContentLoaded` and `MutationObserver` mutations:
  - Supports `CssSelector`, `HasText`, `Has`, and actions: `Remove` (hides/removes container), `RemoveClass` (strips overlay classes), `RemoveAttr` (removes style attributes).
  - Enables uBlock Origin / Brave parity for dynamic sponsored containers on complex web apps (Reddit, Twitter, Facebook).

#### C. Static CNAME Uncloaking
- **Data Source**: Bundled first-party tracking aliases from Brave's `brave-firstparty-cname.txt`.
- **Implementation**:
  Maintain a lookup map in `AdBlockDomainManager.kt`:
  ```kotlin
  private val cnameAliasMap: Map<String, String> = mapOf(...)
  fun uncloakDomain(domain: String): String = cnameAliasMap[domain] ?: domain
  ```
  Before evaluating blocklists, `uncloakDomain(reqDomain)` transforms `metrics.store.com` to `cname.branch.io`, preventing first-party cloaking evasions.

---

### 3.3. Section 3: DOM & Anti-Adblock Defuser Improvements

#### A. Client-Side Selector Deduplication (JS Layer)
- In `AdBlockDocumentStart.kt`:
  ```javascript
  var checkedIdentifiers = new Set();
  ```
- When `inspectElement(el)` processes IDs and classes:
  - Skips any ID or class already present in `checkedIdentifiers`.
  - Upon flushing via `OnyxShieldBridge.getHiddenSelectors()`, all newly queried IDs and classes are marked in `checkedIdentifiers`.
- Avoids re-querying unchanged class names during infinite scrolling on YouTube, Reddit, and Twitter.

#### B. Extended Honeypot Defuser (`getBoundingClientRect`)
- In `AdBlockDocumentStart.kt`:
  ```javascript
  var origGetBCR = Element.prototype.getBoundingClientRect;
  if (origGetBCR) {
      Element.prototype.getBoundingClientRect = makeNative(function() {
          var rect = origGetBCR.call(this);
          if (rect.width === 0 && rect.height === 0 && isBait(this)) {
              return {
                  width: 300,
                  height: 250,
                  top: 0,
                  left: 0,
                  right: 300,
                  bottom: 250,
                  x: 0,
                  y: 0,
                  toJSON: function() { return this; }
              };
          }
          return rect;
      }, 'getBoundingClientRect');
  }
  ```
  Prevents modern anti-adblock scripts from detecting blocked ad containers via bounding box inspections.

---

### 3.4. Section 4: Background Sync & User Tools

#### A. Periodic Filter Updates via AndroidX WorkManager
- **Dependency**: Add `androidx.work:work-runtime-ktx:2.10.0` to `app/build.gradle.kts`.
- **Worker Class**: `FilterUpdateWorker` extending `CoroutineWorker`:
  ```kotlin
  class FilterUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
      override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
          try {
              FilterListManager.checkAndAutoUpdateFilters(applicationContext, force = true)
              Result.success()
          } catch (e: Exception) {
              Result.retry()
          }
      }
  }
  ```
- **Scheduler**: Registered in `OnyxApplication.onCreate()`:
  - Periodic work interval: 24 hours.
  - Constraints: `NetworkType.UNMETERED` (Wi-Fi only) and `setRequiresBatteryNotLow(true)`.

#### B. In-Page Visual Element Blocker (Zapper / Picker)
- **Menu Entry**: "Block Element" option in the browser main overflow menu / context sheet.
- **Overlay Controller**: `ElementPickerManager.kt`:
  - Injects a lightweight interactive selection script into the active WebView.
  - Features:
    - Hover / touch outline in translucent red (`#33FF0000` fill, `2px solid #FF0000` stroke).
    - Sticky floating toolbar at bottom: "Tap an element to block it" with "Cancel" and "Block" buttons.
    - Generates the most specific CSS selector (ID > unique classes > tag hierarchy).
    - Calls `OnyxShieldBridge.saveCustomCosmeticRule(domain, selector)`.
  - Android side:
    - Appends the rule to `BrowserPreferences.customFilterRules`.
    - Injects an inline style rule into the current page immediately.
    - Displays confirmation Toast: "Element blocked. Added rule to custom filters."

---

## 4. Modified Files & Components

| File Path | Description of Changes |
| :--- | :--- |
| `rust_engine/Cargo.toml` | Add `arc-swap = "1.7"` dependency. |
| `rust_engine/src/lib.rs` | Implement `ArcSwapOption`, integer resource type mapping, surrogate `$redirect` extraction, and `procedural_actions` serialization. |
| `app/build.gradle.kts` | Add `androidx.work:work-runtime-ktx:2.10.0`. |
| `app/src/main/java/com/onyx/browser/nativebridge/AdBlockEngine.kt` | Add integer resource type constants, `checkRequest` JNI bridge, and redirect handling. |
| `app/src/main/java/com/onyx/browser/web/AdBlockDomainManager.kt` | Integrate static CNAME uncloaking map. |
| `app/src/main/java/com/onyx/browser/web/AdBlockDocumentStart.kt` | Implement procedural filter runner, selector query deduplication Set, and `getBoundingClientRect` defuser. |
| `app/src/main/java/com/onyx/browser/web/OnyxWebViewClient.kt` | Short-circuit domain checks in Kotlin, detect integer resource types, and handle surrogate redirects. |
| `app/src/main/java/com/onyx/browser/web/OnyxShieldBridge.kt` | Add bridge methods for procedural filters and custom rule persistence from element picker. |
| `app/src/main/java/com/onyx/browser/data/filter/FilterUpdateWorker.kt` | Background worker for WorkManager daily updates. |
| `app/src/main/java/com/onyx/browser/OnyxApplication.kt` | Enqueue WorkManager periodic filter update request. |
| `app/src/main/java/com/onyx/browser/ui/menu/ElementPickerManager.kt` | Interactive touch/click DOM element highlighter and selector generator. |
| `app/src/main/java/com/onyx/browser/MainActivity.kt` | Wire "Block Element" menu action to active tab. |

---

## 5. Verification & Testing Plan

1. **Native Rust Compilation**: Verify `adblock_bridge` builds cleanly with `arc-swap` and integer request mapping across all 4 ABIs in CI.
2. **Short-Circuit Performance**: Benchmark request evaluation latency before and after Kotlin domain short-circuiting.
3. **Surrogate Redirects**: Test against test suites and sites with Google Analytics stubs to verify pages do not throw `TypeError: ga is not a function`.
4. **Procedural Filters**: Validate `:has()` and `:has-text()` element hiding on dynamic feeds (Reddit, Twitter, news articles).
5. **Anti-Adblock Defusers**: Run `adblock-tester.com` and `superadblocktest.com` to confirm 100% scores without honeypot detection.
6. **WorkManager Periodic Execution**: Verify WorkManager enqueues unique work without crashing on boot or process start.
7. **Element Picker**: Test picking elements on diverse web pages (nested divs, images, banners), verifying immediate element collapse and persistence across reloads.
