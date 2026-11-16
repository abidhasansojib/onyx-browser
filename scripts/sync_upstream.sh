#!/usr/bin/env bash
# ==============================================================================
# Script: scripts/sync_upstream.sh
# Purpose: Synchronize upstream Brave adblock-rust engine and/or filter lists.
# Usage:
#   ./scripts/sync_upstream.sh --engine-only   (Syncs only adblock-rust engine)
#   ./scripts/sync_upstream.sh --filters-only  (Syncs only filter lists)
#   ./scripts/sync_upstream.sh [--all]         (Syncs both engine and filters)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
MODE="${1:---all}"

mkdir -p "${ROOT_DIR}/rust_engine"
mkdir -p "${ROOT_DIR}/app/src/main/assets"

sync_engine() {
    echo "=== 1. Updating Upstream adblock-rust Native Engine Submodule ==="
    cd "${ROOT_DIR}"
    git submodule update --init --remote external/adblock-rust
    
    # Refresh symlink: rust_engine/adblock-rust -> external/adblock-rust
    ln -sfn "../external/adblock-rust" "${ROOT_DIR}/rust_engine/adblock-rust"
    echo "  Symlinked rust_engine/adblock-rust -> ../external/adblock-rust"
}

sync_filters() {
    echo "=== 2. Updating Upstream Filter Lists Submodule & Compiling Rules ==="
    cd "${ROOT_DIR}"
    git submodule update --init --remote external/adblock-lists 2>/dev/null || true
    
    # Refresh symlink: app/src/main/assets/brave-lists -> external/adblock-lists/brave-lists
    ln -sfn "../../../../external/adblock-lists/brave-lists" "${ROOT_DIR}/app/src/main/assets/brave-lists"
    echo "  Symlinked app/src/main/assets/brave-lists -> ../../../../external/adblock-lists/brave-lists"
    
    echo "=== 3. Synchronizing and Bundling Adblock Lists ==="
    "${ROOT_DIR}/scripts/update_filter_lists.sh"
}

case "$MODE" in
    --engine-only)
        sync_engine
        ;;
    --filters-only)
        sync_filters
        ;;
    --all)
        sync_engine
        sync_filters
        ;;
    *)
        echo "Unknown mode: $MODE (use --engine-only, --filters-only, or --all)"
        exit 1
        ;;
esac

echo "=== Upstream Synchronization Complete ==="
cd "${ROOT_DIR}"
git status --short
