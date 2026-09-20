#!/usr/bin/env bash
# ==============================================================================
# Script: scripts/sync_upstream.sh
# Purpose: Synchronize upstream Brave adblock-rust and adblock-lists repositories,
#          update symlinks, and regenerate bundled filter rules.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "=== 1. Updating Upstream Git Submodules ==="
cd "${ROOT_DIR}"
git submodule update --init --recursive --remote

echo "=== 2. Refreshing Symlinks ==="
mkdir -p "${ROOT_DIR}/rust_engine"
mkdir -p "${ROOT_DIR}/app/src/main/assets"

# Symlink rust_engine/adblock-rust -> external/adblock-rust
ln -sfn "../external/adblock-rust" "${ROOT_DIR}/rust_engine/adblock-rust"
echo "  Symlinked rust_engine/adblock-rust -> ../external/adblock-rust"

# Symlink app/src/main/assets/brave-lists -> external/adblock-lists/brave-lists
ln -sfn "../../../../external/adblock-lists/brave-lists" "${ROOT_DIR}/app/src/main/assets/brave-lists"
echo "  Symlinked app/src/main/assets/brave-lists -> ../../../../external/adblock-lists/brave-lists"

echo "=== 3. Synchronizing and Bundling Adblock Lists ==="
"${ROOT_DIR}/scripts/update_filter_lists.sh"

echo "=== 4. Upstream Synchronization Complete ==="
cd "${ROOT_DIR}"
git status --short
