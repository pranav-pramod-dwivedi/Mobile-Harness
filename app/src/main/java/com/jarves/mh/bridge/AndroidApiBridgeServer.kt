package com.jarves.mh.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarves.mh.capture.ScreenSpoofAccessibilityService
import com.jarves.mh.cursor.CursorEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CompletableFuture

/**
 * AndroidApiBridgeServer — a local HTTP server on port 9898 that exposes full
 * Android system capabilities to programs running inside the PRoot Linux
 * environment (e.g. Desktop Commander / the AI's terminal session).
 *
 * It is the app-side equivalent of Termux:API.  Every endpoint can be called
 * with a plain HTTP request from inside PRoot:
 *
 *   curl http://localhost:9898/shell -d '{"cmd":"pm list packages"}'
 *   curl http://localhost:9898/tap   -d '{"x":540,"y":1200}'
 *   curl http://localhost:9898/battery
 *   curl http://localhost:9898/clipboard
 *   curl http://localhost:9898/open  -d '{"url":"https://google.com"}'
 *   ... etc.
 */
object AndroidApiBridgeServer {

    const val PORT = 9898
    const val TAG  = "AndroidApiBridge"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun start(context: Context) {
        if (_isRunning.value) return
        val appCtx = context.applicationContext
        scope.launch {
            try {
                val ss = ServerSocket(PORT)
                serverSocket = ss
                _isRunning.value = true
                Log.i(TAG, "Android API Bridge listening on :$PORT")
                while (_isRunning.value) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    launch { handleClient(client, appCtx) }
                }
            } catch (e: Exception) {
                if (_isRunning.value) Log.e(TAG, "Bridge error", e)
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun stop() {
        _isRunning.value = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    // ── HTTP handling ──────────────────────────────────────────────────────

    private fun handleClient(socket: Socket, ctx: Context) {
        try {
            val input  = socket.getInputStream().bufferedReader()
            val output = socket.getOutputStream()

            // Parse request line
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1]
            val (path, queryString) = rawPath.split("?", limit = 2)
                .let { it[0] to (it.getOrElse(1) { "" }) }
            val query = parseQuery(queryString)

            // Read headers
            val headers = mutableMapOf<String, String>()
            var line: String
            do {
                line = input.readLine() ?: break
                if (line.contains(":")) {
                    val (k, v) = line.split(":", limit = 2)
                    headers[k.trim().lowercase()] = v.trim()
                }
            } while (line.isNotEmpty())

            // Read body
            val bodyLen = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyChars = CharArray(bodyLen)
            if (bodyLen > 0) input.read(bodyChars, 0, bodyLen)
            val body = String(bodyChars)
            val json = if (body.isNotBlank()) runCatching { JSONObject(body) }.getOrNull() else null

            // OPTIONS preflight
            if (method == "OPTIONS") {
                sendResponse(output, 200, "OK", "text/plain", "")
                return
            }

            val response: JSONObject = when (path) {
                // ── Termux execution & status ──────────────────────────────
                "/termux/status", "/termux/info" -> getTermuxStatus(ctx)
                "/termux/exec", "/termux/shell" -> {
                    val cmd = json?.optString("cmd") ?: query["cmd"] ?: ""
                    val timeout = (if (json != null && json.has("timeout_ms")) json.optLong("timeout_ms", 30_000L) else query["timeout_ms"]?.toLongOrNull() ?: 30_000L).coerceAtLeast(1000L)
                    if (cmd.isBlank()) {
                        errorJson("Missing 'cmd' parameter")
                    } else {
                        runTermux(ctx, cmd, timeout)
                    }
                }

                // ── Core shell execution ────────────────────────────────────
                "/shell", "/exec" -> {
                    val cmd = json?.optString("cmd") ?: query["cmd"] ?: ""
                    val env = (json?.optString("env") ?: query["env"] ?: "auto").lowercase()
                    if (cmd.isBlank()) {
                        errorJson("Missing 'cmd' parameter")
                    } else if (env == "termux" || (env == "auto" && shouldRouteToTermux(ctx, cmd))) {
                        runTermux(ctx, cmd)
                    } else {
                        runShell(cmd)
                    }
                }

                // ── Battery ────────────────────────────────────────────────
                "/battery" -> getBattery(ctx)

                // ── WiFi / network ─────────────────────────────────────────
                "/wifi", "/network" -> getNetwork(ctx)

                // ── Clipboard read / write ─────────────────────────────────
                "/clipboard" -> {
                    if (method == "POST") {
                        val text = json?.optString("text") ?: query["text"] ?: ""
                        setClipboard(ctx, text)
                    } else {
                        getClipboard(ctx)
                    }
                }

                // ── Location ───────────────────────────────────────────────
                "/location" -> getLocation(ctx)

                // ── Installed packages ─────────────────────────────────────
                "/packages" -> getPackages(ctx)

                // ── Open URL / app ─────────────────────────────────────────
                "/open" -> {
                    val url = json?.optString("url") ?: query["url"] ?: ""
                    openUrl(ctx, url)
                }

                // ── Launch app by package name ─────────────────────────────
                "/launch" -> {
                    val pkg = json?.optString("package") ?: query["package"] ?: ""
                    launchApp(ctx, pkg)
                }

                // ── Send notification ──────────────────────────────────────
                "/notify" -> {
                    val title = json?.optString("title") ?: "Mobile Harness"
                    val msg   = json?.optString("message") ?: query["message"] ?: ""
                    sendNotification(ctx, title, msg)
                }

                // ── Tap / gesture via CursorEngine ─────────────────────────
                "/tap" -> {
                    val x = (json?.optDouble("x") ?: query["x"]?.toDoubleOrNull() ?: 540.0).toFloat()
                    val y = (json?.optDouble("y") ?: query["y"]?.toDoubleOrNull() ?: 1200.0).toFloat()
                    performTap(x, y)
                }
                "/swipe" -> {
                    val x1 = (json?.optDouble("x1") ?: 540.0).toFloat()
                    val y1 = (json?.optDouble("y1") ?: 1000.0).toFloat()
                    val x2 = (json?.optDouble("x2") ?: 540.0).toFloat()
                    val y2 = (json?.optDouble("y2") ?: 500.0).toFloat()
                    val dur = json?.optInt("duration") ?: 250
                    performSwipe(x1, y1, x2, y2, dur)
                }
                "/type", "/input" -> {
                    val text = json?.optString("text") ?: query["text"] ?: ""
                    performType(text)
                }
                "/key" -> {
                    val key = json?.optString("key") ?: query["key"] ?: "back"
                    performKey(key)
                }

                // ── Screen info ────────────────────────────────────────────
                "/screen" -> getScreenInfo(ctx)

                // ── Device info ────────────────────────────────────────────
                "/device", "/info" -> getDeviceInfo(ctx)

                // ── Contacts ───────────────────────────────────────────────
                "/contacts" -> getContacts(ctx)

                // ── Screen capture text (CLI spoof) ────────────────────────
                "/capture", "/spoof" -> captureScreen(ctx)

                // ── Settings read / write ──────────────────────────────────
                "/settings" -> {
                    val key   = json?.optString("key")   ?: query["key"]   ?: ""
                    val value = json?.optString("value") ?: query["value"]
                    if (method == "POST" && key.isNotBlank() && value != null) {
                        writeSetting(key, value)
                    } else {
                        readSetting(ctx, key)
                    }
                }

                // ── List endpoints ─────────────────────────────────────────
                "/", "/help" -> helpJson()

                else -> errorJson("Unknown endpoint: $path")
            }

            sendResponse(output, 200, "OK", "application/json", response.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Client error: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    // ── Endpoint implementations ───────────────────────────────────────────

    /** Run any Android shell command and return stdout+stderr */
    private fun runShell(cmd: String): JSONObject {
        return try {
            val proc = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()

            val out = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()

            JSONObject().apply {
                put("stdout", out)
                put("exit_code", exitCode)
                put("success", exitCode == 0)
                put("cmd", cmd)
            }
        } catch (e: Exception) {
            errorJson("Shell error: ${e.message}")
        }
    }

    private fun getBattery(ctx: Context): JSONObject {
        val intent = ctx.registerReceiver(null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level  = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale  = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING    -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL        -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        return JSONObject().apply {
            put("percentage", if (scale > 0) (level * 100.0 / scale).toInt() else -1)
            put("status", status)
            put("temperature_celsius", temp)
        }
    }

    private fun getNetwork(ctx: Context): JSONObject {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ssid = if (wifi) wm?.connectionInfo?.ssid?.replace("\"", "") else null
        return JSONObject().apply {
            put("connected", caps != null)
            put("wifi", wifi)
            put("cellular", cell)
            put("ssid", ssid ?: JSONObject.NULL)
        }
    }

    private fun getClipboard(ctx: Context): JSONObject {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        return JSONObject().apply { put("text", text) }
    }

    private fun setClipboard(ctx: Context, text: String): JSONObject {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("android-bridge", text))
        return JSONObject().apply { put("success", true); put("text", text) }
    }

    private fun getLocation(ctx: Context): JSONObject {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return errorJson("Location permission not granted. Grant it in App Settings.")
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        return if (loc != null) {
            JSONObject().apply {
                put("latitude", loc.latitude)
                put("longitude", loc.longitude)
                put("accuracy", loc.accuracy)
                put("altitude", loc.altitude)
                put("provider", loc.provider)
            }
        } else errorJson("Location not available. Enable GPS and try again.")
    }

    private fun getPackages(ctx: Context): JSONObject {
        val pm = ctx.packageManager
        val pkgs = pm.getInstalledPackages(0).map { it.packageName }
        return JSONObject().apply {
            put("count", pkgs.size)
            put("packages", JSONArray(pkgs))
        }
    }

    private fun openUrl(ctx: Context, url: String): JSONObject {
        return if (url.isBlank()) errorJson("Missing 'url'") else {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            JSONObject().apply { put("success", true); put("url", url) }
        }
    }

    private fun launchApp(ctx: Context, pkg: String): JSONObject {
        return if (pkg.isBlank()) errorJson("Missing 'package'") else {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                JSONObject().apply { put("success", true); put("package", pkg) }
            } else errorJson("Package not found: $pkg")
        }
    }

    private fun sendNotification(ctx: Context, title: String, message: String): JSONObject {
        val channelId = "android_bridge_notify"
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "AI Notifications", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notif = NotificationCompat.Builder(ctx, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 10000).toInt(), notif)
        return JSONObject().apply { put("success", true) }
    }

    private fun performTap(x: Float, y: Float): JSONObject {
        val future = CompletableFuture<Boolean>()
        if (ScreenSpoofAccessibilityService.isConnected()) {
            CursorEngine.tap(x, y, 50) { future.complete(it) }
        } else {
            // Fallback: Android shell input command
            val result = runShell("input tap ${x.toInt()} ${y.toInt()}")
            return JSONObject().apply {
                put("success", result.optInt("exit_code") == 0)
                put("method", "shell")
                put("x", x); put("y", y)
            }
        }
        val ok = runCatching { future.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { false }
        return JSONObject().apply {
            put("success", ok); put("method", "accessibility"); put("x", x); put("y", y)
        }
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Int): JSONObject {
        if (!ScreenSpoofAccessibilityService.isConnected()) {
            return runShell("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $dur")
        }
        val future = CompletableFuture<Boolean>()
        CursorEngine.swipe(x1, y1, x2, y2, dur.toLong()) { future.complete(it) }
        val ok = runCatching { future.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { false }
        return JSONObject().apply { put("success", ok); put("method", "accessibility") }
    }

    private fun performType(text: String): JSONObject {
        if (!ScreenSpoofAccessibilityService.isConnected()) {
            // Escape special chars for shell
            val escaped = text.replace("'", "'\\''")
            return runShell("input text '$escaped'")
        }
        val future = CompletableFuture<Boolean>()
        CursorEngine.typeText(text) { future.complete(it) }
        val ok = runCatching { future.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { false }
        return JSONObject().apply { put("success", ok) }
    }

    private fun performKey(key: String): JSONObject {
        val keycode = when (key.lowercase()) {
            "back"    -> "KEYCODE_BACK"
            "home"    -> "KEYCODE_HOME"
            "recents", "recent" -> "KEYCODE_APP_SWITCH"
            "power"   -> "KEYCODE_POWER"
            "volume_up" -> "KEYCODE_VOLUME_UP"
            "volume_down" -> "KEYCODE_VOLUME_DOWN"
            "enter"   -> "KEYCODE_ENTER"
            "tab"     -> "KEYCODE_TAB"
            "delete", "del", "backspace" -> "KEYCODE_DEL"
            else      -> key.uppercase().let {
                if (it.startsWith("KEYCODE_")) it else "KEYCODE_$it"
            }
        }
        return runShell("input keyevent $keycode")
    }

    private fun getScreenInfo(ctx: Context): JSONObject {
        val dm = ctx.resources.displayMetrics
        return JSONObject().apply {
            put("width_px", dm.widthPixels)
            put("height_px", dm.heightPixels)
            put("density", dm.density)
            put("dpi", dm.densityDpi)
        }
    }

    private fun getDeviceInfo(ctx: Context): JSONObject {
        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("device", Build.DEVICE)
            put("brand", Build.BRAND)
            put("package", ctx.packageName)
        }
    }

    private fun getContacts(ctx: Context): JSONObject {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            return errorJson("Contacts permission not granted.")
        }
        val contacts = JSONArray()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null
        )?.use { cursor ->
            val nameIdx  = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                contacts.put(JSONObject().apply {
                    put("name",  cursor.getString(nameIdx) ?: "")
                    put("phone", cursor.getString(phoneIdx) ?: "")
                })
            }
        }
        return JSONObject().apply { put("count", contacts.length()); put("contacts", contacts) }
    }

    private fun captureScreen(ctx: Context): JSONObject {
        if (!ScreenSpoofAccessibilityService.isConnected()) {
            return errorJson("Accessibility service not connected. Enable ScreenSpoof in Settings > Accessibility.")
        }
        val future = CompletableFuture<String>()
        ScreenSpoofAccessibilityService.captureAndSpoof(ctx) { result ->
            future.complete(result.fullContent)
        }
        return try {
            val text = future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            JSONObject().apply { put("success", true); put("screen_text", text) }
        } catch (e: Exception) {
            errorJson("Capture timed out: ${e.message}")
        }
    }

    private fun readSetting(ctx: Context, key: String): JSONObject {
        if (key.isBlank()) return errorJson("Missing 'key'")
        val value = Settings.System.getString(ctx.contentResolver, key)
            ?: Settings.Secure.getString(ctx.contentResolver, key)
            ?: Settings.Global.getString(ctx.contentResolver, key)
        return JSONObject().apply { put("key", key); put("value", value ?: JSONObject.NULL) }
    }

    private fun writeSetting(key: String, value: String): JSONObject {
        // Only possible for WRITE_SETTINGS permission — use shell as fallback
        return runShell("settings put system $key $value")
    }

    private fun getTermuxStatus(ctx: Context): JSONObject {
        val installed = com.jarves.mh.termux.TermuxBridge.isInstalled(ctx)
        val perm = com.jarves.mh.termux.TermuxBridge.hasPermission(ctx)
        val status = com.jarves.mh.termux.TermuxBridge.checkStatus(ctx)
        return JSONObject().apply {
            put("installed", installed)
            put("permission", perm)
            put("status", status.name)
            put("status_label", status.label)
            put("description", status.description)
            put("ready", status == com.jarves.mh.termux.TermuxStatus.READY)
        }
    }

    private fun shouldRouteToTermux(ctx: Context, cmd: String): Boolean {
        if (!com.jarves.mh.termux.TermuxBridge.hasPermission(ctx)) return false
        val trimmed = cmd.trim()
        return trimmed.startsWith("pkg ") ||
               trimmed.startsWith("apt ") ||
               trimmed.startsWith("termux-") ||
               trimmed.startsWith("tsu") ||
               trimmed.startsWith("proot-distro")
    }

    private fun runTermux(ctx: Context, cmd: String, timeoutMs: Long = 30_000L): JSONObject {
        val res = com.jarves.mh.termux.TermuxBridge.executeSync(ctx, "bridge", cmd, timeoutMs)
        return if (res != null) {
            JSONObject().apply {
                put("stdout", res.stdout ?: "")
                put("stderr", res.stderr ?: "")
                put("exit_code", res.exitCode ?: -1)
                put("err", res.err ?: 0)
                put("errmsg", res.errmsg ?: "")
                put("success", res.isSuccess)
                put("cmd", cmd)
                put("environment", "termux")
                if (res.internalError != null) put("internal_error", res.internalError)
            }
        } else {
            errorJson("Termux execution timed out or failed to dispatch")
        }
    }

    private fun helpJson(): JSONObject {
        val endpoints = JSONArray(listOf(
            "/shell         POST {cmd[, env]}           — Run Android shell or Termux command",
            "/termux/exec   POST {cmd[, timeout_ms]}    — Run command in Termux (unrestricted)",
            "/termux/status GET                         — Termux installation & permission status",
            "/battery       GET                         — Battery level, status, temp",
            "/wifi          GET                         — Network / WiFi info",
            "/clipboard     GET|POST {text}             — Read or write clipboard",
            "/location      GET                         — GPS location (needs permission)",
            "/packages      GET                         — List installed apps",
            "/open          POST {url}                  — Open URL in browser",
            "/launch        POST {package}              — Launch app by package name",
            "/notify        POST {title, message}       — Send a notification",
            "/tap           POST {x, y}                 — Tap screen at coordinates",
            "/swipe         POST {x1,y1,x2,y2,duration} — Swipe gesture",
            "/type          POST {text}                 — Type text via input",
            "/key           POST {key}                  — Key event (back/home/power/etc)",
            "/screen        GET                         — Screen resolution & density",
            "/device        GET                         — Device model, Android version",
            "/contacts      GET                         — Contact list (needs permission)",
            "/capture       GET                         — CLI text wireframe of current screen",
            "/settings      GET|POST {key[, value]}     — Android Settings read/write"
        ))
        return JSONObject().apply {
            put("name", "Android API Bridge")
            put("port", PORT)
            put("endpoints", endpoints)
        }
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────

    private fun sendResponse(out: OutputStream, code: Int, status: String,
                              contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $contentType; charset=UTF-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun parseQuery(qs: String): Map<String, String> =
        if (qs.isBlank()) emptyMap()
        else qs.split("&").mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) URLDecoder.decode(parts[0], "UTF-8") to
                    URLDecoder.decode(parts[1], "UTF-8")
            else null
        }.toMap()

    private fun errorJson(msg: String) =
        JSONObject().apply { put("error", msg); put("success", false) }
}
