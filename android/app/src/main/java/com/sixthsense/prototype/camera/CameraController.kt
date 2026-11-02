package com.sixthsense.prototype.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed interface CameraState {
    data object Idle : CameraState
    data object Starting : CameraState
    data object Running : CameraState
    data class Error(val message: String) : CameraState
}

class CameraController {
    private val _state = MutableStateFlow<CameraState>(CameraState.Idle)
    val state: StateFlow<CameraState> = _state.asStateFlow()
    private var provider: ProcessCameraProvider? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun start(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer,
    ) {
        _state.value = CameraState.Starting
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer) }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onSuccess { _state.value = CameraState.Running }
                .onFailure { _state.value = CameraState.Error("Rear camera could not start: ${it.message ?: "unknown error"}") }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        provider?.unbindAll()
        _state.value = CameraState.Idle
    }

    fun close() {
        stop()
        analysisExecutor.shutdown()
    }
}
