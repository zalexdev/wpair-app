#!/bin/bash
# WhisperPair Magisk Module Builder for Linux/macOS
# Run this after building the APK with: ./gradlew assembleRelease

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$SCRIPT_DIR/magisk-module"
APK_SOURCE="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
APK_DEST="$MODULE_DIR/system/priv-app/WhisperPair/WhisperPair.apk"
OUTPUT_ZIP="$SCRIPT_DIR/WhisperPair-Magisk.zip"

echo ""
echo "========================================"
echo " WhisperPair Magisk Module Builder"
echo "========================================"
echo ""

# Check if APK exists
if [ ! -f "$APK_SOURCE" ]; then
    echo "ERROR: APK not found at:"
    echo "  $APK_SOURCE"
    echo ""
    echo "Please build the APK first:"
    echo "  ./gradlew assembleRelease"
    echo ""
    exit 1
fi

echo "[1/3] Copying APK to module..."
cp "$APK_SOURCE" "$APK_DEST"
echo "      Done."

echo "[2/3] Creating Magisk module ZIP..."
# Delete old zip if exists
rm -f "$OUTPUT_ZIP"

# Create ZIP
cd "$MODULE_DIR"
zip -r "$OUTPUT_ZIP" ./*
cd "$SCRIPT_DIR"
echo "      Done."

echo "[3/3] Setting permissions..."
chmod 644 "$OUTPUT_ZIP"
echo "      Done."

echo ""
echo "========================================"
echo " SUCCESS!"
echo "========================================"
echo ""
echo "Magisk module created: $OUTPUT_ZIP"
echo ""
echo "Installation:"
echo "  1. Copy to phone: adb push WhisperPair-Magisk.zip /sdcard/"
echo "  2. Open Magisk app"
echo "  3. Modules > Install from storage"
echo "  4. Select WhisperPair-Magisk.zip"
echo "  5. Reboot"
echo ""
