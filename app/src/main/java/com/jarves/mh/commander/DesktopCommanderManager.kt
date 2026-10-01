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
    WAITING_AUTH,          // Got device code, waiting for user to verify in browser
    CONNECTED,             // "Device ready" seen — fully connected
    ERROR,
    STOPPED
}

data class CommanderInfo(
    val deviceId: String? = null,
    val deviceName: String? = null,
    val userEmail: String? = null,
    val sessionRestored: Boolean = false,
    val verifyUrl: String? = null,      // https://mcp.desktopcommander.app/device/verify?user_code=XXXX
    val verifyCode: String? = null,     // e.g. R7FH-XQ7H
    val codeExpiryMinutes: Int? = null  // "expires in N minutes"
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

    /** Convenience alias for the on/off switch in CursorTab — derived from active flag */
    val isRunning: StateFlow<Boolean> get() = _isActive

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
    private var _npmConfirmSent = false
    private val ansiRegex = Regex("\u001B\\[[;\\d]*m")
    private val npmConfirmRegex   = Regex("Ok to proceed\\?\\s*\\(y\\)", RegexOption.IGNORE_CASE)
    /** Matches both the verify URL and any other auth URL */
    private val verifyUrlRegex    = Regex("https://[\\S]+/device/verify[\\S]*", RegexOption.IGNORE_CASE)
    /** Matches the 4-4 user code shown after "user_code=" or on its own line e.g. R7FH-XQ7H */
    private val userCodeRegex     = Regex("\\b([A-Za-z0-9]{4}-[A-Za-z0-9]{4})\\b")
    /** "Code expires in N minutes" */
    private val expiryRegex       = Regex("expires in (\\d+) minutes?", RegexOption.IGNORE_CASE)
    private val deviceIdRegex     = Regex("Device ID:\\s*([\\w-]+)", RegexOption.IGNORE_CASE)
    private val deviceNameRegex   = Regex("Device Name:\\s*(.+)", RegexOption.IGNORE_CASE)
    private val userEmailRegex    = Regex("User:\\s*(\\S+@\\S+)", RegexOption.IGNORE_CASE)
    private val deviceReadyRegex  = Regex("Device ready|Device marked as online", RegexOption.IGNORE_CASE)
    private val sessionRestoredRegex = Regex("Session restored", RegexOption.IGNORE_CASE)
    private val waitingAuthRegex  = Regex("Waiting for authorization", RegexOption.IGNORE_CASE)

    fun start(context: Context) {
        if (_isActive.value) return
        val appCtx = context.applicationContext ?: context
        installer = RuntimeInstaller(appCtx)
        _terminalOutput.value = ""
        _pendingUrl.value = null
        _pairingCode.value = null
        _info.value = CommanderInfo()
        _npmConfirmSent = false
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
                appendOutput(
                    "📱 Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                        "(Android ${android.os.Build.VERSION.RELEASE})\n"
                )
                appendOutput(
                    "🤖 Android bridge ready: am, pm, cmd, dumpsys, input, settings, " +
                        "getprop, svc, wm, screencap, uiautomator work in this shell " +
                        "(e.g. am start -a android.intent.action.VIEW -d " +
                        "'https://youtube.com'). /sdcard and /storage are mounted.\n"
                )

                val outputFile = File(appCtx.cacheDir, "desktop-commander-out.log")
                outputFile.delete()

                val bridgePort = com.jarves.mh.bridge.AndroidApiBridgeServer.PORT
                val bridgeUrl  = "http://localhost:$bridgePort"

                // Install the 'android' helper CLI once into the PRoot rootfs
                installAndroidHelperScript(installed.rootfs, bridgeUrl)

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
                        "TERM" to "xterm-256color",
                        // Android API Bridge — the AI can use curl/wget to call these
                        "ANDROID_BRIDGE_URL"  to bridgeUrl,
                        "ANDROID_BRIDGE_PORT" to "$bridgePort",
                        // Human-readable Android identity
                        "JARVIS_DEVICE_MODEL"        to android.os.Build.MODEL,
                        "JARVIS_DEVICE_MANUFACTURER" to android.os.Build.MANUFACTURER,
                        "JARVIS_DEVICE_ANDROID"      to android.os.Build.VERSION.RELEASE,
                        "JARVIS_DEVICE_FINGERPRINT"  to android.os.Build.FINGERPRINT,
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

    /**
     * Writes the `android` CLI helper script into the PRoot rootfs at
     * /usr/local/bin/android so the AI can call Android APIs from any shell
     * command like `android tap 540 1200` or `android shell pm list packages`.
     *
     * Also appends ANDROID_BRIDGE_URL to /root/.bashrc so it is always set.
     */
    private fun installAndroidHelperScript(rootfs: File, bridgeUrl: String) {
        try {
            val binDir = File(rootfs, "usr/local/bin")
            binDir.mkdirs()

            // ── android CLI ─────────────────────────────────────────────────────
            val script = File(binDir, "android")
            script.writeText("""
#!/usr/bin/env bash
BRIDGE="${'$'}{ANDROID_BRIDGE_URL:-$bridgeUrl}"
CMD="${'$'}1"; shift 2>/dev/null
case "${'$'}CMD" in
  shell|exec)
    if [ "${'$'}#" -eq 0 ]; then exec /system/bin/sh
    else /system/bin/sh -c "${'$'}*"; fi ;;
  tap)    curl -sf "${'$'}BRIDGE/tap"   -H 'Content-Type: application/json' -d "{\"x\":${'$'}1,\"y\":${'$'}2}" ;;
  swipe)  curl -sf "${'$'}BRIDGE/swipe" -H 'Content-Type: application/json' -d "{\"x1\":${'$'}1,\"y1\":${'$'}2,\"x2\":${'$'}3,\"y2\":${'$'}4,\"duration\":${'$'}{5:-250}}" ;;
  type)   curl -sf "${'$'}BRIDGE/type"  -H 'Content-Type: application/json' -d "{\"text\":\"${'$'}*\"}" ;;
  key)    /system/bin/input keyevent "${'$'}1" ;;
  battery)  curl -sf "${'$'}BRIDGE/battery" ;;
  wifi)     curl -sf "${'$'}BRIDGE/wifi" ;;
  clipboard)
    if [ "${'$'}1" = "set" ]; then shift; curl -sf "${'$'}BRIDGE/clipboard" -X POST -H 'Content-Type: application/json' -d "{\"text\":\"${'$'}*\"}"
    else curl -sf "${'$'}BRIDGE/clipboard" | python3 -c "import sys,json; print(json.load(sys.stdin).get('text',''))" 2>/dev/null; fi ;;
  open)     /system/bin/am start -a android.intent.action.VIEW -d "${'$'}1" ;;
  launch)   /system/bin/pm path "${'$'}1" >/dev/null 2>&1 && /system/bin/monkey -p "${'$'}1" -c android.intent.category.LAUNCHER 1 ;;
  notify)   curl -sf "${'$'}BRIDGE/notify" -H 'Content-Type: application/json' -d "{\"title\":\"${'$'}1\",\"message\":\"${'$'}2\"}" ;;
  screen)   /system/bin/wm size && /system/bin/wm density ;;
  device)   echo "$(getprop ro.product.manufacturer) $(getprop ro.product.model) Android $(getprop ro.build.version.release)" ;;
  capture)  curl -sf "${'$'}BRIDGE/capture" | python3 -c "import sys,json; print(json.load(sys.stdin).get('screen_text',''))" 2>/dev/null ;;
  location) curl -sf "${'$'}BRIDGE/location" ;;
  packages) /system/bin/pm list packages "${'$'}@" ;;
  settings)
    if [ -n "${'$'}2" ]; then /system/bin/settings put system "${'$'}1" "${'$'}2"
    else /system/bin/settings get system "${'$'}1" 2>/dev/null || /system/bin/settings get secure "${'$'}1" 2>/dev/null || /system/bin/settings get global "${'$'}1"; fi ;;
  contacts) curl -sf "${'$'}BRIDGE/contacts" ;;
  help|"")
    echo "NATIVE: shell, key, open, launch, screen, device, packages, settings"
    echo "BRIDGE: battery, wifi, clipboard, tap, swipe, type, notify, capture, location" ;;
  *) /system/bin/sh -c "${'$'}CMD ${'$'}*" ;;
esac
""".trimIndent())
            script.setExecutable(true)

            // ── adb shim — maps adb shell to /system/bin/sh directly ───────────
            val adbScript = File(binDir, "adb")
            adbScript.writeText("""
#!/usr/bin/env bash
# adb shim — native Android shell, no ADB daemon needed
BRIDGE="${'$'}{ANDROID_BRIDGE_URL:-$bridgeUrl}"
CMD="${'$'}1"; shift 2>/dev/null
case "${'$'}CMD" in
  shell)
    if [ "${'$'}#" -eq 0 ]; then exec /system/bin/sh
    else /system/bin/sh -c "${'$'}*"; fi ;;
  devices)
    echo "List of devices attached"
    echo "$(getprop ro.serialno 2>/dev/null || echo 'mhdevice')	device" ;;
  install)   /system/bin/pm install "${'$'}@" ;;
  uninstall) /system/bin/pm uninstall "${'$'}@" ;;
  push|pull) cp "${'$'}1" "${'$'}2" ;;
  logcat)    /system/bin/logcat "${'$'}@" ;;
  reboot)    /system/bin/sh -c "reboot ${'$'}*" ;;
  tcpip|usb|connect|disconnect|forward|reverse)
    echo "[adb-shim] Already on-device — no network ADB needed" ;;
  *)         /system/bin/sh -c "${'$'}CMD ${'$'}*" ;;
esac
""".trimIndent())
            adbScript.setExecutable(true)

            // ── .bashrc: full Android PATH + aliases ───────────────────────────
            val bashrc = File(rootfs, "root/.bashrc")
            val existing = bashrc.takeIf { it.exists() }?.readText() ?: ""
            if (!existing.contains("ANDROID_BRIDGE_URL")) {
                bashrc.appendText("""

# Mobile Harness — Android native terminal
export ANDROID_BRIDGE_URL="$bridgeUrl"
export PATH="/usr/local/bin:/system/bin:/system/xbin:/vendor/bin:/sbin:${'$'}PATH"
alias am='am' pm='pm' dumpsys='dumpsys' input='input'
alias getprop='getprop' setprop='setprop' cmd='cmd' wm='wm'
alias settings='settings' logcat='logcat' screencap='screencap'
alias screenrecord='screenrecord' service='service' svc='svc'
""".trimIndent() + "\n")
            } else if (!existing.contains("/system/bin")) {
                bashrc.appendText("export PATH=\"/system/bin:/system/xbin:/vendor/bin:\$PATH\"\n")
            }

            Log.i(TAG, "Android helper + adb shim installed at ${binDir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not install android helper: ${e.message}")
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

    private fun processChunk(rawChunk: String, context: Context) {
        val chunk = ansiRegex.replace(rawChunk, "")
        // Auto-answer "Ok to proceed? (y)" in case --yes flag wasn't enough (single-shot guard)
        if (npmConfirmRegex.containsMatchIn(chunk) && !_npmConfirmSent) {
            _npmConfirmSent = true
            _state.value = CommanderState.WAITING_NPM_CONFIRM
            scope.launch {
                delay(300)
                sendInput("y")
                if (_state.value == CommanderState.WAITING_NPM_CONFIRM) {
                    _state.value = CommanderState.INSTALLING_NPM
                }
            }
        }

        // Detect device verify URL  e.g. https://mcp.desktopcommander.app/device/verify?user_code=R7FH-XQ7H
        verifyUrlRegex.find(chunk)?.value?.let { rawUrl ->
            val url = rawUrl.trim().trimEnd(')', ',', '.', '"', '\'').trim()
            val prev = _info.value.verifyUrl
            if (url.isNotBlank() && url != prev) {
                _info.value = _info.value.copy(verifyUrl = url)
                _pendingUrl.value = url
                if (_state.value != CommanderState.CONNECTED) {
                    _state.value = CommanderState.WAITING_AUTH
                }
                appendOutput("\n🔗 Verification URL detected — opening browser…\n")
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open URL: $url", e)
                }
            }
        }

        // Parse user code  e.g. R7FH-XQ7H (shown on its own line for confirmation)
        userCodeRegex.find(chunk)?.groupValues?.getOrNull(1)?.let { rawCode ->
            val code = rawCode.trim().uppercase()
            // Prefer code matching user_code= in URL when available, else accept first valid code
            val urlCode = _info.value.verifyUrl
                ?.let { Regex("user_code=([A-Za-z0-9-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.uppercase() }
            val accept = when {
                _info.value.verifyCode != null && _info.value.verifyCode == code -> false
                urlCode != null -> code == urlCode
                else -> _info.value.verifyCode == null
            }
            if (accept) {
                _info.value = _info.value.copy(verifyCode = code)
                _pairingCode.value = code
            }
        }

        // Parse expiry  "Code expires in 15 minutes"
        expiryRegex.find(chunk)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { mins ->
            _info.value = _info.value.copy(codeExpiryMinutes = mins)
        }

        // Waiting for authorization → stay in WAITING_AUTH
        if (waitingAuthRegex.containsMatchIn(chunk) && _state.value != CommanderState.CONNECTED) {
            if (_state.value != CommanderState.WAITING_AUTH) {
                _state.value = CommanderState.WAITING_AUTH
            }
        }

        // Parse connected device info
        deviceIdRegex.find(chunk)?.groupValues?.getOrNull(1)?.let { id ->
            _info.value = _info.value.copy(deviceId = id.trim())
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

        // Transition to CONNECTED — clear verify info once done
        if (deviceReadyRegex.containsMatchIn(chunk)) {
            _state.value = CommanderState.CONNECTED
            _pendingUrl.value = null
            _pairingCode.value = null
            _npmConfirmSent = false
            _info.value = _info.value.copy(verifyUrl = null, verifyCode = null, codeExpiryMinutes = null)
        }
    }
}
