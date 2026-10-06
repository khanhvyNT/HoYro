package com.example

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * OverlayService manages the floating subtitle TextView HUD using WindowManager.
 * Displays real-time detection status at the bottom center of the screen.
 *
 * Requirements:
 * - "STATUS: RED" (Text color: Red) when RED enemy is detected
 * - "STATUS: GREEN" (Text color: Green) when GREEN safe is detected
 * - "STATUS: SCANNING..." (Text color: White) when OTHER is detected
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var statusTextView: TextView? = null
    private var statusDot: View? = null

    private val serviceJob = Job()
    private val mainScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var layoutParams: WindowManager.LayoutParams? = null

    companion object {
        const val ACTION_START = "com.example.OverlayService.ACTION_START"
        const val ACTION_STOP = "com.example.OverlayService.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createFloatingOverlay()
        observeDetectionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingOverlay() {
        if (overlayView != null) return

        val wm = windowManager ?: return

        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        fun dp(dp: Float): Int = (dp * density + 0.5f).toInt()

        // Root container: sleek translucent HUD pill with only the status
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))

            // Rounded dark background with glow outline
            background = GradientDrawable().apply {
                setColor(0xEE0D1117.toInt()) // Deep translucent slate
                cornerRadius = dp(24f).toFloat()
                setStroke(dp(1.5f), 0x55FFFFFF)
            }
        }

        // Circular glowing indicator dot
        val dot = View(this).apply {
            val size = dp(12f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(10f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }
        statusDot = dot
        container.addView(dot)

        // Single clean status text: STATUS: RED / GREEN / SCANNING...
        val textView = TextView(this).apply {
            text = "STATUS: SCANNING..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        statusTextView = textView
        container.addView(textView)

        // WindowManager Layout Params (TYPE_APPLICATION_OVERLAY)
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Use TOP or START for absolute screen coordinate positioning everywhere
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Position near bottom center by default, but freely movable anywhere
            x = (screenWidth / 2) - dp(95f)
            y = screenHeight - dp(110f)
        }
        layoutParams = params

        // Free 2D Drag handling: Move anywhere on the screen
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try {
                        wm.updateViewLayout(container, params)
                    } catch (e: Exception) {
                        // Ignored if view detached
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(container, params)
            overlayView = container
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun observeDetectionState() {
        mainScope.launch {
            DetectionState.metrics.collectLatest { metrics ->
                updateOverlayContent(metrics)
            }
        }
    }

    private fun updateOverlayContent(metrics: DetectionMetrics) {
        val tv = statusTextView ?: return
        val dot = statusDot ?: return

        when (metrics.result) {
            DetectionResult.RED -> {
                tv.text = "STATUS: RED"
                tv.setTextColor(Color.RED)
                (dot.background as? GradientDrawable)?.setColor(Color.RED)
                (overlayView?.background as? GradientDrawable)?.setStroke(
                    (resources.displayMetrics.density * 2).toInt(),
                    0xAAFF3B30.toInt()
                )
            }
            DetectionResult.GREEN -> {
                tv.text = "STATUS: GREEN"
                tv.setTextColor(Color.GREEN)
                (dot.background as? GradientDrawable)?.setColor(Color.GREEN)
                (overlayView?.background as? GradientDrawable)?.setStroke(
                    (resources.displayMetrics.density * 2).toInt(),
                    0xAA34C759.toInt()
                )
            }
            DetectionResult.SCANNING -> {
                tv.text = "STATUS: SCANNING..."
                tv.setTextColor(Color.WHITE)
                (dot.background as? GradientDrawable)?.setColor(0xFFB0BEC5.toInt())
                (overlayView?.background as? GradientDrawable)?.setStroke(
                    (resources.displayMetrics.density * 1.5f).toInt(),
                    0x55FFFFFF
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        overlayView = null
        statusTextView = null
        statusDot = null
    }
}
