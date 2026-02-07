

## 这面都是些临时信息

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

