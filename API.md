

# 人脸认证接口 `face_auth`

 **接口概述**

- URL：`http://127.0.0.1:8080/face_auth`  
- 方法：`POST`  
- 功能：根据传入的人脸图像（base64 编码）做一次人脸认证，返回对应的读者 ID，用于后续 SIP2 检查和门禁开门。

**请求参数**

- 请求头：
  - `Content-Type: application/json; charset=utf-8`
- 请求体（JSON）：

  ```json
  {
    "image_base64": "<字符串，单张人脸图片的 Base64 编码>"
  }
  ```

  说明：
  - `image_base64`：必填，前端从摄像头抓取的最佳帧图像做 Base64 编码后的字符串，建议是单人脸、正面、清晰图像。

**响应参数**

- 成功时（认证通过）示例：

  ```json
  {
    "reader_id": "1234567890"
  }
  ```

  - `reader_id`：字符串，认证通过后识别出的读者唯一标识（例如借阅证号或用户 ID）。  
  - 在代码中：`reader_id` 非空 → 视为成功，返回 `FaceAuthResult.Success(userId = reader_id)`。

- 失败时（认证未通过或系统错误）：

  - 可能返回非 2xx 状态码（例如 4xx/5xx），或返回 JSON 中没有 `reader_id` 字段 / 字段为空：
    - 对 SPA 而言，只要：
      - HTTP 非成功状态，或  
      - 响应体缺少 `reader_id` / 为空  
    - 都视为失败，返回 `FaceAuthResult.Failure`，前端走“认证失败”流程。

你如果需要，我可以再帮你写一段这个接口的 README 片段，方便给后端同事对接。

User: 为 http://127.0.0.1:8080/face_auth  接口写个简单说明，功能，输入参数， 输出参数

GitHub Copilot: **接口地址**

- `POST http://127.0.0.1:8080/face_auth`

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