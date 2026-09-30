package com.jarves.mh.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SpoofResult(
    val file: File,
    val summary: String,
    val elementCount: Int,
    val fullContent: String,
    val success: Boolean,
    val error: String? = null
)

object ScreenSpoofEngine {

    private const val SCREENSHOT_PATH = "/data/local/tmp/screenspoof_tmp.png"
    private const val WINDOW_XML_PATH = "/data/local/tmp/screenspoof_window.xml"
    private val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")

    data class UiElement(
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

    data class OcrBox(
        val text: String,
        val bounds: Rect,
        val center: Pair<Int, Int>
    )

    fun processScreen(context: Context): SpoofResult {
        val now = Date()
        val dateDisplay = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)

        // 1. Screen Dimensions
        val (screenWidth, screenHeight) = resolveResolution(context)

        // 2. Detect Foreground App
        val foreground = resolveForegroundApp()

        // 3. Dump UI Hierarchy XML
        val xmlElements = extractHierarchyElements()

        // 4. Take Screenshot & Extract OCR
        val ocrElements = captureAndExtractOcr()

        // 5. Merge Elements
        val allElements = mergeElements(xmlElements, ocrElements)

        // 6. Generate UI Summary
        val summary = buildSummary(foreground, allElements, screenWidth, screenHeight)

        // 7. Generate ASCII CLI Schematic
        val schematic = buildAsciiSchematic(allElements, screenWidth, screenHeight)

        // 8. Generate Detailed Directory
        val directory = buildDirectory(allElements)

        // 9. Generate OCR Section
        val ocrSection = buildOcrSection(ocrElements)

        // 10. Assemble Full CLI Document
        val document = buildString {
            appendLine("================================================================================")
            appendLine("JARVIS SCREEN SPOOF — CLI VIEW")
            appendLine("Captured: $dateDisplay | Device: ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
            appendLine("Foreground: $foreground")
            appendLine("Resolution: ${screenWidth}x${screenHeight} px | Total Interactive Elements: ${allElements.size}")
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
            appendLine("# Text recognized directly from pixels for graphics, games, and custom controls:")
            appendLine(ocrSection)
            appendLine()
            appendLine("================================================================================")
            appendLine("END OF SCREEN CAPTURE")
            appendLine("================================================================================")
        }

        // 11. Save
        val file = StorageManager.saveCapture(context, document, now)

        return SpoofResult(
            file = file,
            summary = summary,
            elementCount = allElements.size,
            fullContent = document,
            success = true
        )
    }

    private fun resolveResolution(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            return Pair(metrics.widthPixels, metrics.heightPixels)
        }
        val sizeRes = ShellRunner.exec("wm size", asRoot = true)
        val match = Regex("""(\d+)x(\d+)""").find(sizeRes.out)
        if (match != null) {
            val (w, h) = match.destructured
            return Pair(w.toInt(), h.toInt())
        }
        return Pair(1080, 2340)
    }

    private fun resolveForegroundApp(): String {
        val res = ShellRunner.exec("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 1", asRoot = true)
        val match = Regex("""([a-zA-Z0-9._]+/[a-zA-Z0-9._]+)""").find(res.out)
        if (match != null) return match.groupValues[1]
        return "Unknown Active App"
    }

    private fun extractHierarchyElements(): List<UiElement> {
        ShellRunner.exec("uiautomator dump $WINDOW_XML_PATH >/dev/null 2>&1", asRoot = true, timeoutMs = 2500L)
        val cat = ShellRunner.exec("cat $WINDOW_XML_PATH", asRoot = true, timeoutMs = 2000L)
        if (cat.out.isBlank()) return emptyList()

        val nodeRegex = Regex("""<node[^>]*text="([^"]*)"[^>]*content-desc="([^"]*)"[^>]*resource-id="([^"]*)"[^>]*class="([^"]*)"[^>]*clickable="([^"]*)"[^>]*checkable="([^"]*)"[^>]*checked="([^"]*)"[^>]*scrollable="([^"]*)"[^>]*bounds="([^"]*)"[^>]*>""")
        val list = ArrayList<UiElement>()

        for (m in nodeRegex.findAll(cat.out)) {
            val (text, desc, resId, cls, clickable, checkable, checked, scrollable, bounds) = m.destructured
            val label = listOf(text, desc).firstOrNull { it.isNotBlank() } ?: ""
            if (label.isBlank() && clickable != "true" && !cls.contains("Edit") && !cls.contains("Image")) {
                continue
            }
            val rect = parseBounds(bounds) ?: continue
            val cx = (rect.left + rect.right) / 2
            val cy = (rect.top + rect.bottom) / 2

            val isCheck = checkable == "true"
            val isChecked = if (isCheck) checked == "true" else null
            val isEdit = cls.contains("Edit", ignoreCase = true)
            val isClick = clickable == "true"
            val isScroll = scrollable == "true"

            val type = when {
                isCheck -> "TOGGLE"
                isEdit -> "INPUT"
                isClick -> "BUTTON"
                isScroll -> "SCROLLABLE"
                cls.contains("Image", ignoreCase = true) -> "IMAGE/ICON"
                else -> "TEXT"
            }

            list.add(
                UiElement(
                    type = type,
                    text = label.ifBlank { cls.substringAfterLast('.') },
                    bounds = rect,
                    center = Pair(cx, cy),
                    viewId = resId.substringAfterLast('/'),
                    className = cls.substringAfterLast('.'),
                    clickable = isClick,
                    editable = isEdit,
                    scrollable = isScroll,
                    checkable = isCheck,
                    checked = isChecked
                )
            )
        }
        return list
    }

    private fun captureAndExtractOcr(): List<OcrBox> {
        val screencap = ShellRunner.exec("screencap -p $SCREENSHOT_PATH", asRoot = true)
        if (screencap.rc != 0 || !File(SCREENSHOT_PATH).exists()) return emptyList()

        val bitmap = BitmapFactory.decodeFile(SCREENSHOT_PATH) ?: return emptyList()
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val taskResult = Tasks.await(recognizer.process(image), 4, TimeUnit.SECONDS)
            val list = ArrayList<OcrBox>()
            for (block in taskResult.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    val text = line.text.replace("\n", " ").trim()
                    if (text.isNotBlank()) {
                        val cx = (box.left + box.right) / 2
                        val cy = (box.top + box.bottom) / 2
                        list.add(OcrBox(text, box, Pair(cx, cy)))
                    }
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        } finally {
            try { bitmap.recycle() } catch (_: Exception) {}
        }
    }

    private fun mergeElements(xml: List<UiElement>, ocr: List<OcrBox>): List<UiElement> {
        val merged = ArrayList(xml)
        for (o in ocr) {
            val existsInXml = xml.any {
                it.bounds.contains(o.center.first, o.center.second) ||
                it.text.contains(o.text, ignoreCase = true)
            }
            if (!existsInXml) {
                merged.add(
                    UiElement(
                        type = "TEXT",
                        text = o.text,
                        bounds = o.bounds,
                        center = o.center,
                        viewId = "",
                        className = "OcrVisualText",
                        clickable = true, // Can be tapped directly
                        editable = false,
                        scrollable = false,
                        checkable = false,
                        checked = null
                    )
                )
            }
        }
        return merged
    }

    private fun buildSummary(
        foreground: String,
        elements: List<UiElement>,
        width: Int,
        height: Int
    ): String {
        val app = foreground.substringBefore('/').substringAfterLast('.')
        val buttons = elements.filter { it.type == "BUTTON" }
        val inputs = elements.filter { it.type == "INPUT" }
        val toggles = elements.filter { it.type == "TOGGLE" }

        val sb = StringBuilder()
        sb.append("Current active screen belongs to '$app' ($foreground).\n")
        sb.append("Display viewport is ${width}x${height} containing ${elements.size} detected elements.\n")

        if (inputs.isNotEmpty()) {
            val inLabels = inputs.take(3).joinToString(", ") { "\"${it.text}\"" }
            sb.append("• Input fields: $inLabels\n")
        }
        if (buttons.isNotEmpty()) {
            val btnLabels = buttons.take(5).joinToString(", ") { "\"${it.text}\"" }
            sb.append("• Primary clickable buttons: $btnLabels\n")
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
        elements: List<UiElement>,
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

    private fun buildDirectory(elements: List<UiElement>): String {
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

    private fun buildOcrSection(ocr: List<OcrBox>): String {
        if (ocr.isEmpty()) return "No OCR visual text detected or screencap unavailable."
        val sb = StringBuilder()
        ocr.take(60).forEachIndexed { i, o ->
            val num = String.format(Locale.US, "%02d", i + 1)
            sb.appendLine("$num. [OCR] \"${o.text}\" center=(${o.center.first}, ${o.center.second}) bounds=[${o.bounds.left}, ${o.bounds.top}][${o.bounds.right}, ${o.bounds.bottom}]")
        }
        if (ocr.size > 60) {
            sb.appendLine("... (${ocr.size - 60} additional OCR lines detected)")
        }
        return sb.toString().trimEnd()
    }

    private fun parseBounds(str: String): Rect? {
        val m = BOUNDS_REGEX.matchEntire(str) ?: return null
        val (l, t, r, b) = m.destructured
        return Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }
}
