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

enum class CommanderBackend {
    PROOT,
    TERMUX
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
    private var appContext: Context? = null
    private var termuxStreamServer: java.net.ServerSocket? = null
    private var termuxClientSocket: java.net.Socket? = null

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

    // Backend selection: PROOT (built-in Debian) vs TERMUX (unrestricted native Bionic)
    private val _backend = MutableStateFlow(CommanderBackend.TERMUX)
    val backend: StateFlow<CommanderBackend> = _backend.asStateFlow()

    fun setBackend(b: CommanderBackend) {
        _backend.value = b
    }

    fun start(context: Context) {
        if (_isActive.value) return
        val appCtx = context.applicationContext ?: context
        appContext = appCtx
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
                if (_backend.value == CommanderBackend.TERMUX && com.jarves.mh.termux.TermuxBridge.hasPermission(appCtx)) {
                    startTermux(appCtx)
                } else {
                    if (_backend.value == CommanderBackend.TERMUX) {
                        appendOutput("ℹ Termux permission not granted. Running in built-in PRoot…\n")
                    }
                    startPRoot(appCtx)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in start", e)
                appendOutput("\n[Error: ${e.message}]\n")
                _state.value = CommanderState.ERROR
                _isActive.value = false
            }
        }
    }

    private suspend fun startTermux(appCtx: Context) {
        appendOutput("🚀 Starting Desktop Commander in Termux (Unrestricted native mode)…\n")
        appendOutput("📱 Native Bionic environment with wake-lock active.\n")

        val bridgePort = com.jarves.mh.bridge.AndroidApiBridgeServer.PORT
        val bridgeUrl = "http://localhost:$bridgePort"

        // Check if Node.js & npx are installed in Termux
        val checkNode = withContext(Dispatchers.IO) {
            com.jarves.mh.termux.TermuxBridge.executeSync(appCtx, "check-node", "command -v node && command -v npx")
        }
        if (checkNode == null || !checkNode.isSuccess || checkNode.stdout.isNullOrBlank()) {
            appendOutput("📦 Node.js / npx not found in Termux. Running bootstrap setup...\n")
            withContext(Dispatchers.IO) {
                com.jarves.mh.termux.TermuxBridge.executeSync(
                    appCtx, "install-node",
                    "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs-lts npm termux-api android-tools",
                    120_000L
                )
            }
        }

        // Start a local TCP server to receive real-time stdout/stderr from Termux
        val server = try {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Termux stream ServerSocket", e)
            null
        }
        termuxStreamServer = server
        val streamPort = server?.localPort ?: 0

        val termuxScript = buildString {
            append("export HOME=\"/data/data/com.termux/files/home\"; ")
            append("export PATH=\"/data/data/com.termux/files/usr/bin:\$PATH\"; ")
            append("export LD_LIBRARY_PATH=\"/data/data/com.termux/files/usr/lib\"; ")
            append("export ANDROID_BRIDGE_URL=\"$bridgeUrl\"; ")
            append("export CI=1; export NPM_CONFIG_YES=true; ")
            append("termux-wake-lock 2>/dev/null; ")
            append("echo \$\$ > ~/.desktop-commander.pid; ")
            append("NODE_BIN=\"/data/data/com.termux/files/usr/bin/node\"; ")
            append("DC_JS=\"/data/data/com.termux/files/usr/lib/node_modules/@wonderwhy-er/desktop-commander/dist/index.js\"; ")
            if (streamPort > 0) {
                append("{ \"\$NODE_BIN\" \"\$DC_JS\" remote 2>&1 | tee ~/.desktop-commander.log; } | \"\$NODE_BIN\" -e 'process.stdin.pipe(require(\"net\").connect($streamPort, \"127.0.0.1\"))' 2>&1")
            } else {
                append("\"\$NODE_BIN\" \"\$DC_JS\" remote > ~/.desktop-commander.log 2>&1")
            }
        }

        val launched = com.jarves.mh.termux.TermuxBridge.launchBackground(
            appCtx, "DesktopCommander", termuxScript
        )

        if (!launched) {
            appendOutput("⚠ Failed to dispatch to Termux. Falling back to built-in PRoot…\n")
            runCatching { server?.close() }
            termuxStreamServer = null
            startPRoot(appCtx)
            return
        }

        if (server != null) {
            withContext(Dispatchers.IO) {
                try {
                    server.soTimeout = 25_000
                    val client = server.accept()
                    termuxClientSocket = client
                    val reader = client.getInputStream().bufferedReader()
                    val charBuf = CharArray(2048)
                    val outputBuffer = StringBuilder()

                    while (_isActive.value) {
                        val count = reader.read(charBuf)
                        if (count == -1) break
                        val chunk = String(charBuf, 0, count)
                        outputBuffer.append(chunk)
                        _terminalOutput.value = outputBuffer.toString().takeLast(8000)
                        processChunk(chunk, appCtx)
                    }
                } catch (e: Exception) {
                    if (_isActive.value) {
                        Log.i(TAG, "Termux stream ended or timed out: ${e.message}")
                    }
                } finally {
                    runCatching { server.close() }
                    termuxStreamServer = null
                    runCatching { termuxClientSocket?.close() }
                    termuxClientSocket = null
                }
            }
        }
    }

    private suspend fun startPRoot(appCtx: Context) {
        val inst = installer ?: return
        if (!inst.isInstalled()) {
            appendOutput("⚠ Subsystem not installed yet. Complete the main setup first.\n")
            _state.value = CommanderState.ERROR
            _isActive.value = false
            return
        }

        val installed = inst.installedRuntime()
        val workspace = File(installed.rootfs, "root")
        workspace.mkdirs()

        appendOutput("🚀 Starting Desktop Commander Remote (Built-in PRoot)…\n")
        appendOutput(
            "📱 Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                "(Android ${android.os.Build.VERSION.RELEASE})\n"
        )

        val outputFile = File(appCtx.cacheDir, "desktop-commander-out.log")
        outputFile.delete()

        val bridgePort = com.jarves.mh.bridge.AndroidApiBridgeServer.PORT
        val bridgeUrl  = "http://localhost:$bridgePort"

        // Install the 'android', 'adb', and 'termux' helper CLIs into PRoot rootfs
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
                "ANDROID_BRIDGE_URL"  to bridgeUrl,
                "ANDROID_BRIDGE_PORT" to "$bridgePort",
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
        process = null
        processStdin = null
    }

    fun stop() {
        val appCtx = appContext
        scope.launch {
            _isActive.value = false
            try {
                processStdin?.close()
                process?.destroy()
                delay(400)
                process?.destroyForcibly()
            } catch (_: Exception) {}
            process = null
            processStdin = null

            runCatching { termuxClientSocket?.close() }
            termuxClientSocket = null
            runCatching { termuxStreamServer?.close() }
            termuxStreamServer = null

            try {
                appCtx?.let {
                    if (com.jarves.mh.termux.TermuxBridge.hasPermission(it)) {
                        com.jarves.mh.termux.TermuxBridge.launchBackground(
                            it, "StopDC",
                            "test -f ~/.desktop-commander.pid && kill -9 $(cat ~/.desktop-commander.pid) 2>/dev/null; killall node 2>/dev/null; termux-wake-unlock 2>/dev/null; rm -f ~/.desktop-commander.pid"
                        )
                    }
                }
            } catch (_: Exception) {}

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

            // ── termux CLI shim — routes to /termux/exec on bridge ──────────────
            val termuxScript = File(binDir, "termux")
            termuxScript.writeText("""
#!/usr/bin/env bash
BRIDGE="${'$'}{ANDROID_BRIDGE_URL:-$bridgeUrl}"
if [ "${'$'}#" -eq 0 ]; then
  curl -sf "${'$'}BRIDGE/termux/status"
  exit 0
fi

CMD_ARG="$(python3 -c "import json,sys; print(json.dumps(' '.join(sys.argv[1:])))" "$@")"
curl -sf "${'$'}BRIDGE/termux/exec" -H 'Content-Type: application/json' \
  -d "{\"cmd\": ${'$'}CMD_ARG}" | \
  python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    if 'stdout' in d: sys.stdout.write(d['stdout'])
    if 'stderr' in d: sys.stderr.write(d['stderr'])
    if 'error' in d: sys.stderr.write('Error: ' + str(d['error']) + '\n')
    sys.exit(d.get('exit_code', 0))
except Exception as e:
    sys.stderr.write(str(e) + '\n')
    sys.exit(1)
"
""".trimIndent())
            termuxScript.setExecutable(true)

            val termuxExecScript = File(binDir, "termux-exec")
            termuxExecScript.writeText("""
#!/usr/bin/env bash
exec /usr/local/bin/termux "${'$'}@"
""".trimIndent())
            termuxExecScript.setExecutable(true)

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

                // If user_code is embedded in the URL, extract and copy it immediately
                val embeddedCode = Regex("user_code=([A-Za-z0-9-]+)", RegexOption.IGNORE_CASE)
                    .find(url)?.groupValues?.getOrNull(1)?.uppercase()
                if (embeddedCode != null) {
                    _info.value = _info.value.copy(verifyCode = embeddedCode)
                    _pairingCode.value = embeddedCode
                    try {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("MCP Auth Code", embeddedCode))
                        appendOutput("📋 Auth code copied: $embeddedCode\n")
                    } catch (_: Exception) {}
                }

                appendOutput("\n🔗 Opening browser for verification…\n")
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open URL: $url", e)
                }
            }
        }

        // Parse user code e.g. R7FH-XQ7H if printed separately
        userCodeRegex.find(chunk)?.groupValues?.getOrNull(1)?.let { rawCode ->
            val code = rawCode.trim().uppercase()
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
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("MCP Auth Code", code))
                    appendOutput("📋 Auth code copied: $code\n")
                } catch (_: Exception) {}
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
