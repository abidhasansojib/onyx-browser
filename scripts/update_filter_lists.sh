#!/usr/bin/env bash
# ==============================================================================
# Script: scripts/update_filter_lists.sh
# Purpose: Synchronize and bundle official Brave adblock filter lists from
#          https://github.com/brave/adblock-lists into Onyx Browser assets.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ASSETS_DIR="${ROOT_DIR}/app/src/main/assets"
EXTERNAL_LISTS_DIR="${ROOT_DIR}/external/adblock-lists"
OUTPUT_RULES_FILE="${ASSETS_DIR}/easylist_rules.txt"

echo "=== Synchronizing Brave Adblock Filter Lists ==="

# Create assets directory if missing
mkdir -p "${ASSETS_DIR}"

# Determine source: use local submodule if present, otherwise fetch from GitHub master
if [ -d "${EXTERNAL_LISTS_DIR}/brave-lists" ]; then
    echo "Found local submodule at ${EXTERNAL_LISTS_DIR}/brave-lists"
    SOURCE_MODE="submodule"
else
    echo "Submodule not initialized, downloading directly from upstream brave/adblock-lists master..."
    SOURCE_MODE="remote"
fi

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

RULES=(
    "brave-unbreak.txt"
    "brave-android-specific.txt"
    "brave-specific.txt"
    "brave-social.txt"
    "brave-cookie-specific.txt"
)

# Initialize output with header
cat << 'EOF' > "${OUTPUT_RULES_FILE}"
[Adblock Plus 2.0]
! Title: Onyx Browser Bundled Brave Filter Rules
! Description: Compiled directly from official Brave Adblock Lists
! Source: https://github.com/brave/adblock-lists
! Auto-updated for high-performance mobile ad-blocking

EOF

for rule_name in "${RULES[@]}"; do
    echo "Processing ${rule_name}..."
    if [ "${SOURCE_MODE}" = "submodule" ] && [ -f "${EXTERNAL_LISTS_DIR}/brave-lists/${rule_name}" ]; then
        cat "${EXTERNAL_LISTS_DIR}/brave-lists/${rule_name}" >> "${OUTPUT_RULES_FILE}"
        echo "" >> "${OUTPUT_RULES_FILE}"
    else
        URL="https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/${rule_name}"
        if curl -fsSL "${URL}" -o "${TEMP_DIR}/${rule_name}"; then
            cat "${TEMP_DIR}/${rule_name}" >> "${OUTPUT_RULES_FILE}"
            echo "" >> "${OUTPUT_RULES_FILE}"
            echo "  Successfully downloaded ${rule_name}"
        else
            echo "  Warning: Failed to fetch ${rule_name} from ${URL}, skipping."
        fi
    fi
done

# Also create symlink from assets/brave-lists to external/adblock-lists/brave-lists if submodule exists
if [ -d "${EXTERNAL_LISTS_DIR}/brave-lists" ]; then
    mkdir -p "${ASSETS_DIR}"
    rm -rf "${ASSETS_DIR}/brave-lists"
    ln -sfn "../../../../external/adblock-lists/brave-lists" "${ASSETS_DIR}/brave-lists" || true
    echo "Created symlink: app/src/main/assets/brave-lists -> external/adblock-lists/brave-lists"
fi

RULE_COUNT=$(grep -c "^[^!#[]" "${OUTPUT_RULES_FILE}" || true)
FILE_SIZE=$(wc -c < "${OUTPUT_RULES_FILE}")
echo "=== Filter lists sync complete ==="
echo "Active rules count: ${RULE_COUNT}"
echo "Total file size: ${FILE_SIZE} bytes"
echo "Target file: ${OUTPUT_RULES_FILE}"
