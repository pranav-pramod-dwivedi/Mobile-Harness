package com.jarves.mh.cursor

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BroadcastReceiver for zero-setup command line execution via ADB or local broadcast:
 * adb shell am broadcast -a com.screenspoof.app.CURSOR --es action tap --ef x 540 --ef y 1200 --ez respoof true
 */
class CursorActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action")?.lowercase() ?: "tap"
        val respoof = intent.getBooleanExtra("respoof", true)
        val x = intent.getFloatExtra("x", 540f)
        val y = intent.getFloatExtra("y", 1200f)

        when (action) {
            "tap" -> {
                CursorOverlayManager.onTap(x, y)
                if (respoof) {
                    CursorEngine.executeAndRespoof(context, "tap", { CursorEngine.tap(x, y, 50, it) }, {})
                } else {
                    CursorEngine.tap(x, y)
                }
            }
            "double_tap" -> {
                CursorOverlayManager.onDoubleTap(x, y)
                if (respoof) {
                    CursorEngine.executeAndRespoof(context, "double_tap", { CursorEngine.doubleTap(x, y, it) }, {})
                } else {
                    CursorEngine.doubleTap(x, y)
                }
            }
            "long_press" -> {
                val dur = intent.getLongExtra("duration", 650)
                CursorOverlayManager.onLongPress(x, y)
                if (respoof) {
                    CursorEngine.executeAndRespoof(context, "long_press", { CursorEngine.longPress(x, y, dur, it) }, {})
                } else {
                    CursorEngine.longPress(x, y, dur)
                }
            }
            "swipe" -> {
                val x1 = intent.getFloatExtra("x1", 540f)
                val y1 = intent.getFloatExtra("y1", 1600f)
                val x2 = intent.getFloatExtra("x2", 540f)
                val y2 = intent.getFloatExtra("y2", 400f)
                val dur = intent.getLongExtra("duration", 250)
                CursorOverlayManager.onSwipe(x1, y1, x2, y2)
                if (respoof) {
                    CursorEngine.executeAndRespoof(context, "swipe", { CursorEngine.swipe(x1, y1, x2, y2, dur, it) }, {})
                } else {
                    CursorEngine.swipe(x1, y1, x2, y2, dur)
                }
            }
            "type" -> {
                val text = intent.getStringExtra("text") ?: ""
                CursorOverlayManager.onType(text)
                if (respoof) {
                    CursorEngine.executeAndRespoof(context, "type", { CursorEngine.typeText(text, it) }, {})
                } else {
                    CursorEngine.typeText(text)
                }
            }
            "key", "nav" -> {
                val key = intent.getStringExtra("key")?.lowercase() ?: "back"
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
                if (respoof) {
                    CursorEngine.executeAndRespoof(context, key, { CursorEngine.pressKey(globalAction, it) }, {})
                } else {
                    CursorEngine.pressKey(globalAction)
                }
            }
            "cursor_overlay" -> {
                val enable = intent.getBooleanExtra("enable", true)
                if (enable) CursorOverlayManager.show(context) else CursorOverlayManager.hide()
            }
            "two_finger", "two-finger", "twofinger" -> {
                val x1 = intent.getFloatExtra("x1", 440f)
                val y1 = intent.getFloatExtra("y1", 1200f)
                val x2 = intent.getFloatExtra("x2", 640f)
                val y2 = intent.getFloatExtra("y2", 1200f)
                val dx = intent.getFloatExtra("dx", 0f)
                val dy = intent.getFloatExtra("dy", -600f)
                val dur = intent.getLongExtra("duration", 300)
                val run: ((Boolean) -> Unit) -> Unit = { cb ->
                    CursorEngine.twoFingerSwipe(x1, y1, x1 + dx, y1 + dy, x2, y2, x2 + dx, y2 + dy, dur, cb)
                }
                if (respoof) CursorEngine.executeAndRespoof(context, "two_finger", run, {})
                else run {}
            }
            "pinch" -> {
                val cx = intent.getFloatExtra("x", 540f)
                val cy = intent.getFloatExtra("y", 1200f)
                val factor = intent.getFloatExtra("factor", 0.5f)
                val dur = intent.getLongExtra("duration", 300)
                if (respoof) CursorEngine.executeAndRespoof(context, "pinch", { CursorEngine.pinch(cx, cy, factor = factor, durationMs = dur, onDone = it) }, {})
                else CursorEngine.pinch(cx, cy, factor = factor, durationMs = dur)
            }
            "zoom" -> {
                val cx = intent.getFloatExtra("x", 540f)
                val cy = intent.getFloatExtra("y", 1200f)
                val factor = intent.getFloatExtra("factor", 2.0f)
                val dur = intent.getLongExtra("duration", 300)
                if (respoof) CursorEngine.executeAndRespoof(context, "zoom", { CursorEngine.zoom(cx, cy, factor = factor, durationMs = dur, onDone = it) }, {})
                else CursorEngine.zoom(cx, cy, factor = factor, durationMs = dur)
            }
            "three_finger", "three-finger", "threefinger" -> {
                val dir = (intent.getStringExtra("direction") ?: "up").lowercase()
                val dur = intent.getLongExtra("duration", 300)
                val cx = intent.getFloatExtra("x", 540f)
                val cy = intent.getFloatExtra("y", 1200f)
                val dist = intent.getFloatExtra("distance", 600f)
                val (dx, dy) = when (dir) {
                    "down" -> Pair(0f, dist)
                    "left" -> Pair(-dist, 0f)
                    "right" -> Pair(dist, 0f)
                    else -> Pair(0f, -dist)
                }
                val run: ((Boolean) -> Unit) -> Unit = { cb ->
                    CursorEngine.threeFingerSwipe(
                        cx - 100f, cy, cx - 100f + dx, cy + dy,
                        cx, cy, cx + dx, cy + dy,
                        cx + 100f, cy, cx + 100f + dx, cy + dy,
                        durationMs = dur, onDone = cb
                    )
                }
                if (respoof) CursorEngine.executeAndRespoof(context, "three_finger", run, {})
                else run {}
            }
        }
    }
}
