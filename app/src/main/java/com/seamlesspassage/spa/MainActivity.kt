package com.seamlesspassage.spa

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.seamlesspassage.spa.camera.FrontCameraPreview
import com.seamlesspassage.spa.speech.AudioPromptManager
import com.seamlesspassage.spa.ui.AppViewModel
import com.seamlesspassage.spa.ui.components.BottomStatusPanel
import com.seamlesspassage.spa.ui.components.StatusType
import com.seamlesspassage.spa.ui.state.UiState

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private var audio: AudioPromptManager? = null

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.setError("缺少摄像头权限")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        audio = AudioPromptManager(this)

        cameraPermission.launch(Manifest.permission.CAMERA)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SpaScreen(viewModel = viewModel, speak = { audio?.speak(it) })
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audio?.shutdown()
    }
}

@Composable
fun SpaScreen(viewModel: AppViewModel, speak: (String) -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val ctx = LocalContext.current

    // trigger mocked detection periodically when idle
    LaunchedEffect(uiState) {
        if (uiState is UiState.Idle) {
            // Periodic mock: assume a face appears within 1s
            kotlinx.coroutines.delay(1000)
            viewModel.onFaceDetected("mock-face")
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 1) Full-screen front camera preview
        FrontCameraPreview(modifier = Modifier.fillMaxSize())

        // 2) Face overlay (only when FaceDetected)
        if (uiState is UiState.FaceDetected) {
            FaceOverlay(modifier = Modifier.fillMaxSize())
        }

        // 3) Bottom 25% status area (贴底，不留空)
        val status = when (uiState) {
            is UiState.Idle -> StatusUi(
                title = "请正对摄像头以完成识别",
                sub = "无须操作，识别完成后自动检査权限并开门",
                type = StatusType.Info
            )
            is UiState.FaceDetected -> StatusUi(
                title = "正在认证身份…",
                sub = "请保持正对镜头",
                type = StatusType.Info
            )
            is UiState.AuthSuccess -> StatusUi(
                title = "身份验证成功 · 权限检査通过 · 门已开启",
                sub = "欢迎进入",
                type = StatusType.Success
            )
            is UiState.Denied -> StatusUi(
                title = "认证失败或权限不足",
                sub = "请联系管理员",
                type = StatusType.Error
            )
            is UiState.Error -> StatusUi(
                title = "系统错误",
                sub = (uiState as UiState.Error).message,
                type = StatusType.Error
            )
        }

        // Speak key messages
        LaunchedEffect(status.title) {
            when (uiState) {
                is UiState.AuthSuccess -> speak("身份验证成功，权限通过，门已开启")
                is UiState.Denied -> speak("认证失败或权限不足")
                is UiState.Error -> speak("系统错误，请稍后重试")
                else -> {}
            }
        }

        BottomStatusPanel(
            modifier = Modifier.align(Alignment.BottomCenter),
            statusTitle = status.title,
            statusSubtitle = status.sub,
            statusType = status.type
        )
    }
}

private data class StatusUi(val title: String, val sub: String?, val type: StatusType)

@Composable
private fun FaceOverlay(modifier: Modifier = Modifier) {
    val w = LocalConfiguration.current.screenWidthDp
    val h = LocalConfiguration.current.screenHeightDp
    Canvas(modifier = modifier) {
        val rect = Rect(
            left = size.width * 0.25f,
            top = size.height * 0.18f,
            right = size.width * 0.75f,
            bottom = size.height * 0.58f
        )
        drawRect(color = Color.Transparent)
        drawRect(color = Color(0x80FFFFFF), style = Stroke(width = 4f), topLeft = rect.topLeft, size = rect.size)
        // simple grid
        val cols = 3
        val rows = 3
        for (i in 1 until cols) {
            val x = rect.left + rect.width * (i / cols.toFloat())
            drawLine(Color(0x66FFFFFF), start = androidx.compose.ui.geometry.Offset(x, rect.top), end = androidx.compose.ui.geometry.Offset(x, rect.bottom), strokeWidth = 2f)
        }
        for (j in 1 until rows) {
            val y = rect.top + rect.height * (j / rows.toFloat())
            drawLine(Color(0x66FFFFFF), start = androidx.compose.ui.geometry.Offset(rect.left, y), end = androidx.compose.ui.geometry.Offset(rect.right, y), strokeWidth = 2f)
        }
    }
}
