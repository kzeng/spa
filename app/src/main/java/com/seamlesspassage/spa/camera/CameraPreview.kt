package com.seamlesspassage.spa.camera

import android.content.Context
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
import java.util.concurrent.Executors
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark

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
                // Trigger higher-level pipeline once a face is present
                onFaceDetected("mlkit-face")

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
                    val steps = 8
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
                    val steps = 8
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
                if (mouthBottom != null) {
                    val chin = NormalizedPoint(mouthBottom.x, chinY)
                    val leftEar = base[FaceLandmark.LEFT_EAR]
                    val rightEar = base[FaceLandmark.RIGHT_EAR]
                    val steps = 10
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

                onFaceOverlay(
                    FaceOverlayData(
                        bounds = bounds,
                        landmarks = points,
                        imageAspect = if (imageHeight != 0f) imageWidth / imageHeight else 1f,
                    )
                )
            } else {
                onFaceOverlay(null)
            }
        }
        .addOnFailureListener { e ->
            Log.e("CameraPreview", "Face detection failed", e)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
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

