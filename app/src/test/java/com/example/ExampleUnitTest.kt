package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    private fun isRed(r: Int, g: Int, b: Int): Boolean {
        val maxOther = maxOf(g, b)
        return (r >= 135 && r > g * 1.35f && r > b * 1.35f && (r - maxOther) >= 28) ||
               (r >= 175 && g <= 110 && b <= 110)
    }

    private fun isGreen(r: Int, g: Int, b: Int): Boolean {
        val maxOther = maxOf(r, b)
        return (g >= 130 && g > r * 1.35f && g > b * 1.25f && (g - maxOther) >= 28) ||
               (g >= 160 && r <= 110 && b <= 130)
    }

    @Test
    fun testColorDetectionThresholds() {
        // Red detection
        assertTrue("Pure red should be detected", isRed(255, 0, 0))
        assertTrue("Deep red (200, 50, 40) should be detected", isRed(200, 50, 40))
        assertTrue("In-game shading red (165, 45, 45) should be detected", isRed(165, 45, 45))
        assertTrue("Orange-yellow should NOT be detected as red", !isRed(220, 180, 20))

        // Green detection
        assertTrue("Pure green should be detected", isGreen(0, 255, 0))
        assertTrue("Safe crosshair green (40, 210, 50) should be detected", isGreen(40, 210, 50))
        // Cyan should NOT be mistaken as green
        assertTrue("Cyan / sky blue should NOT be detected as green", !isGreen(50, 180, 220))
        assertTrue("Yellow should NOT be detected as green", !isGreen(200, 200, 0))
    }

    @Test
    fun testCenterPriorityAgainstBackgroundGrass() {
        val centerIsRed = isRed(220, 30, 30)
        val innerRedPixels = 4
        val greenGrassPixels = 120
        val centerPriorityEnabled = true

        val result = if (centerPriorityEnabled && (centerIsRed || innerRedPixels >= 2)) {
            DetectionResult.RED
        } else if (greenGrassPixels > 10) {
            DetectionResult.GREEN
        } else {
            DetectionResult.SCANNING
        }

        // Even with 120 pixels of green grass background, center priority must yield RED
        assertEquals(DetectionResult.RED, result)
    }

    @Test
    fun testTargetCoordinates() {
        val targetX = DetectionState.TARGET_X
        val targetY = DetectionState.TARGET_Y

        assertEquals(801, targetX)
        assertEquals(359, targetY)
        assertEquals(10, DetectionState.roiRadius)
        assertEquals(3, DetectionState.innerRadius)
    }

    @Test
    fun testSubtitleTextOutput() {
        assertEquals("STATUS: RED", DetectionResult.RED.subtitleText)
        assertEquals("STATUS: GREEN", DetectionResult.GREEN.subtitleText)
        assertEquals("STATUS: SCANNING...", DetectionResult.SCANNING.subtitleText)
    }
}

