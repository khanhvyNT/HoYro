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
    private var closeButton: TextView? = null

    // Draggable circular floating On/Off toggle button
    private var circularToggleView: View? = null
    private var circularToggleIcon: TextView? = null

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
        createCircularToggleButton()
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

        // Root container: sleek translucent HUD pill
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(8f), dp(10f), dp(8f))

            // Rounded dark background with glow outline
            background = GradientDrawable().apply {
                setColor(0xEE0D1117.toInt()) // Deep translucent slate
                cornerRadius = dp(24f).toFloat()
                setStroke(dp(1.5f), 0x55FFFFFF)
            }
        }

        // Circular glowing indicator dot
        val dot = View(this).apply {
            val size = dp(11f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(8f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }
        statusDot = dot
        dot.isClickable = false
        dot.isFocusable = false
        container.addView(dot)

        // Single clean status text: STATUS: RED / GREEN / SCANNING...
        val textView = TextView(this).apply {
            text = "STATUS: SCANNING..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setShadowLayer(5f, 0f, 2f, Color.BLACK)
            isClickable = false
            isFocusable = false
        }
        statusTextView = textView
        container.addView(textView)

        // Emergency Close Button: ✕ to stop detection & overlay immediately
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(0xDDFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val size = dp(24f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(10f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            isClickable = true
            isFocusable = true

            setOnClickListener {
                ScreenCaptureService.stop(this@OverlayService)
                OverlayService.stop(this@OverlayService)
                DetectionState.reset()
            }
        }
        closeButton = closeBtn
        container.addView(closeBtn)

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
            x = (screenWidth / 2) - dp(100f)
            y = screenHeight - dp(105f)
        }
        layoutParams = params

        // Free 2D Drag handling: Move anywhere on the screen while allowing close button click
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { _, event ->
            val touchX = event.x.toInt()
            val touchY = event.y.toInt()

            val closeRect = android.graphics.Rect()
            closeBtn.getHitRect(closeRect)

            if (event.action == MotionEvent.ACTION_DOWN && closeRect.contains(touchX, touchY)) {
                return@setOnTouchListener false
            }

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

    /**
     * Nút On/Off độc lập dạng ICON HÌNH TRÒN kích thước vừa phải (48dp).
     * Có thể kéo thả di chuyển tự do đến bất cứ vị trí nào trên toàn màn hình.
     * Chạm nhanh để Bật/Tắt phản xạ vận động thần kinh (Motor Reflex).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createCircularToggleButton() {
        if (circularToggleView != null) return
        val wm = windowManager ?: return

        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        fun dp(dp: Float): Int = (dp * density + 0.5f).toInt()

        val buttonSize = dp(48f) // Kích thước vừa phải chuẩn công thái học (48dp)

        val circularContainer = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(buttonSize, buttonSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE059669.toInt())
                setStroke(dp(2.5f), 0xFF34D399.toInt())
            }
            elevation = dp(8f).toFloat()
        }

        val icon = TextView(this).apply {
            text = "⚡"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        circularToggleIcon = icon
        circularContainer.addView(icon)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val toggleParams = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Vị trí mặc định: cạnh phải màn hình, tầm với ngón tay cái
            x = screenWidth - buttonSize - dp(18f)
            y = (screenHeight / 2) - (buttonSize / 2)
        }

        var initX = 0
        var initY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var isDrag = false

        circularContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = toggleParams.x
                    initY = toggleParams.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    isDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    if (dx * dx + dy * dy > dp(6f) * dp(6f)) {
                        isDrag = true
                    }
                    if (isDrag) {
                        toggleParams.x = initX + dx
                        toggleParams.y = initY + dy
                        try {
                            wm.updateViewLayout(circularContainer, toggleParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDrag) {
                        // Click event: Bật / Tắt phản xạ thần kinh vận động
                        DetectionState.toggleMotorReflex()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(circularContainer, toggleParams)
            circularToggleView = circularContainer
            updateCircularButtonUi(DetectionState.isMotorReflexEnabled)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateCircularButtonUi(isEnabled: Boolean) {
        val container = circularToggleView as? android.widget.FrameLayout ?: return
        val icon = circularToggleIcon ?: return
        val density = resources.displayMetrics.density
        fun dp(dp: Float): Int = (dp * density + 0.5f).toInt()

        if (isEnabled) {
            icon.text = "⚡"
            icon.setTextColor(Color.WHITE)
            container.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE059669.toInt()) // Vibrant Emerald Green
                setStroke(dp(2.5f), 0xFF34D399.toInt()) // Glowing ring
            }
        } else {
            icon.text = "⏸"
            icon.setTextColor(0xFFFF5252.toInt())
            container.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE1E293B.toInt()) // Dark Slate
                setStroke(dp(2f), 0xFFEF4444.toInt()) // Crimson ring
            }
        }
    }

    private fun observeDetectionState() {
        mainScope.launch {
            DetectionState.metrics.collectLatest { metrics ->
                updateOverlayContent(metrics, DetectionState.neuralStatus.value)
            }
        }

        mainScope.launch {
            DetectionState.neuralStatus.collectLatest { neural ->
                updateOverlayContent(DetectionState.metrics.value, neural)
            }
        }

        mainScope.launch {
            DetectionState.isMotorReflexEnabledFlow.collectLatest { isEnabled ->
                updateCircularButtonUi(isEnabled)
            }
        }

        mainScope.launch {
            DetectionState.bacteriumStatusFlow.collectLatest { bac ->
                if (bac.isBacteriumFound) {
                    val tv = statusTextView ?: return@collectLatest
                    tv.text = "QUE DỌC: ${bac.lastSwipeDirection} (ΔX:${bac.deltaX})"
                    tv.setTextColor(0xFFFBBF24.toInt())
                }
            }
        }
    }

    private fun updateOverlayContent(metrics: DetectionMetrics, neural: NeuralStatusData) {
        val tv = statusTextView ?: return
        val dot = statusDot ?: return

        when (metrics.result) {
            DetectionResult.RED -> {
                if (neural.currentState == NeuralState.HOLDING) {
                    tv.text = String.format("STATUS: HOLDING RED (%dms)", neural.holdElapsedMs)
                    tv.setTextColor(0xFFFF9100.toInt()) // Amber-orange while holding
                    (dot.background as? GradientDrawable)?.setColor(0xFFFF9100.toInt())
                    (overlayView?.background as? GradientDrawable)?.setStroke(
                        (resources.displayMetrics.density * 2).toInt(),
                        0xAAFF9100.toInt()
                    )
                } else {
                    tv.text = "STATUS: RED"
                    tv.setTextColor(Color.RED)
                    (dot.background as? GradientDrawable)?.setColor(Color.RED)
                    (overlayView?.background as? GradientDrawable)?.setStroke(
                        (resources.displayMetrics.density * 2).toInt(),
                        0xAAFF3B30.toInt()
                    )
                }
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
        circularToggleView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        overlayView = null
        circularToggleView = null
        circularToggleIcon = null
        statusTextView = null
        statusDot = null
        closeButton = null
    }
}
