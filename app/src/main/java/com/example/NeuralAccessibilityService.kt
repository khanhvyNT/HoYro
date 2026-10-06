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
}
