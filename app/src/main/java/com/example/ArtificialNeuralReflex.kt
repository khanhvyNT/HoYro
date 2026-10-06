package com.example

import android.util.Log

/**
 * Neural states in the hospital patient monitor analogy:
 * - UNKNOWN: Initial uncalibrated state before any background observation
 * - BACKGROUND (State A): Normal baseline state (observed GREEN or SCANNING)
 * - STIMULATED (State B): Excited stimulus state (observed RED)
 */
enum class NeuralState(val label: String) {
    UNKNOWN("UNKNOWN"),
    BACKGROUND("BACKGROUND (A: Green/Scanning)"),
    STIMULATED("STIMULATED (B: Red)")
}

/**
 * Output action of the neural reflex center.
 */
enum class ReflexDecision {
    REFLEX_TAP,
    NO_ACTION
}

/**
 * Snapshot data of the patient's artificial neural status for UI & diagnostics.
 */
data class NeuralStatusData(
    val sensoryInput: DetectionResult = DetectionResult.SCANNING,
    val previousState: NeuralState = NeuralState.UNKNOWN,
    val currentState: NeuralState = NeuralState.UNKNOWN,
    val reflexDecision: ReflexDecision = ReflexDecision.NO_ACTION,
    val reflexCount: Long = 0L,
    val lastReflexTimestamp: Long = 0L,
    val lastLatencyMs: Long = 0L,
    val isAccessibilityActive: Boolean = false,
    val motorTargetX: Float = 1205f,
    val motorTargetY: Float = 479f
)

/**
 * ArtificialNeuralReflex - Edge-triggered state machine.
 *
 * Rules:
 * 1. GREEN and SCANNING are Background (State A).
 * 2. RED is Stimulus (State B).
 * 3. Only A (Background) -> B (RED) triggers a motor reflex TAP at (1205, 479).
 * 4. RED -> RED is sustained stimulus, NO tap.
 * 5. UNKNOWN -> RED at boot is uncalibrated, NO tap. Must observe baseline A first.
 * 6. Edge-triggered transition detector with zero polling delay.
 */
class ArtificialNeuralReflex(
    private val onReflexTriggered: ((detectionTimestamp: Long) -> Unit)? = null
) {

    private val tag = "NEURAL"

    private fun logI(message: String) {
        try {
            Log.i(tag, message)
        } catch (_: Throwable) {
            println("[$tag] $message")
        }
    }

    private fun logD(message: String) {
        try {
            Log.d(tag, message)
        } catch (_: Throwable) {
            println("[$tag] $message")
        }
    }

    private fun logV(message: String) {
        try {
            Log.v(tag, message)
        } catch (_: Throwable) {
            println("[$tag] $message")
        }
    }

    var currentState: NeuralState = NeuralState.UNKNOWN
        private set

    var previousState: NeuralState = NeuralState.UNKNOWN
        private set

    var reflexCount: Long = 0L
        private set

    var lastReflexTimestamp: Long = 0L
        private set

    var lastLatencyMs: Long = 0L
        private set

    init {
        logI("[NEURAL] Initial state: UNKNOWN")
    }

    /**
     * Process sensory input (DetectionResult) and decide reflex action.
     * Edge-triggered: only Background -> RED fires REFLEX_TAP.
     */
    @Synchronized
    fun process(
        sensoryResult: DetectionResult,
        detectionTimestamp: Long = System.currentTimeMillis()
    ): ReflexDecision {
        val priorState = currentState
        val isStimulus = (sensoryResult == DetectionResult.RED)

        var decision = ReflexDecision.NO_ACTION

        if (isStimulus) {
            when (priorState) {
                NeuralState.UNKNOWN -> {
                    // Rule 8: Boot uncalibrated. Patient starts with RED without baseline -> DO NOT TAP
                    currentState = NeuralState.STIMULATED
                    logD("[NEURAL] State: UNKNOWN → RED (Boot uncalibrated, no reflex)")
                }
                NeuralState.BACKGROUND -> {
                    // Rule 1 & 2: Background (GREEN/SCANNING) -> RED = TRIGGER TAP!
                    currentState = NeuralState.STIMULATED
                    reflexCount++
                    lastReflexTimestamp = detectionTimestamp
                    decision = ReflexDecision.REFLEX_TAP
                    logI("[NEURAL] RED detected at $detectionTimestamp")
                    logI("[NEURAL] State: BACKGROUND → RED")
                    logI("[NEURAL] REFLEX TRIGGERED (Count #$reflexCount)")
                    logI("[NEURAL] TAP: (1205, 479)")
                    onReflexTriggered?.invoke(detectionTimestamp)
                }
                NeuralState.STIMULATED -> {
                    // Rule 2: RED -> RED is sustained stimulus, NOT a new trigger -> IGNORE
                    logV("[NEURAL] State: RED → RED")
                    logV("[NEURAL] RED sustained - no reflex")
                }
            }
        } else {
            // sensoryResult is GREEN or SCANNING (Background state)
            when (priorState) {
                NeuralState.UNKNOWN -> {
                    currentState = NeuralState.BACKGROUND
                    logD("[NEURAL] State: UNKNOWN → ${sensoryResult.name} (Baseline calibrated)")
                }
                NeuralState.STIMULATED -> {
                    currentState = NeuralState.BACKGROUND
                    logD("[NEURAL] State: RED → ${sensoryResult.name} (Returned to baseline)")
                }
                NeuralState.BACKGROUND -> {
                    // Background -> Background, remain in baseline
                    currentState = NeuralState.BACKGROUND
                }
            }
        }

        previousState = priorState

        // Update shared state for UI diagnostics
        DetectionState.updateNeuralStatus(
            NeuralStatusData(
                sensoryInput = sensoryResult,
                previousState = previousState,
                currentState = currentState,
                reflexDecision = decision,
                reflexCount = reflexCount,
                lastReflexTimestamp = lastReflexTimestamp,
                lastLatencyMs = lastLatencyMs,
                isAccessibilityActive = NeuralAccessibilityService.isServiceConnected(),
                motorTargetX = DetectionState.TAP_X,
                motorTargetY = DetectionState.TAP_Y
            )
        )

        return decision
    }

    /**
     * Record measured gesture dispatch latency.
     */
    fun recordLatency(latencyMs: Long) {
        lastLatencyMs = latencyMs
        logI("[NEURAL] Reflex latency: $latencyMs ms")
    }

    /**
     * Reset the neural state machine back to UNKNOWN.
     */
    @Synchronized
    fun reset() {
        previousState = currentState
        currentState = NeuralState.UNKNOWN
        logI("[NEURAL] State reset to UNKNOWN")
    }
}
