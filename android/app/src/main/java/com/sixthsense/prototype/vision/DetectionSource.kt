package com.sixthsense.prototype.vision

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
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        detector.process(input)
            .addOnSuccessListener { objects ->
                val detections = objects.mapNotNull { detected ->
                    val box = detected.boundingBox
                    if (normalizedWidth <= 0f) return@mapNotNull null
                    val label = detected.labels.maxByOrNull { it.confidence }
                    DetectionResult(
                        label = label?.text ?: "Unclassified object",
                        confidence = label?.confidence ?: 0f,
                        normalizedHorizontalCenter = ((box.left + box.right) / 2f / normalizedWidth)
                            .coerceIn(0f, 1f),
                    )
                }
                onDetections(detections)
            }
            .addOnFailureListener { error -> onError(error) }
            .addOnCompleteListener {
                processing.set(false)
                imageProxy.close()
            }
    }

    override fun close() {
        detector.close()
    }
}
