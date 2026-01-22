#!/system/bin/sh
# WhisperPair Magisk Module - Service Script
# Runs after system boot is complete

MODDIR=${0%/*}

# Wait for boot to complete
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done

# Additional delay to ensure package manager is ready
sleep 5

# Grant runtime permissions that require user consent normally
# These supplement the privapp-permissions.xml static grants

PACKAGE="com.zalexdev.whisperpair"

# Bluetooth permissions (Android 12+)
pm grant $PACKAGE android.permission.BLUETOOTH_CONNECT 2>/dev/null
pm grant $PACKAGE android.permission.BLUETOOTH_SCAN 2>/dev/null
pm grant $PACKAGE android.permission.BLUETOOTH_ADVERTISE 2>/dev/null

# Location (needed for BLE scanning)
pm grant $PACKAGE android.permission.ACCESS_FINE_LOCATION 2>/dev/null
pm grant $PACKAGE android.permission.ACCESS_COARSE_LOCATION 2>/dev/null
pm grant $PACKAGE android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null

# Recording
pm grant $PACKAGE android.permission.RECORD_AUDIO 2>/dev/null

# Notifications
pm grant $PACKAGE android.permission.POST_NOTIFICATIONS 2>/dev/null

# These are signature/system permissions - may fail on stock ROMs but work with priv-app
pm grant $PACKAGE android.permission.MODIFY_PHONE_STATE 2>/dev/null
pm grant $PACKAGE android.permission.BLUETOOTH_PRIVILEGED 2>/dev/null

# Log completion
log -t WhisperPair "Runtime permissions granted"
