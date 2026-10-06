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
    const val TARGET_X = 801
    const val TARGET_Y = 359

    // Configurable ROI settings
    var roiRadius: Int = 10 // ROI diameter = 20px
    var innerRadius: Int = 3 // Core crosshair diameter = 7px (radius 3)
    var centerPriorityEnabled: Boolean = true // Always prioritize center core over background
    var sensitivityThreshold: Float = 1.0f // Sensitivity multiplier

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _metrics = MutableStateFlow(DetectionMetrics())
    val metrics: StateFlow<DetectionMetrics> = _metrics.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    fun updateMetrics(newMetrics: DetectionMetrics) {
        _metrics.value = newMetrics
    }

    fun reset() {
        _metrics.value = DetectionMetrics()
    }
}

