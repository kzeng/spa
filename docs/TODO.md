# Seamless Passage Application (SPA) 无感通道应用



## 集中配置管理

所有应用配置现在都集中在 `AppConfig.kt` 文件中管理，包括：

### 配置项说明
- **HTTP 接口配置**：`BASE_URL`、`FACE_AUTH_ENDPOINT`、`SIP2_CHECK_ENDPOINT`
- **厂家通道配置**：`SERIAL_PORT_PATH`、`BAUD_RATE`、`FRAME_FORMAT`、`DEVICE_ADDR`
- **超时配置**：`DEFAULT_INVENTORY_TIMEOUT_MS`、`ENTRY_DOOR_DELAY_MS` 等
- **门控制映射**：`DoorId.ENTRY_1` → `1`、`DoorId.EXIT_2` → `2`
- **通道参数配置**：借书/还书/验证使能、RFID 功率和频率设置等

### 配置修改方法
所有配置修改只需在 `AppConfig.kt` 文件中进行，无需在多个文件中搜索和替换。

---

## 上线调试与实施检查清单（要点）

> 详细流程与架构请参考 flow.md，这里列的是现场调试/上线时需要一条条核对的关键项。

### 一、本地 HTTP 服务（127.0.0.1:8080）

- 确认本地服务进程已启动，`/face_auth` 与 `/sip2_check` 均可通过 curl 或 Postman 在设备上访问：
   - `curl -X POST ${AppConfig.FACE_AUTH_ENDPOINT}`
   - `curl -X POST ${AppConfig.SIP2_CHECK_ENDPOINT}`
- `/face_auth`：
   - 请求体字段名为 `image_base64`（纯 Base64 字符串，无 data: 前缀）。
   - 成功示例响应应包含非空 `reader_id` 字段；失败时可返回 4xx/5xx 或缺失/置空 `reader_id`。
- `/sip2_check`：
   - 请求体包含：`reader_id` + `tags`（`[["<epc>", "<uid>"], ...]`）。
   - 响应体至少应包含：`error`（boolean）、`borrow_allowed`（boolean）、`message`（string）。
   - 与 API.md 中示例保持一致，避免字段名/类型不一致导致解析失败。

### 二、厂家通道与硬件连接

- 串口配置：

   厂家示例里，COM 串口方式最终拼出来的连接串是这一种格式：
   通用格式：
   RDType=QBChannel;CommType=COM;ComPath=<串口路径>;Baund=<波特率>;Frame=<帧格式>;Addr=255

   结合他们 Demo 里默认选项（第 5 个串口、115200、8N1），典型实际例子大概是：
   RDType=QBChannel;CommType=COM;ComPath=/dev/ttyS4;Baund=115200;Frame=8N1;Addr=255

   也就是你在 SPA 里如果用 COM 方式连设备，拼的 connstr 要和上面这个结构一致，只是把 <串口路径>、波特率、帧格式按现场实际改。

   - 当前代码使用 `/dev/ttyS4` + 115200 波特率 + 8N1；上线前需与厂家确认实际串口设备号是否一致（例如 /dev/ttyS3、/dev/ttyUSB0 等）。
   - 若串口号不一致，需要在 QbChannelRfidChannelService 中调整并重新打包。
- 门号与方向：
   - DoorId.ENTRY_1 → `QB_SetChannelState(1)`，应对应“读者进入时的一号门（进馆方向）”；
   - DoorId.EXIT_2 → `QB_SetChannelState(2)`，应对应“借书成功后离馆方向的二号门”。
   - 现场需要实际走一次流程验证：
      - 人脸+有书 → 是否正确开二号门出馆；
      - 无书或借书失败 → 是否重新开一号门退回。
- 盘点标签结果： [跳转到具体实现说明](#图书盘点获取图书信息)
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



### 图书盘点获取图书信息
盘点这块在 SPA 里是这样分层实现的：

**1. 抽象接口（定义“盘点图书”功能）**  
- 文件：  
  - RfidChannelService.kt  
- 关键方法：  
  - `suspend fun startInventory(timeoutMillis: Long = DEFAULT_INVENTORY_TIMEOUT): InventoryResult`  
  - 返回 `InventoryResult.Success(tags: List<RfidTag>) / NoTags / Error`，其中 `RfidTag(epc, uid)` 就是每本书的信息。

**2. 厂家实现（真正从通道设备读标签）**  
- 文件：  
  - QbChannelRfidChannelService.kt  
- 关键实现：  
  - `override suspend fun startInventory(timeoutMillis: Long): InventoryResult`  
  - 内部用 `ADReaderInterface`：  
    - `QB_CHANNEL_GetData` 拉取缓冲记录  
    - `QB_CHANNEL_GetBufRecordCount` 看有几条记录  
    - `QB_CHANNEL_ReadBufRecord` + `QB_CHANNEL_ParseGettedData` 解析出 `uidHex` 和 `user` 区（epcHex），最后组装成 `RfidTag(epc = epcHex, uid = uidHex)` 列表。

**3. 业务封装（人脸成功后“一号门 + 盘点”）**  
- 文件：  
  - GateService.kt  
- 关键方法：  
  - `suspend fun inventoryAfterEntry(): InventoryResult`  
    - 先 `channel.connect()`  
    - 再 `openDoor(DoorId.ENTRY_1)` 开一号门  
    - `delay(300)` 给人进入  
    - 最后调用 `channel.startInventory()` 完成盘点并返回 `InventoryResult`。

**4. 工作流入口**  
- 文件：  
  - AppViewModel.kt  
- 在 `onFaceDetected()` 里：  
  - 人脸认证成功后调用 `gate.inventoryAfterEntry()`  
  - 拿到的 `tags` 传给 `Sip2Service.check(readerId, tags)` 走 `/sip2_check`。

总结：  
真正“盘点图书、读取标签信息”的接口就是 `RfidChannelService.startInventory()`，当前由 `QbChannelRfidChannelService.startInventory()` 基于厂家 anreaderlib.jar 实现；业务侧通过 `GateService.inventoryAfterEntry()` 在刷脸成功后触发这一流程。

