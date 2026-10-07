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

    /**
     * Nhận diện tâm màu cam - vàng cam (Orange / Yellow-Orange / Amber) như trong ảnh:
     * - Sắc tố: R rất cao (>= 150), G từ trung bình đến cao (>= 95), B thấp hoặc vừa (<= 140).
     * - Tỷ lệ G so với R cao (G >= 0.48 * R hoặc R - G <= 95) và G vượt trội so với B (G - B >= 18).
     * - Đây là màu vòng ngắm cam / vàng cam, HOÀN TOÀN KHÔNG PHẢI là màu đỏ mục tiêu!
     */
    private fun isOrangeOrYellowOrange(r: Int, g: Int, b: Int): Boolean {
        if (!DetectionState.rejectOrangeYellowFilterEnabled) return false
        if (r >= 150 && g >= 95 && b <= 140) {
            val rMinusG = r - g
            val gMinusB = g - b
            // Vàng cam / Cam: G nằm trong dải 0.48*R đến 0.95*R và G > B + 18
            if (rMinusG in 5..95 && gMinusB >= 18) {
                return true
            }
            if (g >= 115 && g >= r * 0.50f && gMinusB >= 22) {
                return true
            }
        }
        return false
    }

    /**
     * Nhận diện dải màu trắng xanh / cyan mờ như trong ảnh:
     * - Độ sáng cao (luminance >= 110)
     * - Sắc xanh lam (B) hoặc xanh lục (G) tiệm cận hoặc vượt trội so với R (B >= R - 16 hoặc (B+G)/2 >= R - 8)
     * - Thiếu sắc ấm đặc trưng của màu da người / đỏ mục tiêu (R - B < 22).
     */
    private fun isBluishWhiteOrCyan(r: Int, g: Int, b: Int): Boolean {
        if (!DetectionState.rejectBluishWhiteFilterEnabled) return false
        val lum = (r * 299 + g * 587 + b * 114) / 1000
        if (lum >= 110) {
            // Sắc xanh B trội hơn hoặc bám sát R (thiên xanh lam / trắng xanh)
            if (b >= r - 16) return true
            // Cyan / trắng xanh: G và B đều xấp xỉ hoặc cao hơn R
            if (g >= r - 8 && b >= 105) return true
            // Thiếu hẳn sắc ấm (R - B < 22 với B >= 105)
            if (r - b < 22 && b >= 105) return true
        }
        return false
    }

    private fun isRedColor(r: Int, g: Int, b: Int): Boolean {
        // Ngăn chặn phản xạ với tâm màu cam - vàng cam hoặc dải trắng xanh
        if (isOrangeOrYellowOrange(r, g, b)) return false
        if (isBluishWhiteOrCyan(r, g, b)) return false

        val maxOther = maxOf(g, b)
        // Chuẩn màu ĐỎ thực sự (Red Target):
        // 1. R phải áp đảo hoàn toàn G và B (tối thiểu 1.65x)
        // 2. G không được quá cao để tránh lọt màu cam (G <= 115 hoặc R - G >= 90)
        // 3. Hoặc màu đỏ thẫm/thuần khiết cổ điển (R >= 170, G <= 95, B <= 95)
        val strictRedDominance = (r >= 145 && r > g * 1.65f && r > b * 1.65f && (r - maxOther) >= 48 && (g <= 115 || (r - g) >= 90))
        val classicPureRed = (r >= 170 && g <= 95 && b <= 95)

        return strictRedDominance || classicPureRed
    }

    private fun isGreenColor(r: Int, g: Int, b: Int): Boolean {
        // Ngăn chặn phản xạ với dải màu trắng xanh hoặc màu cam
        if (isBluishWhiteOrCyan(r, g, b)) return false
        if (isOrangeOrYellowOrange(r, g, b)) return false

        val maxOther = maxOf(r, b)
        // Adaptive green dominance (strictly checking B prevents mistaking cyan/sky/water for green):
        return (g >= 130 && g > r * 1.35f && g > b * 1.25f && (g - maxOther) >= 28) ||
               (g >= 160 && r <= 110 && b <= 130)
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
                                    if (DetectionState.isMacroModeEnabled) {
                                        // Phản xạ kích hoạt chuỗi Macro nhiều điểm (Oppo Game Space / Xiaomi Game Turbo)
                                        NeuralAccessibilityService.dispatchMacro(
                                            profile = DetectionState.activeMacroProfile,
                                            detectionTimestamp = detectionTimestamp
                                        )
                                    } else {
                                        // Phản xạ đơn điểm Tap tiêu chuẩn
                                        NeuralAccessibilityService.dispatchTap(
                                            x = DetectionState.TAP_X,
                                            y = DetectionState.TAP_Y,
                                            detectionTimestamp = detectionTimestamp,
                                            onLatencyMeasured = { latencyMs ->
                                                neuralReflex.recordLatency(latencyMs)
                                            }
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
