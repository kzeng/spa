package com.seamlesspassage.spa.ui.state

sealed class UiState {
    object Idle : UiState()
    object FaceDetected : UiState()
    data class AuthSuccess(val userId: String) : UiState()
    object Denied : UiState()
    data class Error(val message: String) : UiState()
}
