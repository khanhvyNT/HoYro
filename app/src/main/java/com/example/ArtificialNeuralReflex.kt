package com.example

import android.util.Log

/**
 * Neural states in the hospital patient monitor analogy:
 * - UNKNOWN: Initial uncalibrated state before any background observation
 * - BACKGROUND (State A): Normal baseline state (observed GREEN or SCANNING)
 * - HOLDING: Target in RED, maintaining hold for 500-800ms verification to reject fly/dust flickers
 * - STIMULATED (State B): Excited stimulus state confirmed (continuous RED validated -> TAP)
 */
enum class NeuralState(val label: String) {
    UNKNOWN("UNKNOWN"),
    BACKGROUND("BACKGROUND (A: Green/Scanning)"),
    HOLDING("HOLDING (Xác thực 500-800ms)"),
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
    val motorTargetX: Float = 597f,
    val motorTargetY: Float = 497f,
    val holdElapsedMs: Long = 0L,
    val holdTargetMs: Long = 600L,
    val noiseRejectionCount: Long = 0L,
    val isHoldVerificationActive: Boolean = true,
    val isAutoHolding: Boolean = false,
    val isAutoHoldingActive: Boolean = false,
    val horizontalDragDirection: DragDirection = DragDirection.NONE,
    val horizontalDragDistanceX: Float = 0f
)

/**
 * ArtificialNeuralReflex - Edge-triggered state machine with Sustained Hold Filter.
 *
 * Anti-flicker / Anti-fly noise algorithm:
 * 1. GREEN and SCANNING are Background (State A).
 * 2. RED is Stimulus (State B).
 * 3. When RED first arrives, enter HOLDING state.
 * 4. The signal must hold steadily on RED for 500ms - 800ms without interruption.
 * 5. If during that window any frame dips into non-red (green/scanning), the hold is reset and
 *    classified as fly/dust flicker noise (0 tap, noiseRejectionCount incremented).
 * 6. Only after RED is sustained continuously for >= holdConfirmationDurationMs, the reflex fires!
 * 7. RED -> RED once fired is sustained stimulus, NO spam tap.
 * 8. UNKNOWN -> RED at boot is uncalibrated, NO tap.
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

    var redHoldStartTime: Long = 0L
        private set

    var noiseRejectionCount: Long = 0L
        private set

    init {
        logI("[NEURAL] Initial state: UNKNOWN (Hold filter enabled: 500-800ms)")
    }

    /**
     * Process sensory input (DetectionResult) and decide reflex action.
     * Incorporates Sustained Hold Filter (500-800ms) to reject transient noise.
     */
    @Synchronized
    fun process(
        sensoryResult: DetectionResult,
        detectionTimestamp: Long = System.currentTimeMillis()
    ): ReflexDecision {
        val priorState = currentState
        val isStimulus = (sensoryResult == DetectionResult.RED)
        val holdRequiredMs = DetectionState.holdConfirmationDurationMs
        val isHoldEnabled = DetectionState.isHoldVerificationEnabled

        var decision = ReflexDecision.NO_ACTION

        if (isStimulus) {
            when (priorState) {
                NeuralState.UNKNOWN -> {
                    // Boot uncalibrated. Patient starts with RED without baseline -> DO NOT TAP
                    currentState = NeuralState.STIMULATED
                    redHoldStartTime = 0L
                    logD("[NEURAL] State: UNKNOWN → RED (Boot uncalibrated, no reflex)")
                }
                NeuralState.BACKGROUND -> {
                    if (!isHoldEnabled) {
                        // Immediate edge-trigger (without hold filter)
                        currentState = NeuralState.STIMULATED
                        reflexCount++
                        lastReflexTimestamp = detectionTimestamp
                        decision = ReflexDecision.REFLEX_TAP
                        logI("[NEURAL] RED detected at $detectionTimestamp (Immediate mode)")
                        logI("[NEURAL] State: BACKGROUND → RED")
                        logI("[NEURAL] REFLEX TRIGGERED (Count #$reflexCount) at TAP: (597, 497)")
                        onReflexTriggered?.invoke(detectionTimestamp)
                    } else {
                        // Start Sustained Hold Filter verification window (500-800ms)
                        currentState = NeuralState.HOLDING
                        redHoldStartTime = detectionTimestamp
                        logI("[NEURAL] RED target acquired. Starting Sustained Hold Filter (Hold required: ${holdRequiredMs}ms)...")
                    }
                }
                NeuralState.HOLDING -> {
                    val elapsed = (detectionTimestamp - redHoldStartTime).coerceAtLeast(0L)
                    if (elapsed >= holdRequiredMs) {
                        // CONFIRMED! Maintained steadily on RED for required 500-800ms window without dropping!
                        currentState = NeuralState.STIMULATED
                        reflexCount++
                        lastReflexTimestamp = detectionTimestamp
                        decision = ReflexDecision.REFLEX_TAP
                        logI("[NEURAL] RED SUSTAINED FOR ${elapsed}ms >= ${holdRequiredMs}ms! SIGNAL CONFIRMED!")
                        logI("[NEURAL] State: HOLDING → STIMULATED (Conf: $elapsed ms)")
                        logI("[NEURAL] REFLEX TRIGGERED (Count #$reflexCount) at TAP: (597, 497)")
                        onReflexTriggered?.invoke(detectionTimestamp)
                    } else {
                        // Still holding within 500-800ms window
                        decision = ReflexDecision.NO_ACTION
                        logV("[NEURAL] Holding RED: ${elapsed}ms / ${holdRequiredMs}ms...")
                    }
                }
                NeuralState.STIMULATED -> {
                    // Rule 2: RED -> RED is sustained stimulus, NOT a new trigger -> IGNORE
                    logV("[NEURAL] State: RED → RED (Sustained stimulus - no reflex)")
                }
            }
        } else {
            // sensoryResult is GREEN or SCANNING (Background state)
            when (priorState) {
                NeuralState.UNKNOWN -> {
                    currentState = NeuralState.BACKGROUND
                    redHoldStartTime = 0L
                    logD("[NEURAL] State: UNKNOWN → ${sensoryResult.name} (Baseline calibrated)")
                }
                NeuralState.HOLDING -> {
                    // TARGET DROPPED / FLICKER NOISE!
                    // Ruồi bay, vật thể li ti đã biến mất trước khi đủ 500-800ms!
                    val elapsed = (detectionTimestamp - redHoldStartTime).coerceAtLeast(0L)
                    noiseRejectionCount++
                    redHoldStartTime = 0L
                    currentState = NeuralState.BACKGROUND
                    logI("[NEURAL] NOISE REJECTED! Red lost after only ${elapsed}ms (< ${holdRequiredMs}ms). Transient flicker/fly rejected (Total rejections: $noiseRejectionCount)")
                }
                NeuralState.STIMULATED -> {
                    currentState = NeuralState.BACKGROUND
                    redHoldStartTime = 0L
                    logD("[NEURAL] State: RED → ${sensoryResult.name} (Returned to baseline)")
                }
                NeuralState.BACKGROUND -> {
                    currentState = NeuralState.BACKGROUND
                    redHoldStartTime = 0L
                }
            }
        }

        previousState = priorState

        val currentHoldElapsed = if (currentState == NeuralState.HOLDING && redHoldStartTime > 0L) {
            (detectionTimestamp - redHoldStartTime).coerceAtLeast(0L)
        } else {
            0L
        }

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
                motorTargetY = DetectionState.TAP_Y,
                holdElapsedMs = currentHoldElapsed,
                holdTargetMs = holdRequiredMs,
                noiseRejectionCount = noiseRejectionCount,
                isHoldVerificationActive = isHoldEnabled
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
        redHoldStartTime = 0L
        logI("[NEURAL] State reset to UNKNOWN")
    }
}
