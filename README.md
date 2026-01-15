# 无感通道应用  Seamless Passage Application（SPA）

基于 Jetpack Compose（Material 3）的单屏 Android 应用，面向门禁/通道的“无感通行”场景：前置相机沉浸式预览、ML Kit 人脸检测与叠加、底部 25% 固定状态栏、语音播报，以及模拟的鉴权/权限/开门流程。

## 功能特性
- 竖屏，平板 10" 16:10（1200×1920）优先适配
- 前置相机沉浸预览（CameraX，`PreviewView.ScaleType.FILL_CENTER`）
- ML Kit 人脸检测（关键点/边界框），覆盖显示矩形、网格与若干关键点
- 底部固定 25% 半透明状态区（图标 + 主/副文案）
- UI 状态机：Idle → FaceDetected → AuthSuccess / Denied / Error
- 语音播报（TTS）：成功/失败/错误时播报关键提示
- 模拟服务：人脸认证（`FaceAuthService`）、权限检査（`Sip2Service`）、门禁开门（`GateService`）
- 隐藏管理员退出：左下角透明区域长按 6 秒 → 密码框 → 默认口令 `123321`

## 运行流程（工作流）
1) `MainActivity` 全屏展示前置相机预览（CameraX）。
2) `CameraPreview` 使用 ML Kit 检测人脸：
	 - 一旦检测到人脸，触发 `AppViewModel.onFaceDetected()`。
	 - 同时输出用于 UI 叠加的归一化边界框与关键点/衍生点，`FaceOverlay` 进行绘制。
3) `AppViewModel` 串联业务模拟：
	 - `FaceAuthService.authenticate()` → 成功则继续
	 - `Sip2Service.check()` → 允许则继续
	 - `GateService.open()` → 成功则进入 `AuthSuccess`
	 - 任一失败进入 `Denied` 或 `Error`
4) `BottomStatusPanel` 在屏幕底部 25% 区域展示当前状态文字与图标；成功/失败/错误会通过 TTS 播报。
5) 若无操作，状态将在数秒后自动回到 `Idle`。


## UI原型

![UI原型](./spa_proto.jpeg)

## 开发环境与依赖
- JDK：17（项目使用 Java 17 兼容）
- Android Gradle Plugin（AGP）：8.2.2（见根级 `build.gradle.kts`）
- Kotlin：1.9.23（见根级 `build.gradle.kts`）
- 编译/目标 SDK：compileSdk 34，targetSdk 34；minSdk 23（见模块 `app/build.gradle.kts`）
- Compose：BOM `2024.02.01`，Compiler Extension `1.5.11`
- CameraX：`1.3.3`
- ML Kit 人脸检测：`com.google.mlkit:face-detection:16.1.5`
- 包名：`com.seamlesspassage.spa`（见 `namespace` 与 `applicationId`）

提示：建议使用支持 AGP 8.2 的 Android Studio 稳定版。项目已包含 Gradle Wrapper（Windows 使用 `gradlew.bat`）。

## 目录结构（摘）
- 应用模块：`app/`
	- 清单与资源：`app/src/main/AndroidManifest.xml`，`app/src/main/res/values/strings.xml`
	- 入口与界面：
		- `app/src/main/java/com/seamlesspassage/spa/MainActivity.kt`
		- `app/src/main/java/com/seamlesspassage/spa/ui/AppViewModel.kt`
		- `app/src/main/java/com/seamlesspassage/spa/ui/state/UiState.kt`
		- `app/src/main/java/com/seamlesspassage/spa/ui/components/*`
	- 相机与检测：
		- `app/src/main/java/com/seamlesspassage/spa/camera/CameraPreview.kt`（CameraX + ML Kit）
	- 语音播报：
		- `app/src/main/java/com/seamlesspassage/spa/speech/AudioPromptManager.kt`
	- 模拟服务：
		- `app/src/main/java/com/seamlesspassage/spa/services/FaceAuthService.kt`
		- `app/src/main/java/com/seamlesspassage/spa/services/Sip2Service.kt`
		- `app/src/main/java/com/seamlesspassage/spa/services/GateService.kt`

## 构建与运行
### Android Studio
1) 打开工程根目录，自动使用 Gradle Wrapper 同步依赖。
2) 选择目标设备（建议真机，前置相机可用）。
3) 运行 `app`（Debug）。

### 命令行（Windows）
```bat
gradlew.bat assembleDebug
gradlew.bat installDebug
```


### 权限与特性
- 运行时会请求相机权限（`CAMERA`）。清单已声明 `RECORD_AUDIO` 以支持 TTS；如需 TTS 引擎语音输出，请确保设备安装并启用对应语音包。
- 清单已设置 `keepScreenOn`、竖屏固定、前置相机能力标记。



## 使用说明
- 首次运行请允许相机与麦克风权限。
- 面向前置相机站立，系统自动检测并执行认证流程。
- 管理员退出：按住左下角透明区域 6 秒 → 输入口令 `123321` → 退出应用。

## 集成与定制
- 人脸识别对接：替换 `FaceAuthService` 的模拟逻辑，接入自研算法或外部 SDK；在 `onFaceDetected(faceId)` 中使用真实人脸 ID/特征。
- 权限系统对接：替换 `Sip2Service`，使用实际的权限/借阅/门禁服务（SIP2/HTTP/Socket 等）。
- 门禁控制：替换 `GateService`，接入实际的串口/继电器/网络控制。
- 叠加渲染：`FaceOverlay` 对 ML Kit 返回的坐标做了 `FILL_CENTER` 裁剪映射与轻微水平位移（约 -0.035），可按实机微调。
- 文案本地化：见 `app/src/main/res/values/strings.xml`。

## 已知事项
- 当前以单人脸为主，未实现多人选择策略。
- `PreviewView` 使用 `FILL_CENTER` 会产生居中裁剪，坐标已做映射；如设备比例差异大，可在 `FaceOverlay` 中进一步调整。
- 模拟服务带有随机成功率，仅用于演示流程，不代表真实效果。
- ML Kit 人脸检测为本地推理，无需联网；首次使用可能触发依赖组件下载，请保持设备可用的 Google 组件环境。

## 兼容性
- Android 6.0（API 23）及以上；建议使用 Android 13/14 设备（targetSdk 34）。

## 许可证
未设置开源许可，默认内部项目用途。如需开源或分发，请补充合适的 LICENSE 文件。
