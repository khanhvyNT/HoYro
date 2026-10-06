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
    val isAutoHolding: Boolean = false,
    val isAutoHoldingActive: Boolean = false,
    val horizontalDragDirection: String = "NONE",
    val horizontalDragDistanceX: Float = 0f
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

    // Auto-holding and assistive drag properties
    var isAutoHolding: Boolean = false
    var isAutoHoldingActive: Boolean = false
    var horizontalDragDirection: String = "NONE"
    var horizontalDragDistanceX: Float = 0f

    // Parkinson Motor Assistance: Horizontal scan strip (X = max, Y = 50px) and assisted swipe tracking
    const val DEFAULT_SWIPE_START_X = 1200f
    const val DEFAULT_SWIPE_START_Y = 450f
    const val DEFAULT_SCAN_STRIP_HEIGHT = 50

    private val _isHumanoidTrackingEnabled = MutableStateFlow(true)
    val isHumanoidTrackingEnabledFlow: StateFlow<Boolean> = _isHumanoidTrackingEnabled.asStateFlow()
    var isHumanoidTrackingEnabled: Boolean
        get() = _isHumanoidTrackingEnabled.value
        set(value) {
            _isHumanoidTrackingEnabled.value = value
        }

    private val _swipeStartX = MutableStateFlow(DEFAULT_SWIPE_START_X)
    val swipeStartXFlow: StateFlow<Float> = _swipeStartX.asStateFlow()
    var swipeStartX: Float
        get() = _swipeStartX.value
        set(value) {
            _swipeStartX.value = value
        }

    private val _swipeStartY = MutableStateFlow(DEFAULT_SWIPE_START_Y)
    val swipeStartYFlow: StateFlow<Float> = _swipeStartY.asStateFlow()
    var swipeStartY: Float
        get() = _swipeStartY.value
        set(value) {
            _swipeStartY.value = value
        }

    private val _scanStripHeight = MutableStateFlow(DEFAULT_SCAN_STRIP_HEIGHT)
    val scanStripHeightFlow: StateFlow<Int> = _scanStripHeight.asStateFlow()
    var scanStripHeight: Int
        get() = _scanStripHeight.value
        set(value) {
            _scanStripHeight.value = value.coerceIn(20, 150)
        }

    private val _trackingSensitivity = MutableStateFlow(1.2f)
    val trackingSensitivityFlow: StateFlow<Float> = _trackingSensitivity.asStateFlow()
    var trackingSensitivity: Float
        get() = _trackingSensitivity.value
        set(value) {
            _trackingSensitivity.value = value.coerceIn(0.2f, 3.0f)
        }

    private val _lastTrackedClusterX = MutableStateFlow(-1)
    val lastTrackedClusterXFlow: StateFlow<Int> = _lastTrackedClusterX.asStateFlow()
    var lastTrackedClusterX: Int
        get() = _lastTrackedClusterX.value
        set(value) {
            _lastTrackedClusterX.value = value
        }

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

