package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * NeuralAccessibilityService:
 * Motor response controller for the Artificial Neural Reflex system.
 * Dispatches simulated hardware tap gesture at (597, 497) with minimum possible latency,
 * and executes multi-point reflex Macro sequences (similar to Oppo Game Space / Xiaomi Game Turbo).
 */
class NeuralAccessibilityService : AccessibilityService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var isMacroExecuting = false

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
         * Dispatches a mechanical swipe gesture from (startX, startY) to (endX, endY)
         * to mechanically pull the crosshair to track or recoil compensation.
         */
        fun dispatchSwipe(
            startX: Float = DetectionState.PERIPHERAL_MOTOR_X,
            startY: Float = DetectionState.PERIPHERAL_MOTOR_Y,
            endX: Float,
            endY: Float,
            durationMs: Long = 50L,
            detectionTimestamp: Long = System.currentTimeMillis(),
            onCompleted: (() -> Unit)? = null
        ): Boolean {
            val service = instance
            if (service == null) {
                if (DetectionState.debugLoggingEnabled) {
                    Log.w(TAG, "[NEURAL] AccessibilityService not connected for swipe.")
                }
                return false
            }

            return service.executeSwipe(startX, startY, endX, endY, durationMs, detectionTimestamp, onCompleted)
        }

        /**
         * Dispatches a multi-point Macro reflex sequence (Oppo Game Space / Xiaomi Game Turbo).
         */
        fun dispatchMacro(
            profile: MacroProfile = DetectionState.activeMacroProfile,
            detectionTimestamp: Long = System.currentTimeMillis(),
            onCompleted: (() -> Unit)? = null
        ): Boolean {
            val service = instance
            if (service == null) {
                if (DetectionState.debugLoggingEnabled) {
                    Log.w(TAG, "[NEURAL] AccessibilityService not connected for Macro.")
                }
                return false
            }
            return service.executeMacro(profile, detectionTimestamp, onCompleted)
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
        serviceJob.cancel()
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

            if (DetectionState.debugLoggingEnabled) {
                Log.i(TAG, "[NEURAL] Gesture dispatched at $dispatchStartTimestamp")
                Log.i(TAG, "[NEURAL] Reflex latency: $latencyMs ms (Target: [X=$x, Y=$y])")
            }

            onLatencyMeasured?.invoke(latencyMs)

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        if (DetectionState.debugLoggingEnabled) {
                            Log.d(TAG, "[NEURAL] Tap gesture completed successfully at ($x, $y)")
                        }

                        // Sau khi phát động tap xong: Tự động vuốt Y + 20 để tránh tâm nháy đỏ làm loạn phản xạ do nhiễu
                        if (DetectionState.isPostTapSwipeEnabled) {
                            val flickOriginX = if (DetectionState.useMotorForPostTapSwipe) {
                                DetectionState.PERIPHERAL_MOTOR_X
                            } else {
                                x
                            }
                            val flickOriginY = if (DetectionState.useMotorForPostTapSwipe) {
                                DetectionState.PERIPHERAL_MOTOR_Y
                            } else {
                                y
                            }
                            val deltaY = DetectionState.postTapFlickDeltaY // Mặc định +20f
                            dispatchSwipe(
                                startX = flickOriginX,
                                startY = flickOriginY,
                                endX = flickOriginX,
                                endY = flickOriginY + deltaY,
                                durationMs = 35L
                            )
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        if (DetectionState.debugLoggingEnabled) {
                            Log.w(TAG, "[NEURAL] Tap gesture cancelled at ($x, $y)")
                        }
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

    private var isSwipeInProgress = false

    /**
     * Executes a hardware-level swipe gesture using dispatchGesture.
     * Pulls the crosshair from (startX, startY) towards (endX, endY).
     */
    private fun executeSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        detectionTimestamp: Long,
        onCompleted: (() -> Unit)?
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            if (DetectionState.debugLoggingEnabled) {
                Log.e(TAG, "[NEURAL] dispatchGesture requires API 24+")
            }
            return false
        }

        // Avoid overlapping concurrent swipes
        if (isSwipeInProgress) return false

        try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(20L, 200L))
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            val dispatchStart = System.currentTimeMillis()
            isSwipeInProgress = true

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        isSwipeInProgress = false
                        if (DetectionState.debugLoggingEnabled) {
                            val latency = System.currentTimeMillis() - dispatchStart
                            Log.d(TAG, "[NEURAL] Swipe completed in ${latency}ms from ($startX, $startY) to ($endX, $endY)")
                        }
                        onCompleted?.invoke()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        isSwipeInProgress = false
                        if (DetectionState.debugLoggingEnabled) {
                            Log.w(TAG, "[NEURAL] Swipe cancelled at ($endX, $endY)")
                        }
                    }
                },
                null
            )

            if (!dispatched) {
                isSwipeInProgress = false
            }

            return dispatched
        } catch (e: Exception) {
            isSwipeInProgress = false
            if (DetectionState.debugLoggingEnabled) {
                Log.e(TAG, "[NEURAL] Failed to dispatch swipe gesture: ${e.message}", e)
            }
            return false
        }
    }

    /**
     * Executes a multi-point Macro reflex sequence asynchronously step-by-step.
     * Compatible with Oppo Game Space and Xiaomi Game Turbo macro chains.
     */
    fun executeMacro(
        profile: MacroProfile,
        detectionTimestamp: Long = System.currentTimeMillis(),
        onCompleted: (() -> Unit)? = null
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (isMacroExecuting) return false
        if (profile.steps.isEmpty()) return false

        isMacroExecuting = true
        serviceScope.launch {
            try {
                DetectionState.updateExecutionStatus(
                    MacroExecutionStatus(
                        isExecuting = true,
                        activeProfileName = profile.name,
                        currentStepIndex = 0,
                        totalSteps = profile.steps.size,
                        lastExecutedTimestamp = System.currentTimeMillis(),
                        statusMessage = "Đang thực thi chuỗi Macro: ${profile.name}"
                    )
                )

                profile.steps.forEachIndexed { index, step ->
                    DetectionState.updateExecutionStatus(
                        DetectionState.macroExecutionStatusFlow.value.copy(
                            currentStepIndex = index,
                            statusMessage = "Bước ${index + 1}/${profile.steps.size}: ${step.label}"
                        )
                    )

                    val stepDeferred = CompletableDeferred<Boolean>()

                    val gesture = when (step.type) {
                        MacroActionType.TAP -> {
                            val path = Path().apply { moveTo(step.x, step.y) }
                            GestureDescription.Builder()
                                .addStroke(GestureDescription.StrokeDescription(path, 0, step.durationMs.coerceIn(15L, 300L)))
                                .build()
                        }
                        MacroActionType.SWIPE -> {
                            val path = Path().apply {
                                moveTo(step.x, step.y)
                                lineTo(step.endX, step.endY)
                            }
                            GestureDescription.Builder()
                                .addStroke(GestureDescription.StrokeDescription(path, 0, step.durationMs.coerceIn(20L, 500L)))
                                .build()
                        }
                    }

                    val dispatched = dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                super.onCompleted(gestureDescription)
                                stepDeferred.complete(true)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                super.onCancelled(gestureDescription)
                                stepDeferred.complete(false)
                            }
                        },
                        null
                    )

                    if (!dispatched) {
                        stepDeferred.complete(false)
                    }

                    stepDeferred.await()

                    if (step.delayAfterMs > 0) {
                        delay(step.delayAfterMs)
                    }
                }

                val currentStatus = DetectionState.macroExecutionStatusFlow.value
                DetectionState.updateExecutionStatus(
                    currentStatus.copy(
                        isExecuting = false,
                        totalExecutions = currentStatus.totalExecutions + 1,
                        statusMessage = "Hoàn tất chuỗi Macro (${profile.steps.size} bước) thành công!"
                    )
                )
                onCompleted?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "[NEURAL] Macro execution failed: ${e.message}", e)
                DetectionState.updateExecutionStatus(
                    DetectionState.macroExecutionStatusFlow.value.copy(
                        isExecuting = false,
                        statusMessage = "Lỗi Macro: ${e.message}"
                    )
                )
            } finally {
                isMacroExecuting = false
            }
        }
        return true
    }
}
