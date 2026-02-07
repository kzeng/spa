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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.seamlesspassage.spa.camera.FaceOverlayData
import com.seamlesspassage.spa.camera.FrontCameraPreview
import com.seamlesspassage.spa.speech.AudioPromptManager
import com.seamlesspassage.spa.ui.AppViewModel
import com.seamlesspassage.spa.ui.components.BottomStatusPanel
import com.seamlesspassage.spa.ui.components.StatusType
import com.seamlesspassage.spa.ui.components.AdminExitDialog
import com.seamlesspassage.spa.ui.components.LongHoldHotspot
import com.seamlesspassage.spa.ui.state.UiState
import android.app.Activity

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private var audio: AudioPromptManager? = null

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setCameraPermissionGranted(granted)
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
    val hasCameraPermission by viewModel.hasCameraPermission.collectAsState()
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    var showAdminDialog by remember { mutableStateOf(false) }
    var faceOverlayData by remember { mutableStateOf<FaceOverlayData?>(null) }

    // 避免同一提示语被重复播放过多次
    var hasSpokenIdle by remember { mutableStateOf(false) }
    var hasSpokenFaceDetected by remember { mutableStateOf(false) }

    // APP 首次进入组合时，预先初始化通道设备
    LaunchedEffect(Unit) {
        viewModel.initChannelOnStart()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 1) Full-screen front camera preview (only when camera permission is granted)
        if (hasCameraPermission) {
            FrontCameraPreview(
                modifier = Modifier.fillMaxSize(),
                isCameraPermissionGranted = true,
                onFaceDetected = { faceImageBase64 ->
                    viewModel.onFaceDetected(faceImageBase64)
                },
                onFaceOverlay = { data ->
                    faceOverlayData = data
                }
            )
        }

        // 2) Face overlay (only when FaceDetected)
        if (uiState is UiState.FaceDetected && faceOverlayData != null) {
            FaceOverlay(modifier = Modifier.fillMaxSize(), data = faceOverlayData!!)
        }

        // 3) Bottom 25% status area (贴底，不留空)
        val status = when (uiState) {
            is UiState.Idle -> StatusUi(
                title = "请正对摄像头",
                sub = "您无须操作，等待完成识别",
                type = StatusType.Info
            )
            is UiState.FaceDetected -> StatusUi(
                title = "正在认证身份…",
                sub = "请保持正对镜头",
                type = StatusType.Info
            )
            is UiState.AuthSuccess -> StatusUi(
                title = "认证成功",
                sub = "请通过",
                type = StatusType.Success
            )
            is UiState.Denied -> StatusUi(
                title = "认证失败",
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
        LaunchedEffect(uiState) {
            when (uiState) {
                is UiState.Idle -> {
                    if (!hasSpokenIdle) {
                        speak("请正对摄像头，您无须操作，等待完成识别")
                        hasSpokenIdle = true
                    }
                }
                is UiState.FaceDetected -> {
                    if (!hasSpokenFaceDetected) {
                        speak("正在认证身份，请保持正对镜头")
                        hasSpokenFaceDetected = true
                    }
                }
                is UiState.AuthSuccess -> speak("认证成功，请通过")
                is UiState.Denied -> speak("认证失败")
                is UiState.Error -> speak("系统错误，请稍后重试")
            }
        }

        BottomStatusPanel(
            modifier = Modifier.align(Alignment.BottomCenter),
            statusTitle = status.title,
            statusSubtitle = status.sub,
            statusType = status.type
        )

        // Hidden admin long-press hotspot (bottom-left transparent area)
        LongHoldHotspot(
            modifier = Modifier.align(Alignment.BottomStart),
            holdMillis = 6000,
            onTriggered = { showAdminDialog = true }
        )

        if (showAdminDialog) {
            AdminExitDialog(
                onDismiss = { showAdminDialog = false },
                onConfirm = { pwd ->
                    if (pwd == "123321") {
                        showAdminDialog = false
                        activity?.finishAffinity()
                    }
                }
            )
        }
    }
}

private data class StatusUi(val title: String, val sub: String?, val type: StatusType)

@Composable
private fun FaceOverlay(modifier: Modifier = Modifier, data: FaceOverlayData) {
    Canvas(modifier = modifier) {
        val bounds = data.bounds

        // 处理 PreviewView.ScaleType.FILL_CENTER 带来的裁剪：
        // 按照相机图像宽高比和当前画布宽高比，把 [0,1] 归一化坐标映射到被居中裁剪后的坐标系
        val viewAspect = if (size.height != 0f) size.width / size.height else 1f
        val imageAspect = if (data.imageAspect > 0f) data.imageAspect else 1f

        // 细微的全局水平方向微调（>0 向右，<0 向左），可根据实测继续微调
        val horizontalOffset = -0.035f

        fun mapX(x: Float): Float {
            val base = if (viewAspect > imageAspect) {
                // 竖直方向被裁剪，水平方向完整
                x
            } else {
                // 水平方向被裁剪
                val k = imageAspect / viewAspect
                x * k + (1f - k) / 2f
            }
            val shifted = base + horizontalOffset
            return shifted.coerceIn(0f, 1f)
        }

        fun mapY(y: Float): Float {
            return if (viewAspect > imageAspect) {
                // 竖直方向被裁剪
                val k = viewAspect / imageAspect
                y * k + (1f - k) / 2f
            } else {
                // 水平方向被裁剪，竖直方向完整
                y
            }
        }

        val rect = Rect(
            left = mapX(bounds.left) * size.width,
            top = mapY(bounds.top) * size.height,
            right = mapX(bounds.right) * size.width,
            bottom = mapY(bounds.bottom) * size.height
        )

        drawRect(color = Color.Transparent)
        val contourColor = Color(0xFF00FF00)
        val gridColor = Color(0x6600FF00)

        // bright green rectangle contour from ML Kit bounding box
        drawRect(
            color = contourColor,
            topLeft = rect.topLeft,
            size = rect.size,
            style = Stroke(width = 5f)
        )

        // grid inside the detected face bounds
        val cols = 3
        val rows = 3
        for (i in 1 until cols) {
            val x = rect.left + rect.width * (i / cols.toFloat())
            drawLine(gridColor, start = Offset(x, rect.top), end = Offset(x, rect.bottom), strokeWidth = 2f)
        }
        for (j in 1 until rows) {
            val y = rect.top + rect.height * (j / rows.toFloat())
            drawLine(gridColor, start = Offset(rect.left, y), end = Offset(rect.right, y), strokeWidth = 2f)
        }

        // draw ML Kit landmark points (plus derived points) as bright green dots
        data.landmarks.forEach { p ->
            val cx = mapX(p.x) * size.width
            val cy = mapY(p.y) * size.height
            drawCircle(color = contourColor, radius = 6f, center = Offset(cx, cy))
        }
    }
}
