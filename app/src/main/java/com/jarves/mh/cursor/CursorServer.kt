package com.jarves.mh.cursor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.util.Log
import com.jarves.mh.capture.ScreenSpoofAccessibilityService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Pure Java ServerSocket HTTP server running on port 8899.
 * 100% native on Android without any external dependencies or missing JVM classes.
 * Provides a direct, root-free, ADB-free API for AI models (Jarvis, Gemini, Claude)
 * to control the phone, execute multi-touch gestures, inject text, trigger nav actions,
 * and receive the freshly spoofed CLI UI hierarchy in milliseconds.
 */
object CursorServer {

    private const val TAG = "CursorServer"
    const val PORT = 8899

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val workerPool = Executors.newFixedThreadPool(6)
    private var appContext: Context? = null

    @Volatile
    var isRunning = false
        private set

    fun start(context: Context) {
        if (isRunning) return
        val appCtx = context.applicationContext ?: context
        appContext = appCtx

        // Ensure overlay is active
        CursorOverlayManager.show(appCtx)

        scope.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                serverSocket = ss
                isRunning = true
                Log.i(TAG, "CursorServer successfully listening on port $PORT")

                while (isRunning && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        workerPool.execute {
                            handleClient(client)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "CursorServer startup failed: ${e.message}")
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        scope.launch {
            try {
                serverSocket?.close()
            } catch (_: Exception) {}
            serverSocket = null
            Log.i(TAG, "CursorServer stopped")
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr.hostAddress?.indexOf(':') == -1) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    // ------------------------------------------------------------------------
    // Client Connection Handler
    // ------------------------------------------------------------------------

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val uri = parts[1]

            // Read headers
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val lower = line!!.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter("content-length:").trim().toIntOrNull() ?: 0
                }
            }

            // Read body if POST
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val count = reader.read(buf, readTotal, contentLength - readTotal)
                    if (count == -1) break
                    readTotal += count
                }
                String(buf, 0, readTotal)
            } else ""

            // Handle CORS OPTIONS
            if (method == "OPTIONS") {
                sendRawResponse(out, 204, "No Content", "text/plain", "")
                return
            }

            when {
                uri.startsWith("/cursor/act") -> handleAct(out, method, body)
                uri.startsWith("/cursor/spoof") -> handleSpoof(out)
                uri.startsWith("/cursor/status") -> handleStatus(out)
                uri.startsWith("/cursor/batch") -> handleBatch(out, body)
                else -> handleDashboard(out)
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------------
    // Endpoint Handlers
    // ------------------------------------------------------------------------

    private fun handleAct(out: OutputStream, method: String, body: String) {
        if (method != "POST") {
            sendJson(out, 405, JSONObject().put("error", "Use POST"))
            return
        }

        val ctx = appContext ?: run {
            sendJson(out, 500, JSONObject().put("error", "Context not ready"))
            return
        }

        // Ensure overlay is alive
        if (!CursorOverlayManager.isShowing()) {
            CursorOverlayManager.show(ctx)
        }

        try {
            val json = JSONObject(body)
            val action = json.optString("action", "tap").lowercase()
            val respoof = json.optBoolean("respoof", true)
            val startTime = System.currentTimeMillis()

            fun handleAction(onComplete: (Boolean) -> Unit) {
                when (action) {
                    "tap" -> {
                        val x = json.getDouble("x").toFloat()
                        val y = json.getDouble("y").toFloat()
                        CursorOverlayManager.onTap(x, y) {
                            CursorEngine.tap(x, y, 50, onComplete)
                        }
                    }
                    "double_tap" -> {
                        val x = json.getDouble("x").toFloat()
                        val y = json.getDouble("y").toFloat()
                        CursorOverlayManager.onDoubleTap(x, y) {
                            CursorEngine.doubleTap(x, y, onComplete)
                        }
                    }
                    "long_press" -> {
                        val x = json.getDouble("x").toFloat()
                        val y = json.getDouble("y").toFloat()
                        val dur = json.optLong("duration", 650)
                        CursorOverlayManager.onLongPress(x, y) {
                            CursorEngine.longPress(x, y, dur, onComplete)
                        }
                    }
                    "swipe" -> {
                        val x1 = json.getDouble("x1").toFloat()
                        val y1 = json.getDouble("y1").toFloat()
                        val x2 = json.getDouble("x2").toFloat()
                        val y2 = json.getDouble("y2").toFloat()
                        val dur = json.optLong("duration", 250)
                        CursorOverlayManager.onSwipe(x1, y1, x2, y2) {
                            CursorEngine.swipe(x1, y1, x2, y2, dur, onComplete)
                        }
                    }
                    "two_finger" -> {
                        val x1 = json.getDouble("x1").toFloat()
                        val y1 = json.getDouble("y1").toFloat()
                        val x2 = json.getDouble("x2").toFloat()
                        val y2 = json.getDouble("y2").toFloat()
                        val x3 = json.getDouble("x3").toFloat()
                        val y3 = json.getDouble("y3").toFloat()
                        val x4 = json.getDouble("x4").toFloat()
                        val y4 = json.getDouble("y4").toFloat()
                        val dur = json.optLong("duration", 300)
                        CursorOverlayManager.onTwoFinger(listOf(PointF(x1, y1), PointF(x3, y3))) {
                            CursorEngine.twoFingerSwipe(x1, y1, x2, y2, x3, y3, x4, y4, dur, onComplete)
                        }
                    }
                    "pinch" -> {
                        val cx = json.getDouble("x").toFloat()
                        val cy = json.getDouble("y").toFloat()
                        val factor = json.optDouble("factor", 0.5).toFloat()
                        val span = json.optDouble("span", 400.0).toFloat()
                        val dur = json.optLong("duration", 300)
                        CursorOverlayManager.onTwoFinger(listOf(PointF(cx - span / 2, cy), PointF(cx + span / 2, cy)), "PINCH") {
                            CursorEngine.pinch(cx, cy, factor, span, dur, onComplete)
                        }
                    }
                    "zoom" -> {
                        val cx = json.getDouble("x").toFloat()
                        val cy = json.getDouble("y").toFloat()
                        val factor = json.optDouble("factor", 2.0).toFloat()
                        val span = json.optDouble("span", 200.0).toFloat()
                        val dur = json.optLong("duration", 300)
                        CursorOverlayManager.onTwoFinger(listOf(PointF(cx - span / 2, cy), PointF(cx + span / 2, cy)), "ZOOM") {
                            CursorEngine.zoom(cx, cy, factor, span, dur, onComplete)
                        }
                    }
                    "three_finger" -> {
                        val x1 = json.getDouble("x1").toFloat()
                        val y1 = json.getDouble("y1").toFloat()
                        val x2 = json.getDouble("x2").toFloat()
                        val y2 = json.getDouble("y2").toFloat()
                        val x3 = json.getDouble("x3").toFloat()
                        val y3 = json.getDouble("y3").toFloat()
                        val x4 = json.getDouble("x4").toFloat()
                        val y4 = json.getDouble("y4").toFloat()
                        val x5 = json.getDouble("x5").toFloat()
                        val y5 = json.getDouble("y5").toFloat()
                        val x6 = json.getDouble("x6").toFloat()
                        val y6 = json.getDouble("y6").toFloat()
                        val dur = json.optLong("duration", 300)
                        CursorOverlayManager.onThreeFinger(listOf(PointF(x1, y1), PointF(x3, y3), PointF(x5, y5))) {
                            CursorEngine.threeFingerSwipe(x1, y1, x2, y2, x3, y3, x4, y4, x5, y5, x6, y6, dur, onComplete)
                        }
                    }
                    "type" -> {
                        val text = json.getString("text")
                        CursorOverlayManager.onType(text)
                        CursorEngine.typeText(text, onComplete)
                    }
                    "key", "nav" -> {
                        val key = json.getString("key").lowercase()
                        val (globalAction, label) = when (key) {
                            "back" -> Pair(AccessibilityService.GLOBAL_ACTION_BACK, "◀ BACK")
                            "home" -> Pair(AccessibilityService.GLOBAL_ACTION_HOME, "■ HOME")
                            "recents" -> Pair(AccessibilityService.GLOBAL_ACTION_RECENTS, "▦ RECENTS")
                            "notifications" -> Pair(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "🔔 NOTIFICATIONS")
                            "quick_settings" -> Pair(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "⚙ QUICK SETTINGS")
                            "power" -> Pair(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "⏻ POWER")
                            else -> Pair(AccessibilityService.GLOBAL_ACTION_BACK, "◀ BACK")
                        }
                        CursorOverlayManager.onNavAction(label)
                        CursorEngine.pressKey(globalAction, onComplete)
                    }
                    else -> onComplete(false)
                }
            }

            val responseFuture = CompletableDeferred<Pair<Int, JSONObject>>()
            if (respoof) {
                CursorEngine.executeAndRespoof(ctx, action, { handleAction(it) }) { result ->
                    val resp = JSONObject().apply {
                        put("status", if (result.success) "ok" else "failed")
                        put("action", action)
                        put("latency_ms", result.latencyMs)
                        result.record?.let { rec ->
                            put("spoof_file", rec.file.absolutePath)
                            put("timestamp", rec.formattedDate)
                            put("elements_count", rec.elementCount)
                            put("spoof_text", try { rec.file.readText() } catch (_: Exception) { rec.previewSnippet })
                        }
                        result.error?.let { put("error", it) }
                    }
                    responseFuture.complete(Pair(200, resp))
                }
            } else {
                handleAction { ok ->
                    val latency = System.currentTimeMillis() - startTime
                    val resp = JSONObject().apply {
                        put("status", if (ok) "ok" else "failed")
                        put("action", action)
                        put("latency_ms", latency)
                    }
                    responseFuture.complete(Pair(200, resp))
                }
            }
            val (statusCode, resp) = runBlocking { responseFuture.await() }
            sendJson(out, statusCode, resp)
        } catch (e: Exception) {
            sendJson(out, 400, JSONObject().put("error", e.message))
        }
    }

    private fun handleSpoof(out: OutputStream) {
        val ctx = appContext ?: run {
            sendJson(out, 500, JSONObject().put("error", "Context not ready"))
            return
        }

        val startTime = System.currentTimeMillis()
        val responseFuture = CompletableDeferred<Pair<Int, JSONObject>>()
        ScreenSpoofAccessibilityService.captureAndSpoof(ctx) { spoof ->
            val latency = System.currentTimeMillis() - startTime
            if (spoof.success) {
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("latency_ms", latency)
                    put("spoof_file", spoof.file.absolutePath)
                    put("elements_count", spoof.elementCount)
                    put("spoof_text", spoof.fullContent)
                }
                responseFuture.complete(Pair(200, resp))
            } else {
                responseFuture.complete(Pair(500, JSONObject().put("error", "Failed to capture spoof")))
            }
        }
        val (statusCode, resp) = runBlocking { responseFuture.await() }
        sendJson(out, statusCode, resp)
    }

    private fun handleStatus(out: OutputStream) {
        val resp = JSONObject().apply {
            put("status", "online")
            put("service", "ScreenSpoof Cursor Engine")
            put("port", PORT)
            put("local_ip", getLocalIpAddress())
            put("accessibility_connected", ScreenSpoofAccessibilityService.isConnected())
            put("overlay_active", CursorOverlayManager.isShowing())
            put("device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        }
        sendJson(out, 200, resp)
    }

    private fun handleBatch(out: OutputStream, body: String) {
        val ctx = appContext ?: run {
            sendJson(out, 500, JSONObject().put("error", "Context not ready"))
            return
        }

        try {
            val json = JSONObject(body)
            val actions = json.getJSONArray("actions")
            val delayBetween = json.optLong("delay_ms", 150)
            val results = JSONArray()

            val responseFuture = CompletableDeferred<Pair<Int, JSONObject>>()
            scope.launch {
                for (i in 0 until actions.length()) {
                    val act = actions.getJSONObject(i)
                    val actionName = act.optString("action", "tap")
                    val isLast = (i == actions.length() - 1)

                    val doneLatch = CompletableDeferred<Boolean>()
                    val x = act.optDouble("x", 0.0).toFloat()
                    val y = act.optDouble("y", 0.0).toFloat()

                    when (actionName) {
                        "tap" -> {
                            CursorOverlayManager.onTap(x, y)
                            CursorEngine.tap(x, y, 40) { doneLatch.complete(it) }
                        }
                        "type" -> {
                            val t = act.getString("text")
                            CursorOverlayManager.onType(t)
                            CursorEngine.typeText(t) { doneLatch.complete(it) }
                        }
                        "key", "nav" -> {
                            val k = act.getString("key")
                            val g = if (k == "home") AccessibilityService.GLOBAL_ACTION_HOME else AccessibilityService.GLOBAL_ACTION_BACK
                            CursorOverlayManager.onNavAction(k.uppercase())
                            CursorEngine.pressKey(g) { doneLatch.complete(it) }
                        }
                        else -> doneLatch.complete(true)
                    }

                    val ok = doneLatch.await()
                    results.put(JSONObject().put("index", i).put("action", actionName).put("success", ok))

                    if (!isLast && delayBetween > 0) {
                        delay(delayBetween)
                    }
                }

                ScreenSpoofAccessibilityService.captureAndSpoof(ctx) { spoof ->
                    val resp = JSONObject().apply {
                        put("status", "ok")
                        put("executed_count", actions.length())
                        put("results", results)
                        if (spoof.success) {
                            put("spoof_file", spoof.file.absolutePath)
                            put("spoof_text", spoof.summary)
                        }
                    }
                    responseFuture.complete(Pair(200, resp))
                }
            }
            val (statusCode, resp) = runBlocking { responseFuture.await() }
            sendJson(out, statusCode, resp)
        } catch (e: Exception) {
            sendJson(out, 400, JSONObject().put("error", e.message))
        }
    }

    private fun handleDashboard(out: OutputStream) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>ScreenSpoof Cursor AI Console</title>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { background: #040711; color: #f1f5f9; font-family: monospace; padding: 20px; }
                    h1 { color: #00F0FF; margin-bottom: 4px; }
                    .subtitle { color: #64748b; font-size: 13px; margin-bottom: 20px; }
                    .card { background: #0c1322; border: 1px solid #1e293b; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
                    .badge { display: inline-block; padding: 4px 8px; border-radius: 4px; background: #0369a1; color: white; font-size: 11px; }
                    button { background: #0284c7; color: white; border: none; padding: 8px 14px; border-radius: 4px; cursor: pointer; font-family: monospace; font-weight: bold; margin-right: 6px; }
                    button:hover { background: #0ea5e9; }
                    input { background: #090d18; border: 1px solid #334155; color: #38bdf8; padding: 8px; border-radius: 4px; font-family: monospace; width: 140px; }
                    pre { background: #000; border: 1px solid #1e293b; padding: 12px; border-radius: 6px; overflow-x: auto; font-size: 12px; max-height: 400px; }
                </style>
            </head>
            <body>
                <h1>CURSOR AI ENGINE</h1>
                <div class="subtitle">ScreenSpoof Zero-ADB High-Speed Computer Control Interface</div>
                
                <div class="card">
                    <div style="margin-bottom: 10px;">
                        <span class="badge">SERVER: ONLINE (PORT $PORT)</span>
                    </div>
                    <div style="margin-bottom: 14px;">
                        <label>X: <input type="number" id="x" value="540"></label>
                        <label style="margin-left: 10px;">Y: <input type="number" id="y" value="1200"></label>
                        <button onclick="sendAction('tap')">⚡ TAP</button>
                        <button onclick="sendAction('double_tap')">⚡⚡ DOUBLE TAP</button>
                        <button onclick="sendAction('long_press')">⏳ LONG PRESS</button>
                    </div>
                    <div style="margin-bottom: 14px;">
                        <button onclick="sendNav('back')" style="background:#d97706;">◀ BACK</button>
                        <button onclick="sendNav('home')" style="background:#d97706;">■ HOME</button>
                        <button onclick="sendNav('recents')" style="background:#d97706;">▦ RECENTS</button>
                        <button onclick="fetchSpoof()" style="background:#059669;">📸 RE-SPOOF</button>
                    </div>
                    <div>
                        <input type="text" id="typeText" placeholder="Type text here..." style="width: 280px;">
                        <button onclick="sendType()">⌨ TYPE</button>
                    </div>
                </div>

                <div class="card">
                    <div style="font-weight: bold; color: #38bdf8; margin-bottom: 8px;">LIVE CLI WIREFRAME OUTPUT</div>
                    <pre id="cliOutput">Click 'RE-SPOOF' or perform any tap action to see live text spoof...</pre>
                </div>

                <script>
                    async function sendAction(act) {
                        const x = parseFloat(document.getElementById('x').value);
                        const y = parseFloat(document.getElementById('y').value);
                        document.getElementById('cliOutput').textContent = "Executing " + act + " and re-spoofing screen...";
                        const res = await fetch('/cursor/act', {
                            method: 'POST',
                            headers: {'Content-Type': 'application/json'},
                            body: JSON.stringify({action: act, x: x, y: y, respoof: true})
                        });
                        const data = await res.json();
                        document.getElementById('cliOutput').textContent = "Latency: " + data.latency_ms + "ms\n\n" + (data.spoof_text || JSON.stringify(data, null, 2));
                    }

                    async function sendNav(key) {
                        document.getElementById('cliOutput').textContent = "Navigating " + key + "...";
                        const res = await fetch('/cursor/act', {
                            method: 'POST',
                            headers: {'Content-Type': 'application/json'},
                            body: JSON.stringify({action: 'key', key: key, respoof: true})
                        });
                        const data = await res.json();
                        document.getElementById('cliOutput').textContent = "Latency: " + data.latency_ms + "ms\n\n" + (data.spoof_text || JSON.stringify(data, null, 2));
                    }

                    async function sendType() {
                        const text = document.getElementById('typeText').value;
                        if (!text) return;
                        const res = await fetch('/cursor/act', {
                            method: 'POST',
                            headers: {'Content-Type': 'application/json'},
                            body: JSON.stringify({action: 'type', text: text, respoof: true})
                        });
                        const data = await res.json();
                        document.getElementById('cliOutput').textContent = "Latency: " + data.latency_ms + "ms\n\n" + (data.spoof_text || JSON.stringify(data, null, 2));
                    }

                    async function fetchSpoof() {
                        document.getElementById('cliOutput').textContent = "Capturing screen CLI spoof...";
                        const res = await fetch('/cursor/spoof');
                        const data = await res.json();
                        document.getElementById('cliOutput').textContent = "Latency: " + data.latency_ms + "ms\n\n" + (data.spoof_text || JSON.stringify(data, null, 2));
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        sendRawResponse(out, 200, "OK", "text/html; charset=UTF-8", html)
    }

    private fun sendJson(out: OutputStream, code: Int, json: JSONObject) {
        val text = json.toString()
        val statusText = if (code == 200) "OK" else if (code == 400) "Bad Request" else if (code == 405) "Method Not Allowed" else "Internal Server Error"
        sendRawResponse(out, code, statusText, "application/json; charset=UTF-8", text)
    }

    private fun sendRawResponse(out: OutputStream, code: Int, statusText: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $statusText\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${bytes.size}\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")

        val headerBytes = sb.toString().toByteArray(Charsets.UTF_8)
        out.write(headerBytes)
        if (bytes.isNotEmpty()) {
            out.write(bytes)
        }
        out.flush()
    }
}
