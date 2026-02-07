package com.seamlesspassage.spa.ui.state

sealed class UiState {
    object Idle : UiState()
    object FaceDetected : UiState()
    data class AuthSuccess(val userId: String) : UiState()
    /** 人脸认证未通过。*/
    object AuthDenied : UiState()

    /** 人脸认证通过，但借书流程（盘点/SIP2 检查）失败。*/
    object BorrowDenied : UiState()
    data class Error(val message: String) : UiState()
}
