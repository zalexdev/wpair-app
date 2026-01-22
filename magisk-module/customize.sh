#!/system/bin/sh
# WhisperPair Magisk Module - Customize Script

SKIPUNZIP=0

# Print module info
ui_print "- WhisperPair CVE-2025-36911 PoC"
ui_print "- Installing as system privileged app..."

# Set permissions for the APK
set_perm_recursive $MODPATH/system/priv-app 0 0 0755 0644

ui_print "- Setting up privileged permissions..."
ui_print "- Installation complete!"
ui_print ""
ui_print "IMPORTANT: After reboot, WhisperPair will have:"
ui_print "  - MODIFY_PHONE_STATE permission"
ui_print "  - BLUETOOTH_PRIVILEGED permission"
ui_print "  - Full HFP connection control"
ui_print ""
ui_print "The app can now connect to exploited devices"
ui_print "without going to Settings!"
