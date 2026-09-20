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
if command -v npx >/dev/null 2>&1; then
    echo "  -> Using npx svg2vectordrawable..."
    npx --yes svg2vectordrawable --in "$TEMP_DIR/" --out "$DRAWABLE_DIR/" --tint "?attr/colorControlNormal" || \
    npx --yes svg2vectordrawable -f "$TEMP_DIR" -o "$DRAWABLE_DIR" || \
    python3 "$SCRIPT_DIR/svg_to_vector.py" "$TEMP_DIR" "$DRAWABLE_DIR"
else
    echo "  -> npx not found in environment, converting using svg_to_vector.py..."
    python3 "$SCRIPT_DIR/svg_to_vector.py" "$TEMP_DIR" "$DRAWABLE_DIR"
fi

echo "==> Cleaning up temporary download directory..."
rm -rf "$TEMP_DIR"

echo "==> Done! Official Lucide vector drawables generated in $DRAWABLE_DIR:"
ls -la "$DRAWABLE_DIR"/ic_*.xml
