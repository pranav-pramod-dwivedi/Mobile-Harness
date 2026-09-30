package com.jarves.mh.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ExtractedElement(
    val type: String, // BUTTON, INPUT, TOGGLE, IMAGE/ICON, SCROLLABLE, TEXT
    val text: String,
    val bounds: Rect,
    val center: Pair<Int, Int>,
    val viewId: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean?
)

class ScreenSpoofAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ScreenSpoofAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null

        private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        /**
         * Dispatches a precise touch gesture to (x, y) without requiring root.
         */
        fun tap(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val path = Path().apply {
                    moveTo(x, y)
                    lineTo(x + 0.5f, y + 0.5f)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, 50)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                return service.dispatchGesture(gesture, null, null)
            }
            return false
        }

        /**
         * Walks the active window of ANY app on screen and extracts all UI elements.
         */
        fun extractActiveWindow(maxNodes: Int = 300): Pair<String, List<ExtractedElement>> {
            val service = instance ?: return Pair("Accessibility Service Disconnected", emptyList())
            val root = service.rootInActiveWindow ?: return Pair("No Active Window", emptyList())

            val packageName = root.packageName?.toString() ?: "unknown.app"
            val elements = ArrayList<ExtractedElement>()

            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || elements.size >= maxNodes || depth > 30) return

                val text = node.text?.toString()?.trim() ?: ""
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                val label = listOf(text, desc).firstOrNull { it.isNotBlank() } ?: ""
                val cls = node.className?.toString() ?: ""
                val isClickable = node.isClickable
                val isEditable = node.isEditable
                val isScrollable = node.isScrollable
                val isCheckable = node.isCheckable
                val isChecked = if (isCheckable) node.isChecked else null

                val rect = Rect()
                node.getBoundsInScreen(rect)

                // Only include elements that have meaningful dimensions
                if (rect.width() > 0 && rect.height() > 0) {
                    val cx = (rect.left + rect.right) / 2
                    val cy = (rect.top + rect.bottom) / 2

                    if (label.isNotBlank() || isClickable || isEditable || isScrollable || isCheckable || cls.contains("Image", ignoreCase = true)) {
                        val type = when {
                            isCheckable -> "TOGGLE"
                            isEditable -> "INPUT"
                            isClickable -> "BUTTON"
                            isScrollable -> "SCROLLABLE"
                            cls.contains("Image", ignoreCase = true) -> "IMAGE/ICON"
                            else -> "TEXT"
                        }

                        val viewId = node.viewIdResourceName?.substringAfterLast('/') ?: ""
                        elements.add(
                            ExtractedElement(
                                type = type,
                                text = label.ifBlank { cls.substringAfterLast('.') },
                                bounds = rect,
                                center = Pair(cx, cy),
                                viewId = viewId,
                                className = cls.substringAfterLast('.'),
                                clickable = isClickable,
                                editable = isEditable,
                                scrollable = isScrollable,
                                checkable = isCheckable,
                                checked = isChecked
                            )
                        )
                    }
                }

                for (i in 0 until node.childCount) {
                    walk(node.getChild(i), depth + 1)
                }
            }

            walk(root, 0)
            return Pair(packageName, elements)
        }

        /**
         * Root-free full-screen capture on Android 11+ (API 30+)
         */
        fun takeScreenshotWithoutRoot(callback: (Bitmap?) -> Unit) {
            val service = instance
            if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        Executors.newSingleThreadExecutor(),
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshotResult: ScreenshotResult) {
                                val buffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val bmp = Bitmap.wrapHardwareBuffer(buffer, colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                                buffer.close()
                                callback(bmp)
                            }

                            override fun onFailure(errorCode: Int) {
                                callback(null)
                            }
                        }
                    )
                    return
                } catch (_: Exception) {
                    callback(null)
                }
            } else {
                callback(null)
            }
        }

        /**
         * High-level capture & spoof generator without requiring root.
         */
        fun captureAndSpoof(context: Context, onResult: (SpoofResult) -> Unit) {
            serviceScope.launch {
                val (foregroundPkg, elements) = extractActiveWindow()

                // Resolution
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                val width = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
                val height = if (metrics.heightPixels > 0) metrics.heightPixels else 2340

                // Attempt root-free screenshot + OCR
                var ocrList = emptyList<ScreenSpoofEngine.OcrBox>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    var screenshotBitmap: Bitmap? = null
                    val latch = java.util.concurrent.CountDownLatch(1)
                    takeScreenshotWithoutRoot { bmp ->
                        screenshotBitmap = bmp
                        latch.countDown()
                    }
                    latch.await(1500, TimeUnit.MILLISECONDS)

                    screenshotBitmap?.let { bmp ->
                        try {
                            val img = InputImage.fromBitmap(bmp, 0)
                            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                            val result = Tasks.await(recognizer.process(img), 3, TimeUnit.SECONDS)
                            val boxes = ArrayList<ScreenSpoofEngine.OcrBox>()
                            for (block in result.textBlocks) {
                                for (line in block.lines) {
                                    val b = line.boundingBox ?: continue
                                    val t = line.text.replace("\n", " ").trim()
                                    if (t.isNotBlank()) {
                                        boxes.add(ScreenSpoofEngine.OcrBox(t, b, Pair((b.left + b.right) / 2, (b.top + b.bottom) / 2)))
                                    }
                                }
                            }
                            ocrList = boxes
                            bmp.recycle()
                        } catch (_: Exception) {}
                    }
                }

                // Build CLI Document
                val now = Date()
                val dateDisplay = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)

                val summary = buildSummary(foregroundPkg, elements, width, height)
                val schematic = buildAsciiSchematic(elements, width, height)
                val directory = buildDirectory(elements)
                val ocrSection = buildOcrSection(ocrList)

                val doc = buildString {
                    appendLine("================================================================================")
                    appendLine("JARVIS SCREEN SPOOF — CLI VIEW (ROOT-FREE ACCESSIBILITY ENGINE)")
                    appendLine("Captured: $dateDisplay | Device: ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
                    appendLine("Foreground: $foregroundPkg")
                    appendLine("Resolution: ${width}x${height} px | Total Interactive Elements: ${elements.size}")
                    appendLine("================================================================================")
                    appendLine()
                    appendLine("[UI BRIEF SUMMARY]")
                    appendLine(summary)
                    appendLine()
                    appendLine("[CLI VISUAL WIREFRAME / ASCII SCHEMATIC]")
                    appendLine(schematic)
                    appendLine()
                    appendLine("[DETAILED INTERACTIVE ELEMENT DIRECTORY & COORDINATES]")
                    appendLine("# All clickable buttons, inputs, toggles, and views with exact tap coordinates:")
                    appendLine(directory)
                    appendLine()
                    appendLine("[OCR DETECTED CANVAS / IMAGE TEXT]")
                    appendLine("# Text recognized from visual display (graphics/canvas):")
                    appendLine(ocrSection)
                    appendLine()
                    appendLine("================================================================================")
                    appendLine("END OF SCREEN CAPTURE")
                    appendLine("================================================================================")
                }

                val file = StorageManager.saveCapture(context, doc, now)
                val res = SpoofResult(
                    file = file,
                    summary = summary,
                    elementCount = elements.size,
                    fullContent = doc,
                    success = true
                )

                withContext(Dispatchers.Main) {
                    onResult(res)
                }
            }
        }

        private fun buildSummary(
            pkg: String,
            elements: List<ExtractedElement>,
            width: Int,
            height: Int
        ): String {
            val app = pkg.substringAfterLast('.')
            val buttons = elements.filter { it.type == "BUTTON" }
            val inputs = elements.filter { it.type == "INPUT" }
            val toggles = elements.filter { it.type == "TOGGLE" }

            val sb = StringBuilder()
            sb.append("Current active screen belongs to '$app' ($pkg).\n")
            sb.append("Display viewport is ${width}x${height} containing ${elements.size} detected elements.\n")

            if (inputs.isNotEmpty()) {
                val inLabels = inputs.take(3).joinToString(", ") { "\"${it.text}\"" }
                sb.append("• Input fields: $inLabels\n")
            }
            if (buttons.isNotEmpty()) {
                val btnLabels = buttons.take(5).joinToString(", ") { "\"${it.text}\"" }
                sb.append("• Clickable buttons: $btnLabels\n")
            }
            if (toggles.isNotEmpty()) {
                val togLabels = toggles.take(4).joinToString(", ") {
                    "\"${it.text}\" [${if (it.checked == true) "ON" else "OFF"}]"
                }
                sb.append("• Toggles / switches: $togLabels\n")
            }

            val topArea = elements.filter { it.center.second < height * 0.15 }
            val bottomArea = elements.filter { it.center.second > height * 0.85 }
            if (topArea.isNotEmpty()) {
                sb.append("• Top bar: ${topArea.take(3).joinToString { it.text }}\n")
            }
            if (bottomArea.isNotEmpty()) {
                sb.append("• Bottom navigation: ${bottomArea.take(4).joinToString { it.text }}\n")
            }

            return sb.toString().trim()
        }

        private fun buildAsciiSchematic(
            elements: List<ExtractedElement>,
            width: Int,
            height: Int
        ): String {
            val sb = StringBuilder()
            sb.appendLine("+----------------------------------------------------------------+")
            sb.appendLine("| [STATUS / TOP BAR]                                       (0,0) |")

            val header = elements.filter { it.center.second < height * 0.15 }.take(4)
            val body = elements.filter { it.center.second in (height * 0.15).toInt()..(height * 0.85).toInt() }
            val footer = elements.filter { it.center.second > height * 0.85 }.take(4)

            if (header.isNotEmpty()) {
                val headText = header.joinToString(" | ") { "[${it.type}] ${it.text.take(16)}" }
                sb.appendLine("| TOP: ${headText.padEnd(58).take(58)} |")
                sb.appendLine("|----------------------------------------------------------------|")
            }

            val sampleBody = body.sortedBy { it.center.second }.take(8)
            for (item in sampleBody) {
                val label = "[${item.type}] \"${item.text.take(24)}\""
                val coord = "center=(${item.center.first}, ${item.center.second})"
                val row = String.format(Locale.US, "| %-38s %22s |", label.take(38), coord)
                sb.appendLine(row)
            }

            if (body.size > 8) {
                sb.appendLine("| ... (${body.size - 8} more content items in scroll view)            |")
            }

            sb.appendLine("|----------------------------------------------------------------|")
            if (footer.isNotEmpty()) {
                val footText = footer.joinToString("  ") { "[${it.text.take(12)}]" }
                sb.appendLine("| NAV: ${footText.padEnd(58).take(58)} |")
            } else {
                sb.appendLine("| [SYSTEM NAV: BACK | HOME | RECENTS]                            |")
            }
            sb.appendLine("+----------------------------------------------------------------+ (${width},${height})")
            return sb.toString().trimEnd()
        }

        private fun buildDirectory(elements: List<ExtractedElement>): String {
            if (elements.isEmpty()) return "No interactive elements detected."
            val sb = StringBuilder()
            elements.forEachIndexed { i, elem ->
                val num = String.format(Locale.US, "%02d", i + 1)
                val w = elem.bounds.width()
                val h = elem.bounds.height()
                sb.appendLine("$num. [${elem.type}] \"${elem.text}\"")
                sb.appendLine("    Center:    (${elem.center.first}, ${elem.center.second})")
                sb.appendLine("    Bounds:    [${elem.bounds.left}, ${elem.bounds.top}] [${elem.bounds.right}, ${elem.bounds.bottom}] (Width: $w, Height: $h)")
                if (elem.viewId.isNotBlank()) sb.appendLine("    Resource:  ${elem.viewId}")
                sb.appendLine("    Class:     ${elem.className}")
                sb.append("    Flags:     clickable=${elem.clickable}, editable=${elem.editable}, scrollable=${elem.scrollable}")
                if (elem.checkable) sb.append(", checked=${elem.checked}")
                sb.appendLine()
            }
            return sb.toString().trimEnd()
        }

        private fun buildOcrSection(ocr: List<ScreenSpoofEngine.OcrBox>): String {
            if (ocr.isEmpty()) return "No OCR visual text detected (Accessibility tree captured directly)."
            val sb = StringBuilder()
            ocr.take(60).forEachIndexed { i, o ->
                val num = String.format(Locale.US, "%02d", i + 1)
                sb.appendLine("$num. [OCR] \"${o.text}\" center=(${o.center.first}, ${o.center.second}) bounds=[${o.bounds.left}, ${o.bounds.top}][${o.bounds.right}, ${o.bounds.bottom}]")
            }
            return sb.toString().trimEnd()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            com.jarves.mh.cursor.CursorServer.start(this)
            com.jarves.mh.cursor.CursorOverlayManager.show(this)
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            com.jarves.mh.cursor.CursorServer.stop()
            com.jarves.mh.cursor.CursorOverlayManager.hide()
        } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }
}
