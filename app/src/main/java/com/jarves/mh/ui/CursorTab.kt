package com.jarves.mh.ui

import android.accessibilityservice.AccessibilityService
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.capture.CaptureRecord
import com.jarves.mh.capture.ScreenSpoofAccessibilityService
import com.jarves.mh.cursor.CursorEngine
import com.jarves.mh.cursor.CursorOverlayManager
import com.jarves.mh.cursor.CursorServer

@Composable
fun CursorTab(onOpenCapture: (CaptureRecord) -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isOverlayActive by CursorOverlayManager.isOverlayActive.collectAsState()
    val isServerRunning by CursorServer.isRunningState.collectAsState()
    val isAccConnected = ScreenSpoofAccessibilityService.isConnected()

    var lastAction by remember { mutableStateOf("Ready for AI actions") }
    var lastLatency by remember { mutableStateOf<Long?>(null) }
    var lastRecord by remember { mutableStateOf<CaptureRecord?>(null) }
    var isExecuting by remember { mutableStateOf(false) }

    // Coordinates inputs
    var inputX by remember { mutableStateOf("540") }
    var inputY by remember { mutableStateOf("1200") }
    var inputText by remember { mutableStateOf("Hello World") }
    var commandLine by remember { mutableStateOf("tap 540 1200") }

    val scrollState = rememberScrollState()

    fun runAction(name: String, block: (onDone: (Boolean) -> Unit) -> Unit) {
        if (!isAccConnected) {
            Toast.makeText(context, "Enable ScreenSpoof in Accessibility Settings first", Toast.LENGTH_SHORT).show()
            return
        }
        isExecuting = true
        lastAction = "Executing $name..."
        CursorEngine.executeAndRespoof(context, name, block) { result ->
            isExecuting = false
            lastLatency = result.latencyMs
            lastAction = if (result.success) "$name completed in ${result.latencyMs}ms" else "Failed: ${result.error}"
            result.record?.let {
                lastRecord = it
                Toast.makeText(context, "Action done (${result.latencyMs}ms) • Spoof saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF040711))
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // --------------------------------------------------------------------
        // Header Status Card
        // --------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1322)),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00F0FF).copy(alpha = 0.5f)))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "🎯 CURSOR AI CONTROL",
                            color = Color(0xFF00F0FF),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Zero-ADB Sub-250ms Computer Control Engine",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isServerRunning) Color(0xFF065F46) else Color(0xFF991B1B))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isServerRunning) "PORT :${CursorServer.PORT}" else "OFFLINE",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val ip = remember { CursorServer.getLocalIpAddress() }
                    HudBadge(label = "IP: $ip", active = true, modifier = Modifier.weight(1f))
                    HudBadge(label = if (isAccConnected) "ACC: CONNECTED" else "ACC: OFF", active = isAccConnected, modifier = Modifier.weight(1f))
                    HudBadge(label = if (lastLatency != null) "${lastLatency}ms" else "LATENCY: ~140ms", active = lastLatency != null, modifier = Modifier.weight(1f))
                }
            }
        }

        // --------------------------------------------------------------------
        // Overlay & Server Toggles
        // --------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF090D18)),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E293B)))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Persistent Animated Cursor Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Animated Cursor Overlay",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Permanent pointer that glides across coordinates, squashes on taps, and shows trails",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = isOverlayActive,
                        onCheckedChange = { enable ->
                            if (enable) CursorOverlayManager.show(context) else CursorOverlayManager.hide()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00F0FF),
                            checkedTrackColor = Color(0xFF0369A1)
                        )
                    )
                }

                HorizontalDivider(color = Color(0xFF1E293B))

                // Server Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Local HTTP Server (:8899)",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Accepts POST /cursor/act from AI models, python scripts, or curl",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = isServerRunning,
                        onCheckedChange = { enable ->
                            if (enable) CursorServer.start(context) else CursorServer.stop()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF10B981),
                            checkedTrackColor = Color(0xFF047857)
                        )
                    )
                }

                HorizontalDivider(color = Color(0xFF1E293B))

                // Floating Capture Orb
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isOrbRunning by com.jarves.mh.overlay.FloatingOverlayService.isRunning.collectAsState()
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Floating Capture Orb",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Draggable orb over any app — tap to instantly capture & spoof screen",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = isOrbRunning,
                        onCheckedChange = { enable ->
                            if (enable) com.jarves.mh.overlay.FloatingOverlayService.start(context)
                            else com.jarves.mh.overlay.FloatingOverlayService.stop(context)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF10B981),
                            checkedTrackColor = Color(0xFF047857)
                        )
                    )
                }
            }
        }

        // --------------------------------------------------------------------
        // Action Studio & Quick Triggers
        // --------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF090D18)),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E293B)))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "ACTION PALETTE (ATOMIC ACT & RESPOOF)",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                // Input Coordinates Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputX,
                        onValueChange = { inputX = it },
                        label = { Text("X px", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF00F0FF))
                    )
                    OutlinedTextField(
                        value = inputY,
                        onValueChange = { inputY = it },
                        label = { Text("Y px", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF00F0FF))
                    )
                }

                // Row 1: Tap, Double Tap, Long Press
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val x = inputX.toFloatOrNull() ?: 540f
                    val y = inputY.toFloatOrNull() ?: 1200f

                    ActionButton(label = "⚡ Tap", color = Color(0xFF0284C7), modifier = Modifier.weight(1f)) {
                        CursorOverlayManager.onTap(x, y)
                        runAction("Tap ($x, $y)") { CursorEngine.tap(x, y, 50, it) }
                    }
                    ActionButton(label = "⚡⚡ Double", color = Color(0xFF0369A1), modifier = Modifier.weight(1f)) {
                        CursorOverlayManager.onDoubleTap(x, y)
                        runAction("Double Tap ($x, $y)") { CursorEngine.doubleTap(x, y, it) }
                    }
                    ActionButton(label = "⏳ Long", color = Color(0xFF075985), modifier = Modifier.weight(1f)) {
                        CursorOverlayManager.onLongPress(x, y)
                        runAction("Long Press ($x, $y)") { CursorEngine.longPress(x, y, 650, it) }
                    }
                }

                // Row 2: Swipes
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton(label = "⬆ Up", color = Color(0xFF1E293B), modifier = Modifier.weight(1f)) {
                        CursorOverlayManager.onSwipe(540f, 1600f, 540f, 400f)
                        runAction("Swipe Up") { CursorEngine.swipe(540f, 1600f, 540f, 400f, 250, it) }
                    }
                    ActionButton(label = "⬇ Down", color = Color(0xFF1E293B), modifier = Modifier.weight(1f)) {
                        CursorOverlayManager.onSwipe(540f, 400f, 540f, 1600f)
                        runAction("Swipe Down") { CursorEngine.swipe(540f, 400f, 540f, 1600f, 250, it) }
                    }
                    ActionButton(label = "← Left", color = Color(0xFF1E293B), modifier = Modifier.weight(1f)) {
                        CursorOverlayManager.onSwipe(900f, 1000f, 100f, 1000f)
                        runAction("Swipe Left") { CursorEngine.swipe(900f, 1000f, 100f, 1000f, 250, it) }
                    }
                    ActionButton(label = "→ Right", color = Color(0xFF1E293B), modifier = Modifier.weight(1f)) {
                        CursorOverlayManager.onSwipe(100f, 1000f, 900f, 1000f)
                        runAction("Swipe Right") { CursorEngine.swipe(100f, 1000f, 900f, 1000f, 250, it) }
                    }
                }

                // Row 3: Multi-Touch (2-finger, 3-finger, Pinch, Zoom)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton(label = "✌ 2-Finger", color = Color(0xFF334155), modifier = Modifier.weight(1f)) {
                        runAction("2-Finger Scroll") {
                            CursorEngine.twoFingerSwipe(400f, 1500f, 400f, 500f, 680f, 1500f, 680f, 500f, 300, it)
                        }
                    }
                    ActionButton(label = "🖐 3-Finger", color = Color(0xFF334155), modifier = Modifier.weight(1f)) {
                        runAction("3-Finger Swipe") {
                            CursorEngine.threeFingerSwipe(200f, 1400f, 200f, 400f, 540f, 1400f, 540f, 400f, 880f, 1400f, 880f, 400f, 300, it)
                        }
                    }
                    ActionButton(label = "👌 Pinch", color = Color(0xFF334155), modifier = Modifier.weight(1f)) {
                        runAction("Pinch") { CursorEngine.pinch(540f, 1000f, 0.5f, 400f, 300, it) }
                    }
                    ActionButton(label = "🔍 Zoom", color = Color(0xFF334155), modifier = Modifier.weight(1f)) {
                        runAction("Zoom") { CursorEngine.zoom(540f, 1000f, 2.0f, 200f, 300, it) }
                    }
                }

                // Row 4: Navigation Keys (With Amber Visual HUD)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton(label = "◀ Back", color = Color(0xFFB45309), modifier = Modifier.weight(1f)) {
                        CursorOverlayManager.onNavAction("◀ BACK")
                        runAction("Back") { CursorEngine.pressKey(AccessibilityService.GLOBAL_ACTION_BACK, it) }
                    }
                    ActionButton(label = "■ Home", color = Color(0xFFB45309), modifier = Modifier.weight(1f)) {
                        CursorOverlayManager.onNavAction("■ HOME")
                        runAction("Home") { CursorEngine.pressKey(AccessibilityService.GLOBAL_ACTION_HOME, it) }
                    }
                    ActionButton(label = "▦ Recents", color = Color(0xFFB45309), modifier = Modifier.weight(1f)) {
                        CursorOverlayManager.onNavAction("▦ RECENTS")
                        runAction("Recents") { CursorEngine.pressKey(AccessibilityService.GLOBAL_ACTION_RECENTS, it) }
                    }
                    ActionButton(label = "🔔 Notif", color = Color(0xFFB45309), modifier = Modifier.weight(1f)) {
                        CursorOverlayManager.onNavAction("🔔 NOTIFICATIONS")
                        runAction("Notifications") { CursorEngine.pressKey(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, it) }
                    }
                }

                // Row 5: Text Typing Input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        label = { Text("Text to type", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White)
                    )
                    Button(
                        onClick = {
                            CursorOverlayManager.onType(inputText)
                            runAction("Type \"$inputText\"") { CursorEngine.typeText(inputText, it) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("⌨ Type", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }

        // --------------------------------------------------------------------
        // Latency Benchmark & Execution Result
        // --------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF090D18)),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E293B)))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LAST PIPELINE EXECUTION",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (lastLatency != null) {
                        Text(
                            text = "${lastLatency}ms",
                            color = Color(0xFF10B981),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Text(
                    text = lastAction,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )

                lastRecord?.let { rec ->
                    Button(
                        onClick = { onOpenCapture(rec) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔍 View Generated CLI Wireframe (${rec.elementCount} elements)", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // --------------------------------------------------------------------
        // AI Model API Guide
        // --------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1322)),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E293B)))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "AI MODEL / SCRIPT INTEGRATION",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                val curlCmd = """
curl -X POST http://127.0.0.1:8899/cursor/act \
  -H "Content-Type: application/json" \
  -d '{"action": "tap", "x": 540, "y": 1200, "respoof": true}'
                """.trimIndent()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black)
                        .padding(10.dp)
                ) {
                    Text(
                        text = curlCmd,
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(curlCmd))
                        Toast.makeText(context, "Copied cURL command to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📋 Copy cURL Command", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                }
            }
        }

        // --------------------------------------------------------------------
        // Command Sandbox (tap / swipe / type / back|home|recents)
        // --------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF090D18)),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E293B)))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "COMMAND SANDBOX",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "tap X Y | swipe X1 Y1 X2 Y2 [dur] | type <text> | back|home|recents",
                    color = Color(0xFF64748B),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = commandLine,
                        onValueChange = { commandLine = it },
                        label = { Text("command", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White)
                    )
                    Button(
                        onClick = {
                            val parts = commandLine.trim().split("\\s+".toRegex())
                            if (parts.isEmpty() || parts[0].isEmpty()) return@Button
                            when (parts[0].lowercase()) {
                                "tap" -> {
                                    val x = parts.getOrNull(1)?.toFloatOrNull() ?: return@Button
                                    val y = parts.getOrNull(2)?.toFloatOrNull() ?: return@Button
                                    CursorOverlayManager.onTap(x, y)
                                    runAction("Tap ($x, $y)") { CursorEngine.tap(x, y, 50, it) }
                                }
                                "swipe" -> {
                                    val x1 = parts.getOrNull(1)?.toFloatOrNull() ?: return@Button
                                    val y1 = parts.getOrNull(2)?.toFloatOrNull() ?: return@Button
                                    val x2 = parts.getOrNull(3)?.toFloatOrNull() ?: return@Button
                                    val y2 = parts.getOrNull(4)?.toFloatOrNull() ?: return@Button
                                    val dur = parts.getOrNull(5)?.toLongOrNull() ?: 250L
                                    CursorOverlayManager.onSwipe(x1, y1, x2, y2)
                                    runAction("Swipe") { CursorEngine.swipe(x1, y1, x2, y2, dur, it) }
                                }
                                "type" -> {
                                    val text = commandLine.substringAfter("type").trim().removeSurrounding("\"")
                                    if (text.isEmpty()) return@Button
                                    CursorOverlayManager.onType(text)
                                    runAction("Type \"$text\"") { CursorEngine.typeText(text, it) }
                                }
                                "back" -> {
                                    CursorOverlayManager.onNavAction("◀ BACK")
                                    runAction("Back") { CursorEngine.pressKey(AccessibilityService.GLOBAL_ACTION_BACK, it) }
                                }
                                "home" -> {
                                    CursorOverlayManager.onNavAction("■ HOME")
                                    runAction("Home") { CursorEngine.pressKey(AccessibilityService.GLOBAL_ACTION_HOME, it) }
                                }
                                "recents" -> {
                                    CursorOverlayManager.onNavAction("▦ RECENTS")
                                    runAction("Recents") { CursorEngine.pressKey(AccessibilityService.GLOBAL_ACTION_RECENTS, it) }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("GO", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun ActionButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        modifier = modifier.height(38.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = Color.White,
            maxLines = 1
        )
    }
}

@Composable
private fun HudBadge(label: String, active: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Color(0xFF0369A1).copy(alpha = 0.25f) else Color(0xFF1E293B))
            .border(1.dp, if (active) Color(0xFF38BDF8).copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = if (active) Color(0xFF38BDF8) else Color(0xFF64748B),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}
