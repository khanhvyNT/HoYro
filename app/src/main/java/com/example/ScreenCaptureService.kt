package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service for in-memory screen capture using MediaProjection and ImageReader.
 *
 * Targets:
 * - Device: OPPO CPH2631
 * - Resolution: Landscape (1604 x 720 pixels)
 * - Crosshair target coordinates: X = 801, Y = 359
 * - Region of Interest (ROI): 20x20 pixels centered at (801, 359)
 * - Delay: ~30ms loop interval
 *
 * Direct ByteBuffer processing: Image is closed immediately after processing each frame.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var captureLoopJob: Job? = null
    private val neuralReflex: ArtificialNeuralReflex get() = DetectionState.neuralReflex

    companion object {
        const val CHANNEL_ID = "screen_capture_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.ScreenCaptureService.ACTION_START"
        const val ACTION_STOP = "com.example.ScreenCaptureService.ACTION_STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopScreenCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode != 0 && resultData != null) {
                startForegroundWithNotification()
                initMediaProjection(resultCode, resultData)
            } else {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Color Detection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors crosshair coordinates on screen for color detection"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Color Detector Active")
            .setContentText("Target: (801, 359) | OPPO CPH2631")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpManager == null) {
            stopSelf()
            return
        }

        try {
            val projection = mpManager.getMediaProjection(resultCode, data)
            if (projection == null) {
                stopSelf()
                return
            }
            mediaProjection = projection

            // In Android 14+, callback MUST be registered before createVirtualDisplay
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    stopScreenCapture()
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            setupVirtualDisplay(projection)
            DetectionState.setServiceRunning(true)
            startCaptureLoop()

            // Also ensure OverlayService is started
            OverlayService.start(this)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun setupVirtualDisplay(projection: MediaProjection) {
        val downscale = DetectionState.captureDownscaleFactor.coerceIn(1, 4)
        val width = DetectionState.TARGET_WIDTH / downscale    // 802 when downscale=2
        val height = DetectionState.TARGET_HEIGHT / downscale  // 360 when downscale=2
        val dpi = (resources.displayMetrics.densityDpi / downscale).coerceAtLeast(120)

        // In-memory ImageReader with 2 buffers to prevent starvation
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenColorCapture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
    }

    private fun isRedColor(r: Int, g: Int, b: Int): Boolean {
        val maxOther = maxOf(g, b)
        // Adaptive red dominance:
        // 1. High contrast red (R significantly exceeds G and B)
        // 2. Or classic strong red
        return (r >= 135 && r > g * 1.35f && r > b * 1.35f && (r - maxOther) >= 28) ||
               (r >= 175 && g <= 110 && b <= 110)
    }

    private fun isGreenColor(r: Int, g: Int, b: Int): Boolean {
        val maxOther = maxOf(r, b)
        // Adaptive green dominance (strictly checking B prevents mistaking cyan/sky/water for green):
        return (g >= 130 && g > r * 1.35f && g > b * 1.25f && (g - maxOther) >= 28) ||
               (g >= 160 && r <= 110 && b <= 130)
    }

    /**
     * Nhận diện sắc tố "màu da nhợt, gần trắng" của vi khuẩn hình que dọc:
     * - Độ sáng cao (luminance >= 130)
     * - Tương quan sắc tố: R, G, B đều sáng, hơi thiên ấm/da nhợt (R >= G >= B hoặc cân bằng)
     * - Không bị bão hòa đơn sắc quá mạnh như cỏ xanh, nước biển, hay đỏ chói.
     */
    private fun isPaleNearWhiteColor(r: Int, g: Int, b: Int): Boolean {
        if (r < 135 || g < 120 || b < 105) return false
        val lum = (r * 299 + g * 587 + b * 114) / 1000
        if (lum < 130) return false

        // Loại bỏ màu xanh lá rực (cỏ/cây)
        if (g > r + 22 || g > b + 32) return false
        // Loại bỏ màu xanh lam rực (nước/bầu trời)
        if (b > r + 25 || b > g + 25) return false
        // Loại bỏ màu đỏ chói
        if (r > g + 60 && r > b + 60) return false

        // Kiểm tra độ bão hòa thấp đến vừa phải (màu nhợt / gần trắng)
        val maxC = maxOf(r, maxOf(g, b))
        val minC = minOf(r, minOf(g, b))
        val diff = maxC - minC
        return diff <= 65
    }

    private data class BacteriumDetectionResult(
        val found: Boolean,
        val bufferX: Int,
        val bufferY: Int,
        val rodWidth: Int,
        val rodHeight: Int,
        val confidence: Float
    )

    /**
     * Thuật toán phân tích hình thái học không gian phát hiện "vi khuẩn hình que dọc":
     * - Quét dải pixel màu da nhợt theo chiều dọc (vertical rod morphology)
     * - Tỷ lệ chiều cao / bề ngang (Aspect Ratio) >= 1.35
     * - Lọc nhiễu sắc tố hỗn tạp xung quanh
     */
    private fun detectVerticalRodBacterium(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        imgWidth: Int,
        imgHeight: Int,
        retinaX: Int,
        retinaY: Int
    ): BacteriumDetectionResult {
        val searchRadiusX = 85
        val searchRadiusY = 45

        val startX = (retinaX - searchRadiusX).coerceIn(0, imgWidth - 1)
        val endX = (retinaX + searchRadiusX).coerceIn(0, imgWidth - 1)
        val startY = (retinaY - searchRadiusY).coerceIn(0, imgHeight - 1)
        val endY = (retinaY + searchRadiusY).coerceIn(0, imgHeight - 1)

        val capacity = buffer.capacity()

        var bestX = 0
        var bestY = 0
        var bestHeight = 0
        var bestWidth = 0
        var bestConfidence = 0f

        var currentRodSpanStart = -1
        var currentRodTotalHeight = 0
        var currentRodColumnSpan = 0
        var accumulatedX = 0L
        var accumulatedY = 0L
        var totalRodPixels = 0

        // Quét theo bước nhảy 2 pixel để duy trì tốc độ ~125Hz
        for (x in startX..endX step 2) {
            var colMaxRun = 0
            var colCurrentRun = 0
            var colRunCenterY = 0

            for (y in startY..endY step 2) {
                val offset = y * rowStride + x * pixelStride
                if (offset + 2 < capacity) {
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF

                    if (isPaleNearWhiteColor(r, g, b)) {
                        colCurrentRun++
                        if (colCurrentRun > colMaxRun) {
                            colMaxRun = colCurrentRun
                            colRunCenterY = y - (colCurrentRun / 2) * 2
                        }
                    } else {
                        colCurrentRun = 0
                    }
                }
            }

            // Đoạn que dọc liên tiếp >= 4 bước (tương đương >= 8px buffer)
            if (colMaxRun >= 4) {
                if (currentRodSpanStart == -1) {
                    currentRodSpanStart = x
                }
                currentRodColumnSpan++
                currentRodTotalHeight += colMaxRun * 2
                accumulatedX += x * colMaxRun
                accumulatedY += colRunCenterY * colMaxRun
                totalRodPixels += colMaxRun
            } else {
                if (currentRodColumnSpan in 1..8 && totalRodPixels >= 6) {
                    val avgHeight = currentRodTotalHeight / currentRodColumnSpan
                    val width = (currentRodColumnSpan * 2).coerceAtLeast(2)
                    val aspectRatio = avgHeight.toFloat() / width.toFloat()

                    if (aspectRatio >= 1.35f) {
                        val centerX = (accumulatedX / totalRodPixels).toInt()
                        val centerY = (accumulatedY / totalRodPixels).toInt()
                        val conf = (aspectRatio * 1.5f + (totalRodPixels / 10f)).coerceAtMost(10f)

                        if (conf > bestConfidence) {
                            bestConfidence = conf
                            bestX = centerX
                            bestY = centerY
                            bestWidth = width
                            bestHeight = avgHeight
                        }
                    }
                }
                currentRodSpanStart = -1
                currentRodColumnSpan = 0
                currentRodTotalHeight = 0
                accumulatedX = 0L
                accumulatedY = 0L
                totalRodPixels = 0
            }
        }

        return if (bestConfidence > 1.8f) {
            BacteriumDetectionResult(
                found = true,
                bufferX = bestX,
                bufferY = bestY,
                rodWidth = bestWidth,
                rodHeight = bestHeight,
                confidence = bestConfidence
            )
        } else {
            BacteriumDetectionResult(false, 0, 0, 0, 0, 0f)
        }
    }

    private fun startCaptureLoop() {
        captureLoopJob?.cancel()
        captureLoopJob = serviceScope.launch {
            val downscale = DetectionState.captureDownscaleFactor.coerceIn(1, 4)
            val fullTargetCenterX = DetectionState.TARGET_X // Full screen coordinates (801)
            val fullTargetCenterY = DetectionState.TARGET_Y // Full screen coordinates (359)

            var lastFpsTimestamp = System.currentTimeMillis()
            var framesCounted = 0
            var currentFps = 0
            var totalProcessedFrames = 0L
            var totalBacteriumSwipes = 0L

            var lastDetectionResult = DetectionResult.SCANNING

            while (isActive) {
                val loopStartTime = System.currentTimeMillis()
                val reader = imageReader

                if (reader != null) {
                    var image: android.media.Image? = null
                    try {
                        image = reader.acquireLatestImage()
                        if (image != null) {
                            val planes = image.planes
                            if (planes.isNotEmpty()) {
                                val plane = planes[0]
                                val buffer = plane.buffer
                                val pixelStride = plane.pixelStride
                                val rowStride = plane.rowStride
                                val imgWidth = image.width
                                val imgHeight = image.height

                                // Downscaled buffer target coordinates
                                val targetCenterX = (fullTargetCenterX / downscale).coerceIn(0, imgWidth - 1)
                                val targetCenterY = (fullTargetCenterY / downscale).coerceIn(0, imgHeight - 1)

                                val radius = (DetectionState.roiRadius / downscale).coerceAtLeast(2)
                                val innerRadius = (DetectionState.innerRadius / downscale).coerceAtLeast(1)
                                val centerPriority = DetectionState.centerPriorityEnabled
                                val sensitivity = DetectionState.sensitivityThreshold

                                val startX = (targetCenterX - radius).coerceIn(0, imgWidth - 1)
                                val endX = (targetCenterX + radius - 1).coerceIn(0, imgWidth - 1)
                                val startY = (targetCenterY - radius).coerceIn(0, imgHeight - 1)
                                val endY = (targetCenterY + radius - 1).coerceIn(0, imgHeight - 1)

                                var redPixels = 0
                                var greenPixels = 0
                                var otherPixels = 0
                                var innerRedPixels = 0
                                var innerGreenPixels = 0
                                var redScore = 0f
                                var greenScore = 0f

                                // Exact center pixel sampling in buffer
                                val centerOffset = targetCenterY * rowStride + targetCenterX * pixelStride
                                var centerR = 0
                                var centerG = 0
                                var centerB = 0
                                if (centerOffset + 2 < buffer.capacity()) {
                                    centerR = buffer.get(centerOffset).toInt() and 0xFF
                                    centerG = buffer.get(centerOffset + 1).toInt() and 0xFF
                                    centerB = buffer.get(centerOffset + 2).toInt() and 0xFF
                                }
                                val centerIsRed = isRedColor(centerR, centerG, centerB)
                                val centerIsGreen = isGreenColor(centerR, centerG, centerB)

                                // Fix 3: Eliminate sqrt() completely using squared distance comparison
                                val innerRadiusSq = (innerRadius * innerRadius).toFloat()
                                val radiusSq = (radius * radius).coerceAtLeast(1).toFloat()

                                // Fix 4: Stride 2 (Step 2) -> Scans only ~100 pixels instead of 400!
                                val strideStep = DetectionState.roiScanStride.coerceIn(1, 4)

                                // Iterate pixels in ROI with spatial distance weighting (Zero sqrt calls)
                                for (y in startY..endY step strideStep) {
                                    val rowOffset = y * rowStride
                                    val dy = (y - targetCenterY).toFloat()
                                    val dySq = dy * dy

                                    for (x in startX..endX step strideStep) {
                                        val dx = (x - targetCenterX).toFloat()
                                        val distSq = dx * dx + dySq

                                        // Skip points outside circular ROI
                                        if (distSq > radiusSq) continue

                                        val pixelIndex = rowOffset + x * pixelStride
                                        if (pixelIndex + 2 < buffer.capacity()) {
                                            val r = buffer.get(pixelIndex).toInt() and 0xFF
                                            val g = buffer.get(pixelIndex + 1).toInt() and 0xFF
                                            val b = buffer.get(pixelIndex + 2).toInt() and 0xFF

                                            val isInner = distSq <= innerRadiusSq

                                            // Center distance weight: 5.0x at exact center down to 1.0x at perimeter (NO SQRT)
                                            val normalizedDistSq = (distSq / radiusSq).coerceIn(0f, 1f)
                                            val weight = 1.0f + 4.0f * (1.0f - normalizedDistSq)

                                            val redMatched = isRedColor(r, g, b)
                                            val greenMatched = isGreenColor(r, g, b)

                                            if (redMatched) {
                                                redPixels++
                                                redScore += weight
                                                if (isInner) innerRedPixels++
                                            } else if (greenMatched) {
                                                greenPixels++
                                                greenScore += weight
                                                if (isInner) innerGreenPixels++
                                            } else {
                                                otherPixels++
                                            }
                                        }
                                    }
                                }

                                // Smart Hierarchical Classification:
                                var triggerReason = "Scanning"
                                val result = if (centerPriority && (centerIsRed || innerRedPixels >= 2)) {
                                    triggerReason = if (centerIsRed) "Center Pixel Red" else "Core Red ($innerRedPixels px)"
                                    DetectionResult.RED
                                } else if (centerPriority && (centerIsGreen || innerGreenPixels >= 2)) {
                                    triggerReason = if (centerIsGreen) "Center Pixel Green" else "Core Green ($innerGreenPixels px)"
                                    DetectionResult.GREEN
                                } else {
                                    val minScoreThreshold = 10f * sensitivity
                                    when {
                                        redScore >= minScoreThreshold && redScore > greenScore * 1.2f -> {
                                            triggerReason = "Weighted Red (Score: %.1f)".format(redScore)
                                            DetectionResult.RED
                                        }
                                        greenScore >= minScoreThreshold && greenScore > redScore * 1.2f -> {
                                            triggerReason = "Weighted Green (Score: %.1f)".format(greenScore)
                                            DetectionResult.GREEN
                                        }
                                        else -> {
                                            triggerReason = "No Target"
                                            DetectionResult.SCANNING
                                        }
                                    }
                                }

                                lastDetectionResult = result

                                // Artificial Neural Reflex layer (Hospital patient monitor edge-trigger):
                                val detectionTimestamp = System.currentTimeMillis()
                                val reflexDecision = neuralReflex.process(result, detectionTimestamp)

                                if (reflexDecision == ReflexDecision.REFLEX_TAP && DetectionState.isMotorReflexEnabled) {
                                    NeuralAccessibilityService.dispatchTap(
                                        x = DetectionState.TAP_X,
                                        y = DetectionState.TAP_Y,
                                        detectionTimestamp = detectionTimestamp,
                                        onLatencyMeasured = { latencyMs ->
                                            neuralReflex.recordLatency(latencyMs)
                                        }
                                    )
                                }

                                // Cơ chế phản xạ vận động thần kinh tự động săn bắt vi khuẩn hình que dọc:
                                if (DetectionState.isBacteriumTrackingEnabled && DetectionState.isMotorReflexEnabled) {
                                    val bacResult = detectVerticalRodBacterium(
                                        buffer = buffer,
                                        rowStride = rowStride,
                                        pixelStride = pixelStride,
                                        imgWidth = imgWidth,
                                        imgHeight = imgHeight,
                                        retinaX = targetCenterX,
                                        retinaY = targetCenterY
                                    )

                                    if (bacResult.found) {
                                        val bacteriumRealX = (bacResult.bufferX * downscale).coerceIn(0, DetectionState.TARGET_WIDTH)
                                        val bacteriumRealY = (bacResult.bufferY * downscale).coerceIn(0, DetectionState.TARGET_HEIGHT)

                                        val deltaX = bacteriumRealX - fullTargetCenterX
                                        val deltaY = bacteriumRealY - fullTargetCenterY

                                        val motorX = DetectionState.PERIPHERAL_MOTOR_X
                                        val motorY = DetectionState.PERIPHERAL_MOTOR_Y

                                        val deadZonePx = 8
                                        val isLocked = kotlin.math.abs(deltaX) <= deadZonePx

                                        val swipeDirection = when {
                                            isLocked -> "🎯 GHIM CHẶT VÀO TÂM"
                                            deltaX > 0 -> "KÉO SANG PHẢI (→)"
                                            else -> "KÉO SANG TRÁI (←)"
                                        }

                                        if (!isLocked) {
                                            val sensitivity = DetectionState.trackingSensitivity
                                            val swipeDist = (kotlin.math.abs(deltaX) * sensitivity * 0.85f).coerceIn(24f, 175f)
                                            val swipeDx = if (deltaX > 0) swipeDist else -swipeDist
                                            val swipeDy = (deltaY * sensitivity * 0.35f).coerceIn(-40f, 40f)

                                            val endX = (motorX + swipeDx).coerceIn(40f, DetectionState.TARGET_WIDTH.toFloat() - 40f)
                                            val endY = (motorY + swipeDy).coerceIn(40f, DetectionState.TARGET_HEIGHT.toFloat() - 40f)

                                            val dispatched = NeuralAccessibilityService.dispatchSwipe(
                                                startX = motorX,
                                                startY = motorY,
                                                endX = endX,
                                                endY = endY,
                                                durationMs = 45L,
                                                detectionTimestamp = detectionTimestamp
                                            )
                                            if (dispatched) {
                                                totalBacteriumSwipes++
                                            }
                                        }

                                        DetectionState.updateBacteriumStatus(
                                            BacteriumTrackingStatus(
                                                isBacteriumFound = true,
                                                bacteriumX = bacteriumRealX,
                                                bacteriumY = bacteriumRealY,
                                                deltaX = deltaX,
                                                deltaY = deltaY,
                                                rodConfidence = bacResult.confidence,
                                                rodHeight = bacResult.rodHeight * downscale,
                                                rodWidth = bacResult.rodWidth * downscale,
                                                isTrackingActive = true,
                                                lastSwipeDirection = swipeDirection,
                                                swipeCount = totalBacteriumSwipes,
                                                lastSwipeTimestamp = System.currentTimeMillis(),
                                                statusMessage = if (isLocked) "Đã ghim chặt tâm vào vi khuẩn (ΔX: $deltaX px)" else "Đang kéo chi cơ học $swipeDirection (Lệch $deltaX px)"
                                            )
                                        )
                                    } else {
                                        DetectionState.updateBacteriumStatus(
                                            DetectionState.bacteriumStatusFlow.value.copy(
                                                isBacteriumFound = false,
                                                statusMessage = "Võng mạc đang quan sát vi khuẩn que dọc..."
                                            )
                                        )
                                    }
                                }

                                totalProcessedFrames++
                                framesCounted++
                                val now = System.currentTimeMillis()
                                if (now - lastFpsTimestamp >= 1000L) {
                                    currentFps = framesCounted
                                    framesCounted = 0
                                    lastFpsTimestamp = now
                                }

                                DetectionState.updateMetrics(
                                    DetectionMetrics(
                                        result = result,
                                        redPixels = redPixels,
                                        greenPixels = greenPixels,
                                        otherPixels = otherPixels,
                                        innerRedPixels = innerRedPixels,
                                        innerGreenPixels = innerGreenPixels,
                                        centerR = centerR,
                                        centerG = centerG,
                                        centerB = centerB,
                                        redScore = redScore,
                                        greenScore = greenScore,
                                        fps = currentFps,
                                        frameCount = totalProcessedFrames,
                                        lastUpdateTimeMs = now,
                                        triggerReason = triggerReason
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // Suppress frame processing hiccups
                    } finally {
                        // CRITICAL: Close image immediately to return buffer to pool
                        image?.close()
                    }
                }

                // Fix 2: Giảm Loop Delay
                // Khi đang ở trạng thái RED hoặc HOLDING: BỎ DELAY HOÀN TOÀN để phản xạ tức thì!
                // Khi ở trạng thái bình thường (SCANNING/GREEN): Giảm xuống 8ms (~125Hz sampling rate)
                val isRedState = (lastDetectionResult == DetectionResult.RED ||
                    neuralReflex.currentState == NeuralState.HOLDING ||
                    neuralReflex.currentState == NeuralState.STIMULATED)

                if (isRedState) {
                    // ZERO delay when RED is active to process consecutive frames at maximum throughput
                    kotlinx.coroutines.yield()
                } else {
                    val loopElapsed = System.currentTimeMillis() - loopStartTime
                    val targetDelay = DetectionState.loopDelayMs // 8ms default (~125Hz)
                    val delayTime = (targetDelay - loopElapsed).coerceAtLeast(0L)
                    if (delayTime > 0L) {
                        delay(delayTime)
                    } else {
                        kotlinx.coroutines.yield()
                    }
                }
            }
        }
    }

    private fun stopScreenCapture() {
        captureLoopJob?.cancel()
        captureLoopJob = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaProjection = null

        DetectionState.setServiceRunning(false)
        neuralReflex.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScreenCapture()
        serviceJob.cancel()
    }
}
