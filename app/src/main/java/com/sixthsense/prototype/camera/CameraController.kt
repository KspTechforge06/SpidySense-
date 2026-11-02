package com.sixthsense.prototype.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun start(context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        _state.value = CameraState.Starting
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            }.onSuccess { _state.value = CameraState.Running }
                .onFailure { _state.value = CameraState.Error("Rear camera could not start: ${it.message ?: "unknown error"}") }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        provider?.unbindAll()
        _state.value = CameraState.Idle
    }
}
