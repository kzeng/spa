package com.seamlesspassage.spa.services

import com.seamlesspassage.spa.AppConfig
import com.rfid.api.ADReaderInterface
import com.rfid.def.ApiErrDefinition
import com.rfid.def.RfidDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 扩展的 RFID 通道接口，支持厂家特定的 QB_Authentication 和 QB_DetectBooks 接口。
 */
interface QbChannelService : RfidChannelService {
    /**
     * 调用厂家 QB_Authentication 接口进行认证并开门。
     * 根据厂家 Demo：flag=1 表示还书方向（进入闸机），flag=2 表示借书方向（离开闸机）
     */
    suspend fun authenticate(flag: Byte): DoorControlResult

    /**
     * 调用厂家 QB_DetectBooks 接口通知检测结果并开门。
     * 根据厂家 Demo：detectResult=1 (Pass) 表示允许通过，detectResult=0 (Failed) 表示拒绝
     */
    suspend fun detectBooks(detectResult: Byte): DoorControlResult
}

/**
 * 基于厂家 QBChannel Demo 所用 anreaderlib.jar 的实现。
 *
 * 封装本项目需要的能力：
 *  - 串口方式连接/初始化通道
 *  - 一号门 / 二号门 开门（使用 QB_Authentication 和 QB_DetectBooks 接口）
 *  - 读取通道缓冲中的标签记录，转换为简单的 RfidTag 列表
 */
class QbChannelRfidChannelService(
    private val serialPortPath: String = AppConfig.SERIAL_PORT_PATH,
    private val baudRate: Int = AppConfig.BAUD_RATE,
    private val frame: String = AppConfig.FRAME_FORMAT
) : QbChannelService {

    private val reader = ADReaderInterface()
    private var connected = false
    private var initialized = false

    companion object {
        // 多标签场景下的聚合窗口：首次读到标签后再等待一小段时间，
        // 以便把同一轮过闸产生的后续标签一并读齐，减少漏读概率。
        private const val AGGREGATION_WINDOW_MS: Long = 300
    }

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

    /**
     * 清空通道缓冲中的历史记录，避免本次盘点读到旧标签数据。
     * 参考正常解析流程，完整遍历缓冲记录但不构造 RfidTag。
     */
    private fun clearChannelBuffer() {
        try {
            while (true) {
                val delete = true
                val getRet = reader.QB_CHANNEL_GetData(delete)
                if (getRet != ApiErrDefinition.NO_ERROR) {
                    break
                }

                val count = reader.QB_CHANNEL_GetBufRecordCount()
                if (count <= 0) {
                    break
                }

                var hReport: Any? = reader.QB_CHANNEL_ReadBufRecord(RfidDef.RFID_SEEK_FIRST)
                while (hReport != null) {
                    hReport = reader.QB_CHANNEL_ReadBufRecord(RfidDef.RFID_SEEK_NEXT)
                }
            }
        } catch (_: Exception) {
            // Do something if needed
            // 缓冲清理失败不影响主业务流程 
        }
    }

    /**
     * 调用厂家 QB_Authentication 接口进行认证并开门。
     * 根据厂家 Demo：flag=1 表示还书方向（进入闸机），flag=2 表示借书方向（离开闸机）
     * 本项目仅用于借书场景，使用 flag=2 表示借书方向（离开闸机）
     */
    override suspend fun authenticate(flag: Byte): DoorControlResult = withContext(Dispatchers.IO) {
        if (!connected) {
            val conn = connect()
            if (conn is ChannelConnectResult.Failed) {
                return@withContext DoorControlResult.Failed(conn.message)
            }
        }

        // 在本次借书流程开始前先清空历史缓冲记录，确保后续盘点只看到本次经过通道产生的数据
        clearChannelBuffer()

        val ret = reader.QB_Authentication(flag)
        if (ret != ApiErrDefinition.NO_ERROR) {
            return@withContext DoorControlResult.Failed("QB_Authentication 失败: code=$ret, flag=$flag")
        }
        DoorControlResult.Opened
    }

    /**
     * 调用厂家 QB_DetectBooks 接口通知检测结果并开门。
     * 根据厂家 Demo：detectResult=1 (Pass) 表示允许通过，detectResult=0 (Failed) 表示拒绝
     */
    override suspend fun detectBooks(detectResult: Byte): DoorControlResult = withContext(Dispatchers.IO) {
        if (!connected) {
            val conn = connect()
            if (conn is ChannelConnectResult.Failed) {
                return@withContext DoorControlResult.Failed(conn.message)
            }
        }

        val ret = reader.QB_DetectBooks(detectResult)
        if (ret != ApiErrDefinition.NO_ERROR) {
            return@withContext DoorControlResult.Failed("QB_DetectBooks 失败: code=$ret, detectResult=$detectResult")
        }
        DoorControlResult.Opened
    }

    override suspend fun openDoor(door: DoorId): DoorControlResult = withContext(Dispatchers.IO) {
        if (!connected) {
            val conn = connect()
            if (conn is ChannelConnectResult.Failed) {
                return@withContext DoorControlResult.Failed(conn.message)
            }
        }

        // 对于 QbChannelService，使用正确的厂家接口替代旧的 QB_SetChannelState
        // 根据门的方向调用相应的接口
        when (door) {
            DoorId.ENTRY_1 -> {
                // 开一号门：使用 QB_Authentication(flag=2) 开一号门 + 启动盘点
                // 注意：这里仅开门，不启动盘点，盘点由专门的 inventoryAfterEntry 方法处理
                val ret = reader.QB_Authentication(2)
                if (ret != ApiErrDefinition.NO_ERROR) {
                    DoorControlResult.Failed("QB_Authentication 失败: code=$ret, flag=2")
                } else {
                    DoorControlResult.Opened
                }
            }
            DoorId.EXIT_2 -> {
                // 开二号门：使用 QB_DetectBooks(detectResult=1) 开二号门出馆
                val ret = reader.QB_DetectBooks(1)
                if (ret != ApiErrDefinition.NO_ERROR) {
                    DoorControlResult.Failed("QB_DetectBooks 失败: code=$ret, detectResult=1")
                } else {
                    DoorControlResult.Opened
                }
            }
        }
    }

    override suspend fun startInventory(timeoutMillis: Long): InventoryResult = withContext(Dispatchers.IO) {
        if (!connected || !initialized) {
            val conn = connect()
            if (conn is ChannelConnectResult.Failed) {
                return@withContext InventoryResult.Error(conn.message)
            }
        }

        val deadline = System.currentTimeMillis() + timeoutMillis
        val allTags = LinkedHashMap<String, RfidTag>()
        // 记录最近一次新增标签的时间，用于聚合窗口判断
        var lastTagTime: Long? = null

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
                } else {
                    val count = reader.QB_CHANNEL_GetBufRecordCount()
                    if (count > 0) {
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
                        var addedInThisBatch = 0
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
                                // 厂家 Demo 中 user 区承载 EPC/TID 等业务数据，这里：
                                // - epc：保持为规范化后的 16 字节 EPC（32 个十六进制字符）；
                                // - tid：保留原始长度的十六进制串，便于与厂家 Demo（如 E200680A0000400093042465）对比。
                                val rawUserHex = if (userSize[0] > 0) {
                                    bytesToHex(user, userSize[0].toInt())
                                } else {
                                    ""
                                }
                                val epcHex = if (rawUserHex.isNotEmpty()) {
                                    normalizeEpc(rawUserHex)
                                } else {
                                    ""
                                }
                                val tidHex = rawUserHex

                                if (epcHex.isNotEmpty() || uidHex.isNotEmpty() || tidHex.isNotEmpty()) {
                                    val key = when {
                                        tidHex.isNotEmpty() -> tidHex
                                        uidHex.isNotEmpty() -> uidHex
                                        else -> epcHex
                                    }
                                    allTags[key] = RfidTag(epc = epcHex, uid = uidHex, tid = tidHex)
                                    addedInThisBatch++
                                }
                            }
                            hReport = reader.QB_CHANNEL_ReadBufRecord(RfidDef.RFID_SEEK_NEXT)
                        }

                        if (addedInThisBatch > 0) {
                            // 每次本轮有新增标签，都刷新最近标签时间，用于"静默时间"聚合判断
                            lastTagTime = System.currentTimeMillis()
                        }
                    }
                }

                val now = System.currentTimeMillis()
                val latestTagTime = lastTagTime
                // 若在最近一次新增标签之后已静默超过聚合窗口，则返回当前已聚合到的所有标签
                if (allTags.isNotEmpty() && latestTagTime != null && now - latestTagTime >= AGGREGATION_WINDOW_MS) {
                    return@withContext InventoryResult.Success(allTags.values.toList())
                }

                delay(100)
            }

            // 超时：若已读到标签，则返回已聚合的全部标签；否则视为无标签
            if (allTags.isNotEmpty()) {
                InventoryResult.Success(allTags.values.toList())
            } else {
                InventoryResult.NoTags
            }
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
