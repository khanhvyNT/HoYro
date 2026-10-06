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
        val width = DetectionState.TARGET_WIDTH    // 1604
        val height = DetectionState.TARGET_HEIGHT  // 720
        val dpi = resources.displayMetrics.densityDpi

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

    private var prevStripLuminance: FloatArray? = null
    private var isSwipeGestureInProgress = false
    private var lastSwipeTime = 0L
    private var isGripHoldActive = false

    /**
     * Scans horizontal strip (X = 0..imgWidth, Y = 50px around crosshair)
     * Detects dynamic moving humanoid/cylindrical entities, filtering out static background.
     * Returns centroid X of nearest moving humanoid entity, or -1 if none found.
     */
    private fun detectDynamicHumanoidCluster(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        imgWidth: Int,
        imgHeight: Int,
        targetCenterY: Int,
        targetCenterX: Int
    ): Int {
        val stripHeight = DetectionState.scanStripHeight.coerceIn(20, 100)
        val halfHeight = stripHeight / 2
        val startY = (targetCenterY - halfHeight).coerceIn(0, imgHeight - stripHeight)
        val endY = startY + stripHeight - 1

        val colStep = 4 // Sample every 4 pixels across X for high 30+ FPS speed
        val numCols = imgWidth / colStep
        val currentLuminance = FloatArray(numCols)

        // 5 vertical samples per column across the 50px height
        val sampleOffsetsY = intArrayOf(
            startY,
            startY + stripHeight / 4,
            startY + stripHeight / 2,
            startY + 3 * stripHeight / 4,
            endY
        )

        for (c in 0 until numCols) {
            val x = c * colStep
            var sumLum = 0f
            for (sy in sampleOffsetsY) {
                val offset = sy * rowStride + x * pixelStride
                if (offset + 2 < buffer.capacity()) {
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    sumLum += (0.299f * r + 0.587f * g + 0.114f * b)
                }
            }
            currentLuminance[c] = sumLum / sampleOffsetsY.size
        }

        val prevLum = prevStripLuminance
        prevStripLuminance = currentLuminance

        if (prevLum == null || prevLum.size != numCols) {
            return -1 // Need at least 2 frames for motion delta
        }

        // Detect moving columns (Temporal Motion Filter)
        val isMoving = BooleanArray(numCols)
        var totalMovingCols = 0
        for (c in 0 until numCols) {
            val delta = kotlin.math.abs(currentLuminance[c] - prevLum[c])
            // Moving threshold: filters out static background (rocks, ground, walls where delta ~ 0)
            if (delta >= 16f) {
                isMoving[c] = true
                totalMovingCols++
            }
        }

        // If whole screen is moving (camera panning), discard to avoid false full-screen trigger
        if (totalMovingCols > numCols * 0.45f) {
            return -1
        }

        // Find connected clusters of moving columns (Humanoid / Cylinder aspect ratio)
        var bestClusterCenter = -1
        var minDistanceToCenter = Int.MAX_VALUE

        var clusterStart = -1
        for (c in 0 until numCols) {
            if (isMoving[c]) {
                if (clusterStart == -1) clusterStart = c
            } else {
                if (clusterStart != -1) {
                    val clusterWidthPx = (c - clusterStart) * colStep
                    // Humanoid / cylindrical silhouette: width typically between 12px and 95px
                    if (clusterWidthPx in 12..95) {
                        val clusterCenterX = ((clusterStart + c) / 2) * colStep
                        val dist = kotlin.math.abs(clusterCenterX - targetCenterX)
                        if (dist < minDistanceToCenter) {
                            minDistanceToCenter = dist
                            bestClusterCenter = clusterCenterX
                        }
                    }
                    clusterStart = -1
                }
            }
        }

        // Check end of array
        if (clusterStart != -1) {
            val clusterWidthPx = (numCols - clusterStart) * colStep
            if (clusterWidthPx in 12..95) {
                val clusterCenterX = ((clusterStart + numCols) / 2) * colStep
                val dist = kotlin.math.abs(clusterCenterX - targetCenterX)
                if (dist < minDistanceToCenter) {
                    bestClusterCenter = clusterCenterX
                }
            }
        }

        return bestClusterCenter
    }

    private fun startCaptureLoop() {
        captureLoopJob?.cancel()
        captureLoopJob = serviceScope.launch {
            val targetCenterX = DetectionState.TARGET_X // 801
            val targetCenterY = DetectionState.TARGET_Y // 359

            var lastFpsTimestamp = System.currentTimeMillis()
            var framesCounted = 0
            var currentFps = 0
            var totalProcessedFrames = 0L

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

                                val radius = DetectionState.roiRadius
                                val innerRadius = DetectionState.innerRadius
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

                                // Exact center pixel sampling (801, 359)
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

                                // Iterate pixels in ROI with spatial distance weighting
                                for (y in startY..endY) {
                                    val rowOffset = y * rowStride
                                    val dy = (y - targetCenterY).toFloat()
                                    for (x in startX..endX) {
                                        val pixelIndex = rowOffset + x * pixelStride
                                        if (pixelIndex + 2 < buffer.capacity()) {
                                            val r = buffer.get(pixelIndex).toInt() and 0xFF
                                            val g = buffer.get(pixelIndex + 1).toInt() and 0xFF
                                            val b = buffer.get(pixelIndex + 2).toInt() and 0xFF

                                            val dx = (x - targetCenterX).toFloat()
                                            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                            val isInner = dist <= innerRadius.toFloat()

                                            // Center distance weight: 5.0x at exact center down to 1.0x at edge
                                            val normalizedDist = (dist / radius.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                                            val weight = 1.0f + 4.0f * (1.0f - normalizedDist)

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
                                // 1. Priority 1 (Center Core Priority):
                                // If the center crosshair or inner core (radius <= 3) is RED,
                                // immediately trigger RED even if the outer ROI contains green foliage/grass!
                                var triggerReason = "Scanning"
                                val result = if (centerPriority && (centerIsRed || innerRedPixels >= 2)) {
                                    triggerReason = if (centerIsRed) "Center Pixel Red" else "Core Red ($innerRedPixels px)"
                                    DetectionResult.RED
                                } else if (centerPriority && (centerIsGreen || innerGreenPixels >= 2)) {
                                    triggerReason = if (centerIsGreen) "Center Pixel Green" else "Core Green ($innerGreenPixels px)"
                                    DetectionResult.GREEN
                                } else {
                                    // Priority 2: Fall back to distance-weighted score across ROI
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

                                // Parkinson Motor Assistance: Horizontal Scan Strip (X = max, Y = 50px) & Assisted Swipe
                                if (DetectionState.isHumanoidTrackingEnabled && NeuralAccessibilityService.isServiceConnected()) {
                                    val movingHumanoidX = detectDynamicHumanoidCluster(
                                        buffer = buffer,
                                        rowStride = rowStride,
                                        pixelStride = pixelStride,
                                        imgWidth = imgWidth,
                                        imgHeight = imgHeight,
                                        targetCenterY = targetCenterY,
                                        targetCenterX = targetCenterX
                                    )
                                    DetectionState.lastTrackedClusterX = movingHumanoidX

                                    val nowSwipe = System.currentTimeMillis()

                                    if (result != DetectionResult.RED && movingHumanoidX >= 0) {
                                        // Target not locked on red yet: calculate angular error and perform fast assistive swipe!
                                        val errX = movingHumanoidX - targetCenterX
                                        if (kotlin.math.abs(errX) >= 15 && (nowSwipe - lastSwipeTime >= 85L) && !isSwipeGestureInProgress) {
                                            isSwipeGestureInProgress = true
                                            lastSwipeTime = nowSwipe
                                            val sensitivity = DetectionState.trackingSensitivity
                                            val deltaXDrag = (-errX * sensitivity).coerceIn(-380f, 380f)

                                            val sX = DetectionState.swipeStartX
                                            val sY = DetectionState.swipeStartY
                                            val eX = (sX + deltaXDrag).coerceIn(80f, (imgWidth - 80).toFloat())

                                            NeuralAccessibilityService.dispatchSwipe(
                                                startX = sX,
                                                startY = sY,
                                                endX = eX,
                                                endY = sY,
                                                durationMs = 70L,
                                                onCompleted = {
                                                    isSwipeGestureInProgress = false
                                                }
                                            )
                                        }
                                    } else if (result == DetectionResult.RED) {
                                        // Target acquired on RED!
                                        // Parkinson Assist: Grip Hold assistance during confirmation window
                                        if (!isGripHoldActive && DetectionState.holdConfirmationDurationMs > 0) {
                                            isGripHoldActive = true
                                            NeuralAccessibilityService.dispatchHold(
                                                x = DetectionState.swipeStartX,
                                                y = DetectionState.swipeStartY,
                                                durationMs = DetectionState.holdConfirmationDurationMs,
                                                onCompleted = {
                                                    isGripHoldActive = false
                                                }
                                            )
                                        }
                                    } else {
                                        isGripHoldActive = false
                                    }
                                }

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

                // Throttle loop to ~30ms to prevent CPU overload while maintaining ~33 FPS
                val loopElapsed = System.currentTimeMillis() - loopStartTime
                val delayTime = (30L - loopElapsed).coerceAtLeast(10L)
                delay(delayTime)
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
