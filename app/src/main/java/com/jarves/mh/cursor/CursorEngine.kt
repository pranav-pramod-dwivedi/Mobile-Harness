package com.jarves.mh.cursor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.jarves.mh.capture.CaptureRecord
import com.jarves.mh.capture.ScreenSpoofAccessibilityService
import com.jarves.mh.capture.ShellRunner
import com.jarves.mh.capture.SpoofResult
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class CursorResult(
    val success: Boolean,
    val action: String,
    val latencyMs: Long,
    val record: CaptureRecord? = null,
    val spoofResult: SpoofResult? = null,
    val error: String? = null
)

/**
 * High-speed multi-touch and AI computer control synthesizer.
 * Operates 100% root-free through Android Accessibility multi-stroke gestures,
 * with instant act-and-respoof pipelines.
 */
object CursorEngine {

    private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Visual pointer callback (can be registered by floating cursor overlay)
    var pointerListener: ((x: Float, y: Float, type: String) -> Unit)? = null

    fun isReady(): Boolean = ScreenSpoofAccessibilityService.isConnected()

    // ------------------------------------------------------------------------
    // 1. Single Tap
    // ------------------------------------------------------------------------
    fun tap(x: Float, y: Float, durationMs: Long = 50, onDone: (Boolean) -> Unit = {}) {
        pointerListener?.invoke(x, y, "tap")
        val service = ScreenSpoofAccessibilityService.instance
        if (service == null) {
            val rootOk = ShellRunner.tap(x, y)
            onDone(rootOk)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 0.5f, y + 0.5f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(10))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchWithCallback(service, gesture, onDone)
        } else {
            onDone(ShellRunner.tap(x, y))
        }
    }

    // ------------------------------------------------------------------------
    // 2. Double Tap
    // ------------------------------------------------------------------------
    fun doubleTap(x: Float, y: Float, onDone: (Boolean) -> Unit = {}) {
        pointerListener?.invoke(x, y, "double_tap")
        tap(x, y, 40) { ok1 ->
            if (!ok1) {
                onDone(false)
                return@tap
            }
            engineScope.launch {
                delay(90)
                tap(x, y, 40, onDone)
            }
        }
    }

    // ------------------------------------------------------------------------
    // 3. Long Press
    // ------------------------------------------------------------------------
    fun longPress(x: Float, y: Float, durationMs: Long = 650, onDone: (Boolean) -> Unit = {}) {
        pointerListener?.invoke(x, y, "long_press")
        val service = ScreenSpoofAccessibilityService.instance
        if (service == null) {
            onDone(ShellRunner.swipe(x, y, x, y, durationMs.toInt()))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 0.5f, y + 0.5f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchWithCallback(service, gesture, onDone)
        } else {
            onDone(ShellRunner.swipe(x, y, x, y, durationMs.toInt()))
        }
    }

    // ------------------------------------------------------------------------
    // 4. Smooth Swipe / Drag / Fling
    // ------------------------------------------------------------------------
    fun swipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 250,
        onDone: (Boolean) -> Unit = {}
    ) {
        pointerListener?.invoke(x1, y1, "swipe_start")
        val service = ScreenSpoofAccessibilityService.instance
        if (service == null) {
            onDone(ShellRunner.swipe(x1, y1, x2, y2, durationMs.toInt()))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchWithCallback(service, gesture) { ok ->
                pointerListener?.invoke(x2, y2, "swipe_end")
                onDone(ok)
            }
        } else {
            onDone(ShellRunner.swipe(x1, y1, x2, y2, durationMs.toInt()))
        }
    }

    // ------------------------------------------------------------------------
    // 5. Two-Finger Swipe / Multi-Touch Scroll
    // ------------------------------------------------------------------------
    fun twoFingerSwipe(
        x1: Float, y1: Float, x2: Float, y2: Float,
        x3: Float, y3: Float, x4: Float, y4: Float,
        durationMs: Long = 300,
        onDone: (Boolean) -> Unit = {}
    ) {
        pointerListener?.invoke((x1 + x3) / 2, (y1 + y3) / 2, "two_finger_start")
        val service = ScreenSpoofAccessibilityService.instance
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onDone(false)
            return
        }

        val path1 = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val path2 = Path().apply { moveTo(x3, y3); lineTo(x4, y4) }

        val stroke1 = GestureDescription.StrokeDescription(path1, 0, durationMs.coerceAtLeast(50))
        val stroke2 = GestureDescription.StrokeDescription(path2, 0, durationMs.coerceAtLeast(50))

        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()

        dispatchWithCallback(service, gesture, onDone)
    }

    // ------------------------------------------------------------------------
    // 6. Pinch & Zoom (Two-Finger)
    // ------------------------------------------------------------------------
    fun pinch(
        centerX: Float, centerY: Float,
        factor: Float = 0.5f,
        span: Float = 400f,
        durationMs: Long = 300,
        onDone: (Boolean) -> Unit = {}
    ) {
        val halfSpan = span / 2f
        val targetHalf = halfSpan * factor.coerceIn(0.1f, 0.9f)

        twoFingerSwipe(
            x1 = centerX - halfSpan, y1 = centerY,
            x2 = centerX - targetHalf, y2 = centerY,
            x3 = centerX + halfSpan, y3 = centerY,
            x4 = centerX + targetHalf, y4 = centerY,
            durationMs = durationMs,
            onDone = onDone
        )
    }

    fun zoom(
        centerX: Float, centerY: Float,
        factor: Float = 2.0f,
        span: Float = 200f,
        durationMs: Long = 300,
        onDone: (Boolean) -> Unit = {}
    ) {
        val halfSpan = span / 2f
        val targetHalf = (halfSpan * factor).coerceAtMost(500f)

        twoFingerSwipe(
            x1 = centerX - halfSpan, y1 = centerY,
            x2 = centerX - targetHalf, y2 = centerY,
            x3 = centerX + halfSpan, y3 = centerY,
            x4 = centerX + targetHalf, y4 = centerY,
            durationMs = durationMs,
            onDone = onDone
        )
    }

    // ------------------------------------------------------------------------
    // 7. Three-Finger Swipe
    // ------------------------------------------------------------------------
    fun threeFingerSwipe(
        x1: Float, y1: Float, x2: Float, y2: Float,
        x3: Float, y3: Float, x4: Float, y4: Float,
        x5: Float, y5: Float, x6: Float, y6: Float,
        durationMs: Long = 300,
        onDone: (Boolean) -> Unit = {}
    ) {
        pointerListener?.invoke(x3, y3, "three_finger_start")
        val service = ScreenSpoofAccessibilityService.instance
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onDone(false)
            return
        }

        val p1 = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val p2 = Path().apply { moveTo(x3, y3); lineTo(x4, y4) }
        val p3 = Path().apply { moveTo(x5, y5); lineTo(x6, y6) }

        val s1 = GestureDescription.StrokeDescription(p1, 0, durationMs.coerceAtLeast(50))
        val s2 = GestureDescription.StrokeDescription(p2, 0, durationMs.coerceAtLeast(50))
        val s3 = GestureDescription.StrokeDescription(p3, 0, durationMs.coerceAtLeast(50))

        val gesture = GestureDescription.Builder()
            .addStroke(s1)
            .addStroke(s2)
            .addStroke(s3)
            .build()

        dispatchWithCallback(service, gesture, onDone)
    }

    // ------------------------------------------------------------------------
    // 8. Type Text
    // ------------------------------------------------------------------------
    fun typeText(text: String, onDone: (Boolean) -> Unit = {}) {
        val service = ScreenSpoofAccessibilityService.instance
        if (service != null) {
            val root = service.rootInActiveWindow
            val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val setOk = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (setOk) {
                    onDone(true)
                    return
                }
            }
        }

        // Fallback to Shell
        engineScope.launch(Dispatchers.IO) {
            val escaped = text.replace(" ", "%s").replace("\"", "\\\"")
            val res = ShellRunner.run("input text \"$escaped\"")
            withContext(Dispatchers.Main) {
                onDone(res.isSuccess)
            }
        }
    }

    // ------------------------------------------------------------------------
    // 9. System Navigation Keys
    // ------------------------------------------------------------------------
    fun pressKey(globalAction: Int, onDone: (Boolean) -> Unit = {}) {
        val service = ScreenSpoofAccessibilityService.instance
        if (service != null) {
            val ok = service.performGlobalAction(globalAction)
            onDone(ok)
            return
        }

        // Shell fallback for key events
        val keycode = when (globalAction) {
            AccessibilityService.GLOBAL_ACTION_BACK -> 4
            AccessibilityService.GLOBAL_ACTION_HOME -> 3
            AccessibilityService.GLOBAL_ACTION_RECENTS -> 187
            AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS -> 83
            else -> 0
        }
        if (keycode > 0) {
            engineScope.launch(Dispatchers.IO) {
                val res = ShellRunner.run("input keyevent $keycode")
                withContext(Dispatchers.Main) { onDone(res.isSuccess) }
            }
        } else {
            onDone(false)
        }
    }

    // ------------------------------------------------------------------------
    // 10. Atomic "Act-and-Respoof" Pipeline
    // ------------------------------------------------------------------------
    fun executeAndRespoof(
        context: Context,
        actionName: String,
        actionDispatcher: (onGestureDone: (Boolean) -> Unit) -> Unit,
        onResult: (CursorResult) -> Unit
    ) {
        val startTime = System.currentTimeMillis()
        actionDispatcher { success ->
            if (!success) {
                val latency = System.currentTimeMillis() - startTime
                onResult(CursorResult(false, actionName, latency, record = null, spoofResult = null, error = "Gesture dispatch failed"))
                return@actionDispatcher
            }

            // Small settle window for UI animation, then trigger instant root-free spoof
            engineScope.launch {
                delay(120) // settle delay
                ScreenSpoofAccessibilityService.captureAndSpoof(context) { spoof ->
                    val latency = System.currentTimeMillis() - startTime
                    val rec = CaptureRecord(
                        file = spoof.file,
                        title = spoof.file.nameWithoutExtension,
                        timestampMs = System.currentTimeMillis(),
                        formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                        sizeBytes = spoof.file.length(),
                        previewSnippet = spoof.summary,
                        elementCount = spoof.elementCount
                    )
                    onResult(CursorResult(true, actionName, latency, rec, spoof, null))
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Internal Helper
    // ------------------------------------------------------------------------
    @RequiresApi(Build.VERSION_CODES.N)
    private fun dispatchWithCallback(
        service: AccessibilityService,
        gesture: GestureDescription,
        onDone: (Boolean) -> Unit
    ) {
        val called = AtomicBoolean(false)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i("CursorEngine", "dispatchGesture -> onCompleted")
                if (called.compareAndSet(false, true)) {
                    onDone(true)
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w("CursorEngine", "dispatchGesture -> onCancelled")
                if (called.compareAndSet(false, true)) {
                    // Try shell runner fallback if gesture was cancelled
                    onDone(false)
                }
            }
        }

        val dispatched = service.dispatchGesture(gesture, callback, null)
        Log.i("CursorEngine", "dispatchGesture called. returned dispatched=$dispatched")
        if (!dispatched) {
            Log.w("CursorEngine", "service.dispatchGesture returned false")
            if (called.compareAndSet(false, true)) {
                onDone(false)
            }
        }
    }
}
