@echo off
REM WhisperPair Magisk Module Builder for Windows
REM Run this after building the APK with: gradlew assembleRelease

setlocal

set SCRIPT_DIR=%~dp0
set MODULE_DIR=%SCRIPT_DIR%magisk-module
set APK_SOURCE=%SCRIPT_DIR%app\build\outputs\apk\release\app-release.apk
set APK_DEST=%MODULE_DIR%\system\priv-app\WhisperPair\WhisperPair.apk
set OUTPUT_ZIP=%SCRIPT_DIR%WhisperPair-Magisk.zip

echo.
echo ========================================
echo  WhisperPair Magisk Module Builder
echo ========================================
echo.

REM Check if APK exists
if not exist "%APK_SOURCE%" (
    echo ERROR: APK not found at:
    echo   %APK_SOURCE%
    echo.
    echo Please build the APK first:
    echo   gradlew assembleRelease
    echo.
    pause
    exit /b 1
)

echo [1/3] Copying APK to module...
copy /Y "%APK_SOURCE%" "%APK_DEST%" > nul
if errorlevel 1 (
    echo ERROR: Failed to copy APK
    pause
    exit /b 1
)
echo       Done.

echo [2/3] Creating Magisk module ZIP...
REM Delete old zip if exists
if exist "%OUTPUT_ZIP%" del "%OUTPUT_ZIP%"

REM Use PowerShell to create ZIP (available on all modern Windows)
powershell -Command "Compress-Archive -Path '%MODULE_DIR%\*' -DestinationPath '%OUTPUT_ZIP%' -Force"
if errorlevel 1 (
    echo ERROR: Failed to create ZIP
    echo Make sure PowerShell is available
    pause
    exit /b 1
)
echo       Done.

echo [3/3] Cleaning up...
REM Remove the copied APK from module dir (keep source clean)
REM del "%APK_DEST%" > nul 2>&1
echo       Done.

echo.
echo ========================================
echo  SUCCESS!
echo ========================================
echo.
echo Magisk module created: %OUTPUT_ZIP%
echo.
echo Installation:
echo   1. Copy to phone
echo   2. Open Magisk app
echo   3. Modules ^> Install from storage
echo   4. Select WhisperPair-Magisk.zip
echo   5. Reboot
echo.

pause
