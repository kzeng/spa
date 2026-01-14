# Seamless Passage Application (SPA) 无感通道应用

## PROTO

这是一个 Android App 页面，风格用 Material 3, Jetpack Compose， 运行在安卓PAD （10 inch, 16:10, 1200*1920） 上 ，竖屏显示

1. 应用在无感通道上，对人脸进行识别和认证。 整个过程不需要用户与APP过多交互（无感的）， APP给出文字和语音提示，同时驱动门禁开门。

2. 设计为单页面应用。
   页面构成：
   - 屏幕前置摄像头打开，沉浸式满屏摄像头画面。
   - 识别到人脸后，可使用线条或者网格勾勒出人脸 （识别到人脸后才调用认证接口）
   - 页面底部25%面积用来实时显示处理后接口返回信息
   - 页面底部25%面积 使用半透明遮罩效果，提示信息使用大号颜色醒目的： 图标+文字
   
3. 处理逻辑 
   - 屏幕前置摄像头打开，摄像头画面。
   - 调用人脸识别库内部库（如果有就用内部库）或者第三方库
   - 如果没有识别到人脸什么也不做
   - 如果识别到人脸则调用人脸认证库接口(如： face_auth)
   - 人脸认证库接口返回成功，则使用用户ID 请求 SIP2检查接口(图书馆流通接口， 如:sip2_check) 
   - SIP2检查接口返回信息，如果成功，需要开门，则需要调用门禁开门接口（如: gate_open）
   - 所有接口反馈后都需要处理后显示在页面底部，并语音提示。
   
4. 整体感觉： - 简洁 / 工业风 / 偏工具


## 工作流

CameraX 提供帧 → ML Kit 检测到人脸并输出框/trackId
连续稳定 N 帧 → 抓取最佳帧 → 调用 face_auth（你的库/服务）
认证成功 → sip2_check → gate_open
底部 25% 状态区 + TTS 播报

检测到人脸后会进入“人脸认证→权限检査→开门(报警)”的工作流。

入口与位置：
MainActivity.kt 的 FrontCameraPreview 回调 onFaceDetected 调用 viewModel.onFaceDetected(faceId)。
工作流实现：app/src/main/java/com/seamlesspassage/spa/ui/AppViewModel.kt 的 onFaceDetected() 方法，依次调用：
app/src/main/java/com/seamlesspassage/spa/services/FaceAuthService.kt：authenticate(faceId) 获取用户身份/ID
app/src/main/java/com/seamlesspassage/spa/services/Sip2Service.kt：check(userId) 做权限/借阅/门禁校验
app/src/main/java/com/seamlesspassage/spa/services/GateService.kt：open() 执行开门
状态更新：AppViewModel 根据结果切换 UiState 到 AuthSuccess / Denied / Error，并由 BottomStatusPanel 与 TTS 播报。


## TARGET

- Android Studio 2025.2
- JDK 17 (required by AGP 8.2)
- Kotlin 1.9.22
- Material 3 (Jetpack Compose)
- Minimum SDK 23; Target SDK 33 (Android 13)


## ISSUES


现在项目里的“人脸识别”是模拟的：CameraX 仅做前置预览，定时触发 onFaceDetected()。
要做真实识别，建议用内置库或接入第三方。最稳的是：ML Kit 做“人脸检测（框/关键点）”，然后把最佳帧送到你的 face_auth 做“人脸认证（比对身份）”。检测≠认证。


CameraX 获取帧
ML Kit Face Detection 判断是否有人脸、输出框/关键点
稳定 N 帧后抓取最佳帧 → 调用 face_auth（本地模型或服务端）
成功 → sip2_check → 需要开门 → gate_open
底部 25% 状态区+TTS 播报
最小接入示例（检测用 ML Kit，认证仍走你的 face_auth）

// ...existing code...
dependencies {
    // CameraX (已集成则保留)
    implementation("androidx.camera:camera-core:1.3.3")
    implementation("androidx.camera:camera-camera2:1.3.3")
    implementation("androidx.camera:camera-lifecycle:1.3.3")
    implementation("androidx.camera:camera-view:1.3.3")

    // ML Kit 人脸检测（仅检测，不做身份比对）
    implementation("com.google.mlkit:face-detection:16.1.5")
}
// ...existing code...


## BUILD & INSTALL
Build:
--------------
ANDROID_SDK_ROOT="$HOME/Android/Sdk" "$HOME/.gradle/wrapper/dists/gradle-8.13-bin/ap7pdhvhnjtc6mxtzz89gkh0c/gradle-8.13/bin/gradle" --no-daemon assembleDebug


ANDROID_SDK_ROOT="$HOME/Android/Sdk" "$HOME/.gradle/wrapper/dists/gradle-8.13-bin/ap7pdhvhnjtc6mxtzz89gkh0c/gradle-8.13/bin/gradle" --no-daemon --refresh-dependencies assembleDebug



APK Install:
---------------
adb install -r app/build/outputs/apk/debug/app-debug.apk


