package com.jarves.mh.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Result data class for commands executed via Termux RUN_COMMAND.
 */
data class TermuxResult(
    val reqId: Int = 0,
    val label: String = "",
    val cmd: String = "",
    val stdout: String? = null,
    val stderr: String? = null,
    val exitCode: Int? = null,
    val err: Int? = null,
    val errmsg: String? = null,
    val internalError: String? = null
) {
    val isSuccess: Boolean get() = internalError == null && (err == null || err == 0 || err == -1) && (exitCode == null || exitCode == 0)

    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label)
        put("cmd", cmd)
        put("stdout", stdout ?: "")
        put("stderr", stderr ?: "")
        put("exit_code", exitCode ?: -1)
        put("err", err ?: 0)
        put("errmsg", errmsg ?: "")
        put("internal_error", internalError ?: "")
        put("success", isSuccess)
    }
}

enum class TermuxStatus(
    val label: String,
    val description: String
) {
    READY("READY", "Termux installed, permission granted, external apps enabled"),
    NEEDS_ALLOW_EXTERNAL_APPS("CONFIG NEEDED", "Missing 'allow-external-apps = true' in ~/.termux/termux.properties"),
    NEEDS_PERMISSION("PERMISSION NEEDED", "Missing com.termux.permission.RUN_COMMAND"),
    INSTALLED("INSTALLED", "Termux installed, initial setup needed"),
    NOT_INSTALLED("NOT INSTALLED", "Termux is not installed on this device")
}

/**
 * BroadcastReceiver triggered by Termux when a RUN_COMMAND PendingIntent completes.
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "?"
        val cmd = intent.getStringExtra(EXTRA_CMD) ?: ""
        val reqId = intent.getIntExtra(EXTRA_REQ_ID, 0)
        val b: Bundle? = intent.getBundleExtra(KEY_RESULT_BUNDLE)
        val result = if (b == null) {
            TermuxResult(
                reqId = reqId,
                label = label,
                cmd = cmd,
                internalError = "result bundle missing"
            )
        } else {
            TermuxResult(
                reqId = reqId,
                label = label,
                cmd = cmd,
                stdout = b.getString(KEY_STDOUT),
                stderr = b.getString(KEY_STDERR),
                exitCode = if (b.containsKey(KEY_EXIT_CODE)) b.getInt(KEY_EXIT_CODE) else null,
                err = if (b.containsKey(KEY_ERR)) b.getInt(KEY_ERR) else null,
                errmsg = b.getString(KEY_ERRMSG) ?: ""
            )
        }
        Log.i("TermuxResultReceiver", "Result [$reqId]: rc=${result.exitCode}, err=${result.err}")
        TermuxBridge.deliver(result)
    }

    companion object {
        const val EXTRA_LABEL = "mh_label"
        const val EXTRA_CMD = "mh_cmd"
        const val EXTRA_REQ_ID = "mh_req_id"
        const val KEY_RESULT_BUNDLE = "result"
        const val KEY_STDOUT = "stdout"
        const val KEY_STDERR = "stderr"
        const val KEY_EXIT_CODE = "exitCode"
        const val KEY_ERR = "err"
        const val KEY_ERRMSG = "errmsg"
    }
}

/**
 * Singleton bridge to Termux RUN_COMMAND service.
 * Allows executing unrestricted bash commands directly in Termux.
 */
object TermuxBridge {
    const val TAG = "TermuxBridge"

    const val TERMUX_PKG = "com.termux"
    const val TERMUX_SVC = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

    const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    const val BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
    const val HOME_DIR = "/data/data/com.termux/files/home"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"

    // One-liner bootstrap script for Termux:
    const val BOOTSTRAP_SCRIPT =
        "pkg update -y && pkg install -y nodejs-lts npm android-tools termux-api jq curl git libc++ && " +
        "npm install -g @wonderwhy-er/desktop-commander && " +
        "mkdir -p ~/.termux && " +
        "grep -q 'allow-external-apps' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps = true' >> ~/.termux/termux.properties && " +
        "termux-reload-settings && " +
        "echo '[OK] Mobile-Harness Termux bootstrap complete!'"

    private val pending = ConcurrentHashMap<Int, java.util.concurrent.ArrayBlockingQueue<TermuxResult>>()
    private val idGen = AtomicInteger(7000)

    @Volatile
    private var cachedWorking: Boolean? = null
    @Volatile
    private var lastCheckTime: Long = 0L

    fun isInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(TERMUX_PKG, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(TERMUX_PKG, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    fun autoConfigureIfRooted(context: Context): Boolean {
        return try {
            val proc = ProcessBuilder("su", "-c",
                "pm grant ${context.packageName} $PERMISSION; " +
                "mkdir -p $HOME_DIR/.termux; " +
                "grep -q 'allow-external-apps' $HOME_DIR/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps = true' >> $HOME_DIR/.termux/termux.properties; " +
                "am broadcast --user 0 -a com.termux.app.reload_style com.termux"
            ).redirectErrorStream(true).start()
            val ok = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0
            if (ok) {
                cachedWorking = null
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    fun checkStatus(context: Context): TermuxStatus {
        if (!isInstalled(context)) return TermuxStatus.NOT_INSTALLED
        if (!hasPermission(context)) {
            if (autoConfigureIfRooted(context) && hasPermission(context)) {
                // successfully auto-granted via root
            } else {
                return TermuxStatus.NEEDS_PERMISSION
            }
        }
        if (isExecutionWorking(context)) return TermuxStatus.READY
        // Try auto-enabling allow-external-apps via root
        if (autoConfigureIfRooted(context) && isExecutionWorking(context, forceRecheck = true)) {
            return TermuxStatus.READY
        }
        return TermuxStatus.NEEDS_ALLOW_EXTERNAL_APPS
    }

    fun isExecutionWorking(context: Context, forceRecheck: Boolean = false): Boolean {
        if (!hasPermission(context)) return false
        val now = System.currentTimeMillis()
        if (!forceRecheck && cachedWorking == true && (now - lastCheckTime < 30_000L)) {
            return true
        }
        val ok = verifyExecution(context, timeoutMs = 8000L)
        if (ok) {
            cachedWorking = true
            lastCheckTime = now
        } else {
            cachedWorking = null
        }
        return ok
    }

    fun verifyExecution(context: Context, timeoutMs: Long = 8000L): Boolean {
        if (!hasPermission(context)) return false
        val res = executeSync(context, "test", "echo 'MH_TERMUX_OK'", timeoutMs = timeoutMs)
        val ok = res != null && res.isSuccess && (res.stdout?.contains("MH_TERMUX_OK") == true)
        Log.i(TAG, "verifyExecution: ok=$ok, stdout='${res?.stdout?.trim()}', err=${res?.err}, rc=${res?.exitCode}")
        return ok
    }

    fun deliver(result: TermuxResult) {
        val q = pending[result.reqId]
        Log.i(TAG, "deliver result: reqId=${result.reqId}, qFound=${q != null}, res=${result.toJson()}")
        q?.offer(result)
    }

    /**
     * Executes a command in Termux and waits synchronously for the result.
     */
    fun executeSync(
        context: Context,
        label: String,
        command: String,
        timeoutMs: Long = 30_000L,
        background: Boolean = true
    ): TermuxResult? {
        if (!hasPermission(context)) return null
        val id = idGen.incrementAndGet()
        val queue = java.util.concurrent.ArrayBlockingQueue<TermuxResult>(1)
        pending[id] = queue

        try {
            val piFlags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE

            val resultIntent = Intent(context, TermuxResultReceiver::class.java).apply {
                setPackage(context.packageName)
                putExtra(TermuxResultReceiver.EXTRA_LABEL, label)
                putExtra(TermuxResultReceiver.EXTRA_CMD, command)
                putExtra(TermuxResultReceiver.EXTRA_REQ_ID, id)
            }

            val pi = PendingIntent.getBroadcast(context, id, resultIntent, piFlags)

            val intent = Intent().apply {
                component = ComponentName(TERMUX_PKG, TERMUX_SVC)
                action = ACTION_RUN_COMMAND
                putExtra(EXTRA_PATH, BASH_PATH)
                putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
                putExtra(EXTRA_WORKDIR, HOME_DIR)
                putExtra(EXTRA_BACKGROUND, background)
                putExtra(EXTRA_COMMAND_LABEL, label)
                putExtra(EXTRA_SESSION_ACTION, "0")
                putExtra(EXTRA_PENDING_INTENT, pi)
            }

            Log.i(TAG, "executeSync dispatching [$id]: $command")

            try {
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Termux service", e)
                return TermuxResult(id, label, command, internalError = "startService failed: ${e.message}")
            }

            val effectiveTimeout = if (timeoutMs <= 0L) 30_000L else timeoutMs
            val res = queue.poll(effectiveTimeout, TimeUnit.MILLISECONDS)
            Log.i(TAG, "executeSync completed [$id]: res=$res")
            return res
        } catch (e: Exception) {
            return TermuxResult(id, label, command, internalError = "dispatch failed: ${e.message}")
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Suspending coroutine execute.
     */
    suspend fun execute(
        context: Context,
        label: String,
        command: String,
        timeoutMs: Long = 30_000L,
        background: Boolean = true
    ): TermuxResult? = withContext(Dispatchers.IO) {
        executeSync(context, label, command, timeoutMs, background)
    }

    /**
     * Dispatches a command in Termux without waiting for completion (fire-and-forget daemon).
     */
    fun launchBackground(
        context: Context,
        label: String,
        command: String
    ): Boolean {
        if (!hasPermission(context)) return false
        return try {
            val intent = Intent().apply {
                component = ComponentName(TERMUX_PKG, TERMUX_SVC)
                action = ACTION_RUN_COMMAND
                putExtra(EXTRA_PATH, BASH_PATH)
                putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
                putExtra(EXTRA_WORKDIR, HOME_DIR)
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_SESSION_ACTION, "0")
                putExtra(EXTRA_COMMAND_LABEL, label)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch background command in Termux", e)
            false
        }
    }

    fun openTermux(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PKG)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    fun openInstallPage(context: Context, fdroid: Boolean = true) {
        val url = if (fdroid) {
            "https://f-droid.org/packages/com.termux/"
        } else {
            "https://github.com/termux/termux-app/releases/latest"
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {}
    }
}
