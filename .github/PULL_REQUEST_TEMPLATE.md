## Summary

<!-- Briefly describe what this PR changes and why. -->

## Subsystem Affected

- [ ] Ad-Blocking & Rust NDK Bridge (`rust_engine/`, `AdBlockEngine`, `AdBlockRequestInterceptor`)
- [ ] WebView Engine & Bridges (`BrowserWebView`, `BrowserWebViewClient`, `ExternalNavigationHandler`, `PasskeyWebAuthnBridge`)
- [ ] Media Playback & Picture-in-Picture (`MediaPlaybackService`, `MediaPlaybackBridge`, `PipController`)
- [ ] UI, Coordinators & ViewModels (`MainActivity`, `BrowserMenuCoordinator`, `ToolbarLayoutController`, `ui/viewmodel/`)
- [ ] Data, SQLCipher Room & Preferences (`AppDatabase`, `SitePermissionRepository`, `data/preferences/`)
- [ ] UserScripts (`userscript/`, `UserScriptBridge`, `assets/scripts/userscript_wrapper.js`)
- [ ] Updates & Background Scheduling (`update/`, `UpdateScheduler`, `ReleaseFeedParser`)
- [ ] Build System, Scripts & Documentation (`gradle/libs.versions.toml`, `scripts/`, `docs/`)

## Testing Done

- [ ] Unit tests pass (`./gradlew :core:common:test testReleaseUnitTest --no-daemon`)
- [ ] Added or updated unit tests under `app/src/test/` or `core/common/src/test/`
- [ ] Verified on Android device / emulator (API level: `___`)

## Privacy & Security Checklist

- [ ] No sensitive browsing or authentication data is written to unencrypted storage (all Room tables route through SQLCipher `AppDatabase`).
- [ ] `@JavascriptInterface` methods do not access `WebView` properties off the Main UI thread.
- [ ] Color resources are paired across `res/values/colors.xml` and `res/values-night/colors.xml`.
- [ ] Documentation (`AGENTS.md`, `docs/ARCHITECTURE.md`, `docs/FEATURES.md`) updated if architecture or features changed.
