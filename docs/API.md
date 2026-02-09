

# 应用配置说明

## 集中配置管理

SPA 应用的所有配置现在都集中在 `AppConfig.kt` 文件中管理，包括：

### HTTP 接口配置
- `BASE_URL = "http://127.0.0.1:8080"`
- `FACE_AUTH_ENDPOINT = "$BASE_URL/face_auth"`
- `SIP2_CHECK_ENDPOINT = "$BASE_URL/sip2_check"`

### 厂家通道配置
- `SERIAL_PORT_PATH = "/dev/ttyS4"`
- `BAUD_RATE = 115200`
- `FRAME_FORMAT = "8N1"`
- `DEVICE_ADDR = 255`

### 超时配置
- `DEFAULT_INVENTORY_TIMEOUT_MS = 5_000L`
- `ENTRY_DOOR_DELAY_MS = 300L`
- `CONNECT_DELAY_MS = 200L`
- `OPEN_DOOR_DELAY_MS = 150L`
- `INVENTORY_DELAY_MS = 500L`

### 门控制映射
- `DoorId.ENTRY_1` → `1` (一号门进闸)
- `DoorId.EXIT_2` → `2` (二号门出闸)

### 通道参数配置
- 借书/还书/验证使能
- 进入通道超时
- 开门延时
- RFID 功率和频率设置

## 配置修改方法

所有配置修改只需在 `AppConfig.kt` 文件中进行，无需在多个文件中搜索和替换。

---

# 人脸认证接口 `face_auth`

 **接口概述**

- URL：`${AppConfig.FACE_AUTH_ENDPOINT}` (默认: `http://127.0.0.1:8080/face_auth`)  
- 方法：`POST`  

**功能说明**

- 接收前端抓取的人脸图片（Base64 编码），调用你的人脸识别/比对服务。
- 如果比对成功，返回该读者在图书馆系统中的唯一标识 `reader_id`，供后续 SIP2 校验和门禁流程使用。

**请求参数（Request Body，JSON）**

```json
{
  "image_base64": "<字符串，人脸图片的 Base64 编码>"
}
```

- `image_base64`  
  - 类型：`string`  
  - 必填  
  - 内容：一张单人脸照片的 Base64 字符串，不含前缀（比如不需要 `data:image/jpeg;base64,`）。

**响应参数（Response Body，JSON）**

1. **认证成功**

```json
{
  "reader_id": "1234567890"
}
```

- `reader_id`  
  - 类型：`string`  
  - 含义：通过人脸认证得到的读者 ID。  
  - SPA 客户端中，只有当 `reader_id` 非空时才认为认证成功。

2. **认证失败或错误**

- 可以返回以下任一形式（由服务自行约定），客户端当前逻辑是：**没有拿到非空 `reader_id` 就当失败**：

```json
{
  "reader_id": ""
}
```

或：

```json
{
  "error": "face not recognized"
}
```

或直接返回非 2xx 的 HTTP 状态码（如 `400/401/500` 等）。

**SPA 客户端处理逻辑简述**

- 发送：`POST /face_auth`，Body 为上述 JSON。
- 若 HTTP 非 2xx，或 Body 为空 → 视为失败。
- 若 JSON 中 `reader_id` 非空 → 视为成功，进入后续 `sip2_check` 流程。  
- 若 `reader_id` 缺失或为空字符串 → 视为认证失败。




# 图书馆业务接口 `sip2_check`

 **接口概述**

- URL：`POST http://127.0.0.1:8080/sip2_check`  
- 功能：对“读者 + 本次通道盘点到的图书标签”做一次 SIP2 借阅校验（是否允许本次出馆），内部具体 SIP2 细节由馆方系统处理，SPA 只关心“借书成功/失败”的结果。

---

**请求（Request）**

- Header：
  - `Content-Type: application/json; charset=utf-8`

- Body（JSON）示例：

```json
{
  "reader_id": "1234567890",
  "tags": [
    ["0123456789ABCDEF0123456789ABCDEF", "UID0001"],
    ["FEDCBA9876543210FEDCBA9876543210", "UID0002"]
  ]
}
```

字段说明：

- `reader_id`  
  - 类型：`string`  
  - 必填  
  - 来源：`/face_auth` 返回的 `reader_id`，唯一标识当前读者。

- `tags`  
  - 类型：`array`，可以为空数组  
  - 含义：本次从通道设备盘点到的图书标签列表。  
  - 每个元素是一个二元数组：`[epc, uid]`  
    - `epc`：`string`，长度 32 bytes（建议用 64 个十六进制字符表示），为业务 EPC 编码；  
    - `uid`：`string`，芯片出厂唯一 UID 标识（十六进制或其他约定格式）。  

业务约定：

- `tags` 为空表示本次盘点没有读到任何图书标签，通常视为“借书失败”场景。

---

**响应（Response）**

最小约定：后端需要明确告诉前端“借书成功 / 借书失败”。

推荐响应格式：

```json
{
  "error": false,
  "message": "Borrow successful",
  "borrow_allowed": true
}
```

或失败示例：

```json
{
  "error": true,
  "message": "User has overdue items",
  "borrow_allowed": false
}
```

字段说明：

- `error`  
  - 类型：`boolean`  
  - `false`：接口调用成功（业务上有明确结论）；  
  - `true`：接口本身或业务出现错误（如参数错误、SIP2 网关不可达等）。

- `message`  
  - 类型：`string`  
  - 可选，用于说明成功/失败原因（前端可用于日志或 UI 提示）。

- `borrow_allowed`  
  - 类型：`boolean`  
  - `true`：借书成功；  
  - `false`：借书失败（包括各种业务不允许的情况）。

---

**前端（SPA）后续逻辑约定**

- 若 `error == false && borrow_allowed == true`：
  - 认为“借书成功”：
    - 语音播报：“借书成功”；  
    - 调用门禁逻辑开**二号门**（出馆方向）。

- 其它情况（`error == true` 或 `borrow_allowed == false`）：
  - 认为“借书失败”：
    - 语音播报：“借书失败”；  
    - 调用门禁逻辑开**一号门**，引导读者退回。