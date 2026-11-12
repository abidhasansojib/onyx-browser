## Summary

<!-- Briefly describe what this PR changes and why. -->

## Subsystem Affected

- [ ] Ad-Blocking & Rust NDK Bridge (`rust_engine/`, `AdBlockEngine`, `AdBlockRequestInterceptor`)
- [ ] WebView Engine & Bridges (`OnyxWebView`, `OnyxWebViewClient`, `ExternalNavigationHandler`, `PasskeyWebAuthnBridge`)
- [ ] Media Playback & Picture-in-Picture (`MediaPlaybackService`, `MediaPlaybackBridge`, `PipController`)
- [ ] UI, Coordinators & ViewModels (`MainActivity`, `BrowserMenuCoordinator`, `ToolbarLayoutDelegate`, `ui/viewmodel/`)
- [ ] Data, SQLCipher Room & Preferences (`OnyxDatabase`, `SitePermissionRepository`, `data/preferences/`)
- [ ] UserScripts (`userscript/`, `OnyxUserScriptBridge`)
- [ ] Build System, Scripts & Documentation (`gradle/libs.versions.toml`, `scripts/`, `docs/`)

## Testing Done

- [ ] Unit tests pass (`./gradlew :core:common:test testReleaseUnitTest --no-daemon`)
- [ ] Added or updated unit tests under `app/src/test/` or `core/common/src/test/`
- [ ] Verified on Android device / emulator (specify API level: `___`)

## Privacy & Security Checklist

- [ ] No sensitive browsing or authentication data is written to unencrypted storage (all Room tables route through SQLCipher `OnyxDatabase`).
- [ ] `@JavascriptInterface` methods do not access `WebView` properties off the Main UI thread.
- [ ] Color resources are paired across `res/values/colors.xml` and `res/values-night/colors.xml`.
- [ ] Documentation (`AGENTS.md`, `docs/ARCHITECTURE.md`, `docs/FEATURES.md`) updated if architecture or features changed.
