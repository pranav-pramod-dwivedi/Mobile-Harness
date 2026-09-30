package com.jarves.mh.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.jarves.mh.capture.ScreenSpoofEngine
import com.jarves.mh.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

class FloatingOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "screenspoof_overlay_channel"
        private const val NOTIF_ID = 8801

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        const val ACTION_START = "com.jarves.mh.START"
        const val ACTION_STOP = "com.jarves.mh.STOP"
        const val ACTION_TRIGGER_CAPTURE = "com.jarves.mh.TRIGGER_CAPTURE"

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "Grant 'Display Over Other Apps' permission first", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var floatingView: FloatingOrbView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var isBusy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        makeNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        _isRunning.value = true
        initFloatingView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TRIGGER_CAPTURE) {
            triggerCapture()
            return START_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        floatingView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        floatingView = null
        scope.cancel()
        super.onDestroy()
    }

    private fun initFloatingView() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(metrics)

        val size = (60 * resources.displayMetrics.density).toInt()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - size - 24
            y = metrics.heightPixels / 3
        }

        floatingView = FloatingOrbView(this).apply {
            setOnTouchListener(object : View.OnTouchListener {
                private var startX = 0
                private var startY = 0
                private var touchX = 0f
                private var touchY = 0f
                private var downTime = 0L

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val p = windowParams ?: return false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = p.x
                            startY = p.y
                            touchX = event.rawX
                            touchY = event.rawY
                            downTime = System.currentTimeMillis()
                            floatingView?.isPressedState = true
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - touchX).toInt()
                            val dy = (event.rawY - touchY).toInt()
                            p.x = startX + dx
                            p.y = startY + dy
                            try { windowManager?.updateViewLayout(v, p) } catch (_: Exception) {}
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            floatingView?.isPressedState = false
                            val delta = hypot(event.rawX - touchX, event.rawY - touchY)
                            val elapsed = System.currentTimeMillis() - downTime

                            // Snap to edge
                            val screenW = resources.displayMetrics.widthPixels
                            p.x = if (p.x + size / 2 < screenW / 2) 20 else screenW - size - 20
                            try { windowManager?.updateViewLayout(v, p) } catch (_: Exception) {}

                            // If short tap without significant movement -> Trigger Screen Capture
                            if (delta < 25 && elapsed < 350) {
                                triggerCapture()
                            }
                            return true
                        }
                    }
                    return false
                }
            })
        }

        try {
            windowManager?.addView(floatingView, windowParams)
        } catch (e: Exception) {
            Toast.makeText(this, "Overlay display error: ${e.message}", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun triggerCapture() {
        if (isBusy) return
        isBusy = true

        buzz()

        // 1. Hide the overlay button so it is excluded from the screenshot
        floatingView?.visibility = View.GONE

        scope.launch {
            kotlinx.coroutines.delay(80)
            if (com.jarves.mh.capture.ScreenSpoofAccessibilityService.isConnected()) {
                com.jarves.mh.capture.ScreenSpoofAccessibilityService.captureAndSpoof(applicationContext) { res ->
                    floatingView?.visibility = View.VISIBLE
                    floatingView?.pulseSuccess()
                    isBusy = false

                    Toast.makeText(
                        applicationContext,
                        "✓ Screen Spoofed: ${res.file.name}\n${res.elementCount} elements extracted",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                val res = withContext(Dispatchers.IO) {
                    ScreenSpoofEngine.processScreen(applicationContext)
                }

                // 2. Restore overlay visibility with pulse
                floatingView?.visibility = View.VISIBLE
                floatingView?.pulseSuccess()
                isBusy = false

                Toast.makeText(
                    applicationContext,
                    "✓ Screen Captured: ${res.file.name}\n${res.elementCount} elements extracted",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun buzz() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(35)
            }
        } catch (_: Exception) {}
    }

    private fun makeNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "ScreenSpoof Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows floating capture button over apps"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    private fun buildNotification(): Notification {
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pApp = PendingIntent.getActivity(this, 0, appIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, FloatingOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val pStop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenSpoof Overlay Active")
            .setContentText("Tap the floating button over any app to capture a CLI UI spoof")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pStop)
            .setOngoing(true)
            .build()
    }

    private class FloatingOrbView(context: Context) : View(context) {
        var isPressedState = false
            set(value) { field = value; invalidate() }

        private var pulse = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val handler = Handler(Looper.getMainLooper())

        fun pulseSuccess() {
            pulse = 255
            invalidate()
            handler.postDelayed({
                pulse = 0
                invalidate()
            }, 600)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = (width.coerceAtMost(height) / 2f) - 6f

            // Outer Ring
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (isPressedState) 5f else 3.5f
            paint.color = if (pulse > 0) Color.parseColor("#10B981") else Color.parseColor("#06B6D4")
            paint.alpha = if (pulse > 0) pulse else if (isPressedState) 255 else 200
            canvas.drawCircle(cx, cy, radius, paint)

            // Inner Core
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#090D1A")
            paint.alpha = 240
            canvas.drawCircle(cx, cy, radius - 4f, paint)

            // Target Crosshair Ring
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            paint.color = if (pulse > 0) Color.parseColor("#34D399") else Color.parseColor("#38BDF8")
            paint.alpha = 240
            canvas.drawCircle(cx, cy, radius * 0.45f, paint)

            // Ticks
            val len = radius * 0.28f
            canvas.drawLine(cx, cy - radius * 0.45f - len, cx, cy - radius * 0.45f, paint)
            canvas.drawLine(cx, cy + radius * 0.45f, cx, cy + radius * 0.45f + len, paint)
            canvas.drawLine(cx - radius * 0.45f - len, cy, cx - radius * 0.45f, cy, paint)
            canvas.drawLine(cx + radius * 0.45f, cy, cx + radius * 0.45f + len, cy, paint)

            // Center Point
            paint.style = Paint.Style.FILL
            paint.color = if (pulse > 0) Color.parseColor("#10B981") else Color.parseColor("#38BDF8")
            canvas.drawCircle(cx, cy, 3.5f, paint)
        }
    }
}
