package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the ArtificialNeuralReflex state machine.
 *
 * Verifies:
 * 1. Immediate mode (without hold filter)
 * 2. Sustained Hold Filter mode (500-800ms):
 *    - Rejection of transient noise / fly flicker (e.g. 150ms red -> 0 tap, noiseRejectionCount++)
 *    - Confirmation of steady continuous RED sustained for >= 600ms (1 tap triggered)
 *    - Prevention of spamming (RED -> RED after confirmation does not re-tap)
 *    - Multiple valid hold cycles (RED sustained -> tap 1 -> GREEN -> RED sustained -> tap 2)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtificialNeuralReflexTest {

    private lateinit var reflex: ArtificialNeuralReflex

    @Before
    fun setUp() {
        DetectionState.isHoldVerificationEnabled = true
        DetectionState.holdConfirmationDurationMs = 600L
        reflex = ArtificialNeuralReflex()
    }

    @Test
    fun `test boot uncalibrated - UNKNOWN to RED should NOT tap`() {
        val decision = reflex.process(DetectionResult.RED, 1000L)
        assertEquals("Initial RED without prior baseline must NOT tap", ReflexDecision.NO_ACTION, decision)
        assertEquals("Reflex count should be 0", 0L, reflex.reflexCount)
    }

    @Test
    fun `test fly noise flicker - 150ms RED dropped to SCANNING should be rejected with 0 TAP`() {
        // Baseline established
        reflex.process(DetectionResult.SCANNING, 1000L)
        assertEquals(NeuralState.BACKGROUND, reflex.currentState)

        // Fly/Dust flickers red at t = 1050ms
        val d1 = reflex.process(DetectionResult.RED, 1050L)
        assertEquals(ReflexDecision.NO_ACTION, d1)
        assertEquals(NeuralState.HOLDING, reflex.currentState)

        // Frame at t = 1150ms (held for only 100ms so far)
        val d2 = reflex.process(DetectionResult.RED, 1150L)
        assertEquals(ReflexDecision.NO_ACTION, d2)
        assertEquals(NeuralState.HOLDING, reflex.currentState)

        // Fly vanishes, frame reverts to SCANNING at t = 1200ms (total 150ms < 600ms requirement)
        val d3 = reflex.process(DetectionResult.SCANNING, 1200L)
        assertEquals(ReflexDecision.NO_ACTION, d3)
        assertEquals(NeuralState.BACKGROUND, reflex.currentState)

        // Verifications:
        assertEquals("Reflex count must be 0 (no tap for fly noise)", 0L, reflex.reflexCount)
        assertEquals("Noise rejection count must increment to 1", 1L, reflex.noiseRejectionCount)
    }

    @Test
    fun `test sustained hold 650ms - continuous RED confirmed and produces 1 TAP`() {
        // Baseline established
        reflex.process(DetectionResult.GREEN, 1000L)

        // First red frame at t = 1100ms
        val d1 = reflex.process(DetectionResult.RED, 1100L)
        assertEquals(ReflexDecision.NO_ACTION, d1)
        assertEquals(NeuralState.HOLDING, reflex.currentState)

        // Mid-hold check at t = 1400ms (elapsed 300ms < 600ms)
        val d2 = reflex.process(DetectionResult.RED, 1400L)
        assertEquals(ReflexDecision.NO_ACTION, d2)
        assertEquals(NeuralState.HOLDING, reflex.currentState)

        // Hold complete at t = 1750ms (elapsed 650ms >= 600ms requirement!)
        val d3 = reflex.process(DetectionResult.RED, 1750L)
        assertEquals(ReflexDecision.REFLEX_TAP, d3)
        assertEquals(NeuralState.STIMULATED, reflex.currentState)
        assertEquals(1L, reflex.reflexCount)

        // Subsequent red frames at t = 1900ms and 2100ms must NOT re-tap (no spam)
        val d4 = reflex.process(DetectionResult.RED, 1900L)
        val d5 = reflex.process(DetectionResult.RED, 2100L)
        assertEquals(ReflexDecision.NO_ACTION, d4)
        assertEquals(ReflexDecision.NO_ACTION, d5)
        assertEquals("Reflex count remains 1", 1L, reflex.reflexCount)
    }

    @Test
    fun `test multiple legitimate hold cycles produce multiple TAPS`() {
        // Baseline A
        reflex.process(DetectionResult.SCANNING, 1000L)

        // Cycle 1: Hold RED for 650ms
        reflex.process(DetectionResult.RED, 1100L) // start hold
        reflex.process(DetectionResult.RED, 1400L)
        val tap1 = reflex.process(DetectionResult.RED, 1750L) // confirmed
        assertEquals(ReflexDecision.REFLEX_TAP, tap1)
        assertEquals(1L, reflex.reflexCount)

        // Return to baseline GREEN
        val backToGreen = reflex.process(DetectionResult.GREEN, 2000L)
        assertEquals(ReflexDecision.NO_ACTION, backToGreen)
        assertEquals(NeuralState.BACKGROUND, reflex.currentState)

        // Cycle 2: Hold RED again for 650ms
        reflex.process(DetectionResult.RED, 2200L) // start hold
        reflex.process(DetectionResult.RED, 2500L)
        val tap2 = reflex.process(DetectionResult.RED, 2850L) // confirmed
        assertEquals(ReflexDecision.REFLEX_TAP, tap2)
        assertEquals(2L, reflex.reflexCount)
    }

    @Test
    fun `test immediate mode without hold filter`() {
        DetectionState.isHoldVerificationEnabled = false

        reflex.process(DetectionResult.GREEN, 1000L)
        val decision = reflex.process(DetectionResult.RED, 1050L)

        assertEquals("Immediate mode fires tap on first edge", ReflexDecision.REFLEX_TAP, decision)
        assertEquals(1L, reflex.reflexCount)
    }

    @Test
    fun `test color noise rejection and post tap swipe settings`() {
        DetectionState.rejectOrangeYellowFilterEnabled = true
        DetectionState.rejectBluishWhiteFilterEnabled = true
        DetectionState.isPostTapSwipeEnabled = true
        DetectionState.postTapFlickDeltaY = 20f

        assertEquals(true, DetectionState.rejectOrangeYellowFilterEnabled)
        assertEquals(true, DetectionState.rejectBluishWhiteFilterEnabled)
        assertEquals(true, DetectionState.isPostTapSwipeEnabled)
        assertEquals(20f, DetectionState.postTapFlickDeltaY)

        DetectionState.rejectOrangeYellowFilterEnabled = false
        assertEquals(false, DetectionState.rejectOrangeYellowFilterEnabled)

        DetectionState.rejectBluishWhiteFilterEnabled = false
        assertEquals(false, DetectionState.rejectBluishWhiteFilterEnabled)
    }
}
