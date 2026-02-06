
# Camera Issues

相机权限逻辑

在 AppViewModel.kt 增加了 hasCameraPermission 状态和 setCameraPermissionGranted()。
在 MainActivity.kt 的权限回调里，调用 viewModel.setCameraPermissionGranted(granted)，并在 SpaScreen 里只在 hasCameraPermission == true 时渲染 FrontCameraPreview。
预览启动时机 + 线程

在 CameraPreview.kt：
FrontCameraPreview 新增参数 isCameraPermissionGranted，用 LaunchedEffect(isCameraPermissionGranted) 只有在权限已授予时才调用 startCamera。
把 ImageAnalysis.setAnalyzer 从主线程执行器改到了单独的后台线程（Executors.newSingleThreadExecutor()），避免分析任务卡住 UI/预览。
前摄失败时自动回退后摄

在同一个 CameraPreview.kt 里：
原来只用 DEFAULT_FRONT_CAMERA，现在是：先绑前摄，失败就 catch 日志后再尝试 DEFAULT_BACK_CAMERA，两者都失败才放弃。
CameraX 版本降级

在 build.gradle.kts：
把 CameraX 从 1.3.3 全部改为 1.2.3（core/camera2/lifecycle/view），绕开 logcat 里那个 “No available camera can be found / 设备少报摄像头” 的 1.3.x 问题。
综合起来：现在是「有权限时才启动 CameraX + 不在主线程压分析 + 前后摄都尽量试一遍 + CameraX 用更兼容的版本」，所以在这台星空麒麟 Android 13 上预览终于正常了。

