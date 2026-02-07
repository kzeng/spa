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

ANDROID_SDK_ROOT="$HOME/Android/Sdk" "$HOME/.gradle/wrapper/dists/gradle-8.13-bin/ap7pdhvhnjtc6mxtzz89gkh0c/gradle-8.13/bin/gradle" --no-daemon --refresh-dependencies assembleDebug

ANDROID_SDK_ROOT="$HOME/Android/Sdk" "$HOME/.gradle/wrapper/dists/gradle-8.13-bin/ap7pdhvhnjtc6mxtzz89gkh0c/gradle-8.13/bin/gradle" --no-daemon assembleDebug

APK Install:
---------------
adb install -r app/build/outputs/apk/debug/app-debug.apk


WiFi debug
-----------------

adb kill-server
adb start-server
adb devices


adb connect 192.168.0.101:5555
adb devices       # 确认有 192.168.0.101:5555 device


adb install -r app/build/outputs/apk/debug/app-debug.apk
already connected to 192.168.0.101:5555
List of devices attached
192.168.0.101:5555      device

Performing Streamed Install


TTS
----------


https://ttsfree.com/#try-now  USE IT!!! DONNOT USE WAV FILES!!!!!


cd /home/zengkai/Codes/spa/app/src/main/res/raw && 
espeak-ng -v zh -s 140 -w prompt_idle.wav "请正对摄像头，您无须操作，等待完成识别" && 
espeak-ng -v zh -s 140 -w prompt_verifying.wav "正在认证身份，请保持正对镜头" && 
espeak-ng -v zh -s 140 -w prompt_success.wav "认证成功，请通过" && 
espeak-ng -v zh -s 140 -w prompt_failed.wav "认证失败" && 
espeak-ng -v zh -s 140 -w prompt_error.wav "系统错误，请稍后重试"


Workflow
==============

进一步完善人脸认证工作流： FaceAuthService.authenticate()， 
局域网会提供了人脸认证借口： 如 face_auth， 方法post
CameraX 获取帧ML Kit Face Detection 稳定 N 帧后抓取最佳帧图片 base64编码 → 调用 face_auth（本地http服务）

 face_auth 可以参考人脸认证主流标准写法返回json ， （成功） 其中应包含关键字段 reader_id



厂家提供的rfid无感借书通道库及示例代码： /home/zengkai/Codes/QBChannelAS
1. 仔细阅读代码，分析归纳功能接口
2. 需要把厂家提供的依赖库 /home/zengkai/Codes/QBChannelAS/app/libs （按需）集成到SPA 应用中来
3. 需要提供一下接口（包含处理逻辑）
   - 连接（采用COM 串口方式）通道, 必要的初始化
   - 能控制（开门）通道的一号门（借书进入通道，人脸认证成功后开） 和 二号门（借书成功后开）， 一号门至二号门是出馆方向。
   - 读取图书标签信息借口。 当通道的一号门打开，读者进入通道时启动盘点，读取图书信息 （仅读标签， 不写防盗位，不写标签， 写操作由厂家设备自动完成），读到图书信息成功则开二号门，读者离开;否则，开一号门，读者退回。
   


当前状态小结：

厂家 jar/so 已放在 spa 的 libs 下，并在 build.gradle.kts 中通过：
jniLibs.srcDir("libs")
implementation(files("libs/anreaderlib.jar"))
implementation(files("libs/d2xx.jar"))
正常参与构建。
我这边已经加了基于 ADReaderInterface 的 QbChannelRfidChannelService，并让 GateService 默认用它来完成：
串口连接 + 初始化
一号门开门 → 盘点标签 → 二号门 / 退回
接下来建议你在设备上装上最新 APK，实际走一遍流程（刷脸成功后），看：

是否能正常开一号门、进通道；
是否能在有书通过时开二号门、无书退回。
如果运行时有异常（比如 native 库找不到、串口号不对、门方向反了等），把具体报错或现象贴给我，我再帮你一起调参数和逻辑。


Plan: APP 启动即连接通道设备
在不改变现有人脸认证与门禁工作流结构的前提下，把“通道连接/初始化”从 GateService.open() 中拆分出来，提供独立的初始化入口，并在 APP 首次启动时调用一次完成设备连接和参数配置。

Steps
在 GateService.kt 中新增 suspend initChannel(): GateResult，内部只负责调用 channel.connect() 并返回 GateResult.Opened / Failed，将原先 open() 中的“连接+初始化”逻辑迁移到这里。
保持现有 GateService.open() 的业务流程（一号门→盘点→二号门/退回）不变，但去掉重复的连接逻辑，假设通道已在启动阶段初始化完成，如检测到未连接可按需再次调用 channel.connect() 兜底。
在 AppViewModel.kt 中新增一个公共方法（例如 fun initChannelOnStart()），在 viewModelScope.launch 中调用 gate.initChannel()，并根据结果在 UiState 中记录通道初始化失败的错误信息（例如转成 UiState.Error）。
在 MainActivity.kt 或 SpaScreen 组合函数里，通过 LaunchedEffect(Unit) 或生命周期回调，在 APP 首帧渲染时调用 viewModel.initChannelOnStart()，确保设备在用户看到界面时就已建立连接并完成初始化。
如有需要，在底部状态区和 TTS 中增加“通道初始化中 / 初始化失败”的状态提示，帮助现场运维快速判断设备连通性问题。




希望盘点图书得到图书信息如下： 每本书由epc (长度32Bytes) + uid 构成 , 
   "tags": [
      ["<epc1>", "<uid1>"],
      ["<epc2>", "<uid2>"],
      ...
   ]
tags 不为空视为成功， 然后发起sip2_check接口调用 （参数： reader_id + tags)

tags 为空便是失败。


其中： sip2_check接口 说明

为 http://127.0.0.1:8080/sip2_check 接口输入参数，输出信息

SIP2检查接口 主要连接图书馆流通系统，具体内部业务处理逻辑忽略。

输入参数： reader_id + tags
输出信息： 借书成功 或者 借书失败

每本书由epc (长度32个十六进制字符) + uid 构成 , 
   "tags": [
      ["<epc1>", "<uid1>"],
      ["<epc2>", "<uid2>"],
      ...
   ]

后续逻辑： 
借书成功 ， 语音播报 “借书成功” 并开二号门
借书失败 ， 语音播报 “借书失败” 并开一号门


盘点结果：
RfidTag 现在是 epc + uid，QbChannel 实现里从设备解析出 uidHex 和 user 区（按十六进制）作为 ["<epc>", "<uid>"]。
工作流调整（在 AppViewModel）：
人脸认证成功 → 调用 gate.inventoryAfterEntry()：
内部：开一号门，让人进通道，短暂延时后启动盘点。
若盘点 tags 非空：
调用 Sip2Service.check(readerId, tags)，HTTP POST http://127.0.0.1:8080/sip2_check，Body 中就是你文档里的：
reader_id: "<readerId>"
tags: [[ "<epc1>", "<uid1>" ], ...]
响应里 error == false && borrow_allowed == true：
语义“借书成功”：调用 gate.openExitDoor() 开二号门，UI 走 AuthSuccess（你可以接 TTS 播报“借书成功”）。
其它情况：
视为“借书失败”：调用 gate.openEntryDoor() 再开一号门让读者退回，UI 走 Denied（TTS 播报“借书失败”）。
若盘点 NoTags 或 Success 但 tags.isEmpty()：
直接视为失败：开一号门退回，UiState.Denied。
若盘点 Error：
开一号门退回，UiState.Error(错误信息)。
你现在可以重新安装 APK，在设备上实际刷脸走一遍，看：

有书经过时是否触发 /sip2_check 并按借书成功/失败开二号门/一号门；
日志/后端能否收到期望格式的 reader_id + tags。
如果现场抓到一条真实的 sip2_check 请求/响应或发现 EPC/UID 字段对不上，我可以再帮你一起对齐字段或增加日志。



现在梳理SPA整个项目代码、业务流程和功能实现，最好能画出一个直观的流程图。
目的： 
1. 流程是否闭环，是否有缺陷？
2. 内置的厂家接口调用是否合理，是否正确？
3. 本地http 业务接口数据格式是否对齐？ 
3. 把重点要关注的点列出来，一切为了调试和实施落地。


----------------

## Plan: 梳理 SPA 业务流程与接口闭环

整体思路：先从文档和代码结构提炼系统架构，再把“人脸→本地 HTTP→RFID 厂家通道→SIP2→开门/退回”这条核心业务链完整画出来；在此基础上，专项检查厂家 SDK 调用是否合理、本地 HTTP 数据格式是否与 API 约定一致，最后沉淀为一张可视化流程图和一份调试/实施关注点清单。

### Steps

1. 文档与架构总览  
   - 通读 spa/README.md、spa/API.md、spa/TODO.md、spa/Bugfixed.md，整理术语、角色（读者/标签/闸机）、目标流程。  
   - 从 spa/app/build.gradle.kts、spa/app/src/main/AndroidManifest.xml 抽取应用类型、入口 Activity、使用的厂商库和权限。

2. 主业务闭环梳理（UI→HTTP→通道→HTTP→门禁）  
   - 以 spa/app/src/main/java/com/seamlesspassage/spa/MainActivity.kt 和 spa/app/src/main/java/com/seamlesspassage/spa/AppViewModel.kt 为主线，抽取：  
     - 应用启动 → 通道初始化 → Idle 状态。  
     - 检测到人脸 → 调用 `onFaceDetected` → 顺序调用 FaceAuthService、GateService 和 Sip2Service。  
   - 明确每个分支的终点：AuthSuccess / Denied / Error 以及门的开关逻辑，写成“线性步骤列表”，确保每条分支都能回到 Idle，标出可能的“半途而废”点。

3. 厂家通道集成专项检查  
   - 深入阅读 spa/app/src/main/java/com/seamlesspassage/spa/QbChannelRfidChannelService.kt 与 spa/app/src/main/java/com/seamlesspassage/spa/GateService.kt：  
     - 梳理串口配置（端口、波特率、帧格式）、通道初始化参数、门状态编号与“进/出闸”逻辑。  
     - 整理标签读取流程：buffer 读取、UID/EPC 解析、错误码处理和超时策略。  
   - 对照 spa/TODO.md 中对厂商协议的描述，列出需要现场验证的点（如 1/2 号门编号是否与硬件一致、EPC 长度是否满足后端要求）。

4. 本地 HTTP 接口数据格式核对  
   - 逐条核对 spa/API.md 与实现：  
     - FaceAuthService 在 spa/app/src/main/java/com/seamlesspassage/spa/FaceAuthService.kt：确认 `/face_auth` 的请求字段 `image_base64`、响应字段 `reader_id` 与文档一致。  
     - Sip2Service 在 spa/app/src/main/java/com/seamlesspassage/spa/Sip2Service.kt：确认 `/sip2_check` 中 `reader_id`、`tags` 结构（[epc, uid]）、以及响应的 `error`、`borrow_allowed`、`message` 与文档一致。  
   - 选择 1–2 条关键业务（例如成功借书、借书失败）画出“UI 状态→HTTP 请求报文→HTTP 响应报文→UI 状态”的小流程，用来检查字段是否在各层保持一致、有无被忽略的关键信息。

5. 流程图与模块关系图整理  
   - 基于前面整理的步骤，起草一张文本版流程图：  
     - 从 App 启动 → 通道初始化 → Idle → 人脸检测 → `/face_auth` → 通道/盘点 → `/sip2_check` → 开一号/二号门或退回 → 重新 Idle。  
   - 选择你方便的形式（例如 Mermaid 或 PlantUML）在单独文档中（如 spa/flow.md）画出：  
     - 上层：UI/状态机（MainActivity、SpaScreen、UiState）。  
     - 中层：服务层（FaceAuthService、Sip2Service、GateService）。  
     - 下层：厂家 SDK + 本地 HTTP 服务。  

6. 调试与实施关注清单提炼  
   - 从上述分析中列出 10–20 条“上线前必须核对/调试项”，归类为：  
     - 本地 HTTP：端口、路径、字段名、典型成功/失败示例报文。  
     - 厂家通道：串口号、波特率、门号映射、EPC/UID 映射规则、典型错误码。  
     - 业务闭环：每类错误在 UI 上是否有明确反馈、是否都会在若干秒后回到 Idle。  
   - 将清单整合进 spa/TODO.md 或新文档（例如 deploy-checklist.md），以便现场调试和实施使用。

### Further Considerations

1. 流程图形式偏好：你更希望我后续用 Mermaid 文本（便于版本管理），还是帮你抽象成自然语言“流程稿”，再由你画成 Visio/白板图？  
2. 是否需要顺便把“错误原因”更详细地展示在界面下方（例如直接显示 `/sip2_check` 返回的 message），作为后续 UI 优化的一部分？


----------------

## 上线调试与实施检查清单（要点）

> 详细流程与架构请参考 flow.md，这里列的是现场调试/上线时需要一条条核对的关键项。

### 一、本地 HTTP 服务（127.0.0.1:8080）

- 确认本地服务进程已启动，`/face_auth` 与 `/sip2_check` 均可通过 curl 或 Postman 在设备上访问：
   - `curl -X POST http://127.0.0.1:8080/face_auth`
   - `curl -X POST http://127.0.0.1:8080/sip2_check`
- `/face_auth`：
   - 请求体字段名为 `image_base64`（纯 Base64 字符串，无 data: 前缀）。
   - 成功示例响应应包含非空 `reader_id` 字段；失败时可返回 4xx/5xx 或缺失/置空 `reader_id`。
- `/sip2_check`：
   - 请求体包含：`reader_id` + `tags`（`[["<epc>", "<uid>"], ...]`）。
   - 响应体至少应包含：`error`（boolean）、`borrow_allowed`（boolean）、`message`（string）。
   - 与 API.md 中示例保持一致，避免字段名/类型不一致导致解析失败。

### 二、厂家通道与硬件连接

- 串口配置：
   - 当前代码使用 `/dev/ttyS4` + 115200 波特率 + 8N1；上线前需与厂家确认实际串口设备号是否一致（例如 /dev/ttyS3、/dev/ttyUSB0 等）。
   - 若串口号不一致，需要在 QbChannelRfidChannelService 中调整并重新打包。
- 门号与方向：
   - DoorId.ENTRY_1 → `QB_SetChannelState(1)`，应对应“读者进入时的一号门（进馆方向）”；
   - DoorId.EXIT_2 → `QB_SetChannelState(2)`，应对应“借书成功后离馆方向的二号门”。
   - 现场需要实际走一次流程验证：
      - 人脸+有书 → 是否正确开二号门出馆；
      - 无书或借书失败 → 是否重新开一号门退回。
- 盘点标签结果：
   - 确认从厂家设备解析出的 EPC 与 UID 与馆方系统约定一致：
      - EPC 建议为 32 字节（64 个十六进制字符）；
      - UID 为芯片出厂唯一标识（十六进制或其他格式）。
   - 如果后端期望的 EPC 长度/编码方式与厂家给出的 user 区不同，需要调整 RfidTag 构造逻辑。

### 三、业务闭环与 UI 表达

- 正向闭环（Happy Path）：
   - 人脸认证成功 → 有书盘点成功 → `/sip2_check` 返回 `borrow_allowed = true` → 二号门打开 → UI 显示 AuthSuccess，语音“认证成功/借书成功” → 3 秒后回到 Idle。
- 失败/异常分支：
   - 人脸认证失败（/face_auth 未返回 reader_id）：UI 显示 Denied，语音“认证失败”，不打开任何门，3 秒后回到 Idle。
   - 有读者进通道但盘点 NoTags 或 tags 为空：一号门重新打开，UI 显示 Denied（视为“无书”），3 秒后回到 Idle。
   - 盘点 Error（串口/厂家错误）：一号门重新打开，UI 显示 Error(message)，3 秒后回到 Idle。
   - `/sip2_check` 返回失败或接口异常：一号门重新打开，UI 显示 Denied，语音“借书失败”，3 秒后回到 Idle。
- 检查点：
   - 任意异常情况下，界面是否都能在若干秒内回到 Idle，而不会卡在中间状态；
   - 文案与语音提示是否和门的实际动作一致，避免误导读者。

### 四、日志、监控与排错

- HTTP 与业务日志：
   - 建议在本地 HTTP 服务侧记录 `/face_auth` 与 `/sip2_check` 的请求/响应日志（脱敏后），用于排查联调问题。
   - 如有需要，可以在 App 里临时加日志（Logcat）打印关键字段（reader_id、tags 长度、error/borrow_allowed 等）。
- 厂家通道错误码：
   - 对照 QBChannel 文档，记录常见错误码（例如串口打开失败、通道未初始化、读写超时等），便于看到 Error(message) 时快速定位原因。
- 现场联调建议：
   - 先在“模拟通道 + 模拟 HTTP”环境下验证 UI 流程闭环；
   - 再逐步切换为“真实通道 + 模拟 HTTP”、“真实通道 + 真实 HTTP”，分步排除问题。

### 五、部署与版本管理

- 确认使用的 APK 版本号与 TODO/flow 文档描述的实现一致（例如是否已经包含 QbChannelRfidChannelService 与启动时 initChannelOnStart 流程）。
- 若后续对串口号、门号映射或标签解析逻辑做了修改，务必在 TODO.md / flow.md 中更新对应说明，避免现场文档与代码脱节。
