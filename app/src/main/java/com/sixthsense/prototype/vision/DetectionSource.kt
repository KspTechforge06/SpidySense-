package com.sixthsense.prototype.vision

data class DetectionResult(
    val label: String,
    val confidence: Float,
    val normalizedHorizontalCenter: Float,
)

/** Allows a real on-device detector to replace the Phase 1 debug source. */
interface DetectionSource {
    fun latestDetections(): List<DetectionResult>
}

class DebugDetectionSource : DetectionSource {
    override fun latestDetections(): List<DetectionResult> = emptyList()
}
