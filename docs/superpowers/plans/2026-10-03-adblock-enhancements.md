# Adblocking Subsystem & Engine Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement high-performance engine, network interception, cosmetic DOM filtering, background sync, and visual element picker improvements across Onyx Browser's adblocking stack.

**Architecture:** Modernize the native Rust NDK bridge with lock-free atomic reads (`arc-swap`), integer request type mapping, and surrogate redirect extraction; short-circuit network checks in Kotlin memory with static CNAME uncloaking; execute procedural cosmetic rules (`:has()`, `:has-text()`) and honeypot defusers in JavaScript; add background sync via WorkManager and an interactive in-page visual element blocker.

**Tech Stack:** Rust (NDK, `adblock-rust`, `arc-swap`, `serde_json`), Kotlin (Android WebView, ViewBinding, Coroutines, StateFlow), AndroidX WorkManager, JavaScript (Chromium DOM & MutationObserver).

**Spec:** [docs/superpowers/specs/2026-10-03-adblock-enhancements-design.md](file:///root/onyx-browser/docs/superpowers/specs/2026-10-03-adblock-enhancements-design.md)

## Global Constraints

- **No Local Compilation**: Local `./gradlew`, `cargo`, `rustc`, or NDK builds are strictly prohibited on this mobile agent environment.
- **Maintain Verification Milestones**: Preserve canonical stability milestones from `v1.0.210` (no Turnstile CAPTCHA loops, background media playback, Facebook OAuth).
- **Clean Semantic Commits**: Every task must end with a clean, semantic git commit (`feat(...)` or `perf(...)`).
- **Clickable Markdown Links**: All file references in reviews and documentation must use the `file://` scheme.

## Review Focus

1. **CAPTCHA & Auth Safety**: Verify Cloudflare Turnstile, reCAPTCHA, and Meta login flows are never matched by CNAME uncloaking or procedural filters.
2. **Surrogate Script Fallback**: Verify `$redirect` handling cleanly decodes data URIs and falls back to safe empty stubs if corrupted.
3. **Media Streaming Exemption**: Verify audio/video streams (HLS/DASH) continue bypassing domain-level blocklists without regression.
4. **Picker Injection Cleanup**: Verify the visual element picker completely tears down its DOM overlay and event listeners on cancel or completion.
5. **WorkManager Initialization**: Verify `WorkManager` does not trigger blocking I/O on the main thread during `OnyxApplication.onCreate()`.

---

### Task 1: Rust Engine Optimizations & JNI Expansion

**Files:**
- Modify: `rust_engine/Cargo.toml`
- Modify: `rust_engine/src/lib.rs`

- [ ] **Step 1: Update Cargo.toml dependencies**
  Add `arc-swap = "1.7"` to `[dependencies]` in `rust_engine/Cargo.toml`.
- [ ] **Step 2: Replace RwLock with ArcSwapOption**
  In `rust_engine/src/lib.rs`, replace `static ENGINE: RwLock<Option<Engine>>` with `static ENGINE: ArcSwapOption<Engine> = ArcSwapOption::const_empty();`. Update `initEngine`, `initFromRules`, and `loadResources` to store `Arc::new(engine)` atomically via `ENGINE.store()`.
- [ ] **Step 3: Implement integer resource type mapping in checkRequest**
  In `rust_engine/src/lib.rs`, replace string `resource_type: JString` with `resource_type: jint`. Map integer values (0..7) directly to static string slices (`"script"`, `"image"`, `"stylesheet"`, `"sub_frame"`, `"xhr"`, `"media"`, `"main_frame"`, `"other"`).
- [ ] **Step 4: Extract surrogate redirect payload**
  Inspect `blocker_result.redirect`. If present, return `env.new_string(format!("redirect:{}", redirect))`; if `blocker_result.should_block()`, return `env.new_string("blocked")`; otherwise return null pointer `ptr::null_mut()`.
- [ ] **Step 5: Export procedural_actions in getCosmeticResources**
  In `getCosmeticResources`, extract `resources.procedural_actions` as a JSON array of strings and include `"procedural": [...]` in the returned JSON object.
- [ ] **Step 6: Commit changes**
  Commit Task 1 with `git commit -m "perf(native): add arc-swap lockless engine, integer request types, and redirect extraction"`.

---

### Task 2: Native Bridge & Engine Layer in Kotlin

**Files:**
- Modify: `app/src/main/java/com/onyx/browser/nativebridge/AdBlockEngine.kt`

- [ ] **Step 1: Define resource type constants**
  Define `RESOURCE_TYPE_OTHER = 0` through `RESOURCE_TYPE_MAIN_FRAME = 7` in `AdBlockEngine.kt`.
- [ ] **Step 2: Create RequestResult data class**
  Define `data class RequestResult(val shouldBlock: Boolean, val redirectData: String? = null)`.
- [ ] **Step 3: Add checkRequest external JNI binding**
  Declare `external fun checkRequestNative(url: String, sourceUrl: String, resourceType: Int): String?` and implement public `fun checkRequest(url: String, sourceUrl: String, resourceType: Int): RequestResult`.
- [ ] **Step 4: Update getCosmeticResources to extract procedural rules**
  Add `fun getProceduralRules(url: String): List<String>` in `AdBlockEngine.kt`, parsing the `"procedural"` JSON array from `getCosmeticResources`.
- [ ] **Step 5: Commit changes**
  Commit Task 2 with `git commit -m "feat(adblock): bind checkRequestNative and procedural rules in AdBlockEngine"`.

---

### Task 3: Fast Short-Circuiting, Static CNAME Uncloaking & Surrogate Redirects

**Files:**
- Modify: `app/src/main/java/com/onyx/browser/web/AdBlockDomainManager.kt`
- Modify: `app/src/main/java/com/onyx/browser/web/OnyxWebViewClient.kt`
- Modify: `app/src/main/java/com/onyx/browser/web/AdBlockServiceWorkerHelper.kt`

- [ ] **Step 1: Add CNAME uncloaking table in AdBlockDomainManager**
  Define `cnameAliasMap: Map<String, String>` containing known first-party cloaked tracker subdomains (Branch, Adobe Omniture, Criteo, DataUnlocker, Eulerian, AppsFlyer) and `fun uncloakDomain(domain: String): String`.
- [ ] **Step 2: Short-circuit domain checks in OnyxWebViewClient**
  In `OnyxWebViewClient.shouldInterceptRequest`:
  - Detect integer resource type with `detectResourceTypeInt(request)`.
  - Uncloak domain via `AdBlockDomainManager.uncloakDomain(reqDomain)`.
  - Evaluate `AdBlockDomainManager.isBlockedInStandard` and aggressive mode in Kotlin memory *before* calling JNI.
- [ ] **Step 3: Handle surrogate redirect responses**
  When `engineResult.redirectData` starts with `"data:"` or contains script content, construct an HTTP 200 OK `WebResourceResponse` with `application/javascript`, CORS headers, and the decoded byte stream.
- [ ] **Step 4: Update AdBlockServiceWorkerHelper**
  Use `detectResourceTypeInt`, `uncloakDomain`, and short-circuit domain checking in `AdBlockServiceWorkerHelper.shouldInterceptRequest`.
- [ ] **Step 5: Commit changes**
  Commit Task 3 with `git commit -m "feat(web): add fast-path domain short-circuiting, CNAME uncloaking, and surrogate redirects"`.

---

### Task 4: DOM Scriptlets, Procedural Filtering & Honeypot Defusers

**Files:**
- Modify: `app/src/main/java/com/onyx/browser/web/AdBlockDocumentStart.kt`
- Modify: `app/src/main/java/com/onyx/browser/web/OnyxShieldBridge.kt`

- [ ] **Step 1: Implement client-side query deduplication in JS**
  In `AdBlockDocumentStart.kt`, declare `var checkedIdentifiers = new Set();`. Skip checking and flushing IDs and classes that are already members of `checkedIdentifiers`.
- [ ] **Step 2: Monkey-patch getBoundingClientRect on bait elements**
  Defuse `Element.prototype.getBoundingClientRect` when `isBait(this)` and width/height are 0, returning `{ width: 300, height: 250, top: 0, left: 0, bottom: 250, right: 300 }`.
- [ ] **Step 3: Add procedural action runner in JS**
  Implement `runProceduralAction(actionObj)` in `AdBlockDocumentStart.kt` supporting `HasText`, `Has`, `CssSelector`, and actions (`Remove`, `RemoveClass`, `RemoveAttr`).
- [ ] **Step 4: Expose procedural rules through OnyxShieldBridge**
  Add `@JavascriptInterface fun getProceduralActions(pageUrl: String?): String` in `OnyxShieldBridge.kt`, querying `AdBlockEngine.getProceduralRules`.
- [ ] **Step 5: Commit changes**
  Commit Task 4 with `git commit -m "feat(scriptlets): add procedural cosmetic filter runner and getBoundingClientRect defuser"`.

---

### Task 5: Background Sync via AndroidX WorkManager

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/onyx/browser/data/filter/FilterUpdateWorker.kt`
- Modify: `app/src/main/java/com/onyx/browser/OnyxApplication.kt`

- [ ] **Step 1: Add work-runtime-ktx dependency**
  Add `implementation("androidx.work:work-runtime-ktx:2.10.0")` to `app/build.gradle.kts`.
- [ ] **Step 2: Implement FilterUpdateWorker**
  Create `FilterUpdateWorker.kt` extending `CoroutineWorker`, running `FilterListManager.checkAndAutoUpdateFilters(applicationContext, force = true)` on `Dispatchers.IO`.
- [ ] **Step 3: Schedule periodic work in OnyxApplication**
  In `OnyxApplication.onCreate()`, enqueue unique periodic work `filter_auto_update` with `ExistingPeriodicWorkPolicy.KEEP`, 24h interval, `NetworkType.UNMETERED`, and `batteryNotLow` constraints.
- [ ] **Step 4: Commit changes**
  Commit Task 5 with `git commit -m "feat(filter): schedule daily silent filter updates with AndroidX WorkManager"`.

---

### Task 6: In-Page Visual Element Blocker (Zapper / Picker)

**Files:**
- Create: `app/src/main/java/com/onyx/browser/ui/menu/ElementPickerManager.kt`
- Modify: `app/src/main/java/com/onyx/browser/web/OnyxShieldBridge.kt`
- Modify: `app/src/main/java/com/onyx/browser/MainActivity.kt`
- Modify: `app/src/main/res/layout/layout_context_menu.xml` or menu layout

- [ ] **Step 1: Implement ElementPickerManager script generator**
  Create `ElementPickerManager.kt` that generates an interactive overlay script with hover box (`outline: 2px solid red`, `background: rgba(255,0,0,0.2)`), floating bottom bar with "Tap element to block" and Cancel button, and optimal CSS selector calculation.
- [ ] **Step 2: Add saveCustomCosmeticRule in OnyxShieldBridge**
  Add `@JavascriptInterface fun saveCustomCosmeticRule(domain: String?, selector: String?)` in `OnyxShieldBridge.kt`, appending `domain##selector` to `BrowserPreferences.customFilterRules` and notifying `FilterListManager.recompileFilters`.
- [ ] **Step 3: Wire Block Element option in MainActivity menu**
  Add "Block element" menu item in the browser overflow/page menu that triggers `ElementPickerManager.startPicker(activeWebView)`.
- [ ] **Step 4: Commit changes**
  Commit Task 6 with `git commit -m "feat(ui): add interactive in-page visual element blocker zapper"`.

---

### Task 7: Documentation & Feature Tracking Updates

**Files:**
- Modify: `AGENTS.md`
- Modify: `FEATURES.md`

- [ ] **Step 1: Update AGENTS.md**
  Document the v1.0.211 enhancements in the milestones section and update the core subsystem guidelines for integer request types and surrogate redirects.
- [ ] **Step 2: Update FEATURES.md**
  Update feature list to mark procedural filtering, CNAME uncloaking, surrogate script injection, and visual element picker as implemented `[x]`.
- [ ] **Step 3: Final check and commit**
  Commit Task 7 with `git commit -m "docs: update AGENTS.md and FEATURES.md for adblock subsystem enhancements"`.
