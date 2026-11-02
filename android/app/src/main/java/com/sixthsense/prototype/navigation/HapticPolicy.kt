package com.sixthsense.prototype.navigation

import com.sixthsense.prototype.vision.DetectionResult
import com.sixthsense.prototype.vision.DirectionZone
import com.sixthsense.prototype.vision.DirectionZoneMapper

/**
 * Configuration for the haptic navigation policy.
 * All timing values are in milliseconds.
 */
data class HapticPolicyConfig(
    /** Minimum classification confidence to consider a detection. */
    val confidenceThreshold: Float = 0.40f,
    /** Per-zone cooldown — each zone can only fire once per this interval. */
    val perZoneCooldownMs: Long = 700,
    /** A zone must be seen in this many consecutive frames before alerting (1 = responsive). */
    val requiredConsecutiveFrames: Int = 1,
    /** If no detections arrive for this long, reset all zone states. */
    val staleResultTimeoutMs: Long = 1500,
)

/** Result of processing one frame of detections through the policy. */
data class PolicyResult(
    /** The haptic command to send, or null if no alert is warranted. */
    val command: HapticCommand?,
    /** The zone that triggered the command (or UNKNOWN). */
    val activeZone: DirectionZone,
    /** Per-zone consecutive-frame counts for UI display. */
    val zoneCounts: Map<DirectionZone, Int>,
    /** How many commands have been sent in this session. */
    val commandsSentCount: Int,
)

/**
 * Pure, unit-testable haptic navigation policy.
 *
 * Replaces the inline logic in SixthSenseViewModel.handleDetections().
 * Implements the requirements from architecture.md §7.3:
 *
 * - Per-zone cooldowns (not just global)
 * - Persistence: zone must appear in [requiredConsecutiveFrames] consecutive
 *   frames before triggering
 * - Stale-result timeout: if no detection frame arrives within the timeout,
 *   all zone states reset
 * - Front-first priority: CENTER zone (physical FRONT motor) fires first
 *   when multiple zones qualify simultaneously
 * - Never emits HapticCommand.BACK — the forward camera cannot see behind
 *
 * Thread-safety: all public methods are synchronized because the ML Kit
 * analysis callback runs on a background executor thread.
 */
class HapticPolicy {

    @Volatile
    private var config = HapticPolicyConfig()

    // Per-zone consecutive-frame counter. Incremented each frame the zone has
    // a qualifying detection; reset to 0 when it disappears.
    private val consecutiveFrames = mutableMapOf<DirectionZone, Int>()

    // Per-zone timestamp of the last haptic command sent for that zone.
    private val lastSentAtMs = mutableMapOf<DirectionZone, Long>()

    // Timestamp of the most recent call to evaluate() that had any detection.
    private var lastDetectionFrameMs: Long = Long.MIN_VALUE

    // Total commands sent since last reset.
    private var commandsSent = 0

    /** Priority order: front/center first, then left, then right. */
    private val zonePriority = listOf(DirectionZone.CENTER, DirectionZone.LEFT, DirectionZone.RIGHT)

    /**
     * Evaluate one frame of detections and return a [PolicyResult].
     *
     * @param detections the list of ML Kit detection results for this frame
     * @param nowMs monotonic clock in milliseconds (e.g., SystemClock.elapsedRealtime())
     */
    @Synchronized
    fun evaluate(detections: List<DetectionResult>, nowMs: Long): PolicyResult {
        // 1. Stale-result timeout: if too long since any detection, reset.
        if (lastDetectionFrameMs != Long.MIN_VALUE &&
            nowMs - lastDetectionFrameMs > config.staleResultTimeoutMs
        ) {
            // Stale-result timeout reached; resetting all zone state.
            resetInternal()
        }

        // 2. Filter detections by confidence threshold.
        val eligible = detections.filter { it.confidence >= config.confidenceThreshold }

        // 3. Map each eligible detection to a zone and find which zones are active.
        val activeZones = mutableSetOf<DirectionZone>()
        for (det in eligible) {
            val zone = DirectionZoneMapper.fromNormalizedCenter(det.normalizedHorizontalCenter)
            if (zone != DirectionZone.UNKNOWN) {
                activeZones.add(zone)
            }
        }

        // 4. Update consecutive-frame counters (decay by 1 on missed frame to tolerate jitter).
        for (zone in zonePriority) {
            if (zone in activeZones) {
                consecutiveFrames[zone] = (consecutiveFrames[zone] ?: 0) + 1
            } else {
                consecutiveFrames[zone] = maxOf(0, (consecutiveFrames[zone] ?: 0) - 1)
            }
        }

        // Record detection timestamp if any objects were found.
        if (eligible.isNotEmpty()) {
            lastDetectionFrameMs = nowMs
        }

        // 5. Find the highest-priority zone that meets persistence AND cooldown.
        var chosenZone = DirectionZone.UNKNOWN
        var chosenCommand: HapticCommand? = null

        for (zone in zonePriority) {
            val count = consecutiveFrames[zone] ?: 0
            if (count < config.requiredConsecutiveFrames) continue

            // Check per-zone cooldown.
            val lastSent = lastSentAtMs[zone]
            if (lastSent != null && nowMs - lastSent < config.perZoneCooldownMs) continue

            // This zone qualifies — map to a command.
            val command = HapticCommandMapper.forZone(zone)
            if (command != null && command != HapticCommand.BACK) {
                chosenZone = zone
                chosenCommand = command
                break // Front-first priority: take the first qualifying zone.
            }
        }

        // 6. Record the send timestamp if we're firing.
        if (chosenCommand != null) {
            lastSentAtMs[chosenZone] = nowMs
            commandsSent++
            // Fired command for zone.
        }

        return PolicyResult(
            command = chosenCommand,
            activeZone = chosenZone,
            zoneCounts = consecutiveFrames.toMap(),
            commandsSentCount = commandsSent,
        )
    }

    /** Update the policy configuration at runtime (e.g., from Settings). */
    @Synchronized
    fun updateConfig(newConfig: HapticPolicyConfig) {
        config = newConfig
        // Config updated.
    }

    /** Reset all tracking state (e.g., on navigation stop). */
    @Synchronized
    fun reset() = resetInternal()

    private fun resetInternal() {
        consecutiveFrames.clear()
        lastSentAtMs.clear()
        lastDetectionFrameMs = Long.MIN_VALUE
        commandsSent = 0
    }

}
