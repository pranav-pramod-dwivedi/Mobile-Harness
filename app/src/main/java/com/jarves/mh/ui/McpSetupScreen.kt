package com.jarves.mh.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.commander.CommanderState
import com.jarves.mh.commander.DesktopCommanderManager
import kotlinx.coroutines.delay

@Composable
fun McpSetupScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val state by DesktopCommanderManager.state.collectAsState()
    val isActive by DesktopCommanderManager.isActive.collectAsState()
    val output by DesktopCommanderManager.terminalOutput.collectAsState()
    val info by DesktopCommanderManager.info.collectAsState()

    val scrollState = rememberScrollState()
    val termScrollState = rememberScrollState()

    // Countdown timer for code expiry
    var remainingSeconds by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(info.codeExpiryMinutes) {
        val mins = info.codeExpiryMinutes ?: return@LaunchedEffect
        remainingSeconds = mins * 60
        while ((remainingSeconds ?: 0) > 0) {
            delay(1000)
            remainingSeconds = (remainingSeconds ?: 0) - 1
        }
    }
    // Reset timer when code clears (connected)
    LaunchedEffect(info.verifyCode) {
        if (info.verifyCode == null) remainingSeconds = null
    }

    LaunchedEffect(output) { termScrollState.animateScrollTo(termScrollState.maxValue) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF040711))
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        // ── Header ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("🤖 MCP REMOTE", color = Color(0xFF00F0FF), fontSize = 17.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("ChatGPT & Claude → Android Terminal", color = Color(0xFF64748B),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            StatusPill(state)
        }

        // ── DEVICE CODE VERIFICATION CARD (the main new UI) ──────────────────
        AnimatedVisibility(
            visible = state == CommanderState.WAITING_AUTH && (info.verifyUrl != null || info.verifyCode != null),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A1A)),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF6366F1).copy(alpha = 0.8f))
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Title
                    Text("VERIFY THIS DEVICE", color = Color(0xFF818CF8),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)

                    // Big code display
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF13111E))
                            .border(2.dp, Color(0xFF4F46E5).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Your code", color = Color(0xFF6B7280), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace)
                            Text(
                                text = info.verifyCode ?: "",
                                color = Color(0xFFE0E7FF),
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 8.sp
                            )
                            // Countdown
                            remainingSeconds?.let { secs ->
                                val m = secs / 60
                                val s = secs % 60
                                val urgent = secs < 120
                                Text(
                                    text = "Expires in %d:%02d".format(m, s),
                                    color = if (urgent) Color(0xFFEF4444) else Color(0xFF6B7280),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // Instruction
                    Text(
                        text = "Open the link below in your browser,\nthen type this code to authorize your device.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    // URL box
                    info.verifyUrl?.let { url ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF020617))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(url, color = Color(0xFF818CF8), fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace)
                        }

                        // Action buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Open browser — primary action
                            Button(
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Cannot open browser", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(2f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5))
                            ) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open Browser", fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                            // Copy code
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(info.verifyCode ?: ""))
                                    Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF818CF8))
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null,
                                    modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    // Pulse waiting indicator
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f, targetValue = 1f, label = "alpha",
                        animationSpec = infiniteRepeatable(
                            animation = tween(900, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        )
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape)
                            .background(Color(0xFF6366F1).copy(alpha = alpha)))
                        Text("Waiting for authorization in browser…",
                            color = Color(0xFF6B7280).copy(alpha = alpha),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // ── Connected device card ─────────────────────────────────────────────
        AnimatedVisibility(visible = state == CommanderState.CONNECTED,
            enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF021A0E)),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF10B981).copy(alpha = 0.6f)))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF10B981)))
                        Text("DEVICE CONNECTED", color = Color(0xFF10B981), fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    info.userEmail?.let { InfoRow("User", it) }
                    info.deviceName?.let { InfoRow("Device", it) }
                    info.deviceId?.let { id ->
                        InfoRow("ID", id)
                        Button(
                            onClick = {
                                clipboard.setText(AnnotatedString(id))
                                Toast.makeText(context, "Device ID copied", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF065F46)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy Device ID", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (info.sessionRestored) {
                        Text("✅ Session restored — no sign-in needed",
                            color = Color(0xFF34D399), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // ── How it works (collapsed when active) ─────────────────────────────
        AnimatedVisibility(visible = !isActive,
            enter = expandVertically(), exit = shrinkVertically()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1322)),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E3A5F)))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("HOW IT WORKS", color = Color(0xFF38BDF8), fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    StepRow("1", "Tap Connect — Desktop Commander starts in the embedded Linux terminal")
                    StepRow("2", "First run: browser opens with a code to verify your device (one-time)")
                    StepRow("3", "After login the device shows as Online — session saved forever")
                    StepRow("4", "In ChatGPT or Claude, add Desktop Commander MCP → your phone appears")
                    StepRow("5", "AI sends shell commands → they run in this app's Linux terminal")
                }
            }
        }

        // ── Connect / Disconnect button ───────────────────────────────────────
        Button(
            onClick = {
                if (isActive) DesktopCommanderManager.stop()
                else DesktopCommanderManager.start(context)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    !isActive -> Color(0xFF0E4F8B)
                    state == CommanderState.CONNECTED -> Color(0xFF064E3B)
                    else -> Color(0xFF7F1D1D)
                }
            )
        ) {
            Icon(
                imageVector = when {
                    !isActive -> Icons.Default.PlayArrow
                    state == CommanderState.CONNECTED -> Icons.Default.CheckCircle
                    else -> Icons.Default.Stop
                },
                contentDescription = null, modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = when {
                    !isActive -> "Connect to ChatGPT / Claude"
                    state == CommanderState.CONNECTED -> "Connected — Tap to Disconnect"
                    state == CommanderState.WAITING_AUTH -> "Waiting for browser verification…"
                    else -> "Stop"
                },
                fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
            )
        }

        // ── Live terminal ─────────────────────────────────────────────────────
        if (output.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
                shape = RoundedCornerShape(10.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E293B)))
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("TERMINAL", color = Color(0xFF475569), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(output))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ContentCopy, null, tint = Color(0xFF475569),
                                modifier = Modifier.size(14.dp))
                        }
                    }
                    Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 220.dp).verticalScroll(termScrollState)) {
                        Text(output, color = Color(0xFF94A3B8), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                    }
                }
            }
        }

        // ── ChatGPT / Claude guide ────────────────────────────────────────────
        AnimatedVisibility(visible = state == CommanderState.CONNECTED || !isActive,
            enter = expandVertically(), exit = shrinkVertically()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1322)),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E293B)))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ADD TO CHATGPT / CLAUDE", color = Color(0xFF38BDF8), fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(
                        "ChatGPT → Settings → Connected Apps → Desktop Commander\n" +
                        "Claude   → Settings → Integrations → Add MCP Server\n\n" +
                        "Once connected, the AI can run any shell command in this\n" +
                        "app's embedded Linux environment.",
                        color = Color(0xFF94A3B8), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 17.sp
                    )
                    val eg = "Run `ls /root` in the terminal on my Android phone"
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(Color.Black).padding(10.dp)) {
                        Text("\"$eg\"", color = Color(0xFF38BDF8), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(eg))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📋 Copy Example Prompt", fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, color = Color.White) }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusPill(state: CommanderState) {
    val (bg, fg, label) = when (state) {
        CommanderState.CONNECTED    -> Triple(Color(0xFF10B981), Color(0xFF10B981), "ONLINE")
        CommanderState.WAITING_AUTH -> Triple(Color(0xFF6366F1), Color(0xFF818CF8), "VERIFY")
        CommanderState.CONNECTING,
        CommanderState.INSTALLING_NPM,
        CommanderState.WAITING_NPM_CONFIRM -> Triple(Color(0xFFF59E0B), Color(0xFFFBBF24), "CONNECTING")
        CommanderState.NEEDS_SIGNUP -> Triple(Color(0xFFF97316), Color(0xFFFB923C), "SIGN IN")
        CommanderState.ERROR        -> Triple(Color(0xFFEF4444), Color(0xFFF87171), "ERROR")
        else                        -> Triple(Color(0xFF334155), Color(0xFF64748B), "OFFLINE")
    }
    val infiniteTransition = rememberInfiniteTransition(label = "pill")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1f, label = "a",
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse)
    )
    val isAnimated = state == CommanderState.WAITING_AUTH || state == CommanderState.CONNECTING ||
        state == CommanderState.INSTALLING_NPM

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg.copy(alpha = 0.12f))
            .border(1.dp, fg.copy(alpha = if (isAnimated) alpha else 0.6f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape)
                .background(fg.copy(alpha = if (isAnimated) alpha else 1f)))
            Text(label, color = fg, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun StepRow(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0xFF0369A1)),
            contentAlignment = Alignment.Center) {
            Text(number, color = Color.White, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Text(text, color = Color(0xFFCBD5E1), fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label:", color = Color(0xFF64748B), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp))
        Text(value, color = Color(0xFFE2E8F0), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
