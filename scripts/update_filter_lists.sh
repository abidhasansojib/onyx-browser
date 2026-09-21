#!/usr/bin/env bash
# ==============================================================================
# Script: scripts/update_filter_lists.sh
# Purpose: Synchronize and bundle official Brave adblock filter lists,
#          uBlock filters, and Peter Lowe's list into Onyx Browser assets.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
export ASSETS_DIR="${ROOT_DIR}/app/src/main/assets"
export EXTERNAL_LISTS_DIR="${ROOT_DIR}/external/adblock-lists"
export OUTPUT_RULES_FILE="${ASSETS_DIR}/easylist_rules.txt"

echo "=== Synchronizing Brave & Community Adblock Filter Lists ==="

mkdir -p "${ASSETS_DIR}"

python3 - << 'EOF'
import os, urllib.request

external = os.environ.get('EXTERNAL_LISTS_DIR', 'external/adblock-lists')
output_path = os.environ.get('OUTPUT_RULES_FILE', 'app/src/main/assets/easylist_rules.txt')

sources = [
    os.path.join(external, 'brave-lists/filters-mirror.txt'),
    os.path.join(external, 'brave-unbreak.txt'),
    os.path.join(external, 'brave-lists/brave-firstparty.txt'),
    os.path.join(external, 'brave-lists/brave-firstparty-cname.txt'),
    os.path.join(external, 'brave-lists/brave-social.txt'),
    os.path.join(external, 'brave-lists/brave-cookie-specific.txt'),
    os.path.join(external, 'brave-lists/yt-shorts.txt'),
    os.path.join(external, 'brave-lists/yt-distracting.txt'),
    os.path.join(external, 'brave-lists/yt-recommended.txt'),
    os.path.join(external, 'brave-lists/experimental.txt')
]

rules = []
seen = set()

header = """[Adblock Plus 2.0]
! Title: Onyx Browser Production Filter Rules
! Description: Compiled from uBlock, Brave Shields, and Peter Lowe filters
! Auto-compiled for high-performance mobile ad-blocking
"""

for s in sources:
    if os.path.exists(s):
        with open(s, 'r', errors='ignore') as f:
            for line in f:
                l = line.strip()
                if l and not l.startswith('!') and not l.startswith('['):
                    if l not in seen:
                        seen.add(l)
                        rules.append(l)

# Also fetch Peter Lowe adservers list if network available
try:
    req = urllib.request.Request(
        'https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext',
        headers={'User-Agent': 'Mozilla/5.0'}
    )
    with urllib.request.urlopen(req, timeout=8) as resp:
        text = resp.read().decode('utf-8', errors='ignore')
        for line in text.splitlines():
            l = line.strip()
            if l and not l.startswith('!') and not l.startswith('['):
                if l not in seen:
                    seen.add(l)
                    rules.append(l)
except Exception as e:
    print('Notice: Peter Lowe list fetch skipped:', e)

with open(output_path, 'w', encoding='utf-8') as f:
    f.write(header + '\n')
    for r in rules:
        f.write(r + '\n')

print(f"Filter database generated: {len(rules)} rules ({os.path.getsize(output_path)} bytes)")
EOF

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
