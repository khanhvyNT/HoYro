package com.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detection status states:
 * - RED: Enemy target detected at crosshair/ROI
 * - GREEN: Safe target detected at crosshair/ROI
 * - SCANNING: Other / background scanning
 */
enum class DetectionResult(val label: String, val subtitleText: String, val colorHex: Long) {
    RED("RED (Enemy)", "STATUS: RED", 0xFFFF3B30),
    GREEN("GREEN (Safe)", "STATUS: GREEN", 0xFF34C759),
    SCANNING("SCANNING", "STATUS: SCANNING...", 0xFFFFFFFF)
}

/**
 * Direction mode for horizontal spray / recoil / Parkinson assist drag (đè ghìm vuốt trục ngang).
 */
enum class DragDirection(val label: String, val symbol: String) {
    LEFT_TO_RIGHT("Trái sang Phải (→)", "→"),
    RIGHT_TO_LEFT("Phải sang Trái (←)", "←"),
    AUTO_TRACK("Theo cụm đỏ (🎯 Auto)", "🎯"),
    SWEEP_BIDIRECTIONAL("Lắc hai chiều (⇄ Sweep)", "⇄")
}

/**
 * Real-time diagnostic statistics with inner-core and weighted detection details.
 */
data class DetectionMetrics(
    val result: DetectionResult = DetectionResult.SCANNING,
    val redPixels: Int = 0,
    val greenPixels: Int = 0,
    val otherPixels: Int = 0,
    val innerRedPixels: Int = 0,
    val innerGreenPixels: Int = 0,
    val centerR: Int = 0,
    val centerG: Int = 0,
    val centerB: Int = 0,
    val redScore: Float = 0f,
    val greenScore: Float = 0f,
    val fps: Int = 0,
    val frameCount: Long = 0L,
    val lastUpdateTimeMs: Long = 0L,
    val triggerReason: String = "Idle",
    val horizontalScanRedOffset: Int = 0,
    val horizontalRedWidth: Int = 0,
    val isAutoHolding: Boolean = false
)

/**
 * Shared reactive state holder across MainActivity, ScreenCaptureService, and OverlayService.
 */
object DetectionState {
    const val DEVICE_MODEL = "OPPO CPH2631"
    const val TARGET_WIDTH = 1604
    const val TARGET_HEIGHT = 720
    const val DEFAULT_TAP_X = 597f
    const val DEFAULT_TAP_Y = 497f
    const val DEFAULT_TARGET_X = 801
    const val DEFAULT_TARGET_Y = 359
    const val DEFAULT_HOLD_DURATION_MS = 600L

    // Sensory input coordinates (Screen color sensor) - Customizable
    private val _targetX = MutableStateFlow(DEFAULT_TARGET_X)
    val targetXFlow: StateFlow<Int> = _targetX.asStateFlow()
    var TARGET_X: Int
        get() = _targetX.value
        set(value) {
            _targetX.value = value
        }

    private val _targetY = MutableStateFlow(DEFAULT_TARGET_Y)
    val targetYFlow: StateFlow<Int> = _targetY.asStateFlow()
    var TARGET_Y: Int
        get() = _targetY.value
        set(value) {
            _targetY.value = value
        }

    // Motor reflex response coordinates (Hospital patient reflex tap target) - Customizable
    private val _tapX = MutableStateFlow(DEFAULT_TAP_X)
    val tapXFlow: StateFlow<Float> = _tapX.asStateFlow()
    var TAP_X: Float
        get() = _tapX.value
        set(value) {
            _tapX.value = value
        }

    private val _tapY = MutableStateFlow(DEFAULT_TAP_Y)
    val tapYFlow: StateFlow<Float> = _tapY.asStateFlow()
    var TAP_Y: Float
        get() = _tapY.value
        set(value) {
            _tapY.value = value
        }

    // Configurable ROI settings
    var roiRadius: Int = 10 // ROI diameter = 20px
    var innerRadius: Int = 3 // Core crosshair diameter = 7px (radius 3)
    var centerPriorityEnabled: Boolean = true // Always prioritize center core over background
    var sensitivityThreshold: Float = 1.0f // Sensitivity multiplier

    // Temporal verification / Anti-fly noise filter (Arbitrary customizable hold requirement)
    private val _isHoldVerificationEnabled = MutableStateFlow(true)
    val isHoldVerificationEnabledFlow: StateFlow<Boolean> = _isHoldVerificationEnabled.asStateFlow()
    var isHoldVerificationEnabled: Boolean
        get() = _isHoldVerificationEnabled.value
        set(value) {
            _isHoldVerificationEnabled.value = value
        }

    private val _holdConfirmationDurationMs = MutableStateFlow(DEFAULT_HOLD_DURATION_MS)
    val holdConfirmationDurationMsFlow: StateFlow<Long> = _holdConfirmationDurationMs.asStateFlow()
    var holdConfirmationDurationMs: Long
        get() = _holdConfirmationDurationMs.value
        set(value) {
            _holdConfirmationDurationMs.value = value.coerceAtLeast(0L)
        }

    // Parkinson Assist & Auto-Hold Settings
    private val _isParkinsonAutoHoldEnabled = MutableStateFlow(true)
    val isParkinsonAutoHoldEnabledFlow: StateFlow<Boolean> = _isParkinsonAutoHoldEnabled.asStateFlow()
    var isParkinsonAutoHoldEnabled: Boolean
        get() = _isParkinsonAutoHoldEnabled.value
        set(value) {
            _isParkinsonAutoHoldEnabled.value = value
        }

    private val _horizontalScanRangeX = MutableStateFlow(30) // Horizontal X-scan width ±30px
    val horizontalScanRangeXFlow: StateFlow<Int> = _horizontalScanRangeX.asStateFlow()
    var horizontalScanRangeX: Int
        get() = _horizontalScanRangeX.value
        set(value) {
            _horizontalScanRangeX.value = value.coerceIn(10, 80)
        }

    // Horizontal drag distance ΔX (pixels) for "đè ghìm vuốt trục ngang"
    const val DEFAULT_DRAG_DISTANCE_X = 80f
    private val _horizontalDragDistanceX = MutableStateFlow(DEFAULT_DRAG_DISTANCE_X)
    val horizontalDragDistanceXFlow: StateFlow<Float> = _horizontalDragDistanceX.asStateFlow()
    var horizontalDragDistanceX: Float
        get() = _horizontalDragDistanceX.value
        set(value) {
            _horizontalDragDistanceX.value = value.coerceIn(10f, 500f)
        }

    // Horizontal drag direction mode
    private val _horizontalDragDirection = MutableStateFlow(DragDirection.LEFT_TO_RIGHT)
    val horizontalDragDirectionFlow: StateFlow<DragDirection> = _horizontalDragDirection.asStateFlow()
    var horizontalDragDirection: DragDirection
        get() = _horizontalDragDirection.value
        set(value) {
            _horizontalDragDirection.value = value
        }

    private val _isAutoHoldingActive = MutableStateFlow(false)
    val isAutoHoldingActiveFlow: StateFlow<Boolean> = _isAutoHoldingActive.asStateFlow()
    var isAutoHoldingActive: Boolean
        get() = _isAutoHoldingActive.value
        set(value) {
            _isAutoHoldingActive.value = value
        }

    // Neural reflex motor response enable flag & reactive StateFlow
    private val _isMotorReflexEnabled = MutableStateFlow(true)
    val isMotorReflexEnabledFlow: StateFlow<Boolean> = _isMotorReflexEnabled.asStateFlow()

    var isMotorReflexEnabled: Boolean
        get() = _isMotorReflexEnabled.value
        set(value) {
            _isMotorReflexEnabled.value = value
        }

    fun toggleMotorReflex(): Boolean {
        val next = !_isMotorReflexEnabled.value
        _isMotorReflexEnabled.value = next
        return next
    }

    val neuralReflex = ArtificialNeuralReflex()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _metrics = MutableStateFlow(DetectionMetrics())
    val metrics: StateFlow<DetectionMetrics> = _metrics.asStateFlow()

    private val _neuralStatus = MutableStateFlow(NeuralStatusData())
    val neuralStatus: StateFlow<NeuralStatusData> = _neuralStatus.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    fun updateMetrics(newMetrics: DetectionMetrics) {
        _metrics.value = newMetrics
    }

    fun updateNeuralStatus(status: NeuralStatusData) {
        _neuralStatus.value = status
    }

    fun reset() {
        neuralReflex.reset()
        _metrics.value = DetectionMetrics()
        _neuralStatus.value = NeuralStatusData()
    }
}

