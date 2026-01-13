package com.seamlesspassage.spa.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seamlesspassage.spa.services.FaceAuthService
import com.seamlesspassage.spa.services.FaceAuthResult
import com.seamlesspassage.spa.services.GateService
import com.seamlesspassage.spa.services.GateResult
import com.seamlesspassage.spa.services.Sip2Service
import com.seamlesspassage.spa.services.Sip2Result
import com.seamlesspassage.spa.ui.state.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppViewModel : ViewModel() {
    private val faceAuth = FaceAuthService()
    private val sip2 = Sip2Service()
    private val gate = GateService()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    fun onFaceDetected(faceId: String) {
        if (_uiState.value is UiState.Idle || _uiState.value is UiState.Error) {
            _uiState.value = UiState.FaceDetected(faceId)
            viewModelScope.launch {
                // Simulate debounce to ensure stable face
                delay(300)
                when (val auth = faceAuth.authenticate(faceId)) {
                    is FaceAuthResult.Success -> {
                        val check = sip2.check(auth.userId)
                        when (check) {
                            is Sip2Result.Allowed -> {
                                when (val g = gate.open()) {
                                    is GateResult.Opened -> _uiState.value = UiState.AuthSuccess(auth.userId)
                                    is GateResult.Failed -> _uiState.value = UiState.Error(g.message)
                                }
                            }
                            is Sip2Result.Denied -> _uiState.value = UiState.Denied
                        }
                    }
                    is FaceAuthResult.Failure -> _uiState.value = UiState.Denied
                }
                // auto reset back to idle after few seconds
                delay(3000)
                _uiState.value = UiState.Idle
            }
        }
    }

    fun setError(message: String) {
        _uiState.value = UiState.Error(message)
    }
}
