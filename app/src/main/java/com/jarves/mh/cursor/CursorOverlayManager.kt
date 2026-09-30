package com.jarves.mh.cursor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the permanent animated Cursor overlay.
 * Uses TYPE_APPLICATION_OVERLAY with FLAG_NOT_TOUCHABLE so all touches
 * pass seamlessly through to whichever app is active below.
 */
object CursorOverlayManager {

    private const val TAG = "CursorOverlayManager"
    private var windowManager: WindowManager? = null
    @SuppressLint("StaticFieldLeak")
    private var overlayView: CursorOverlayView? = null
    @SuppressLint("StaticFieldLeak")
    private var savedAppContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isOverlayActive = MutableStateFlow(false)
    val isOverlayActive: StateFlow<Boolean> = _isOverlayActive.asStateFlow()

    fun isShowing(): Boolean = overlayView != null

    fun show(context: Context) {
        val appContext = context.applicationContext ?: context
        savedAppContext = appContext
        val canDraw = Settings.canDrawOverlays(appContext)
        Log.i(TAG, "show() requested. canDrawOverlays=$canDraw, overlayViewExists=${overlayView != null}")

        if (overlayView != null) return
        if (!canDraw) {
            Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission not granted")
            return
        }

        mainHandler.post {
            try {
                if (overlayView != null) return@post
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (wm == null) {
                    Log.e(TAG, "WindowManager is null from appContext!")
                    return@post
                }
                windowManager = wm

                val view = CursorOverlayView(appContext)
                overlayView = view

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }

                wm.addView(view, params)
                _isOverlayActive.value = true
                Log.i(TAG, "Cursor overlay view added successfully to WindowManager! Active=true")

                // Connect to CursorEngine pointer listener
                CursorEngine.pointerListener = { x, y, type ->
                    when (type) {
                        "tap" -> onTap(x, y)
                        "double_tap" -> onDoubleTap(x, y)
                        "long_press" -> onLongPress(x, y)
                        "swipe_start" -> onMove(x, y, "SWIPE")
                        "swipe_end" -> onMove(x, y, "READY")
                        else -> onMove(x, y, type.uppercase())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in show() WindowManager.addView: ${e.message}", e)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            try {
                overlayView?.let {
                    windowManager?.removeView(it)
                }
            } catch (_: Exception) {}
            overlayView = null
            _isOverlayActive.value = false
        }
    }

    fun onTap(x: Float, y: Float, onTouchReady: (() -> Unit)? = null) {
        mainHandler.post {
            if (overlayView != null) {
                overlayView?.triggerTap(x, y, "TAP", onTouchReady)
            } else {
                onTouchReady?.invoke()
            }
        }
    }

    fun onDoubleTap(x: Float, y: Float, onTouchReady: (() -> Unit)? = null) {
        mainHandler.post {
            if (overlayView != null) {
                overlayView?.triggerDoubleTap(x, y, onTouchReady)
            } else {
                onTouchReady?.invoke()
            }
        }
    }

    fun onLongPress(x: Float, y: Float, onTouchReady: (() -> Unit)? = null) {
        mainHandler.post {
            if (overlayView != null) {
                overlayView?.triggerLongPress(x, y, 500, onTouchReady)
            } else {
                onTouchReady?.invoke()
            }
        }
    }

    fun onSwipe(x1: Float, y1: Float, x2: Float, y2: Float, onSwipeReady: (() -> Unit)? = null) {
        mainHandler.post {
            if (overlayView != null) {
                overlayView?.triggerSwipe(x1, y1, x2, y2, "SWIPE", onSwipeReady)
            } else {
                onSwipeReady?.invoke()
            }
        }
    }

    fun onMove(x: Float, y: Float, actionLabel: String = "MOVE") {
        mainHandler.post {
            overlayView?.moveTo(x, y, actionLabel)
        }
    }

    fun onTwoFinger(points: List<PointF>, label: String = "2-FINGER", onTouchReady: (() -> Unit)? = null) {
        mainHandler.post {
            if (overlayView != null) {
                overlayView?.triggerMultiTouch(points, label, onTouchReady)
            } else {
                onTouchReady?.invoke()
            }
        }
    }

    fun onThreeFinger(points: List<PointF>, label: String = "3-FINGER", onTouchReady: (() -> Unit)? = null) {
        mainHandler.post {
            if (overlayView != null) {
                overlayView?.triggerMultiTouch(points, label, onTouchReady)
            } else {
                onTouchReady?.invoke()
            }
        }
    }

    fun onNavAction(label: String) {
        mainHandler.post {
            savedAppContext?.let { ctx ->
                Toast.makeText(ctx, label, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onType(text: String) {
        mainHandler.post {
            overlayView?.triggerType(text)
            savedAppContext?.let { ctx ->
                val preview = if (text.length > 20) text.take(17) + "..." else text
                Toast.makeText(ctx, "⌨ $preview", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
