package com.jarves.mh.commander

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.jarves.mh.runtime.RuntimeInstaller
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

enum class CommanderState {
    IDLE,
    WAITING_NPM_CONFIRM,   // Saw "Need to install … Ok to proceed? (y)"
    INSTALLING_NPM,        // Sent "y", waiting for npm install to finish
    CONNECTING,            // npm done, connecting to remote MCP
    NEEDS_SIGNUP,          // Got a sign-up URL, waiting for user to auth
    CONNECTED,             // "Device ready" seen — fully connected
    ERROR,
    STOPPED
}

data class CommanderInfo(
    val deviceId: String? = null,
    val deviceName: String? = null,
    val userEmail: String? = null,
    val sessionRestored: Boolean = false
)

object DesktopCommanderManager {

    private const val TAG = "DesktopCommander"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var process: Process? = null
    private var processStdin: OutputStream? = null
    private var installer: RuntimeInstaller? = null

    // ---- Public state flows ----
    private val _state = MutableStateFlow(CommanderState.IDLE)
    val state: StateFlow<CommanderState> = _state.asStateFlow()

    /** Convenience alias for the on/off switch in CursorTab */
    val isRunning: StateFlow<Boolean> get() = MutableStateFlow(false).also {
        // Real running = anything except IDLE/STOPPED/ERROR
    }

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()

    private val _info = MutableStateFlow(CommanderInfo())
    val info: StateFlow<CommanderInfo> = _info.asStateFlow()

    /** Sign-up/auth URL detected from output — UI should open this */
    private val _pendingUrl = MutableStateFlow<String?>(null)
    val pendingUrl: StateFlow<String?> = _pendingUrl.asStateFlow()

    /** Legacy field kept for CursorTab compatibility */
    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

    // ---- Regex patterns ----
    private val npmConfirmRegex = Regex("Ok to proceed\\?\\s*\\(y\\)", RegexOption.IGNORE_CASE)
    private val signupUrlRegex  = Regex("https://(?:mcp\\.desktopcommander\\.app|accounts\\.[\\w.]+)/(?:signup|login|auth|register|connect)[^\\s]*", RegexOption.IGNORE_CASE)
    private val httpUrlRegex    = Regex("https://\\S+", RegexOption.IGNORE_CASE)
    private val deviceIdRegex   = Regex("Device ID:\\s*([\\w-]+)", RegexOption.IGNORE_CASE)
    private val deviceNameRegex = Regex("Device Name:\\s*(.+)", RegexOption.IGNORE_CASE)
    private val userEmailRegex  = Regex("User:\\s*(\\S+@\\S+)", RegexOption.IGNORE_CASE)
    private val deviceReadyRegex = Regex("Device ready", RegexOption.IGNORE_CASE)
    private val sessionRestoredRegex = Regex("Session restored", RegexOption.IGNORE_CASE)
    private val connectedRegex  = Regex("Connected to (?:Remote|Local) MCP|Device marked as online|Channel subscribed", RegexOption.IGNORE_CASE)

    fun start(context: Context) {
        if (_isActive.value) return
        val appCtx = context.applicationContext ?: context
        installer = RuntimeInstaller(appCtx)
        _terminalOutput.value = ""
        _pendingUrl.value = null
        _state.value = CommanderState.CONNECTING
        _isActive.value = true

        scope.launch {
            try {
                val inst = installer ?: return@launch
                if (!inst.isInstalled()) {
                    appendOutput("⚠ Subsystem not installed yet. Complete the main setup first.\n")
                    _state.value = CommanderState.ERROR
                    _isActive.value = false
                    return@launch
                }

                val installed = inst.installedRuntime()
                val workspace = File(installed.rootfs, "root")
                workspace.mkdirs()

                appendOutput("🚀 Starting Desktop Commander Remote…\n")

                val outputFile = File(appCtx.cacheDir, "desktop-commander-out.log")
                outputFile.delete()

                // Use -y flag to auto-confirm npm install — avoids the interactive "Ok to proceed? (y)" prompt
                val guestCommand = listOf(
                    "/usr/bin/env", "bash", "-lc",
                    "npx --yes @wonderwhy-er/desktop-commander@latest remote"
                )

                val proc = inst.process(
                    proot    = installed.proot,
                    rootfs   = installed.rootfs,
                    workspace = workspace,
                    environment = mapOf(
                        "HOME" to "/root",
                        "CI"   to "1",
                        "NPM_CONFIG_YES" to "true",
                        "TERM" to "xterm-256color"
                    ),
                    guestCommand = guestCommand,
                    outputFile   = outputFile,
                    pseudoTerminal = false
                )

                process = proc

                // Try to get stdin so we can send "y\n" if npm still asks interactively
                try {
                    processStdin = proc.outputStream
                } catch (_: Exception) {}

                var offset = 0L
                val outputBuffer = StringBuilder()

                while (proc.isAlive || (outputFile.exists() && outputFile.length() > offset)) {
                    val currentLen = outputFile.length()
                    if (currentLen > offset) {
                        val available = (currentLen - offset).toInt()
                        val bytes = ByteArray(minOf(available, 16384))
                        RandomAccessFile(outputFile, "r").use { raf ->
                            raf.seek(offset)
                            val read = raf.read(bytes)
                            if (read > 0) {
                                offset += read
                                val chunk = bytes.decodeToString(0, read)
                                outputBuffer.append(chunk)
                                _terminalOutput.value = outputBuffer.toString().takeLast(8000)
                                processChunk(chunk, appCtx)
                            }
                        }
                    } else {
                        delay(150)
                    }
                }

                val exit = proc.waitFor()
                appendOutput("\n[Exited with code $exit]\n")
                _state.value = if (exit == 0) CommanderState.STOPPED else CommanderState.ERROR
                _isActive.value = false

            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
                appendOutput("\n[Error: ${e.message}]\n")
                _state.value = CommanderState.ERROR
                _isActive.value = false
            } finally {
                process = null
                processStdin = null
            }
        }
    }

    fun stop() {
        scope.launch {
            try {
                processStdin?.close()
                process?.destroy()
                delay(400)
                process?.destroyForcibly()
            } catch (_: Exception) {}
            process = null
            processStdin = null
            _isActive.value = false
            _state.value = CommanderState.STOPPED
            appendOutput("\n[Stopped by user]\n")
        }
    }

    /** Send a raw line to the process stdin (e.g. "y\n") */
    fun sendInput(line: String) {
        scope.launch {
            try {
                processStdin?.let {
                    it.write("$line\n".toByteArray())
                    it.flush()
                    appendOutput("> $line\n")
                }
            } catch (e: Exception) {
                Log.w(TAG, "stdin write failed: ${e.message}")
            }
        }
    }

    fun clearPendingUrl() {
        _pendingUrl.value = null
    }

    // ---- Internal output processing ----

    private fun appendOutput(text: String) {
        _terminalOutput.value = (_terminalOutput.value + text).takeLast(8000)
    }

    private fun processChunk(chunk: String, context: Context) {
        // Auto-answer "Ok to proceed? (y)" in case --yes flag wasn't enough
        if (npmConfirmRegex.containsMatchIn(chunk) && _state.value != CommanderState.INSTALLING_NPM) {
            _state.value = CommanderState.WAITING_NPM_CONFIRM
            scope.launch {
                delay(300)
                sendInput("y")
                _state.value = CommanderState.INSTALLING_NPM
            }
        }

        // Detect sign-up / login / auth URLs
        if (_pendingUrl.value == null) {
            val urlMatch = signupUrlRegex.find(chunk) ?: httpUrlRegex.find(chunk)
                ?.takeIf { it.value.contains("signup", true) || it.value.contains("login", true) || it.value.contains("auth", true) }
            if (urlMatch != null) {
                val url = urlMatch.value.trim()
                _pendingUrl.value = url
                _state.value = CommanderState.NEEDS_SIGNUP
                appendOutput("\n🔗 Auth URL detected — opening browser…\n")
                // Open in system browser
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open URL: $url", e)
                }
            }
        }

        // Parse device info
        deviceIdRegex.find(chunk)?.groupValues?.getOrNull(1)?.let { id ->
            _info.value = _info.value.copy(deviceId = id.trim())
            _pairingCode.value = id.trim()
        }
        deviceNameRegex.find(chunk)?.groupValues?.getOrNull(1)?.let { name ->
            _info.value = _info.value.copy(deviceName = name.trim())
        }
        userEmailRegex.find(chunk)?.groupValues?.getOrNull(1)?.let { email ->
            _info.value = _info.value.copy(userEmail = email.trim())
        }
        if (sessionRestoredRegex.containsMatchIn(chunk)) {
            _info.value = _info.value.copy(sessionRestored = true)
        }

        // Transition to CONNECTED
        if (deviceReadyRegex.containsMatchIn(chunk)) {
            _state.value = CommanderState.CONNECTED
        } else if (connectedRegex.containsMatchIn(chunk) && _state.value != CommanderState.CONNECTED) {
            _state.value = CommanderState.CONNECTING
        }
    }
}
