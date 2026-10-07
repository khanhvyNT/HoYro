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
    val triggerReason: String = "Idle"
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

    // Bộ lọc ngăn chặn phản xạ nhầm với dải màu trắng xanh và tâm màu cam - vàng cam
    private val _rejectOrangeYellowFilterEnabled = MutableStateFlow(true)
    val rejectOrangeYellowFilterEnabledFlow: StateFlow<Boolean> = _rejectOrangeYellowFilterEnabled.asStateFlow()
    var rejectOrangeYellowFilterEnabled: Boolean
        get() = _rejectOrangeYellowFilterEnabled.value
        set(value) { _rejectOrangeYellowFilterEnabled.value = value }

    private val _rejectBluishWhiteFilterEnabled = MutableStateFlow(true)
    val rejectBluishWhiteFilterEnabledFlow: StateFlow<Boolean> = _rejectBluishWhiteFilterEnabled.asStateFlow()
    var rejectBluishWhiteFilterEnabled: Boolean
        get() = _rejectBluishWhiteFilterEnabled.value
        set(value) { _rejectBluishWhiteFilterEnabled.value = value }

    // --- Performance Optimization Parameters (Ultra-Low Latency) ---
    // 1. Downscale Capture Factor: 1 = 1604x720 (Full), 2 = 802x360 (1/2, ~40-60ms gain), 4 = 401x180 (1/4)
    private val _captureDownscaleFactor = MutableStateFlow(2)
    val captureDownscaleFactorFlow: StateFlow<Int> = _captureDownscaleFactor.asStateFlow()
    var captureDownscaleFactor: Int
        get() = _captureDownscaleFactor.value
        set(value) {
            _captureDownscaleFactor.value = value.coerceIn(1, 4)
        }

    // 2. Loop Delay Interval: 8ms (~125Hz sampling rate, ~15-22ms gain)
    private val _loopDelayMs = MutableStateFlow(8L)
    val loopDelayMsFlow: StateFlow<Long> = _loopDelayMs.asStateFlow()
    var loopDelayMs: Long
        get() = _loopDelayMs.value
        set(value) {
            _loopDelayMs.value = value.coerceAtLeast(0L)
        }

    // 3 & 4. ROI Scan Stride: Step 2 (stride 2 -> 100 pixels scanned instead of 400, ~5-8ms gain)
    private val _roiScanStride = MutableStateFlow(2)
    val roiScanStrideFlow: StateFlow<Int> = _roiScanStride.asStateFlow()
    var roiScanStride: Int
        get() = _roiScanStride.value
        set(value) {
            _roiScanStride.value = value.coerceIn(1, 4)
        }

    // 5. Hot-Path Debug Logging: Disabled by default to eliminate JNI Log overhead (~5-15ms gain)
    private val _debugLoggingEnabled = MutableStateFlow(false)
    val debugLoggingEnabledFlow: StateFlow<Boolean> = _debugLoggingEnabled.asStateFlow()
    var debugLoggingEnabled: Boolean
        get() = _debugLoggingEnabled.value
        set(value) {
            _debugLoggingEnabled.value = value
        }

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

    // --- Vertical Rod Bacterium Hunting & Mechanical Tracking System ---
    const val DEFAULT_PERIPHERAL_MOTOR_X = 1205f
    const val DEFAULT_PERIPHERAL_MOTOR_Y = 479f

    // Cơ quan vận động ngoại biên (Peripheral Motor Organ) tại (1205, 479)
    private val _peripheralMotorX = MutableStateFlow(DEFAULT_PERIPHERAL_MOTOR_X)
    val peripheralMotorXFlow: StateFlow<Float> = _peripheralMotorX.asStateFlow()
    var PERIPHERAL_MOTOR_X: Float
        get() = _peripheralMotorX.value
        set(value) { _peripheralMotorX.value = value }

    private val _peripheralMotorY = MutableStateFlow(DEFAULT_PERIPHERAL_MOTOR_Y)
    val peripheralMotorYFlow: StateFlow<Float> = _peripheralMotorY.asStateFlow()
    var PERIPHERAL_MOTOR_Y: Float
        get() = _peripheralMotorY.value
        set(value) { _peripheralMotorY.value = value }

    // Bật/tắt cơ chế phản xạ săn bắt vi khuẩn que dọc
    private val _isBacteriumTrackingEnabled = MutableStateFlow(true)
    val isBacteriumTrackingEnabledFlow: StateFlow<Boolean> = _isBacteriumTrackingEnabled.asStateFlow()
    var isBacteriumTrackingEnabled: Boolean
        get() = _isBacteriumTrackingEnabled.value
        set(value) { _isBacteriumTrackingEnabled.value = value }

    // Hệ số độ nhạy vuốt bám đuổi (Tracking Sensitivity Gain: 0.5f - 2.5f)
    private val _trackingSensitivity = MutableStateFlow(1.0f)
    val trackingSensitivityFlow: StateFlow<Float> = _trackingSensitivity.asStateFlow()
    var trackingSensitivity: Float
        get() = _trackingSensitivity.value
        set(value) { _trackingSensitivity.value = value.coerceIn(0.2f, 3.0f) }

    // Cơ chế vuốt Y + 20 sau khi tap để tránh tâm nháy đỏ gây loạn phản xạ do nhiễu
    private val _isPostTapSwipeEnabled = MutableStateFlow(true)
    val isPostTapSwipeEnabledFlow: StateFlow<Boolean> = _isPostTapSwipeEnabled.asStateFlow()
    var isPostTapSwipeEnabled: Boolean
        get() = _isPostTapSwipeEnabled.value
        set(value) { _isPostTapSwipeEnabled.value = value }

    private val _postTapFlickDeltaY = MutableStateFlow(20f)
    val postTapFlickDeltaYFlow: StateFlow<Float> = _postTapFlickDeltaY.asStateFlow()
    var postTapFlickDeltaY: Float
        get() = _postTapFlickDeltaY.value
        set(value) { _postTapFlickDeltaY.value = value }

    private val _useMotorForPostTapSwipe = MutableStateFlow(true)
    val useMotorForPostTapSwipeFlow: StateFlow<Boolean> = _useMotorForPostTapSwipe.asStateFlow()
    var useMotorForPostTapSwipe: Boolean
        get() = _useMotorForPostTapSwipe.value
        set(value) { _useMotorForPostTapSwipe.value = value }

    // Trạng thái theo dõi vi khuẩn thời gian thực
    private val _bacteriumStatus = MutableStateFlow(BacteriumTrackingStatus())
    val bacteriumStatusFlow: StateFlow<BacteriumTrackingStatus> = _bacteriumStatus.asStateFlow()

    fun updateBacteriumStatus(status: BacteriumTrackingStatus) {
        _bacteriumStatus.value = status
    }

    fun reset() {
        neuralReflex.reset()
        _metrics.value = DetectionMetrics()
        _neuralStatus.value = NeuralStatusData()
        _bacteriumStatus.value = BacteriumTrackingStatus()
    }
}

/**
 * Trạng thái theo dõi và săn bắt vi khuẩn hình que dọc của hệ thần kinh nhân tạo.
 */
data class BacteriumTrackingStatus(
    val isBacteriumFound: Boolean = false,
    val bacteriumX: Int = 0,
    val bacteriumY: Int = 0,
    val deltaX: Int = 0,
    val deltaY: Int = 0,
    val rodConfidence: Float = 0f,
    val rodHeight: Int = 0,
    val rodWidth: Int = 0,
    val isTrackingActive: Boolean = false,
    val lastSwipeDirection: String = "IDLE", // "LEFT", "RIGHT", "LOCKED ON TARGET", "IDLE"
    val swipeCount: Long = 0L,
    val lastSwipeTimestamp: Long = 0L,
    val statusMessage: String = "Võng mạc đang quan sát vi khuẩn que dọc..."
)

