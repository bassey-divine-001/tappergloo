package com.macro.gloowall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager

class MacroAccessibilityService : AccessibilityService() {
    companion object {
        private const val PREFERENCES = "macro_settings"
        private const val KEY_MACRO_ENABLED = "macro_enabled"
        private const val TAP_INTERVAL_MS = 65L

        @Volatile
        private var connectedService: MacroAccessibilityService? = null

        fun isMacroEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_MACRO_ENABLED, false)

        fun setMacroEnabled(context: android.content.Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MACRO_ENABLED, enabled)
                .apply()
            if (enabled) {
                connectedService?.showTarget()
            } else {
                connectedService?.removeTarget()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var targetView: View? = null
    private var targetParams: WindowManager.LayoutParams? = null
    private var pointerDown = false
    private var dragging = false
    private var holdTriggered = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    private var tapX = 0f
    private var tapY = 0f
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val holdAndRepeat = object : Runnable {
        override fun run() {
            if (!pointerDown || dragging) return
            holdTriggered = true
            dispatchTap(tapX, tapY)
            handler.postDelayed(this, TAP_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        connectedService = this
        if (isMacroEnabled(this)) showTarget()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopTouchLoop()
    }

    override fun onDestroy() {
        removeTarget()
        if (connectedService === this) connectedService = null
        super.onDestroy()
    }

    private fun showTarget() {
        if (targetView != null || !Settings.canDrawOverlays(this)) return

        val size = dp(58)
        val metrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - size) / 2
            y = (metrics.heightPixels - size) / 2
        }

        val view = TargetView().apply {
            alpha = 0.82f
            contentDescription = "Macro tap target. Drag to move; hold to repeat taps."
            setOnTouchListener { _, event -> handleTargetTouch(event) }
        }
        try {
            windowManager.addView(view, params)
            targetParams = params
            targetView = view
        } catch (_: SecurityException) {
            setMacroEnabled(this, false)
        } catch (_: WindowManager.BadTokenException) {
            setMacroEnabled(this, false)
        }
    }

    private fun removeTarget() {
        stopTouchLoop()
        val view = targetView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // The window may already have been removed by the system.
        }
        targetView = null
        targetParams = null
    }

    private fun handleTargetTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopTouchLoop()
                pointerDown = true
                dragging = false
                holdTriggered = false
                downRawX = event.rawX
                downRawY = event.rawY
                val params = targetParams ?: return true
                downWindowX = params.x
                downWindowY = params.y
                tapX = params.x + event.x
                tapY = params.y + event.y
                handler.postDelayed(holdAndRepeat, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pointerDown) return true
                val deltaX = event.rawX - downRawX
                val deltaY = event.rawY - downRawY
                val touchSlopSquared = touchSlop.toFloat() * touchSlop
                if (!dragging && deltaX * deltaX + deltaY * deltaY > touchSlopSquared) {
                    dragging = true
                    stopTouchLoop()
                }
                if (dragging) {
                    targetParams?.let { params ->
                        params.x = downWindowX + deltaX.toInt()
                        params.y = downWindowY + deltaY.toInt()
                        targetView?.let { windowManager.updateViewLayout(it, params) }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!pointerDown) return true
                pointerDown = false
                stopTouchLoop()
                if (!dragging && !holdTriggered) dispatchTap(tapX, tapY)
                dragging = false
                holdTriggered = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pointerDown = false
                dragging = false
                holdTriggered = false
                stopTouchLoop()
                return true
            }
        }
        return true
    }

    private fun stopTouchLoop() {
        handler.removeCallbacks(holdAndRepeat)
    }

    private fun dispatchTap(x: Float, y: Float) {
        if (!Settings.canDrawOverlays(this)) return
        val metrics = resources.displayMetrics
        val tapPath = Path().apply {
            moveTo(
                x.coerceIn(0f, (metrics.widthPixels - 1).toFloat()),
                y.coerceIn(0f, (metrics.heightPixels - 1).toFloat())
            )
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath, 0L, 1L))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class TargetView : View(this@MacroAccessibilityService) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(21, 39, 37)
            style = Paint.Style.FILL
        }
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(119, 244, 181)
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val centerX = width / 2f
            val centerY = height / 2f
            val radius = minOf(width, height) * 0.47f
            canvas.drawCircle(centerX, centerY, radius, fillPaint)
            canvas.drawCircle(centerX, centerY, radius, edgePaint)
            canvas.drawCircle(centerX, centerY, dp(7).toFloat(), centerPaint)
            canvas.drawLine(centerX - dp(15), centerY, centerX - dp(9), centerY, centerPaint)
            canvas.drawLine(centerX + dp(9), centerY, centerX + dp(15), centerY, centerPaint)
            canvas.drawLine(centerX, centerY - dp(15), centerX, centerY - dp(9), centerPaint)
            canvas.drawLine(centerX, centerY + dp(9), centerX, centerY + dp(15), centerPaint)
        }
    }
}
