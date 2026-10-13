#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMP_DIR="$ROOT_DIR/temp_icons"
DRAWABLE_DIR="$ROOT_DIR/app/src/main/res/drawable"

echo "==> Creating temporary directory for Lucide SVG downloads: $TEMP_DIR"
rm -rf "$TEMP_DIR"
mkdir -p "$TEMP_DIR"
mkdir -p "$DRAWABLE_DIR"

# Official Lucide SVGs (mirrored directly from Lucide-static package)
BASE_URL="https://cdn.jsdelivr.net/npm/lucide-static@latest/icons"
BACKUP_URL="https://unpkg.com/lucide-static@latest/icons"

# Map: <remote_name>:<local_ic_name>
ICONS=(
    "home:ic_home"
    "search:ic_search"
    "mic:ic_mic"
    "layers:ic_tabs"
    "more-vertical:ic_more_vert"
    "history:ic_history"
    "download:ic_download"
    "plus:ic_add"
    "x:ic_close"
    "shield:ic_shield"
    "settings:ic_settings"
    "bookmark:ic_bookmark"
    "trash-2:ic_delete"
    "share-2:ic_share"
    "lock:ic_lock"
    "external-link:ic_external"
    "refresh-cw:ic_refresh"
    "monitor:ic_desktop"
    "folder:ic_folder"
    "globe:ic_web"
    "camera:ic_camera"
    "map-pin:ic_location"
    "bell:ic_bell"
    "volume-2:ic_volume"
    "activity:ic_sensors"
    "clipboard:ic_clipboard"
    "usb:ic_usb"
    "cpu:ic_cpu"
    "gamepad-2:ic_gamepad"
    "music:ic_music"
    "hand:ic_hand"
    "timer:ic_idle"
    "type:ic_fonts"
    "bluetooth:ic_bluetooth"
    "sun:ic_sun"
    "credit-card:ic_credit_card"
    "file-pen:ic_file_edit"
    "glasses:ic_vr"
    "nfc:ic_nfc"
    "layout:ic_window_management"
    "database:ic_storage_access"
    "network:ic_network"
)

echo "==> Downloading official Lucide icons..."
for entry in "${ICONS[@]}"; do
    remote="${entry%%:*}"
    target="${entry##*:}"
    echo "  -> Fetching $remote.svg as $target.svg"
    if ! curl -sSL -f "$BASE_URL/$remote.svg" -o "$TEMP_DIR/$target.svg"; then
        curl -sSL -f "$BACKUP_URL/$remote.svg" -o "$TEMP_DIR/$target.svg"
    fi
done

echo "==> Converting downloaded SVGs to Android Vector Drawables..."
python3 "$SCRIPT_DIR/svg_to_vector.py" "$TEMP_DIR" "$DRAWABLE_DIR"

echo "==> Cleaning up temporary download directory..."
rm -rf "$TEMP_DIR"

echo "==> Done! Official vector drawables generated in $DRAWABLE_DIR:"
ls -la "$DRAWABLE_DIR"/ic_*.xml

