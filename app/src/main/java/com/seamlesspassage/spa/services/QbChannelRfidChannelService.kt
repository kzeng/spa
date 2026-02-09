package com.seamlesspassage.spa.services

import com.seamlesspassage.spa.AppConfig
import com.rfid.api.ADReaderInterface
import com.rfid.def.ApiErrDefinition
import com.rfid.def.RfidDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 基于厂家 QBChannel Demo 所用 anreaderlib.jar 的实现。
 *
 * 仅封装本项目需要的能力：
 *  - 串口方式连接/初始化通道
 *  - 一号门 / 二号门 开门
 *  - 读取通道缓冲中的标签记录，转换为简单的 RfidTag 列表
 */
class QbChannelRfidChannelService(
    private val serialPortPath: String = AppConfig.SERIAL_PORT_PATH,
    private val baudRate: Int = AppConfig.BAUD_RATE,
    private val frame: String = AppConfig.FRAME_FORMAT
) : RfidChannelService {

    private val reader = ADReaderInterface()
    private var connected = false
    private var initialized = false

    /**
     * 构造 QBChannel Demo 中使用的串口连接字符串：
     * RDType=QBChannel;CommType=COM;ComPath=/dev/ttySx;Baund=115200;Frame=8N1;Addr=255
     */
    private fun buildConnStr(): String =
        AppConfig.buildConnectionString(serialPortPath, baudRate, frame)

    override suspend fun connect(): ChannelConnectResult = withContext(Dispatchers.IO) {
        if (connected && initialized) {
            return@withContext ChannelConnectResult.Connected
        }

        val connStr = buildConnStr()
        val openRet = reader.RDR_Open(connStr)
        if (openRet != ApiErrDefinition.NO_ERROR) {
            return@withContext ChannelConnectResult.Failed("RDR_Open 失败: code=$openRet")
        }
        connected = true

        // 配置闸机参数：使用集中配置
        val cfgRet = reader.QBCHANNEL_CFG_TurnstileParam(
            AppConfig.BORROW_ENABLE,
            AppConfig.RETURN_ENABLE,
            AppConfig.VERIFY_ENABLE,
            AppConfig.IN_TIMEOUT,
            AppConfig.DELAY_OPEN
        )
        if (cfgRet != ApiErrDefinition.NO_ERROR) {
            return@withContext ChannelConnectResult.Failed("QBCHANNEL_CFG_TurnstileParam 失败: code=$cfgRet")
        }

        // 初始化 QB 通道：仅读标签，不写 EAS/AFI，不读取条码，使用集中配置
        val initRet = reader.QB_CHANNEL_Init(
            AppConfig.EXIT_FLAG,
            AppConfig.BLOCK_OFFSET,
            AppConfig.BLOCK_NUM,
            AppConfig.B_EAS,
            AppConfig.B_AFI,
            AppConfig.AFI_RETURN,
            AppConfig.AFI_BORROW,
            AppConfig.RF_POWER,
            AppConfig.RF_FREQ_LEVEL
        )
        if (initRet != ApiErrDefinition.NO_ERROR) {
            return@withContext ChannelConnectResult.Failed("QB_CHANNEL_Init 失败: code=$initRet")
        }

        initialized = true
        ChannelConnectResult.Connected
    }

    override suspend fun openDoor(door: DoorId): DoorControlResult = withContext(Dispatchers.IO) {
        if (!connected) {
            val conn = connect()
            if (conn is ChannelConnectResult.Failed) {
                return@withContext DoorControlResult.Failed(conn.message)
            }
        }

        // 通道状态控制：使用集中配置中的门状态映射
        val channelState: Byte = AppConfig.getDoorState(door).toByte()
        val ret = reader.QB_SetChannelState(channelState)
        if (ret != ApiErrDefinition.NO_ERROR) {
            return@withContext DoorControlResult.Failed("QB_SetChannelState 失败: code=$ret")
        }
        DoorControlResult.Opened
    }

    override suspend fun startInventory(timeoutMillis: Long): InventoryResult = withContext(Dispatchers.IO) {
        if (!connected || !initialized) {
            val conn = connect()
            if (conn is ChannelConnectResult.Failed) {
                return@withContext InventoryResult.Error(conn.message)
            }
        }

        val deadline = System.currentTimeMillis() + timeoutMillis
        var loopCount = 0L

        try {
            while (System.currentTimeMillis() < deadline) {
                // 触发设备从缓冲区取记录
                val delete: Boolean = true
                val getRet = reader.QB_CHANNEL_GetData(delete)
                if (getRet != ApiErrDefinition.NO_ERROR) {
                    if (getRet == -2) {
                        // 连接断开
                        connected = false
                        initialized = false
                        return@withContext InventoryResult.Error("通道连接已断开")
                    }
                    // 其它错误：稍作等待后继续尝试，直至超时
                    delay(50)
                    continue
                }

                val count = reader.QB_CHANNEL_GetBufRecordCount()
                if (count > 0) {
                    val tags = mutableListOf<RfidTag>()
                    // 解析缓冲区记录（参考 QBChannel.get_QBChannel_report）
                    val sid = ByteArray(1)
                    val date = ByteArray(7)
                    val eventType = ByteArray(1)
                    val dFlag = LongArray(1)
                    val uid = ByteArray(8)
                    val uidSize = longArrayOf(uid.size.toLong())
                    val user = ByteArray(1024)
                    val userSize = longArrayOf(user.size.toLong())

                    var hReport: Any? = reader.QB_CHANNEL_ReadBufRecord(RfidDef.RFID_SEEK_FIRST)
                    while (hReport != null) {
                        val parseRet = reader.QB_CHANNEL_ParseGettedData(
                            hReport,
                            sid,
                            date,
                            eventType,
                            dFlag,
                            uid,
                            uidSize,
                            user,
                            userSize
                        )
                        if (parseRet == ApiErrDefinition.NO_ERROR) {
                            val uidHex = bytesToHex(uid, uidSize[0].toInt())
                            // 厂家 Demo 中 user 区通常承载 EPC/业务数据，这里按十六进制字符串输出
                            val epcHex = if (userSize[0] > 0) {
                                val hex = bytesToHex(user, userSize[0].toInt())
                                // 确保 EPC 符合 16 字节（32 个十六进制字符）的要求
                                normalizeEpc(hex)
                            } else {
                                ""
                            }
                            tags.add(RfidTag(epc = epcHex, uid = uidHex))
                        }
                        hReport = reader.QB_CHANNEL_ReadBufRecord(RfidDef.RFID_SEEK_NEXT)
                    }

                    if (tags.isNotEmpty()) {
                        return@withContext InventoryResult.Success(tags)
                    }
                } else {
                    // 没有记录，发一个 EMPTY 的语义，继续等待直到超时
                    loopCount++
                }

                delay(100)
            }

            // 超时仍未读到标签
            InventoryResult.NoTags
        } finally {
            // 复位立即超时标志，避免影响后续通信
            reader.RDR_ResetCommuImmeTimeout()
        }
    }

    private fun bytesToHex(bytes: ByteArray, length: Int): String {
        if (length <= 0) return ""
        val sb = StringBuilder(length * 2)
        for (i in 0 until length.coerceAtMost(bytes.size)) {
            sb.append(String.format("%02X", bytes[i]))
        }
        return sb.toString()
    }

    /**
     * 规范化 EPC 字符串，确保符合 16 字节（32 个十六进制字符）的要求。
     * 
     * 处理规则：
     * 1. 如果长度正好是 32 个字符，直接返回
     * 2. 如果长度大于 32 个字符，截取前 32 个字符
     * 3. 如果长度小于 32 个字符，用 '0' 填充到 32 个字符
     * 4. 确保所有字符都是有效的十六进制字符（0-9, A-F）
     */
    private fun normalizeEpc(epc: String): String {
        if (epc.isEmpty()) return ""
        
        // 过滤非十六进制字符，只保留 0-9, A-F
        val filtered = epc.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
        
        return when {
            filtered.length == 32 -> filtered
            filtered.length > 32 -> filtered.substring(0, 32)
            else -> filtered.padEnd(32, '0') // 用 '0' 填充到 32 个字符
        }
    }
}
