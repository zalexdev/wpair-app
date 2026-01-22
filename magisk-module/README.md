# WhisperPair Magisk Module

This Magisk module installs WhisperPair as a **system privileged app**, granting it the permissions needed to connect HFP (Hands-Free Profile) directly without user interaction.

## Why a Magisk Module?

Android restricts `MODIFY_PHONE_STATE` and `BLUETOOTH_PRIVILEGED` permissions to system/signature apps only. By installing WhisperPair as a privileged app in `/system/priv-app/`, these permissions can be granted automatically.

### Permissions Granted

| Permission | Purpose |
|------------|---------|
| `MODIFY_PHONE_STATE` | Call `BluetoothHeadset.connect()` without UI |
| `BLUETOOTH_PRIVILEGED` | Privileged Bluetooth operations |
| `CAPTURE_AUDIO_OUTPUT` | Record audio from Bluetooth SCO |
| `ACCESS_BACKGROUND_LOCATION` | BLE scanning in background |

## Installation

### Building the Module

1. **Build the APK:**
   ```bash
   cd /path/to/wpair-app
   ./gradlew assembleRelease
   ```

2. **Copy APK to module:**
   ```bash
   cp app/build/outputs/apk/release/app-release.apk magisk-module/system/priv-app/WhisperPair/WhisperPair.apk
   ```

3. **Create the ZIP:**
   ```bash
   cd magisk-module
   zip -r ../WhisperPair-Magisk.zip *
   ```

### Installing via Magisk

1. Open **Magisk** app
2. Go to **Modules** tab
3. Tap **Install from storage**
4. Select `WhisperPair-Magisk.zip`
5. **Reboot** device

## After Installation

The app will automatically have:
- All Bluetooth permissions pre-granted
- Ability to connect HFP directly via `BluetoothHeadset.connect()`
- No need for the "Manual Connection Required" workaround

## Uninstallation

1. Open Magisk app
2. Go to Modules tab  
3. Toggle off WhisperPair module
4. Reboot

Or remove via Magisk recovery mode.

## Compatibility

- **Magisk**: v20.4+
- **KernelSU**: Should work (uses standard module format)
- **Android**: 10+ (API 29+)

## Troubleshooting

### App crashes after install
- Check `/data/system/priv-app-permissions.xml` for denied permissions
- Some ROMs may need SELinux policy patches

### HFP still doesn't connect
- Ensure device is bonded first (exploit → pair flow)
- Check `adb logcat -s WhisperPair` for errors

### Module doesn't appear
- Verify ZIP structure matches expected Magisk format
- Check Magisk logs in the app

## Security Note

This module is for **security research only**. Installing apps as system privileged apps bypasses Android's security model. Only install on your own test devices.
