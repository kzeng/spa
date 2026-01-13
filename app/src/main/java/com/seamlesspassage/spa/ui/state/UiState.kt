package com.seamlesspassage.spa.ui.state

sealed class UiState {
    object Idle : UiState()
    data class FaceDetected(val faceId: String = "face") : UiState()
    data class AuthSuccess(val userId: String) : UiState()
    object Denied : UiState()
    data class Error(val message: String) : UiState()
}
