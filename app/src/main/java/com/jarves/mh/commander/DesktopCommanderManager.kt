package com.jarves.mh.commander

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarves.mh.runtime.NativeSpawnProcess
import com.jarves.mh.runtime.RuntimeInstaller
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile

/**
 * Manages the background execution of:
 * `npx @wonderwhy-er/desktop-commander@latest remote`
 * inside Mobile Harness's embedded PRoot Linux subsystem.
 *
 * Automatically captures terminal outputs, pairing codes, and status.
 */
object DesktopCommanderManager {

    private const val TAG = "DesktopCommander"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var process: Process? = null
    private var installer: RuntimeInstaller? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _statusText = MutableStateFlow("Idle")
    val statusText: StateFlow<Boolean> = MutableStateFlow(false) // helper placeholder

    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

    private val _terminalOutput = MutableStateFlow<String>("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()

    private val pairingCodeRegex = Regex("([A-Z0-9]{4}-[A-Z0-9]{4}|\\b[0-9]{6}\\b|code:\\s*([A-Za-z0-9-]+))", RegexOption.IGNORE_CASE)

    fun start(context: Context) {
        if (_isRunning.value) return
        val appCtx = context.applicationContext ?: context
        installer = RuntimeInstaller(appCtx)

        scope.launch {
            try {
                val inst = installer ?: return@launch
                if (!inst.isInstalled()) {
                    Log.w(TAG, "Subsystem not installed yet; skipping autostart.")
                    _terminalOutput.value = "Waiting for core Linux subsystem installation...\n"
                    return@launch
                }

                val installed = inst.installedRuntime()
                val workspace = File(installed.rootfs, "root")
                workspace.mkdirs()

                _isRunning.value = true
                _terminalOutput.value = "Starting npx @wonderwhy-er/desktop-commander@latest remote...\n"
                Log.i(TAG, "Launching Desktop Commander in PRoot Linux...")

                val outputFile = File(appCtx.cacheDir, "desktop-commander-${System.currentTimeMillis()}.log")
                
                // Command: execute via bash -lc with non-interactive headless env if needed
                val guestCommand = listOf(
                    "/usr/bin/env", "bash", "-lc",
                    "npx -y @wonderwhy-er/desktop-commander@latest remote"
                )

                val proc = inst.process(
                    proot = installed.proot,
                    rootfs = installed.rootfs,
                    workspace = workspace,
                    environment = mapOf(
                        "HOME" to "/root",
                        "CI" to "1",
                        "TERM" to "xterm-256color"
                    ),
                    guestCommand = guestCommand,
                    outputFile = outputFile,
                    pseudoTerminal = false
                )

                process = proc
                val native = proc as? NativeSpawnProcess

                var offset = 0L
                val outputBuffer = StringBuilder()

                while (proc.isAlive || (outputFile.exists() && outputFile.length() > offset)) {
                    val currentLen = outputFile.length()
                    if (currentLen > offset) {
                        val available = (currentLen - offset).toInt()
                        val bytes = ByteArray(minOf(available, 8192))
                        RandomAccessFile(outputFile, "r").use { file ->
                            file.seek(offset)
                            val read = file.read(bytes)
                            if (read > 0) {
                                offset += read
                                val chunk = bytes.decodeToString(0, read)
                                outputBuffer.append(chunk)

                                val cleanText = outputBuffer.toString()
                                _terminalOutput.value = cleanText.takeLast(4000)

                                // Attempt to parse pairing code if present
                                val match = pairingCodeRegex.find(chunk)
                                if (match != null) {
                                    val code = match.value.trim()
                                    _pairingCode.value = code
                                    Log.i(TAG, "Found Desktop Commander Pairing Code: $code")
                                }
                            }
                        }
                    } else {
                        delay(200)
                    }
                }

                val exit = proc.waitFor()
                Log.i(TAG, "Desktop Commander exited with code $exit")
                _isRunning.value = false
                _terminalOutput.value += "\n[Process exited with code $exit]"
            } catch (e: Exception) {
                Log.e(TAG, "Error running Desktop Commander", e)
                _isRunning.value = false
                _terminalOutput.value += "\nError: ${e.message}"
            } finally {
                process = null
            }
        }
    }

    fun stop() {
        scope.launch {
            try {
                process?.destroy()
                delay(300)
                if (process?.isAlive == true) {
                    process?.destroyForcibly()
                }
            } catch (_: Exception) {}
            process = null
            _isRunning.value = false
            _terminalOutput.value += "\n[Stopped]"
        }
    }
}
