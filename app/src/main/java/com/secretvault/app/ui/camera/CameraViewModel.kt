package com.secretvault.app.ui.camera

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import com.secretvault.app.core.camera.CameraLensOption
import com.secretvault.app.core.camera.CameraMode
import com.secretvault.app.core.camera.FlashMode
import com.secretvault.app.core.camera.LensFacing
import com.secretvault.app.core.camera.VideoMode
import com.secretvault.app.core.camera.VideoOrientation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CameraUiState(
    val cameraMode: CameraMode = CameraMode.PHOTO,
    val flashMode: FlashMode = FlashMode.AUTO,
    val lensFacing: LensFacing = LensFacing.BACK,
    val autoFaceBlur: Boolean = true,
    val recordAudio: Boolean = true,
    val videoMode: VideoMode = VideoMode.UHD_30,
    val videoOrientation: VideoOrientation = VideoOrientation.PORTRAIT,
    val supportedVideoModes: List<VideoMode> = emptyList(),
    val rearLensOptions: List<CameraLensOption> = listOf(CameraLensOption(null, "1×")),
    val selectedRearLensId: String? = null,
    val availableFacings: List<LensFacing> = emptyList(),
    val isRecording: Boolean = false,
    val recordingDurationSeconds: Int = 0,
    val focusPoint: Offset? = null,
    val isSaving: Boolean = false
)

class CameraViewModel(initialVideoMode: VideoMode = VideoMode.UHD_30) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState(videoMode = initialVideoMode))
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    fun toggleFlashMode() {
        val nextMode = when (_uiState.value.flashMode) {
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
            FlashMode.OFF -> FlashMode.AUTO
        }
        _uiState.value = _uiState.value.copy(flashMode = nextMode)
    }

    fun setFlashMode(mode: FlashMode) {
        _uiState.value = _uiState.value.copy(flashMode = mode)
    }

    fun toggleFaceBlur() {
        _uiState.value = _uiState.value.copy(autoFaceBlur = !_uiState.value.autoFaceBlur)
    }

    fun toggleRecordAudio() {
        setRecordAudio(!_uiState.value.recordAudio)
    }

    fun setRecordAudio(enabled: Boolean): Boolean {
        if (_uiState.value.isRecording || _uiState.value.isSaving) return false
        _uiState.value = _uiState.value.copy(recordAudio = enabled)
        return true
    }

    fun toggleLensFacing() {
        if (_uiState.value.isRecording) return
        val nextLens = if (_uiState.value.lensFacing == LensFacing.BACK) {
            LensFacing.FRONT
        } else {
            LensFacing.BACK
        }
        _uiState.value = _uiState.value.copy(lensFacing = nextLens)
    }

    fun setCameraMode(mode: CameraMode) {
        if (_uiState.value.isRecording) return
        _uiState.value = _uiState.value.copy(cameraMode = mode)
    }

    fun setVideoMode(mode: VideoMode) {
        if (_uiState.value.isRecording || mode !in _uiState.value.supportedVideoModes) return
        _uiState.value = _uiState.value.copy(videoMode = mode)
    }

    fun setAvailableFacings(facings: List<LensFacing>) {
        _uiState.value = _uiState.value.copy(availableFacings = facings)
    }

    fun selectStreamCamera(id: String): Boolean {
        val state = _uiState.value
        if (state.isRecording) return false
        if (id == "front" && LensFacing.FRONT in state.availableFacings) {
            _uiState.value = state.copy(lensFacing = LensFacing.FRONT)
            return true
        }
        val rear = state.rearLensOptions.firstOrNull { "back/${it.id ?: "default"}" == id }
            ?: return false
        if (LensFacing.BACK !in state.availableFacings) return false
        _uiState.value = state.copy(lensFacing = LensFacing.BACK, selectedRearLensId = rear.id)
        return true
    }

    fun setVideoOrientation(orientation: VideoOrientation) {
        if (_uiState.value.isRecording) return
        _uiState.value = _uiState.value.copy(videoOrientation = orientation)
    }

    fun setSupportedVideoModes(modes: List<VideoMode>, selectedMode: VideoMode) {
        _uiState.value = _uiState.value.copy(supportedVideoModes = modes, videoMode = selectedMode)
    }

    fun setRearLensOptions(options: List<CameraLensOption>) {
        val available = options.ifEmpty { listOf(CameraLensOption(null, "1×")) }
        val selected = _uiState.value.selectedRearLensId
            ?.takeIf { id -> available.any { it.id == id } }
        _uiState.value = _uiState.value.copy(rearLensOptions = available, selectedRearLensId = selected)
    }

    fun selectRearLens(physicalCameraId: String?) {
        if (_uiState.value.isRecording || _uiState.value.rearLensOptions.none { it.id == physicalCameraId }) return
        _uiState.value = _uiState.value.copy(selectedRearLensId = physicalCameraId)
    }

    fun setFocusPoint(offset: Offset?) {
        _uiState.value = _uiState.value.copy(focusPoint = offset)
    }

    fun setRecordingState(isRecording: Boolean, durationSeconds: Int = 0) {
        _uiState.value = _uiState.value.copy(
            isRecording = isRecording,
            recordingDurationSeconds = durationSeconds
        )
    }
}
