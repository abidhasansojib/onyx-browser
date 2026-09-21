#!/usr/bin/env bash
# =============================================================================
# generate_keystore.sh — Generate a release keystore & prepare GitHub secrets
# =============================================================================
#
# Usage:
#   chmod +x scripts/generate_keystore.sh
#   ./scripts/generate_keystore.sh
#
# This script:
#   1. Generates a new release.keystore (if one does not already exist).
#   2. Base64-encodes it and prints the value for KEYSTORE_BASE64.
#   3. Reminds you which secrets to set in GitHub → Settings → Secrets.
#
# Required GitHub Actions secrets (Settings → Secrets and variables → Actions):
#   KEYSTORE_BASE64    — base64-encoded content of your release.keystore
#   KEYSTORE_PASSWORD  — password you chose for the keystore store
#   KEY_ALIAS          — alias you chose for the key inside the keystore
#   KEY_PASSWORD       — password you chose for the key
#
# =============================================================================

set -euo pipefail

KEYSTORE_FILE="${KEYSTORE_FILE:-release.keystore}"
KEY_ALIAS="${KEY_ALIAS:-onyx-browser}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}"
KEY_PASSWORD="${KEY_PASSWORD:-}"
VALIDITY_DAYS=10000

# ── Prompt for passwords if not pre-set ──────────────────────────────────────
if [ -z "$KEYSTORE_PASSWORD" ]; then
    read -rsp "Enter keystore password (store password): " KEYSTORE_PASSWORD
    echo
fi
if [ -z "$KEY_PASSWORD" ]; then
    read -rsp "Enter key password (can be the same as keystore password): " KEY_PASSWORD
    echo
fi

# ── Generate the keystore ────────────────────────────────────────────────────
if [ -f "$KEYSTORE_FILE" ]; then
    echo "⚠️  '$KEYSTORE_FILE' already exists. Skipping keytool generation."
    echo "   Delete it first if you want to regenerate."
else
    echo ""
    echo "Generating $KEYSTORE_FILE …"
    keytool \
        -genkeypair \
        -v \
        -keystore "$KEYSTORE_FILE" \
        -storetype PKCS12 \
        -alias "$KEY_ALIAS" \
        -keyalg RSA \
        -keysize 4096 \
        -validity "$VALIDITY_DAYS" \
        -storepass "$KEYSTORE_PASSWORD" \
        -keypass "$KEY_PASSWORD" \
        -dname "CN=Onyx Browser, OU=Engineering, O=Onyx, L=Unknown, ST=Unknown, C=US"
    echo "✅  Keystore generated: $KEYSTORE_FILE"
fi

# ── Base64-encode ─────────────────────────────────────────────────────────────
ENCODED=$(base64 -w 0 "$KEYSTORE_FILE")

echo ""
echo "=========================================================="
echo " Add the following secrets to your GitHub repository:"
echo " (Settings → Secrets and variables → Actions → New secret)"
echo "=========================================================="
echo ""
echo "  Secret name : KEYSTORE_BASE64"
echo "  Secret value: (shown below — copy the entire line)"
echo ""
echo "$ENCODED"
echo ""
echo "  Secret name : KEYSTORE_PASSWORD"
echo "  Secret value: $KEYSTORE_PASSWORD"
echo ""
echo "  Secret name : KEY_ALIAS"
echo "  Secret value: $KEY_ALIAS"
echo ""
echo "  Secret name : KEY_PASSWORD"
echo "  Secret value: $KEY_PASSWORD"
echo ""
echo "=========================================================="
echo " ⚠️  IMPORTANT: Keep '$KEYSTORE_FILE' safe and backed up."
echo "     Losing it means you CANNOT update your app on Google Play."
echo "     Never commit it to version control."
echo "=========================================================="
