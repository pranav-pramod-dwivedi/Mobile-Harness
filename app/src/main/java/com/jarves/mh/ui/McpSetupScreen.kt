package com.jarves.mh.ui

import android.widget.Toast
import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Brush
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

@Composable
fun McpSetupScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val state by DesktopCommanderManager.state.collectAsState()
    val isActive by DesktopCommanderManager.isActive.collectAsState()
    val output by DesktopCommanderManager.terminalOutput.collectAsState()
    val info by DesktopCommanderManager.info.collectAsState()
    val pendingUrl by DesktopCommanderManager.pendingUrl.collectAsState()

    val scrollState = rememberScrollState()
    val termScrollState = rememberScrollState()

    // Auto-scroll terminal to bottom
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
                Text(
                    "🤖 MCP REMOTE",
                    color = Color(0xFF00F0FF),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "ChatGPT & Claude → Android Terminal",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            // Status pill
            val (pillColor, pillText) = when (state) {
                CommanderState.CONNECTED       -> Color(0xFF10B981) to "ONLINE"
                CommanderState.CONNECTING      -> Color(0xFFF59E0B) to "CONNECTING"
                CommanderState.INSTALLING_NPM,
                CommanderState.WAITING_NPM_CONFIRM -> Color(0xFF8B5CF6) to "INSTALLING"
                CommanderState.NEEDS_SIGNUP    -> Color(0xFFF97316) to "SIGN IN"
                CommanderState.ERROR           -> Color(0xFFEF4444) to "ERROR"
                else                           -> Color(0xFF334155) to "OFFLINE"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(pillColor.copy(alpha = 0.15f))
                    .border(1.dp, pillColor.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(pillColor))
                    Text(pillText, color = pillColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ── Explanation card ──────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1322)),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E3A5F)))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HOW IT WORKS", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                StepRow("1", "Tap Connect below — Desktop Commander starts in the embedded Linux terminal")
                StepRow("2", "First run: browser opens for one-time sign-up at desktopcommander.app")
                StepRow("3", "After login the device shows as Online — your session is saved forever")
                StepRow("4", "In ChatGPT or Claude, add MCP server → your device appears in the list")
                StepRow("5", "AI sends shell commands → they run in this app's Linux terminal")
            }
        }

        // ── Sign-up prompt (only when auth URL detected) ───────────────────
        AnimatedVisibility(visible = pendingUrl != null, enter = expandVertically(), exit = shrinkVertically()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C0A00)),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFF97316).copy(alpha = 0.7f)))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("🔐 SIGN IN REQUIRED", color = Color(0xFFF97316), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(
                        "A browser tab has opened for you to sign up / log in to Desktop Commander. Complete that and come back — the connection will continue automatically.",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp
                    )
                    pendingUrl?.let { url ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black)
                                .padding(8.dp)
                        ) {
                            Text(url, color = Color(0xFF38BDF8), fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(url))
                                    Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8))
                            ) { Text("Copy URL", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                            Button(
                                onClick = { DesktopCommanderManager.clearPendingUrl() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                            ) { Text("Dismiss", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                        }
                    }
                }
            }
        }

        // ── Connected device card ──────────────────────────────────────────
        AnimatedVisibility(visible = state == CommanderState.CONNECTED, enter = expandVertically(), exit = shrinkVertically()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF021A0E)),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF10B981).copy(alpha = 0.6f)))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF10B981)))
                        Text("DEVICE CONNECTED", color = Color(0xFF10B981), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
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
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy Device ID", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (info.sessionRestored) {
                        Text("✅ Existing session restored — no sign-in needed", color = Color(0xFF34D399), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // ── Connect / Disconnect button ───────────────────────────────────
        Button(
            onClick = {
                if (isActive) DesktopCommanderManager.stop()
                else DesktopCommanderManager.start(context)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isActive) Color(0xFF7F1D1D) else Color(0xFF0E4F8B)
            )
        ) {
            Icon(
                imageVector = if (isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = when {
                    !isActive -> "Connect to ChatGPT / Claude"
                    state == CommanderState.CONNECTED -> "Disconnect"
                    else -> "Stop"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // ── Live terminal output ───────────────────────────────────────────
        if (output.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
                shape = RoundedCornerShape(10.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E293B)))
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("TERMINAL", color = Color(0xFF475569), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(output))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color(0xFF475569), modifier = Modifier.size(14.dp))
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp, max = 260.dp)
                            .verticalScroll(termScrollState)
                    ) {
                        Text(
                            text = output,
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        // ── ChatGPT / Claude usage guide ──────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1322)),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E293B)))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ADD TO CHATGPT / CLAUDE", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(
                    "ChatGPT → Settings → Connected Apps → Desktop Commander\n" +
                    "Claude   → Settings → Integrations → Add MCP Server\n\n" +
                    "Once connected, the AI can run any shell command in this\n" +
                    "app's embedded Linux environment directly.",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp
                )

                val examplePrompt = "Run `ls /root` in the terminal on my Android phone"
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black)
                        .padding(10.dp)
                ) {
                    Text("Example: \"$examplePrompt\"", color = Color(0xFF38BDF8), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(examplePrompt))
                        Toast.makeText(context, "Copied example prompt", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📋 Copy Example Prompt", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepRow(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(0xFF0369A1)),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Text(text, color = Color(0xFFCBD5E1), fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label:", color = Color(0xFF64748B), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp))
        Text(value, color = Color(0xFFE2E8F0), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
