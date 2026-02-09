package com.seamlesspassage.spa.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

@Composable
fun FrontCameraPreview(
    modifier: Modifier = Modifier,
    isCameraPermissionGranted: Boolean,
    onFaceDetected: (String) -> Unit = {},
    onFaceOverlay: (FaceOverlayData?) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)

    LaunchedEffect(isCameraPermissionGranted) {
        if (isCameraPermissionGranted) {
            startCamera(context, lifecycleOwner, previewView, onFaceDetected, onFaceOverlay)
        } else {
            onFaceOverlay(null)
        }
    }
}

private fun startCamera(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    onFaceDetected: (String) -> Unit,
    onFaceOverlay: (FaceOverlayData?) -> Unit,
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val frontCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        val backCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // ML Kit face detector (fast mode, with landmarks)
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        val detector = FaceDetection.getClient(options)

        val analysisUseCase = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val analysisExecutor = Executors.newSingleThreadExecutor()

        analysisUseCase.setAnalyzer(analysisExecutor) { imageProxy: ImageProxy ->
            processImageProxy(detector, imageProxy, onFaceDetected, onFaceOverlay)
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, frontCameraSelector, preview, analysisUseCase)
        } catch (frontExc: Exception) {
            Log.e("CameraPreview", "Front camera start failed, trying back camera", frontExc)
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, backCameraSelector, preview, analysisUseCase)
            } catch (backExc: Exception) {
                Log.e("CameraPreview", "Back camera start also failed", backExc)
            }
        }
    }, ContextCompat.getMainExecutor(context))
}

private const val STABLE_FRAMES_REQUIRED = 2
private const val IOU_THRESHOLD = 0.7f
private const val NO_FACE_FRAMES_THRESHOLD = 60  // 连续60帧无人脸才清除overlay (约2秒)

@Volatile
private var lastFaceBounds: NormalizedRect? = null

@Volatile
private var stableFrameCount: Int = 0

@Volatile
private var hasTriggeredForCurrentFace: Boolean = false

@Volatile
private var noFaceFrameCount: Int = 0

@Volatile
private var lastOverlayData: FaceOverlayData? = null

private fun processImageProxy(
    detector: FaceDetector,
    imageProxy: ImageProxy,
    onFaceDetected: (String) -> Unit,
    onFaceOverlay: (FaceOverlayData?) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val rotation = imageProxy.imageInfo.rotationDegrees
    val image = InputImage.fromMediaImage(mediaImage, rotation)

    // ML Kit 的坐标是在旋转后图像坐标系下，这里用原始 mediaImage + rotation 计算实际宽高
    val isRotated = rotation == 90 || rotation == 270
    val imageWidth: Float
    val imageHeight: Float
    if (isRotated) {
        imageWidth = mediaImage.height.toFloat()
        imageHeight = mediaImage.width.toFloat()
    } else {
        imageWidth = mediaImage.width.toFloat()
        imageHeight = mediaImage.height.toFloat()
    }

    detector.process(image)
        .addOnSuccessListener { faces ->
            if (faces.isNotEmpty()) {
                // 检测到人脸，重置无人脸计数
                noFaceFrameCount = 0
                
                val primary = faces.first()

                val box = primary.boundingBox
                val bounds = NormalizedRect(
                    left = (box.left / imageWidth).coerceIn(0f, 1f),
                    top = (box.top / imageHeight).coerceIn(0f, 1f),
                    right = (box.right / imageWidth).coerceIn(0f, 1f),
                    bottom = (box.bottom / imageHeight).coerceIn(0f, 1f)
                )

                val landmarkTypes = listOf(
                    FaceLandmark.LEFT_EYE,
                    FaceLandmark.RIGHT_EYE,
                    FaceLandmark.NOSE_BASE,
                    FaceLandmark.MOUTH_LEFT,
                    FaceLandmark.MOUTH_RIGHT,
                    FaceLandmark.MOUTH_BOTTOM,
                    FaceLandmark.LEFT_EAR,
                    FaceLandmark.RIGHT_EAR,
                    FaceLandmark.LEFT_CHEEK,
                    FaceLandmark.RIGHT_CHEEK
                )

                val base = mutableMapOf<Int, NormalizedPoint>()
                for (type in landmarkTypes) {
                    val landmark = primary.getLandmark(type) ?: continue
                    val pos = landmark.position
                    base[type] = NormalizedPoint(
                        x = (pos.x / imageWidth).coerceIn(0f, 1f),
                        y = (pos.y / imageHeight).coerceIn(0f, 1f)
                    )
                }

                // 预分配足够的容量以提高性能（约158个点）
                val points = mutableListOf<NormalizedPoint>()
                points.addAll(base.values)

                // derive extra points for forehead / brows / nose bridge / jaw line
                val centerX = (bounds.left + bounds.right) / 2f
                val faceHeight = (bounds.bottom - bounds.top)
                val faceWidth = (bounds.right - bounds.left)

                // forehead center
                val foreheadY = bounds.top + faceHeight * 0.10f
                points.add(NormalizedPoint(centerX, foreheadY))

                // eyebrow line: above eyes between cheeks and center（加密一些点）
                val leftEye = base[FaceLandmark.LEFT_EYE]
                val rightEye = base[FaceLandmark.RIGHT_EYE]
                val leftCheek = base[FaceLandmark.LEFT_CHEEK]
                val rightCheek = base[FaceLandmark.RIGHT_CHEEK]
                if (leftEye != null && rightEye != null) {
                    val browY = (leftEye.y + rightEye.y) / 2f - faceHeight * 0.03f
                    val browLeftX = leftCheek?.x ?: (bounds.left + faceWidth * 0.2f)
                    val browRightX = rightCheek?.x ?: (bounds.right - faceWidth * 0.2f)
                    val steps = 20  // 从8增加到20，获得更平滑的眉毛线
                    for (i in 0..steps) {
                        val t = i / steps.toFloat()
                        val x = browLeftX + (browRightX - browLeftX) * t
                        points.add(NormalizedPoint(x, browY))
                    }
                }

                // nose bridge: from between eyes down to nose base（加密一些点）
                val nose = base[FaceLandmark.NOSE_BASE]
                if (leftEye != null && rightEye != null && nose != null) {
                    val eyeCenter = NormalizedPoint(
                        x = (leftEye.x + rightEye.x) / 2f,
                        y = (leftEye.y + rightEye.y) / 2f
                    )
                    val steps = 15  // 从8增加到15，获得更平滑的鼻梁线
                    for (i in 0..steps) {
                        val t = i / steps.toFloat()
                        val x = eyeCenter.x + (nose.x - eyeCenter.x) * t
                        val y = eyeCenter.y + (nose.y - eyeCenter.y) * t
                        points.add(NormalizedPoint(x, y))
                    }
                }

                // jaw line: interpolate from each ear to chin（加密一些点）
                val mouthBottom = base[FaceLandmark.MOUTH_BOTTOM]
                val chinY = bounds.bottom - faceHeight * 0.03f
                val leftEar = base[FaceLandmark.LEFT_EAR]
                val rightEar = base[FaceLandmark.RIGHT_EAR]
                if (mouthBottom != null) {
                    val chin = NormalizedPoint(mouthBottom.x, chinY)
                    val steps = 25  // 从10增加到25，获得更平滑的下巴线
                    if (leftEar != null) {
                        for (i in 0..steps) {
                            val t = i / steps.toFloat()
                            val x = leftEar.x + (chin.x - leftEar.x) * t
                            val y = leftEar.y + (chin.y - leftEar.y) * t
                            points.add(NormalizedPoint(x, y))
                        }
                    }
                    if (rightEar != null) {
                        for (i in 0..steps) {
                            val t = i / steps.toFloat()
                            val x = rightEar.x + (chin.x - rightEar.x) * t
                            val y = rightEar.y + (chin.y - rightEar.y) * t
                            points.add(NormalizedPoint(x, y))
                        }
                    }
                }

                // 眼睛轮廓：为左右眼添加圆形轮廓点
                if (leftEye != null) {
                    val eyeRadius = faceWidth * 0.05f
                    val eyeSteps = 12  // 每个眼睛12个点
                    for (i in 0 until eyeSteps) {
                        val angle = 2f * Math.PI.toFloat() * i / eyeSteps
                        val x = leftEye.x + eyeRadius * kotlin.math.cos(angle)
                        val y = leftEye.y + eyeRadius * kotlin.math.sin(angle)
                        points.add(NormalizedPoint(x, y))
                    }
                }
                if (rightEye != null) {
                    val eyeRadius = faceWidth * 0.05f
                    val eyeSteps = 12  // 每个眼睛12个点
                    for (i in 0 until eyeSteps) {
                        val angle = 2f * Math.PI.toFloat() * i / eyeSteps
                        val x = rightEye.x + eyeRadius * kotlin.math.cos(angle)
                        val y = rightEye.y + eyeRadius * kotlin.math.sin(angle)
                        points.add(NormalizedPoint(x, y))
                    }
                }

                // 嘴唇轮廓：基于嘴角和嘴唇底部点
                val mouthLeft = base[FaceLandmark.MOUTH_LEFT]
                val mouthRight = base[FaceLandmark.MOUTH_RIGHT]
                if (mouthLeft != null && mouthRight != null && mouthBottom != null) {
                    val mouthWidth = mouthRight.x - mouthLeft.x
                    val mouthHeight = faceHeight * 0.08f
                    val lipSteps = 16  // 嘴唇16个点
                    
                    // 上嘴唇（半椭圆）
                    for (i in 0 until lipSteps / 2) {
                        val t = i.toFloat() / (lipSteps / 2 - 1)
                        val angle = Math.PI.toFloat() * t  // 0到π
                        val x = mouthLeft.x + mouthWidth * t
                        val y = mouthBottom.y - mouthHeight * kotlin.math.sin(angle) * 0.7f
                        points.add(NormalizedPoint(x, y))
                    }
                    
                    // 下嘴唇（半椭圆）
                    for (i in 0 until lipSteps / 2) {
                        val t = i.toFloat() / (lipSteps / 2 - 1)
                        val angle = Math.PI.toFloat() * t  // 0到π
                        val x = mouthRight.x - mouthWidth * t
                        val y = mouthBottom.y + mouthHeight * kotlin.math.sin(angle) * 0.3f
                        points.add(NormalizedPoint(x, y))
                    }
                }

                // 脸颊轮廓：连接耳朵到下巴的额外点
                if (leftCheek != null && leftEar != null && mouthBottom != null) {
                    val cheekSteps = 8
                    for (i in 0..cheekSteps) {
                        val t = i / cheekSteps.toFloat()
                        val x = leftEar.x + (leftCheek.x - leftEar.x) * t
                        val y = leftEar.y + (mouthBottom.y - leftEar.y) * t * 0.7f
                        points.add(NormalizedPoint(x, y))
                    }
                }
                if (rightCheek != null && rightEar != null && mouthBottom != null) {
                    val cheekSteps = 8
                    for (i in 0..cheekSteps) {
                        val t = i / cheekSteps.toFloat()
                        val x = rightEar.x + (rightCheek.x - rightEar.x) * t
                        val y = rightEar.y + (mouthBottom.y - rightEar.y) * t * 0.7f
                        points.add(NormalizedPoint(x, y))
                    }
                }

                val overlayData = FaceOverlayData(
                    bounds = bounds,
                    landmarks = points,
                    imageAspect = if (imageHeight != 0f) imageWidth / imageHeight else 1f,
                )
                
                // 保存并显示overlay
                lastOverlayData = overlayData
                onFaceOverlay(overlayData)

                val previousBounds = lastFaceBounds
                if (previousBounds == null) {
                    lastFaceBounds = bounds
                    stableFrameCount = 1
                } else {
                    val iou = intersectionOverUnion(previousBounds, bounds)
                    if (iou >= IOU_THRESHOLD) {
                        stableFrameCount += 1
                    } else {
                        lastFaceBounds = bounds
                        stableFrameCount = 1
                        hasTriggeredForCurrentFace = false
                    }
                }

                if (!hasTriggeredForCurrentFace && stableFrameCount >= STABLE_FRAMES_REQUIRED) {
                    try {
                        val base64 = imageProxyToBase64(imageProxy)
                        onFaceDetected(base64)
                        hasTriggeredForCurrentFace = true
                    } catch (e: Exception) {
                        Log.e("CameraPreview", "Failed to encode face frame", e)
                    }
                }
            } else {
                // 没有检测到人脸，增加计数
                noFaceFrameCount += 1
                
                // 只有连续多帧无人脸才清除overlay，保持最后一次的显示
                if (noFaceFrameCount >= NO_FACE_FRAMES_THRESHOLD) {
                    onFaceOverlay(null)
                    lastFaceBounds = null
                    stableFrameCount = 0
                    hasTriggeredForCurrentFace = false
                    lastOverlayData = null
                } else {
                    // 在缓冲期内，继续显示上一次的overlay
                    if (lastOverlayData != null) {
                        onFaceOverlay(lastOverlayData)
                    }
                }
            }
        }
        .addOnFailureListener { e ->
            Log.e("CameraPreview", "Face detection failed", e)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

private fun intersectionOverUnion(a: NormalizedRect, b: NormalizedRect): Float {
    val interLeft = maxOf(a.left, b.left)
    val interTop = maxOf(a.top, b.top)
    val interRight = minOf(a.right, b.right)
    val interBottom = minOf(a.bottom, b.bottom)

    val interWidth = (interRight - interLeft).coerceAtLeast(0f)
    val interHeight = (interBottom - interTop).coerceAtLeast(0f)
    val interArea = interWidth * interHeight

    if (interArea <= 0f) return 0f

    val areaA = (a.right - a.left).coerceAtLeast(0f) * (a.bottom - a.top).coerceAtLeast(0f)
    val areaB = (b.right - b.left).coerceAtLeast(0f) * (b.bottom - b.top).coerceAtLeast(0f)
    if (areaA <= 0f || areaB <= 0f) return 0f

    val unionArea = areaA + areaB - interArea
    return if (unionArea > 0f) interArea / unionArea else 0f
}

private fun imageProxyToBase64(imageProxy: ImageProxy): String {
    val nv21 = yuv420ToNv21(imageProxy)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
    val stream = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 85, stream)
    val jpegBytes = stream.toByteArray()
    return Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
}

private fun yuv420ToNv21(image: ImageProxy): ByteArray {
    val width = image.width
    val height = image.height

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val yBuffer: ByteBuffer = yPlane.buffer
    val uBuffer: ByteBuffer = uPlane.buffer
    val vBuffer: ByteBuffer = vPlane.buffer

    val nv21 = ByteArray(width * height * 3 / 2)

    var outputIndex = 0
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    for (row in 0 until height) {
        val rowStart = row * yRowStride
        for (col in 0 until width) {
            val index = rowStart + col * yPixelStride
            nv21[outputIndex++] = yBuffer.get(index)
        }
    }

    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride
    val halfHeight = height / 2
    val halfWidth = width / 2

    for (row in 0 until halfHeight) {
        val rowStart = row * uvRowStride
        for (col in 0 until halfWidth) {
            val colOffset = col * uvPixelStride
            val uIndex = rowStart + colOffset
            val vIndex = rowStart + colOffset
            nv21[outputIndex++] = vBuffer.get(vIndex)
            nv21[outputIndex++] = uBuffer.get(uIndex)
        }
    }

    return nv21
}

data class NormalizedPoint(val x: Float, val y: Float)

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class FaceOverlayData(
    val bounds: NormalizedRect,
    val landmarks: List<NormalizedPoint>,
    // 相机图像宽高比，用于在预览裁剪后做坐标对齐
    val imageAspect: Float,
)

