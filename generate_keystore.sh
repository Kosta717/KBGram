#!/bin/bash
# KBGram — Generate a release keystore for signing APKs
#
# Usage: ./generate_keystore.sh
#
# This will generate a new keystore at TMessagesProj/config/release.keystore
# After generating, update gradle.properties with the passwords you set.

set -e

KEYSTORE_PATH="TMessagesProj/config/release.keystore"
ALIAS="kbgram"
VALIDITY_DAYS=10000

if [ -f "$KEYSTORE_PATH" ]; then
    echo "⚠️  Keystore already exists at $KEYSTORE_PATH"
    echo "    Delete it first if you want to regenerate."
    exit 1
fi

echo "🔑 Generating new release keystore for KBGram..."
echo ""

keytool -genkey -v \
    -keystore "$KEYSTORE_PATH" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity "$VALIDITY_DAYS" \
    -storepass "$@"

echo ""
echo "✅ Keystore generated at: $KEYSTORE_PATH"
echo ""
echo "📝 Now update gradle.properties:"
echo "   RELEASE_KEY_PASSWORD=<your_password>"
echo "   RELEASE_KEY_ALIAS=$ALIAS"
echo "   RELEASE_STORE_PASSWORD=<your_password>"
