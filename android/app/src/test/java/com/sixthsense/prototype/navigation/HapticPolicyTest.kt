package com.sixthsense.prototype.navigation

import com.sixthsense.prototype.vision.DetectionResult
import com.sixthsense.prototype.vision.DirectionZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HapticPolicyTest {

    private lateinit var policy: HapticPolicy

    // Helper to build a detection in a specific zone.
    private fun detection(
        normalizedCenter: Float,
        confidence: Float = 0.7f,
        label: String = "Object",
        boxArea: Float = 0.1f,
    ) = DetectionResult(label, confidence, normalizedCenter, boxArea)

    @Before
    fun setUp() {
        policy = HapticPolicy()
        policy.updateConfig(
            HapticPolicyConfig(
                confidenceThreshold = 0.40f,
                perZoneCooldownMs = 700,
                requiredConsecutiveFrames = 2,
                staleResultTimeoutMs = 1500,
            )
        )
    }

    @Test
    fun `first frame does not fire due to persistence requirement`() {
        val result = policy.evaluate(listOf(detection(0.5f)), nowMs = 1000)
        assertNull("Should not fire on first frame", result.command)
        assertEquals(1, result.zoneCounts[DirectionZone.CENTER])
    }

    @Test
    fun `fires after required consecutive frames`() {
        policy.evaluate(listOf(detection(0.5f)), nowMs = 1000)
        val result = policy.evaluate(listOf(detection(0.5f)), nowMs = 1050)
        assertNotNull("Should fire on second consecutive frame", result.command)
        assertEquals(HapticCommand.FRONT, result.command)
        assertEquals(DirectionZone.CENTER, result.activeZone)
    }

    @Test
    fun `per-zone cooldown prevents rapid re-fire`() {
        // Frame 1 and 2: build persistence and fire
        policy.evaluate(listOf(detection(0.5f)), nowMs = 1000)
        val fired = policy.evaluate(listOf(detection(0.5f)), nowMs = 1050)
        assertEquals(HapticCommand.FRONT, fired.command)

        // Frame 3: within cooldown (700ms), should not fire
        val suppressed = policy.evaluate(listOf(detection(0.5f)), nowMs = 1100)
        assertNull("Should be suppressed by cooldown", suppressed.command)

        // Frame after cooldown expires
        val firedAgain = policy.evaluate(listOf(detection(0.5f)), nowMs = 1800)
        assertEquals(HapticCommand.FRONT, firedAgain.command)
    }

    @Test
    fun `front-first priority when multiple zones have detections`() {
        val leftDet = detection(0.1f)   // LEFT zone
        val centerDet = detection(0.5f) // CENTER zone
        val rightDet = detection(0.9f)  // RIGHT zone

        // Frame 1: build persistence for all zones
        policy.evaluate(listOf(leftDet, centerDet, rightDet), nowMs = 1000)
        // Frame 2: all zones meet persistence; CENTER (FRONT) should win
        val result = policy.evaluate(listOf(leftDet, centerDet, rightDet), nowMs = 1050)
        assertEquals(HapticCommand.FRONT, result.command)
        assertEquals(DirectionZone.CENTER, result.activeZone)
    }

    @Test
    fun `left zone fires when center is on cooldown`() {
        val leftDet = detection(0.1f)
        val centerDet = detection(0.5f)

        // Build persistence and fire CENTER
        policy.evaluate(listOf(leftDet, centerDet), nowMs = 1000)
        val first = policy.evaluate(listOf(leftDet, centerDet), nowMs = 1050)
        assertEquals(HapticCommand.FRONT, first.command)

        // Next frame: CENTER on cooldown, LEFT should fire
        val second = policy.evaluate(listOf(leftDet, centerDet), nowMs = 1100)
        assertEquals(HapticCommand.LEFT, second.command)
    }

    @Test
    fun `low-confidence detections are filtered`() {
        val lowConf = detection(0.5f, confidence = 0.2f)
        policy.evaluate(listOf(lowConf), nowMs = 1000)
        val result = policy.evaluate(listOf(lowConf), nowMs = 1050)
        assertNull("Low confidence should not trigger", result.command)
    }

    @Test
    fun `stale-result timeout resets zone state`() {
        // Build persistence
        policy.evaluate(listOf(detection(0.5f)), nowMs = 1000)
        policy.evaluate(listOf(detection(0.5f)), nowMs = 1050)

        // Long gap with no detections — triggers stale reset
        val result = policy.evaluate(listOf(detection(0.5f)), nowMs = 3000)
        assertNull("Should need fresh persistence after stale timeout", result.command)
        assertEquals(1, result.zoneCounts[DirectionZone.CENTER])
    }

    @Test
    fun `zone counter resets when detection disappears`() {
        policy.evaluate(listOf(detection(0.5f)), nowMs = 1000)
        // Empty frame: counter resets to 0
        policy.evaluate(emptyList(), nowMs = 1050)
        // Detection reappears: counter starts at 1 again
        val result = policy.evaluate(listOf(detection(0.5f)), nowMs = 1100)
        assertNull("Counter should have reset", result.command)
        assertEquals(1, result.zoneCounts[DirectionZone.CENTER])
    }

    @Test
    fun `reset clears all state`() {
        policy.evaluate(listOf(detection(0.5f)), nowMs = 1000)
        policy.evaluate(listOf(detection(0.5f)), nowMs = 1050)
        policy.reset()
        val result = policy.evaluate(listOf(detection(0.5f)), nowMs = 1100)
        assertNull("Should not fire after reset", result.command)
        assertEquals(0, result.commandsSentCount)
    }

    @Test
    fun `never emits BACK command`() {
        // Even if somehow a detection mapped to BACK, the policy should not emit it.
        // In practice, DirectionZone.UNKNOWN -> null from HapticCommandMapper.
        // The forward camera zones only produce LEFT, CENTER, RIGHT — no BACK path exists.
        val left = detection(0.1f)
        val center = detection(0.5f)
        val right = detection(0.9f)

        policy.evaluate(listOf(left, center, right), nowMs = 1000)
        val result = policy.evaluate(listOf(left, center, right), nowMs = 1050)
        if (result.command != null) {
            assert(result.command != HapticCommand.BACK) {
                "Forward camera must never emit BACK"
            }
        }
    }

    @Test
    fun `commands sent count increments correctly`() {
        policy.evaluate(listOf(detection(0.5f)), nowMs = 1000)
        val r1 = policy.evaluate(listOf(detection(0.5f)), nowMs = 1050)
        assertEquals(1, r1.commandsSentCount)

        // After cooldown, fire again
        policy.evaluate(listOf(detection(0.5f)), nowMs = 1800)
        val r2 = policy.evaluate(listOf(detection(0.5f)), nowMs = 1850)
        // r2 should still be within cooldown of the second fire, but the count should reflect
        // Note: At 1800 the counter is 6 consecutive (from frame at 1000, 1050, 1100 implicit...
        // Actually let me recalculate. Cooldown was set at 1050. At 1800 that's 750ms > 700ms.
        // So at 1800 it should fire if persistence is met.
        // Consecutive frames at 1800: we had detections at 1000, 1050, then 1800.
        // But there was no stale timeout (1800-1050=750ms < 1500ms stale timeout), so
        // the counter continued incrementing. Wait, did we call evaluate at any intermediate times?
        // Let me re-check: we only called at 1000, 1050, 1800.
        // At 1800: lastDetectionFrameMs = 1050, nowMs = 1800. 1800-1050 = 750 < 1500 stale.
        // counter was at 2 from 1050, goes to 3 at 1800. persistence met. cooldown: last sent 1050, 1800-1050=750 > 700 → fires!
        assertEquals(2, r2.commandsSentCount)
    }
}
