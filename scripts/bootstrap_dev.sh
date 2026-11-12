#!/usr/bin/env bash
# ==============================================================================
# Script: scripts/bootstrap_dev.sh
# Purpose: One-command local workstation setup for Onyx Browser contributors.
#          Initializes Git submodules, applies patches idempotently, verifies
#          symlinks, syncs Lucide icons & Brave filter lists, and optionally
#          compiles the native Rust NDK bridge (libadblock_bridge.so).
#
# Usage:
#   ./scripts/bootstrap_dev.sh [--skip-rust]
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SKIP_RUST=false

for arg in "$@"; do
    case "$arg" in
        --skip-rust)
            SKIP_RUST=true
            ;;
        -h|--help)
            echo "Usage: ./scripts/bootstrap_dev.sh [--skip-rust]"
            echo ""
            echo "Options:"
            echo "  --skip-rust   Skip compiling the native Rust NDK bridge (libadblock_bridge.so)"
            echo "  -h, --help    Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $arg"
            echo "Run './scripts/bootstrap_dev.sh --help' for usage."
            exit 1
            ;;
    esac
done

cd "${ROOT_DIR}"

echo "=== 1. Initializing & Updating Git Submodules ==="
git submodule update --init --recursive

echo "=== 2. Applying Upstream Patches (Idempotent) ==="
if [ -f "${ROOT_DIR}/patches/adblock-rust.patch" ] && [ -d "${ROOT_DIR}/external/adblock-rust" ]; then
    git -C external/adblock-rust apply --check ../../patches/adblock-rust.patch 2>/dev/null \
        && git -C external/adblock-rust apply ../../patches/adblock-rust.patch \
        && echo "  Applied patches/adblock-rust.patch" \
        || echo "  Patch already applied"
else
    echo "  Notice: patches/adblock-rust.patch or external/adblock-rust not found; skipping patch step."
fi

echo "=== 3. Verifying Symlinks & Assets ==="
mkdir -p "${ROOT_DIR}/rust_engine"
mkdir -p "${ROOT_DIR}/app/src/main/assets"

ln -sfn "../external/adblock-rust" "${ROOT_DIR}/rust_engine/adblock-rust"
echo "  Verified symlink: rust_engine/adblock-rust -> ../external/adblock-rust"

if [ -d "${ROOT_DIR}/external/adblock-lists/brave-lists" ]; then
    rm -rf "${ROOT_DIR}/app/src/main/assets/brave-lists"
    ln -sfn "../../../../external/adblock-lists/brave-lists" "${ROOT_DIR}/app/src/main/assets/brave-lists"
    echo "  Verified symlink: app/src/main/assets/brave-lists -> ../../../../external/adblock-lists/brave-lists"
fi

if [ -f "${ROOT_DIR}/external/adblock-rust/data/brave/brave-resources.json" ]; then
    cp "${ROOT_DIR}/external/adblock-rust/data/brave/brave-resources.json" "${ROOT_DIR}/app/src/main/assets/brave-resources.json"
    echo "  Synced app/src/main/assets/brave-resources.json"
fi

echo "=== 4. Fetching & Converting Official Lucide Icons ==="
chmod +x "${ROOT_DIR}/scripts/fetch_icons.sh"
"${ROOT_DIR}/scripts/fetch_icons.sh"

echo "=== 5. Synchronizing Brave & Community Filter Lists ==="
chmod +x "${ROOT_DIR}/scripts/update_filter_lists.sh"
"${ROOT_DIR}/scripts/update_filter_lists.sh"

echo "=== 6. Native Rust Engine (libadblock_bridge.so) ==="
if [ "$SKIP_RUST" = true ]; then
    echo "  Skipping Rust NDK compilation (--skip-rust passed)."
elif command -v cargo-ndk >/dev/null 2>&1; then
    echo "  Found cargo-ndk; building libadblock_bridge.so across Android ABIs..."
    mkdir -p "${ROOT_DIR}/app/src/main/jniLibs"
    (
        cd "${ROOT_DIR}/rust_engine"
        cargo ndk \
            -t arm64-v8a \
            -t armeabi-v7a \
            -t x86_64 \
            -o ../app/src/main/jniLibs \
            build --release
    )
    echo "  Native libraries built in app/src/main/jniLibs"
else
    echo "  cargo-ndk is not installed. To build libadblock_bridge.so locally, install Rust targets and cargo-ndk:"
    echo "    rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android"
    echo "    cargo install cargo-ndk"
    echo "  Then re-run: ./scripts/bootstrap_dev.sh"
fi

echo ""
echo "=== Bootstrap Complete ==="
echo "You can now open the project in Android Studio or run unit tests via:"
echo "  ./gradlew :core:common:test testReleaseUnitTest"
