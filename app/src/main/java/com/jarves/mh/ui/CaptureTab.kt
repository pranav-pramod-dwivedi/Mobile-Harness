package com.jarves.mh.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.capture.CaptureRecord
import com.jarves.mh.capture.ScreenSpoofEngine
import com.jarves.mh.capture.ShellRunner
import com.jarves.mh.capture.StorageManager
import com.jarves.mh.overlay.FloatingOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CaptureTab(
    onOpenCapture: (CaptureRecord) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isOverlayActive by FloatingOverlayService.isRunning.collectAsState()

    var isCapturing by remember { mutableStateOf(false) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isRootAvailable by remember { mutableStateOf(ShellRunner.isRootAvailable()) }

    LaunchedEffect(Unit) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
        isRootAvailable = ShellRunner.isRootAvailable()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Floating Overlay Controller Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0C1322),
            border = BorderStroke(1.dp, if (isOverlayActive) Color(0xFF06B6D4) else Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isOverlayActive) Color(0xFF10B981) else Color(0xFF64748B))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isOverlayActive) "OVERLAY ACTIVE" else "OVERLAY STOPPED",
                                color = if (isOverlayActive) Color(0xFF34D399) else Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Display Over Other Apps",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Switch(
                        checked = isOverlayActive,
                        onCheckedChange = { checked ->
                            if (checked) {
                                FloatingOverlayService.start(context)
                            } else {
                                FloatingOverlayService.stop(context)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF06B6D4),
                            checkedTrackColor = Color(0xFF083344),
                            uncheckedThumbColor = Color(0xFF64748B),
                            uncheckedTrackColor = Color(0xFF1E293B)
                        )
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "A floating target button sits over any app. When clicked, it takes a temporary snapshot, analyzes all elements & coordinates, and writes a timestamped .txt CLI spoof file.",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                if (!hasOverlayPermission) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant 'Display Over Other Apps' Permission", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

        val isA11yConnected = com.jarves.mh.capture.ScreenSpoofAccessibilityService.isConnected()
        if (!isA11yConnected) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF331505),
                border = BorderStroke(1.dp, Color(0xFFD97706))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "⚠️ ACCESSIBILITY PERMISSION RECOMMENDED",
                        color = Color(0xFFFCD34D),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "To read text, buttons, and coordinates from ANY other app without root, enable ScreenSpoof in Accessibility Settings.",
                        color = Color(0xFFFDE68A),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Enable ScreenSpoof in Accessibility", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // Direct Screen Capture Button
        Button(
            onClick = {
                if (isCapturing) return@Button
                isCapturing = true
                if (com.jarves.mh.capture.ScreenSpoofAccessibilityService.isConnected()) {
                    com.jarves.mh.capture.ScreenSpoofAccessibilityService.captureAndSpoof(context) { result ->
                        isCapturing = false
                        val items = StorageManager.recordsFlow.value
                        val match = items.firstOrNull { it.file.absolutePath == result.file.absolutePath }
                        if (match != null) {
                            onOpenCapture(match)
                        } else {
                            Toast.makeText(context, "Captured: ${result.file.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ScreenSpoofEngine.processScreen(context)
                        }
                        isCapturing = false
                        val items = StorageManager.recordsFlow.value
                        val match = items.firstOrNull { it.file.absolutePath == result.file.absolutePath }
                        if (match != null) {
                            onOpenCapture(match)
                        } else {
                            Toast.makeText(context, "Captured: ${result.file.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (isCapturing) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Analyzing UI & Generating CLI Spoof...", fontSize = 14.sp)
            } else {
                Text("⚡ CAPTURE CURRENT SCREEN NOW", fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }

        // System Diagnostic HUD
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF070B14),
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "SYSTEM ENGINE HUD",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HudBadge("Root Execution (/su)", isRootAvailable)
                    HudBadge("Google ML Kit OCR", true)
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HudBadge("Overlay Permission", hasOverlayPermission) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                    HudBadge("Storage: /ScreenSpoof", true)
                }
            }
        }

        // 4 Tools Catalog
        Text(
            text = "INTEGRATED TOOL SET",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )

        ToolCardItem(
            title = "1. Screen Capture & CLI Spoof Engine",
            tag = "ACTIVE & CORE",
            tagColor = Color(0xFF10B981),
            desc = "Reads screen pixels & hierarchy into a pure-text CLI wireframe spoof with exact (cx, cy) coordinates and bounding boxes."
        )

        ToolCardItem(
            title = "2. Interactive Virtual Touch Simulator",
            tag = "READY",
            tagColor = Color(0xFF06B6D4),
            desc = "Dispatches root-level input taps directly to any coordinate or button discovered in the spoof."
        )

        ToolCardItem(
            title = "3. On-Device Vision OCR Analyzer",
            tag = "OFFLINE ML",
            tagColor = Color(0xFF8B5CF6),
            desc = "Recognizes bounding boxes and center coordinates for custom graphics, games, and web views lacking accessibility tags."
        )

        ToolCardItem(
            title = "4. AI & Agent Sync",
            tag = "EXPORT READY",
            tagColor = Color(0xFFF59E0B),
            desc = "Saves clean timestamped .txt files to public storage for inspection by autonomous AI agents, Termux scripts, and CLI tools."
        )
    }
}

@Composable
private fun HudBadge(title: String, active: Boolean, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) Color(0xFF064E3B) else Color(0xFF450A0A),
        border = BorderStroke(1.dp, if (active) Color(0xFF059669) else Color(0xFFDC2626)),
        modifier = Modifier
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (active) "✓" else "✕", color = if (active) Color(0xFF34D399) else Color(0xFFF87171), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(title, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ToolCardItem(
    title: String,
    tag: String,
    tagColor: Color,
    desc: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF090D16),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = tagColor.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, tagColor)
                ) {
                    Text(tag, color = tagColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(desc, color = Color(0xFF94A3B8), fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
