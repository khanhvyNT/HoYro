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

    // --- HỆ THỐNG MACRO PHẢN XẠ NHIỀU ĐIỂM & GHI PHẢN XẠ MẪU (OPPO/XIAOMI GAME TURBO MACRO) ---
    private val _isMacroModeEnabled = MutableStateFlow(true)
    val isMacroModeEnabledFlow: StateFlow<Boolean> = _isMacroModeEnabled.asStateFlow()
    var isMacroModeEnabled: Boolean
        get() = _isMacroModeEnabled.value
        set(value) { _isMacroModeEnabled.value = value }

    // Danh sách Macro Profiles
    private val _macroProfiles = MutableStateFlow(getDefaultMacroProfiles())
    val macroProfilesFlow: StateFlow<List<MacroProfile>> = _macroProfiles.asStateFlow()

    // Profile đang hoạt động
    private val _activeProfileId = MutableStateFlow(_macroProfiles.value.first().id)
    val activeProfileIdFlow: StateFlow<String> = _activeProfileId.asStateFlow()
    var activeProfileId: String
        get() = _activeProfileId.value
        set(value) { _activeProfileId.value = value }

    val activeMacroProfile: MacroProfile
        get() = _macroProfiles.value.find { it.id == _activeProfileId.value } ?: _macroProfiles.value.first()

    // Trạng thái thực thi Macro thời gian thực
    private val _macroExecutionStatus = MutableStateFlow(MacroExecutionStatus())
    val macroExecutionStatusFlow: StateFlow<MacroExecutionStatus> = _macroExecutionStatus.asStateFlow()

    // Trạng thái Ghi phản xạ mẫu từ user
    private val _macroRecordingState = MutableStateFlow(MacroRecordingState())
    val macroRecordingStateFlow: StateFlow<MacroRecordingState> = _macroRecordingState.asStateFlow()

    fun updateExecutionStatus(status: MacroExecutionStatus) {
        _macroExecutionStatus.value = status
    }

    fun selectProfile(id: String) {
        _activeProfileId.value = id
    }

    fun addProfile(profile: MacroProfile) {
        _macroProfiles.value = _macroProfiles.value + profile
        _activeProfileId.value = profile.id
    }

    fun updateProfile(profile: MacroProfile) {
        _macroProfiles.value = _macroProfiles.value.map { if (it.id == profile.id) profile else it }
    }

    fun deleteProfile(id: String) {
        if (_macroProfiles.value.size <= 1) return // Giữ tối thiểu 1 profile
        val newProfiles = _macroProfiles.value.filter { it.id != id }
        _macroProfiles.value = newProfiles
        if (_activeProfileId.value == id) {
            _activeProfileId.value = newProfiles.first().id
        }
    }

    fun addStepToActiveProfile(step: MacroStep) {
        val current = activeMacroProfile
        val updated = current.copy(steps = current.steps + step)
        updateProfile(updated)
    }

    fun removeStepFromActiveProfile(stepId: String) {
        val current = activeMacroProfile
        val updated = current.copy(steps = current.steps.filter { it.id != stepId })
        updateProfile(updated)
    }

    fun updateStepInActiveProfile(step: MacroStep) {
        val current = activeMacroProfile
        val updated = current.copy(steps = current.steps.map { if (it.id == step.id) step else it })
        updateProfile(updated)
    }

    // --- User Recording Workflow ---
    fun startMacroRecording() {
        _macroRecordingState.value = MacroRecordingState(
            isRecording = true,
            recordedSteps = emptyList(),
            recordingStartTime = System.currentTimeMillis(),
            statusMessage = "Đang ghi lại thao tác mẫu từ người dùng... (Chạm hoặc vuốt)"
        )
    }

    fun recordAction(step: MacroStep) {
        val state = _macroRecordingState.value
        if (!state.isRecording) return
        val newSteps = state.recordedSteps + step
        _macroRecordingState.value = state.copy(
            recordedSteps = newSteps,
            statusMessage = "Đã ghi ${newSteps.size} bước phản xạ..."
        )
    }

    fun stopMacroRecordingAndSave(profileName: String = "Macro Ghi Mẫu ${System.currentTimeMillis() % 1000}") {
        val state = _macroRecordingState.value
        if (state.recordedSteps.isNotEmpty()) {
            val newProfile = MacroProfile(
                id = java.util.UUID.randomUUID().toString(),
                name = profileName,
                description = "Ghi lại từ người dùng gồm ${state.recordedSteps.size} bước phản xạ",
                steps = state.recordedSteps
            )
            addProfile(newProfile)
        }
        _macroRecordingState.value = MacroRecordingState(
            isRecording = false,
            recordedSteps = emptyList(),
            statusMessage = "Đã lưu Macro mẫu mới!"
        )
    }

    fun cancelMacroRecording() {
        _macroRecordingState.value = MacroRecordingState(
            isRecording = false,
            recordedSteps = emptyList(),
            statusMessage = "Đã hủy ghi."
        )
    }

    fun reset() {
        neuralReflex.reset()
        _metrics.value = DetectionMetrics()
        _neuralStatus.value = NeuralStatusData()
        _macroExecutionStatus.value = MacroExecutionStatus()
    }
}

/**
 * Loại hành động trong chuỗi Macro nhiều điểm.
 */
enum class MacroActionType(val label: String) {
    TAP("Chạm (Tap)"),
    SWIPE("Vuốt (Swipe)")
}

/**
 * Một bước hành động trong chuỗi Macro nhiều điểm.
 */
data class MacroStep(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: MacroActionType = MacroActionType.TAP,
    val x: Float = 597f,
    val y: Float = 497f,
    val endX: Float = 597f,
    val endY: Float = 517f,
    val durationMs: Long = 35L,
    val delayAfterMs: Long = 30L,
    val label: String = "Điểm thao tác"
)

/**
 * Profile cấu hình Macro phản xạ nhiều điểm.
 */
data class MacroProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "Macro Bắn + Ghìm Tâm",
    val description: String = "Tap bắn tại (597, 497) và vuốt ghìm Y + 20 tại (1205, 479)",
    val steps: List<MacroStep> = listOf(),
    val isDefault: Boolean = false
)

/**
 * Trạng thái thực thi Macro thời gian thực.
 */
data class MacroExecutionStatus(
    val isExecuting: Boolean = false,
    val activeProfileName: String = "Mặc định",
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 0,
    val totalExecutions: Long = 0L,
    val lastExecutedTimestamp: Long = 0L,
    val statusMessage: String = "Sẵn sàng kích hoạt Macro khi phát hiện RED"
)

/**
 * Trạng thái tiến trình Ghi Macro từ người dùng.
 */
data class MacroRecordingState(
    val isRecording: Boolean = false,
    val recordedSteps: List<MacroStep> = emptyList(),
    val recordingStartTime: Long = 0L,
    val statusMessage: String = "Chưa ghi"
)

/**
 * Các cấu hình Macro mẫu cài sẵn (Preset Macro Profiles).
 */
fun getDefaultMacroProfiles(): List<MacroProfile> {
    return listOf(
        MacroProfile(
            id = "preset_recoil",
            name = "Bắn + Ghìm Tâm (Oppo/Xiaomi Macro)",
            description = "Tap bắn tại (597, 497) và vuốt ghìm Y + 20 tại (1205, 479)",
            steps = listOf(
                MacroStep(
                    id = "step_1",
                    type = MacroActionType.TAP,
                    x = 597f,
                    y = 497f,
                    durationMs = 35L,
                    delayAfterMs = 25L,
                    label = "Tap Nút Bắn (597, 497)"
                ),
                MacroStep(
                    id = "step_2",
                    type = MacroActionType.SWIPE,
                    x = 1205f,
                    y = 479f,
                    endX = 1205f,
                    endY = 499f,
                    durationMs = 40L,
                    delayAfterMs = 20L,
                    label = "Vuốt Ghìm Tâm Y+20 (1205, 479)"
                )
            ),
            isDefault = true
        ),
        MacroProfile(
            id = "preset_burst",
            name = "Macro 3 Điểm Nhanh (Burst 3-Tap)",
            description = "Chuỗi tap 3 lần liên tiếp với khoảng nghỉ 35ms",
            steps = listOf(
                MacroStep(
                    id = "burst_1",
                    type = MacroActionType.TAP,
                    x = 597f,
                    y = 497f,
                    durationMs = 30L,
                    delayAfterMs = 35L,
                    label = "Tap Viên 1 (597, 497)"
                ),
                MacroStep(
                    id = "burst_2",
                    type = MacroActionType.TAP,
                    x = 597f,
                    y = 497f,
                    durationMs = 30L,
                    delayAfterMs = 35L,
                    label = "Tap Viên 2 (597, 497)"
                ),
                MacroStep(
                    id = "burst_3",
                    type = MacroActionType.TAP,
                    x = 597f,
                    y = 497f,
                    durationMs = 30L,
                    delayAfterMs = 20L,
                    label = "Tap Viên 3 (597, 497)"
                ),
                MacroStep(
                    id = "burst_recoil",
                    type = MacroActionType.SWIPE,
                    x = 1205f,
                    y = 479f,
                    endX = 1205f,
                    endY = 505f,
                    durationMs = 40L,
                    delayAfterMs = 20L,
                    label = "Vuốt Ghìm Tâm Y+26 (1205, 479)"
                )
            )
        ),
        MacroProfile(
            id = "preset_jump_shot",
            name = "Macro Combo Bắn + Ngồi (Crouch Shot)",
            description = "Tap bắn tại (597, 497) và tap nút ngồi tại (1380, 560)",
            steps = listOf(
                MacroStep(
                    id = "js_1",
                    type = MacroActionType.TAP,
                    x = 597f,
                    y = 497f,
                    durationMs = 35L,
                    delayAfterMs = 20L,
                    label = "Tap Bắn (597, 497)"
                ),
                MacroStep(
                    id = "js_2",
                    type = MacroActionType.TAP,
                    x = 1380f,
                    y = 560f,
                    durationMs = 35L,
                    delayAfterMs = 20L,
                    label = "Tap Ngồi (1380, 560)"
                ),
                MacroStep(
                    id = "js_3",
                    type = MacroActionType.SWIPE,
                    x = 1205f,
                    y = 479f,
                    endX = 1205f,
                    endY = 499f,
                    durationMs = 35L,
                    delayAfterMs = 20L,
                    label = "Vuốt Ghìm Tâm Y+20"
                )
            )
        )
    )
}

