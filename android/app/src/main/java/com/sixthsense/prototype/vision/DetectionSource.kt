package com.sixthsense.prototype.vision

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

data class DetectionResult(
    val label: String,
    val confidence: Float,
    val normalizedHorizontalCenter: Float,
    /** Bounding-box area relative to the full frame area, 0..1. Larger = closer. */
    val boxAreaRatio: Float,
)

/** Allows a real on-device detector to replace the Phase 1 debug source. */
interface DetectionSource {
    fun latestDetections(): List<DetectionResult>
}

class DebugDetectionSource : DetectionSource {
    override fun latestDetections(): List<DetectionResult> = emptyList()
}

/**
 * Real-time, on-device ML Kit detector for CameraX frames.
 *
 * ML Kit's bundled default classifier recognizes coarse categories only; its
 * labels are useful debug information, not a complete obstacle taxonomy.
 *
 * Multi-object detection is enabled so the device can track obstacles in all
 * three zones simultaneously. Unclassified objects receive a 0.5 confidence
 * floor — for assistive navigation, an unrecognized object is still an obstacle.
 */
class MlKitObjectAnalyzer(
    private val onDetections: (List<DetectionResult>) -> Unit,
    private val onError: (Exception) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val processing = AtomicBoolean(false)
    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableClassification()
            .enableMultipleObjects()   // detect obstacles in all zones
            .build()
    )

    override fun analyze(imageProxy: ImageProxy) {
        // KEEP_ONLY_LATEST normally prevents a queue. This guard also ensures
        // each ImageProxy is closed if ML Kit is still processing the prior one.
        if (!processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            processing.set(false)
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val normalizedWidth = if (rotation == 90 || rotation == 270) {
            imageProxy.height.toFloat()
        } else {
            imageProxy.width.toFloat()
        }
        val normalizedHeight = if (rotation == 90 || rotation == 270) {
            imageProxy.width.toFloat()
        } else {
            imageProxy.height.toFloat()
        }
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        detector.process(input)
            .addOnSuccessListener { objects ->
                val frameArea = normalizedWidth * normalizedHeight
                val detections = objects.mapNotNull { detected ->
                    val box = detected.boundingBox
                    if (normalizedWidth <= 0f) return@mapNotNull null
                    val label = detected.labels.maxByOrNull { it.confidence }
                    DetectionResult(
                        // Unclassified objects are still potential obstacles.
                        label = label?.text ?: "Object",
                        // Floor unclassified at 0.5 so they pass the default
                        // confidence threshold (0.40). The user can raise the
                        // threshold in Settings to filter them out if desired.
                        confidence = label?.confidence ?: 0.5f,
                        normalizedHorizontalCenter = ((box.left + box.right) / 2f / normalizedWidth)
                            .coerceIn(0f, 1f),
                        boxAreaRatio = if (frameArea > 0f) {
                            (box.width().toFloat() * box.height().toFloat() / frameArea)
                                .coerceIn(0f, 1f)
                        } else 0f,
                    )
                }
                Log.d(TAG, "ML Kit: ${detections.size} object(s) ${detections.joinToString { "${it.label}(${(it.confidence*100).toInt()}% zone=${it.normalizedHorizontalCenter} area=${(it.boxAreaRatio*100).toInt()}%)" }}")
                onDetections(detections)
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "ML Kit detection failed", error)
                onError(error)
            }
            .addOnCompleteListener {
                processing.set(false)
                imageProxy.close()
            }
    }

    override fun close() {
        detector.close()
    }

    companion object {
        private const val TAG = "MlKitObjectAnalyzer"
    }
}
