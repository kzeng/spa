package com.seamlesspassage.spa.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seamlesspassage.spa.services.FaceAuthService
import com.seamlesspassage.spa.services.FaceAuthResult
import com.seamlesspassage.spa.services.GateService
import com.seamlesspassage.spa.services.GateResult
import com.seamlesspassage.spa.services.InventoryResult
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
    
    // // 模拟的通道服务，实际使用时替换为真实实现， Kzeng 2026-02-09
    // private val gate = GateService(SimulatedRfidChannelService())

    private val gate = GateService()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    private val _hasCameraPermission = MutableStateFlow(false)
    val hasCameraPermission: StateFlow<Boolean> = _hasCameraPermission

    /**
     * APP 启动时调用：预先连接并初始化通道设备。
     */
    fun initChannelOnStart() {
        viewModelScope.launch {
            when (val r = gate.initChannel()) {
                is GateResult.Opened -> {
                    // 通道就绪，不改变当前 UI 状态
                }
                is GateResult.Failed -> {
                    // 记录错误，让底部状态区和 TTS 能提示“系统错误/通道异常”
                    _uiState.value = UiState.Error(r.message)
                    // 稍后仍可自动回到 Idle，避免卡死在错误界面
                    delay(3000)
                    _uiState.value = UiState.Idle
                }
            }
        }
    }

    fun onFaceDetected(faceImageBase64: String) {
        if (_uiState.value is UiState.Idle || _uiState.value is UiState.Error) {
            _uiState.value = UiState.FaceDetected
            viewModelScope.launch {
                when (val auth = faceAuth.authenticate(faceImageBase64)) {
                    is FaceAuthResult.Success -> {
                        // 1. 一号门开门 + 盘点标签
                        when (val inv = gate.inventoryAfterEntry()) {
                            is InventoryResult.Success -> {
                                val tags = inv.tags
                                // tags 不为空才发起 sip2_check
                                if (tags.isEmpty()) {
                                    gate.openEntryDoor()
                                    // 认证已通过，但未读到书，视为借书失败
                                    _uiState.value = UiState.BorrowDenied
                                } else {
                                    val check = sip2.check(auth.userId, tags)
                                    when (check) {
                                        is Sip2Result.Allowed -> {
                                            when (val g = gate.openExitDoor()) {
                                                is GateResult.Opened -> _uiState.value = UiState.AuthSuccess(auth.userId)
                                                is GateResult.Failed -> _uiState.value = UiState.Error(g.message)
                                            }
                                        }
                                        is Sip2Result.Denied -> {
                                            // 借书失败：开一号门退回
                                            gate.openEntryDoor()
                                            _uiState.value = UiState.BorrowDenied
                                        }
                                    }
                                }
                            }
                            InventoryResult.NoTags -> {
                                // 未读到任何标签，视为失败：开一号门退回
                                gate.openEntryDoor()
                                _uiState.value = UiState.BorrowDenied
                            }
                            is InventoryResult.Error -> {
                                // 盘点错误，同样开一号门退回，前端显示错误信息
                                gate.openEntryDoor()
                                _uiState.value = UiState.Error(inv.message)
                            }
                        }
                    }
                    is FaceAuthResult.Failure -> _uiState.value = UiState.AuthDenied
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

    fun setCameraPermissionGranted(granted: Boolean) {
        _hasCameraPermission.value = granted
    }
}
