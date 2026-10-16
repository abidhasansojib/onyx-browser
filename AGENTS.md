# AGENTS.md — Onyx Browser AI Agent Operational Mandates & Rules

> **CRITICAL DIRECTIVE FOR ALL AI MODELS & AGENTS**:  
> Read this entire document before inspecting, modifying, or executing any task on this codebase. All rules defined herein are absolute, strictly enforced, and take precedence over default assistant behavior.

---

## 1. Overview & Technology Stack

**Onyx Browser** (`com.onyx.browser`) is a fast, lightweight, and privacy-oriented Android browser targeting Min SDK 26 (Android 8.0+) and Target SDK 35 (Android 15).

- **UI Framework**: Native Kotlin and XML Views with ViewBinding (Jetpack Compose is intentionally avoided for fast startup and minimal memory usage).
- **Engine**: Android System WebView hardened with Chromium multi-profile isolation, scriptlet injection, and network-level interception.
- **Content Blocking**: Brave `adblock-rust` engine compiled via Android NDK into `libadblock_bridge.so` across all 4 ABIs.
- **Database**: SQLCipher AES-256 encrypted Room database.

For complete subsystem specifications and technical architecture, see:
- [Architecture & Core Subsystems](docs/ARCHITECTURE.md)
- [Android WebView Engineering Guide](docs/WEBVIEW.md)
- [Features & Roadmap](docs/FEATURES.md)

---

## 2. Mandatory Operational Rules for All Agents

### RULE 1: Local Building Strictly Forbidden
- **Prohibited Operations**: Executing compilation or build commands locally on this Linux machine (including `./gradlew`, `cargo`, `rustc`, `cmake`, `ninja`, `make`, `docker build`, NDK compilation, or running Gradle daemons) is **STRICTLY FORBIDDEN**.
- **Reason**: The host system is a lightweight mobile Linux environment running inside Android with strict CPU, RAM, and thermal headroom.
- **Permitted Operations**: Lightweight CLI utilities, file editing, Python/Bash scripting, lightweight static checks, Git operations, and GitHub CLI (`gh`) are fully permitted.

### RULE 2: Explicit User Build Gate & Build Type Clarification
- **Default State**: Do **NOT** trigger GitHub Actions CI/CD workflows, compile code, or trigger remote builds during regular conversation.
- **Build Type Clarification**: When the user says **"build app now"** without specifying the build type, you **MUST ask the user** whether they want a **Release** or **Prerelease** build before triggering any workflow. Never guess or trigger prematurely.
- **Specific Build Commands**:
  - If the user specifies **"build release app"** (or chooses Release), build **Release only** (`gh workflow run build.yml -f build_type=Release`).
  - If the user specifies **"build prerelease app"** (or chooses Prerelease), build **Prerelease only** (`gh workflow run build.yml -f build_type=Prerelease`).
  - Debug builds are permanently removed and prohibited.

### RULE 3: Autonomous Remote Build & Auto-Fix Loop
When triggered for a build (after user confirms or explicitly requests Release or Prerelease):
1. **Push & Trigger**: Ensure all changes are committed and pushed to `origin main` (or active branch, e.g. `origin dev`), then trigger the workflow:
   - **Release**: `gh workflow run build.yml -f build_type=Release`
   - **Prerelease**: `gh workflow run build.yml -f build_type=Prerelease`
2. **Monitor Execution**: Track the active workflow run to completion using `schedule` timers and `gh run view <run_id>`. Do NOT poll in a busy while-loop; use the `schedule` tool.
3. **If Build Succeeds**:
   - **Release Build**:
     - Released to GitHub Releases as a full release.
     - DO NOT download artifacts to the device unless explicitly requested.
     - Report the release version tag, changelog, and GitHub release download links to the user.
   - **Prerelease Build**:
     - Released to GitHub Releases marked as a **Pre-release** (`applicationId` has `.prerelease` suffix).
     - DO NOT download artifacts to the device unless explicitly requested.
     - Report the prerelease tag, changelog, and GitHub release download links to the user.
4. **If Build Fails (CRITICAL AUTO-REPAIR REQUIREMENT)**:
   - Retrieve the failed step logs via `gh run view --log-failed --job=<job_id>`.
   - Analyze the compiler error, Kotlin syntax error, resource collision, or ProGuard/R8 exception.
   - Apply the necessary code fixes directly in the repository.
   - Commit the fix, push to `origin main`, re-trigger the workflow, and repeat until 100% successful.

### RULE 4: Mandatory Documentation Maintenance
- Whenever a feature is added, a bug is fixed, an architectural decision is made, or a workflow is completed, update this `AGENTS.md` and `docs/FEATURES.md` before concluding the turn.
- Deep subsystem details and technical architecture must be maintained in `docs/ARCHITECTURE.md`.
- WebView engineering standards must be maintained in `docs/WEBVIEW.md`.

### RULE 5: Keep Everything Simple, Minimal & Effective
- All documentation, README files, UI copy, and explanations must remain simple, minimal, and effective.
- Avoid hyperbolic AI buzzwords, repetitive explanations, and emoji clutter. Write like an experienced software engineer.

### RULE 6: Feature Tracking via `docs/FEATURES.md`
- `docs/FEATURES.md` is the single source of truth for tracking browser capabilities and verification status (`[x]` Working, `[-]` Known Issue, `[ ]` Planned).

### RULE 7: No Internal Agent Artifacts in Git
- Never commit internal agent scaffolding, plan files, design specs, or scratch directories (`docs/superpowers/`, `.superpowers/`, scratch scripts) into the repository.

### RULE 8: Golden Milestone Immutability
- AI agents are **strictly forbidden** from automatically bumping or declaring a "Golden Milestone / Role Model" reference in `AGENTS.md` or `docs/FEATURES.md` unless explicitly commanded by the user.

---

## 3. Directory Layout & Architecture Map

```text
onyx-browser/
├── .github/
│   ├── ISSUE_TEMPLATE/       # Structured YAML issue forms (bug reports & feature requests)
│   └── workflows/
│       ├── build.yml         # Rust NDK compile, Lucide sync, Gradle Release/Prerelease APKs
│       └── sync_upstream.yml # 6-hour automated Brave filter lists sync workflow
├── app/
│   ├── src/main/
│   │   ├── assets/           # easylist_rules.txt, eruda.min.js, font assets
│   │   ├── java/com/onyx/browser/
│   │   │   ├── MainActivity.kt     # SingleTask primary activity, toolbar, and window coordinator
│   │   │   ├── OnyxApplication.kt  # Startup lifecycle, SQLCipher init, Safe Browsing setup
│   │   │   ├── data/               # FilterListManager, Room DB, DAOs, BrowserPreferences
│   │   │   ├── media/              # MediaPlaybackService and MediaPlaybackBridge
│   │   │   ├── nativebridge/       # AdBlockEngine JNI bindings to libadblock_bridge.so
│   │   │   ├── ui/                 # ViewBinding presentation layer (tabs, settings, search, menu)
│   │   │   └── web/                # Hardened Chromium WebView subsystem, client, and bridges
│   │   └── res/                    # Google Theme styles (Light/Dark/AMOLED), layouts, vectors
│   └── build.gradle.kts            # App dependencies, NDK configuration, deterministic signing
├── docs/                           # Documentation assets (logo.png), FEATURES.md, ARCHITECTURE.md, WEBVIEW.md
├── rust_engine/                    # Native Rust NDK Bridge (libadblock_bridge.so)
└── scripts/                        # Icon and filter list synchronization automation
```

---

## 4. Architectural & Coding Mandates for AI Agents

1. **Native Views Over Compose**:
   - Strictly write native Android XML Views and ViewBinding.
   - Do NOT introduce Jetpack Compose dependencies or `@Composable` components.
2. **Resource & Color Consistency**:
   - Always pair color resources between `res/values/colors.xml` and `res/values-night/colors.xml`.
   - Use theme attributes (`?attr/colorControlNormal`, `?attr/colorPrimary`) for icon tints and surface backgrounds.
3. **Database & Persistence Security**:
   - All persistent Room database operations must route through SQLCipher AES-256 encryption via `OnyxDatabase`.
   - Never write sensitive browsing records or authentication data into plain SQLite or unencrypted SharedPreferences.
4. **Lifecycle & Coroutine Safety**:
   - Always bind UI-related coroutines to `viewLifecycleOwner.lifecycleScope` with `repeatOnLifecycle`.
   - Database writes and critical cleanup actions must use `NonCancellable` context to prevent corruption on sudden dismissals.
5. **Git Operations & Autonomy**:
   - Full permission granted to use `git` and `gh` to stage, commit, push, and monitor CI/CD workflows.
   - Keep commit messages concise and conventional (e.g. `feat(adblock): ...`, `fix(tabs): ...`, `docs: ...`).
6. **Rolling Odometer Versioning Scheme**:
   - `MAJOR.MINOR.PATCH` increments automatically from GitHub Actions run counter (`RUN_NUM`):
     - `MAJOR = 1 + (RUN_NUM / 1000)`
     - `MINOR = (RUN_NUM / 100) % 10`
     - `PATCH = RUN_NUM % 100`
   - Patch increments 0..99, rolling over to Minor at 100. Minor increments 0..9, rolling over to Major after 9 (e.g., `1.0.99` -> `1.1.0`, `1.9.99` -> `2.0.0`).
7. **Authentication & WebAuthn Bridge Compatibility**:
   - WebAuthn polyfills (`PasskeyWebAuthnBridge`) must be injected on all login domains (e.g., GitHub, Google, Microsoft) and omitted strictly on Meta's internal challenge frames (`fbsbx.com`).
   - `_OnyxPasskeyBridge` must never be deleted from the window or overwritten with `undefined` on login pages.
   - `navigator.userAgentData` must polyfill authentic Chrome/Chromium brands without leaking `"Android WebView"` to ensure Google Sign-In and OAuth flows succeed without `403 disallowed_useragent` blocks.
8. **Adblock Engine & Network Interception Standards**:
   - `adblock-rust` rule parsing in `rust_engine/src/lib.rs` must include full `PermissionMask::from_bits(u8::MAX)` permissions so privileged scriptlets (`trusted-set-cookie`, `trusted-replace-fetch-response`, etc.) are compiled and injected.
   - Resource type classification in `OnyxWebViewClient` and `AdBlockServiceWorkerHelper` must use case-insensitive header lookups (`Accept`, `Sec-Fetch-Dest`, `Sec-Fetch-Mode`, `Ping-To`) to support HTTP/2 and HTTP/3 multiplexed requests.
   - Dual-pass checking must be performed for CNAME uncloaked requests: test uncloaked destination first, and if allowed, test the original alias to catch rules explicitly targeting cloaked first-party domains.
   - `$csp` directives from `AdBlockEngine.getCspDirectives` must be injected early at document start via `<meta http-equiv="Content-Security-Policy">`.
   - Never block authentication endpoints (Google Sign-In, Meta, Microsoft, Apple), bot challenges (Cloudflare Turnstile, Arkose, reCAPTCHA, hCaptcha), or media streaming segments.

