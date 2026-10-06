package com.example

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the ArtificialNeuralReflex state machine.
 *
 * Verifies the edge-triggered transition rules for the hospital patient neural reflex model:
 * - Background (State A): GREEN, SCANNING
 * - Stimulus (State B): RED
 * - Only A -> RED fires a motor reflex tap (1205, 479)
 * - UNKNOWN -> RED at boot is uncalibrated (0 tap)
 * - RED sustained (RED -> RED -> RED) is ignored (1 tap total)
 * - Return to baseline (RED -> GREEN/SCANNING -> RED) allows subsequent tap
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtificialNeuralReflexTest {

    private lateinit var reflex: ArtificialNeuralReflex

    @Before
    fun setUp() {
        reflex = ArtificialNeuralReflex()
    }

    @Test
    fun `test boot uncalibrated - UNKNOWN to RED should NOT tap`() {
        // UNKNOWN -> RED
        val decision = reflex.process(DetectionResult.RED)
        assertEquals("Initial RED without prior baseline must NOT tap", ReflexDecision.NO_ACTION, decision)
        assertEquals("Reflex count should be 0", 0L, reflex.reflexCount)
    }

    @Test
    fun `test UNKNOWN to GREEN to RED should produce 1 TAP`() {
        // UNKNOWN -> GREEN
        val d1 = reflex.process(DetectionResult.GREEN)
        assertEquals(ReflexDecision.NO_ACTION, d1)

        // GREEN -> RED
        val d2 = reflex.process(DetectionResult.RED)
        assertEquals(ReflexDecision.REFLEX_TAP, d2)
        assertEquals(1L, reflex.reflexCount)
    }

    @Test
    fun `test UNKNOWN to SCANNING to RED should produce 1 TAP`() {
        // UNKNOWN -> SCANNING
        val d1 = reflex.process(DetectionResult.SCANNING)
        assertEquals(ReflexDecision.NO_ACTION, d1)

        // SCANNING -> RED
        val d2 = reflex.process(DetectionResult.RED)
        assertEquals(ReflexDecision.REFLEX_TAP, d2)
        assertEquals(1L, reflex.reflexCount)
    }

    @Test
    fun `test GREEN to RED to RED to RED - sustained stimulus should produce only 1 TAP`() {
        reflex.process(DetectionResult.GREEN) // Baseline A

        val d1 = reflex.process(DetectionResult.RED) // First trigger
        assertEquals(ReflexDecision.REFLEX_TAP, d1)

        val d2 = reflex.process(DetectionResult.RED) // Sustained
        assertEquals(ReflexDecision.NO_ACTION, d2)

        val d3 = reflex.process(DetectionResult.RED) // Sustained
        assertEquals(ReflexDecision.NO_ACTION, d3)

        assertEquals("Total taps should remain 1", 1L, reflex.reflexCount)
    }

    @Test
    fun `test GREEN to RED to GREEN to RED should produce 2 TAPS`() {
        reflex.process(DetectionResult.GREEN) // Baseline A

        val d1 = reflex.process(DetectionResult.RED) // Trigger #1
        assertEquals(ReflexDecision.REFLEX_TAP, d1)

        val d2 = reflex.process(DetectionResult.GREEN) // Return to baseline
        assertEquals(ReflexDecision.NO_ACTION, d2)

        val d3 = reflex.process(DetectionResult.RED) // Trigger #2
        assertEquals(ReflexDecision.REFLEX_TAP, d3)

        assertEquals(2L, reflex.reflexCount)
    }

    @Test
    fun `test GREEN to GREEN to SCANNING to SCANNING should produce 0 TAP`() {
        reflex.process(DetectionResult.GREEN)
        val d1 = reflex.process(DetectionResult.GREEN)
        val d2 = reflex.process(DetectionResult.SCANNING)
        val d3 = reflex.process(DetectionResult.SCANNING)

        assertEquals(ReflexDecision.NO_ACTION, d1)
        assertEquals(ReflexDecision.NO_ACTION, d2)
        assertEquals(ReflexDecision.NO_ACTION, d3)
        assertEquals(0L, reflex.reflexCount)
    }

    @Test
    fun `test RED to GREEN to RED should produce 1 TAP`() {
        // Initialize with RED (uncalibrated boot)
        reflex.process(DetectionResult.RED) // 0 tap

        // Return to baseline GREEN
        val d1 = reflex.process(DetectionResult.GREEN)
        assertEquals(ReflexDecision.NO_ACTION, d1)

        // Stimulus RED
        val d2 = reflex.process(DetectionResult.RED)
        assertEquals(ReflexDecision.REFLEX_TAP, d2)
        assertEquals(1L, reflex.reflexCount)
    }

    @Test
    fun `test SCANNING to RED to SCANNING to RED should produce 2 TAPS`() {
        reflex.process(DetectionResult.SCANNING) // Baseline

        val d1 = reflex.process(DetectionResult.RED) // Tap 1
        assertEquals(ReflexDecision.REFLEX_TAP, d1)

        val d2 = reflex.process(DetectionResult.SCANNING) // Baseline
        assertEquals(ReflexDecision.NO_ACTION, d2)

        val d3 = reflex.process(DetectionResult.RED) // Tap 2
        assertEquals(ReflexDecision.REFLEX_TAP, d3)

        assertEquals(2L, reflex.reflexCount)
    }
}
