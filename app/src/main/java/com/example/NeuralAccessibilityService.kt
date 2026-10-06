package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NeuralAccessibilityService:
 * Motor response controller for the Artificial Neural Reflex system.
 * Dispatches simulated hardware tap gesture at (597, 497) with minimum possible latency.
 */
class NeuralAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NEURAL"
        private var instance: NeuralAccessibilityService? = null

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        fun isServiceConnected(): Boolean = (instance != null)

        /**
         * Dispatches a tap gesture immediately without delay.
         * Measures latency from detectionTimestamp to dispatchGesture invocation.
         */
        fun dispatchTap(
            x: Float = DetectionState.TAP_X,
            y: Float = DetectionState.TAP_Y,
            detectionTimestamp: Long = System.currentTimeMillis(),
            onLatencyMeasured: ((Long) -> Unit)? = null
        ): Boolean {
            val service = instance
            if (service == null) {
                Log.w(TAG, "[NEURAL] AccessibilityService not connected. Please enable in Settings.")
                return false
            }

            return service.executeTap(x, y, detectionTimestamp, onLatencyMeasured)
        }

        /**
         * Dispatches an automated sustained press-and-drag gesture (thao tác đè màn hình vuốt theo trục ngang)
         * for patients with Parkinson's disease or neuro-motor impairments.
         * Touches down at (startX, startY), drags horizontally across the screen along the X-axis for holdDurationMs,
         * maintaining continuous ACTION_MOVE touch stream before sending ACTION_UP.
         */
        fun dispatchHold(
            x: Float = DetectionState.TAP_X,
            y: Float = DetectionState.TAP_Y,
            holdDurationMs: Long = DetectionState.holdConfirmationDurationMs,
            dragDistanceX: Float = DetectionState.horizontalDragDistanceX,
            dragDirection: DragDirection = DetectionState.horizontalDragDirection,
            horizontalScanRedOffset: Int = 0,
            detectionTimestamp: Long = System.currentTimeMillis(),
            onLatencyMeasured: ((Long) -> Unit)? = null,
            onHoldCompleted: (() -> Unit)? = null
        ): Boolean {
            val service = instance
            if (service == null) {
                Log.w(TAG, "[NEURAL] AccessibilityService not connected. Please enable in Settings.")
                return false
            }

            return service.executeHorizontalDragHold(
                x,
                y,
                holdDurationMs,
                dragDistanceX,
                dragDirection,
                horizontalScanRedOffset,
                detectionTimestamp,
                onLatencyMeasured,
                onHoldCompleted
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceActive.value = true
        Log.i(TAG, "[NEURAL] Motor Response Controller (AccessibilityService) connected and ready.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event interception needed - purely acting as motor gesture controller
    }

    override fun onInterrupt() {
        Log.w(TAG, "[NEURAL] Motor Response Controller interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isServiceActive.value = false
        Log.i(TAG, "[NEURAL] Motor Response Controller destroyed.")
    }

    /**
     * Executes single point tap via dispatchGesture (Android 7.0+ API 24).
     */
    private fun executeTap(
        x: Float,
        y: Float,
        detectionTimestamp: Long,
        onLatencyMeasured: ((Long) -> Unit)?
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "[NEURAL] dispatchGesture requires API 24+")
            return false
        }

        try {
            val path = Path().apply {
                moveTo(x, y)
            }

            // Duration: 40ms is ideal for Android system to register as a genuine down->up tap
            val stroke = GestureDescription.StrokeDescription(path, 0, 40L)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            val dispatchStartTimestamp = System.currentTimeMillis()
            val latencyMs = (dispatchStartTimestamp - detectionTimestamp).coerceAtLeast(0L)

            Log.i(TAG, "[NEURAL] Gesture dispatched at $dispatchStartTimestamp")
            Log.i(TAG, "[NEURAL] Reflex latency: $latencyMs ms (Target: [X=$x, Y=$y])")

            onLatencyMeasured?.invoke(latencyMs)

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "[NEURAL] Tap gesture completed successfully at ($x, $y)")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        Log.w(TAG, "[NEURAL] Tap gesture cancelled at ($x, $y)")
                    }
                },
                null
            )

            return dispatched
        } catch (e: Exception) {
            Log.e(TAG, "[NEURAL] Failed to dispatch tap gesture: ${e.message}", e)
            return false
        }
    }

    /**
     * Executes automated sustained press-and-drag gesture (thao tác đè màn hình vuốt theo trục ngang)
     * via dispatchGesture.
     * Touches down at (startX, startY), swipes horizontally along the X-axis for holdDurationMs,
     * maintaining continuous ACTION_MOVE touch stream before sending ACTION_UP.
     */
    private fun executeHorizontalDragHold(
        startX: Float,
        startY: Float,
        holdDurationMs: Long,
        dragDistanceX: Float,
        dragDirection: DragDirection,
        horizontalScanRedOffset: Int,
        detectionTimestamp: Long,
        onLatencyMeasured: ((Long) -> Unit)?,
        onHoldCompleted: (() -> Unit)?
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "[PARKINSON] dispatchGesture requires API 24+")
            return false
        }

        try {
            val screenWidth = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(720f)
            val strokeDuration = holdDurationMs.coerceIn(40L, 10000L)
            val distance = dragDistanceX.coerceIn(15f, 500f)

            // Construct horizontal drag path (thao tác đè màn hình vuốt theo trục ngang)
            val path = Path().apply {
                moveTo(startX, startY)
                when (dragDirection) {
                    DragDirection.LEFT_TO_RIGHT -> {
                        val endX = (startX + distance).coerceIn(10f, screenWidth - 10f)
                        lineTo(endX, startY)
                    }
                    DragDirection.RIGHT_TO_LEFT -> {
                        val endX = (startX - distance).coerceIn(10f, screenWidth - 10f)
                        lineTo(endX, startY)
                    }
                    DragDirection.AUTO_TRACK -> {
                        val effectiveOffset = if (kotlin.math.abs(horizontalScanRedOffset) > 3) {
                            (horizontalScanRedOffset.toFloat() * 1.5f).coerceIn(-distance, distance)
                        } else {
                            distance
                        }
                        val endX = (startX + effectiveOffset).coerceIn(10f, screenWidth - 10f)
                        lineTo(endX, startY)
                    }
                    DragDirection.SWEEP_BIDIRECTIONAL -> {
                        val p1X = (startX + distance).coerceIn(10f, screenWidth - 10f)
                        val p2X = (startX - distance).coerceIn(10f, screenWidth - 10f)
                        lineTo(p1X, startY)
                        lineTo(p2X, startY)
                        lineTo(startX, startY)
                    }
                }
            }

            val stroke = GestureDescription.StrokeDescription(path, 0, strokeDuration)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            val dispatchStartTimestamp = System.currentTimeMillis()
            val latencyMs = (dispatchStartTimestamp - detectionTimestamp).coerceAtLeast(0L)

            Log.i(TAG, "[PARKINSON] Đè ghìm vuốt trục ngang (Horizontal Drag) started at $dispatchStartTimestamp for ${strokeDuration}ms from ($startX, $startY) with dist ${distance}px, dir $dragDirection")
            onLatencyMeasured?.invoke(latencyMs)

            DetectionState.isAutoHoldingActive = true

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        DetectionState.isAutoHoldingActive = false
                        Log.d(TAG, "[PARKINSON] Đè ghìm vuốt trục ngang completed after ${strokeDuration}ms")
                        onHoldCompleted?.invoke()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        DetectionState.isAutoHoldingActive = false
                        Log.w(TAG, "[PARKINSON] Đè ghìm vuốt trục ngang cancelled at ($startX, $startY)")
                    }
                },
                null
            )

            if (!dispatched) {
                DetectionState.isAutoHoldingActive = false
                Log.w(TAG, "[PARKINSON] dispatchGesture returned false. Check accessibility permissions.")
            }

            return dispatched
        } catch (e: Exception) {
            DetectionState.isAutoHoldingActive = false
            Log.e(TAG, "[PARKINSON] Failed to dispatch horizontal drag hold gesture: ${e.message}", e)
            return false
        }
    }
}
