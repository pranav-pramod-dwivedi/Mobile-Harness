package com.jarves.mh.cursor

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.PathInterpolator
import java.util.ArrayDeque
import kotlin.math.hypot

enum class CursorMode {
    DEFAULT,      // Sleek Compact Studio Pointer
    CLICKED,      // Subtle Squash bounce & minimalist ripple
    DRAGGING,     // Fast directional drag
    LONG_PRESS,   // Subtle charge ring
    TYPING        // Precision text I-beam
}

/**
 * High-Speed Motion Edit Cursor Overlay (Screen Studio / Keynote style).
 * 
 * Features:
 * - Permanent floating overlay, 100% pass-through touch (FLAG_NOT_TOUCHABLE).
 * - Smooth Ease-in-Out: slow in start, smooth acceleration, slow decelerating landing.
 * - Dynamic High-Visibility Motion Trail: glowing tapered ribbon with persistence
 *   during fast movements that organically fades out on arrival.
 * - Compact ~18.5dp studio pointer with dark obsidian core and crisp white outline.
 * - Clean tap feedback (tactile squash bounce + expanding pulse ripple).
 */
@SuppressLint("ViewConstructor")
class CursorOverlayView(context: Context) : View(context) {

    private val dp = context.resources.displayMetrics.density

    // Cursor coordinates
    var cursorX = 540f
    var cursorY = 1170f
    private var activeMoveAnimator: ValueAnimator? = null

    // Transformations & State
    private var currentMode = CursorMode.DEFAULT
    private var cursorScale = 1.0f
    private var cursorRotation = 0f

    // Motion flight path tracking
    private var startFlightPoint = PointF(540f, 1170f)
    private var controlFlightPoint = PointF(540f, 1170f)
    private var targetFlightPoint = PointF(540f, 1170f)

    // Dynamic Trail Node: coordinates and arrival timestamp
    data class TrailNode(val x: Float, val y: Float, val timestamp: Long)
    private val flightTrail = ArrayDeque<TrailNode>()
    private val maxTrailPoints = 28
    private var trailFadeAnimator: ValueAnimator? = null

    // Tap Ripple & Feedback
    private var tapRippleRadius = 0f
    private val tapRippleMaxRadius = 26f * dp
    private var tapRippleAlpha = 0f

    // Long press charge arc
    private var chargeAngle = 0f

    // Inactivity Fade-out / Disappear
    var cursorAlpha = 1.0f
        private set
    private var inactivityFadeAnimator: ValueAnimator? = null
    private val inactivityTimeoutMs = 1500L // Disappear smoothly after 1.5s of inactivity
    private val inactivityRunnable = Runnable {
        fadeOutOnInactivity()
    }

    private fun resetInactivityTimer() {
        removeCallbacks(inactivityRunnable)
        if (cursorAlpha < 1.0f) {
            fadeInOnActivity()
        }
        postDelayed(inactivityRunnable, inactivityTimeoutMs)
    }

    private fun fadeInOnActivity() {
        inactivityFadeAnimator?.cancel()
        inactivityFadeAnimator = ValueAnimator.ofFloat(cursorAlpha, 1.0f).apply {
            duration = 150
            addUpdateListener { va ->
                cursorAlpha = va.animatedValue as Float
                postInvalidateOnAnimation()
            }
        }
        inactivityFadeAnimator?.start()
    }

    private fun fadeOutOnInactivity() {
        inactivityFadeAnimator?.cancel()
        inactivityFadeAnimator = ValueAnimator.ofFloat(cursorAlpha, 0.0f).apply {
            duration = 350
            interpolator = easeInOutInterpolator
            addUpdateListener { va ->
                cursorAlpha = va.animatedValue as Float
                postInvalidateOnAnimation()
            }
        }
        inactivityFadeAnimator?.start()
    }

    // Compact Cursor Pointer Body (Obsidian Dark with high-contrast depth)
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        style = Paint.Style.FILL
        setShadowLayer(10f * dp, 1.5f * dp, 4f * dp, Color.argb(160, 0, 0, 0))
    }

    // Crisp White Outline Border
    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * dp
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    // Glowing Motion Trail Paint
    private val motionTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(4f * dp, 0f, 0f, Color.argb(120, 255, 255, 255))
    }

    // Clean Minimal Tap Ripple (White/Amber accent)
    private val tapRipplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
    }

    // Long Press Energy Charge
    private val chargeArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        style = Paint.Style.STROKE
        strokeWidth = 3f * dp
        strokeCap = Paint.Cap.ROUND
    }

    // Compact ~18.5dp Studio Arrow Path
    private val arrowPath = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 18.5f * dp)
        lineTo(5.2f * dp, 13.8f * dp)
        lineTo(9.2f * dp, 20.2f * dp)
        lineTo(11.8f * dp, 18.6f * dp)
        lineTo(7.8f * dp, 12.2f * dp)
        lineTo(13.8f * dp, 12.2f * dp)
        close()
    }

    // Ease-in-out Interpolator: Slow start, graceful sweep, slow landing (Cubic Bézier 0.42, 0, 0.58, 1)
    private val easeInOutInterpolator = PathInterpolator(0.42f, 0.0f, 0.58f, 1.0f)

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        resetInactivityTimer()
    }

    // ------------------------------------------------------------------------
    // Smooth Ease-in-out Motion Flight
    // ------------------------------------------------------------------------

    fun moveTo(x: Float, y: Float, actionLabel: String = "MOVE") {
        glideTo(x, y, actionLabel)
    }

    fun glideTo(targetX: Float, targetY: Float, actionLabel: String, onArrived: (() -> Unit)? = null) {
        resetInactivityTimer()
        val startX = cursorX
        val startY = cursorY
        val dx = targetX - startX
        val dy = targetY - startY
        val dist = hypot(dx, dy)

        // If already at target, trigger arrival immediately
        if (dist < 10f * dp) {
            cursorX = targetX
            cursorY = targetY
            cursorRotation = 0f
            flightTrail.clear()
            postInvalidateOnAnimation()
            onArrived?.invoke()
            return
        }

        startFlightPoint = PointF(startX, startY)
        targetFlightPoint = PointF(targetX, targetY)

        val midX = (startX + targetX) / 2f
        val midY = (startY + targetY) / 2f

        // Natural curved arc perpendicular to flight vector
        val curvature = if (dx >= 0) 0.14f else -0.14f
        controlFlightPoint = PointF(midX - dy * curvature, midY + dx * curvature)

        // Motion Edit cinematic duration: 180ms for nearby clicks, ~280ms for cross-screen glides
        // Slow in start, smooth flight, slow when ending
        val durationMs = (dist * 0.10f + 160f).coerceIn(180f, 290f).toLong()

        // Dynamic subtle banking angle
        val bankMaxAngle = if (dx >= 0) 10f else -10f

        activeMoveAnimator?.cancel()
        trailFadeAnimator?.cancel()

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = easeInOutInterpolator // Slow start, accelerate, slow landing
            addUpdateListener { va ->
                val t = va.animatedFraction
                val oneMinusT = 1f - t

                // Quadratic Bézier: B(t) = (1-t)^2 * P0 + 2(1-t)t * P1 + t^2 * P2
                val currentX = (oneMinusT * oneMinusT * startFlightPoint.x) +
                        (2f * oneMinusT * t * controlFlightPoint.x) +
                        (t * t * targetFlightPoint.x)

                val currentY = (oneMinusT * oneMinusT * startFlightPoint.y) +
                        (2f * oneMinusT * t * controlFlightPoint.y) +
                        (t * t * targetFlightPoint.y)

                cursorX = currentX
                cursorY = currentY

                // Flight banking: tilts organically during flight, eases out on arrival
                cursorRotation = (kotlin.math.sin(t * Math.PI) * bankMaxAngle).toFloat()

                // Append trail points
                flightTrail.addLast(TrailNode(cursorX, cursorY, System.currentTimeMillis()))
                if (flightTrail.size > maxTrailPoints) {
                    flightTrail.removeFirst()
                }

                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cursorX = targetX
                    cursorY = targetY
                    cursorRotation = 0f

                    // Smooth fade out of trail instead of instant disappearance
                    trailFadeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                        duration = 180
                        addUpdateListener {
                            postInvalidateOnAnimation()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(a: Animator) {
                                flightTrail.clear()
                                postInvalidateOnAnimation()
                            }
                        })
                    }
                    trailFadeAnimator?.start()

                    onArrived?.invoke()
                }
            })
        }
        activeMoveAnimator = anim
        anim.start()
    }

    // ------------------------------------------------------------------------
    // Minimalist Tap Feedback (Subtle squash & micro-pulse ripple)
    // ------------------------------------------------------------------------

    fun triggerTap(x: Float, y: Float, actionLabel: String = "TAP", onTouchReady: (() -> Unit)? = null) {
        glideTo(x, y, actionLabel) {
            onTouchReady?.invoke()
            currentMode = CursorMode.CLICKED

            // 1. Subtle tactile Squash Bounce (1.0 -> 0.84 -> 1.08 -> 1.0 in 150ms)
            val squashAnim = ValueAnimator.ofFloat(1.0f, 0.84f, 1.08f, 1.0f).apply {
                duration = 150
                interpolator = easeInOutInterpolator
                addUpdateListener { va ->
                    cursorScale = va.animatedValue as Float
                    postInvalidateOnAnimation()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        currentMode = CursorMode.DEFAULT
                        cursorScale = 1.0f
                        postInvalidateOnAnimation()
                    }
                })
            }

            // 2. Minimalist Expanding Ripple (Expands smoothly from 3dp to 26dp in 180ms)
            tapRippleRadius = 3f * dp
            tapRippleAlpha = 0.90f
            val rippleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                interpolator = easeInOutInterpolator
                addUpdateListener { va ->
                    val f = va.animatedFraction
                    tapRippleRadius = (3f * dp) + (tapRippleMaxRadius - (3f * dp)) * f
                    tapRippleAlpha = 0.90f * (1.0f - f)
                    postInvalidateOnAnimation()
                }
            }

            squashAnim.start()
            rippleAnim.start()
        }
    }

    fun triggerDoubleTap(x: Float, y: Float, onTouchReady: (() -> Unit)? = null) {
        triggerTap(x, y, "DOUBLE TAP") {
            onTouchReady?.invoke()
            postDelayed({
                triggerTap(x, y, "DOUBLE TAP")
            }, 110)
        }
    }

    fun triggerLongPress(x: Float, y: Float, durationMs: Long = 500, onTouchReady: (() -> Unit)? = null) {
        glideTo(x, y, "LONG PRESS") {
            onTouchReady?.invoke()
            currentMode = CursorMode.LONG_PRESS
            cursorScale = 0.90f

            val chargeAnim = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = durationMs
                interpolator = easeInOutInterpolator
                addUpdateListener { va ->
                    chargeAngle = va.animatedValue as Float
                    postInvalidateOnAnimation()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        currentMode = CursorMode.DEFAULT
                        cursorScale = 1.0f
                        chargeAngle = 0f
                        triggerTap(x, y, "RELEASE")
                    }
                })
            }
            chargeAnim.start()
        }
    }

    fun triggerSwipe(x1: Float, y1: Float, x2: Float, y2: Float, actionLabel: String = "SWIPE", onSwipeReady: (() -> Unit)? = null) {
        glideTo(x1, y1, actionLabel) {
            onSwipeReady?.invoke()
            currentMode = CursorMode.DRAGGING
            glideTo(x2, y2, actionLabel) {
                currentMode = CursorMode.DEFAULT
                triggerTap(x2, y2, "SWIPE END")
            }
        }
    }

    fun triggerMultiTouch(points: List<PointF>, actionLabel: String, onTouchReady: (() -> Unit)? = null) {
        if (points.isNotEmpty()) {
            val cx = points.map { it.x }.average().toFloat()
            val cy = points.map { it.y }.average().toFloat()
            glideTo(cx, cy, actionLabel) {
                triggerTap(cx, cy, actionLabel, onTouchReady)
            }
        } else {
            onTouchReady?.invoke()
        }
    }

    fun triggerType(text: String) {
        resetInactivityTimer()
        currentMode = CursorMode.TYPING
        postDelayed({
            currentMode = CursorMode.DEFAULT
            postInvalidateOnAnimation()
        }, 300)
    }

    // ------------------------------------------------------------------------
    // Drawing (60-120 FPS High-Speed Clean Renderer)
    // ------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // If completely faded out and no active trail or ripple, skip rendering to save CPU/GPU cycles
        if (cursorAlpha <= 0f && flightTrail.isEmpty() && tapRippleAlpha <= 0.01f) {
            return
        }

        // 1. Dynamic Motion Trail (Visible tapered ribbon following the flight)
        if (flightTrail.size >= 2) {
            val pts = flightTrail.toList()
            val trailCount = pts.size
            val now = System.currentTimeMillis()

            for (i in 0 until trailCount - 1) {
                val n1 = pts[i]
                val n2 = pts[i + 1]
                val progress = (i + 1).toFloat() / trailCount // 0 at tail, 1 near cursor head

                // Width tapers from tail to cursor
                val strokeW = (0.8f + progress * 3.2f) * dp
                
                // Age decay: points older than 250ms fade away
                val age = (now - n2.timestamp).coerceAtLeast(0L)
                val ageAlpha = (1.0f - (age / 250f)).coerceIn(0f, 1f)
                val alpha = (progress * ageAlpha * 210 * cursorAlpha).toInt().coerceIn(0, 255)

                if (alpha > 4) {
                    motionTrailPaint.strokeWidth = strokeW
                    motionTrailPaint.alpha = alpha
                    canvas.drawLine(n1.x, n1.y, n2.x, n2.y, motionTrailPaint)
                }
            }
        }

        // 2. Clean Tap Ripple Pulse (Discreet Feedback)
        if (tapRippleAlpha > 0.01f) {
            tapRipplePaint.alpha = (tapRippleAlpha * 255 * cursorAlpha).toInt()
            canvas.drawCircle(cursorX, cursorY, tapRippleRadius, tapRipplePaint)
        }

        // 3. Long Press Charge Ring
        if (currentMode == CursorMode.LONG_PRESS && chargeAngle > 0f) {
            val arcR = 16f * dp
            val oval = RectF(cursorX - arcR, cursorY - arcR, cursorX + arcR, cursorY + arcR)
            chargeArcPaint.alpha = (255 * cursorAlpha).toInt()
            canvas.drawArc(oval, -90f, chargeAngle, false, chargeArcPaint)
        }

        // 4. Draw Small Studio Cursor Pointer (faded out smoothly with cursorAlpha)
        if (cursorAlpha > 0f) {
            canvas.save()
            canvas.translate(cursorX, cursorY)
            canvas.scale(cursorScale, cursorScale)
            canvas.rotate(cursorRotation)

            // Modulate paints with cursorAlpha
            arrowFillPaint.alpha = (255 * cursorAlpha).toInt()
            arrowStrokePaint.alpha = (255 * cursorAlpha).toInt()

            drawStudioCursor(canvas)

            canvas.restore()
        }
    }

    private fun drawStudioCursor(canvas: Canvas) {
        when (currentMode) {
            CursorMode.DEFAULT, CursorMode.CLICKED, CursorMode.LONG_PRESS, CursorMode.DRAGGING -> {
                // Sleek compact studio arrow
                canvas.drawPath(arrowPath, arrowFillPaint)
                canvas.drawPath(arrowPath, arrowStrokePaint)
            }

            CursorMode.TYPING -> {
                // Precision text I-beam
                val iBeamPaint = Paint(arrowStrokePaint).apply { strokeWidth = 2.2f * dp }
                val halfH = 11f * dp
                val capW = 5f * dp
                canvas.drawLine(0f, -halfH, 0f, halfH, iBeamPaint)
                canvas.drawLine(-capW, -halfH, capW, -halfH, iBeamPaint)
                canvas.drawLine(-capW, halfH, capW, halfH, iBeamPaint)
            }
        }
    }
}
