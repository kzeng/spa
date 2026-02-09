package com.seamlesspassage.spa

import com.seamlesspassage.spa.services.DoorId

/**
 * 应用配置集中管理类。
 * 将所有分散的配置（HTTP接口、通道连接参数、超时设置等）集中在此处管理。
 */
object AppConfig {
    // ==================== HTTP 接口配置 ====================
    const val BASE_URL = "http://127.0.0.1:8080"
    const val FACE_AUTH_ENDPOINT = "$BASE_URL/face_auth"
    const val SIP2_CHECK_ENDPOINT = "$BASE_URL/sip2_check"
    
    // ==================== 厂家通道配置 ====================
    const val SERIAL_PORT_PATH = "/dev/ttyS4"
    const val BAUD_RATE = 115200
    const val FRAME_FORMAT = "8N1"
    const val DEVICE_ADDR = 255
    
    // ==================== 超时配置 ====================
    const val DEFAULT_INVENTORY_TIMEOUT_MS = 5_000L
    const val ENTRY_DOOR_DELAY_MS = 300L
    const val CONNECT_DELAY_MS = 200L
    const val OPEN_DOOR_DELAY_MS = 150L
    const val INVENTORY_DELAY_MS = 500L
    
    // ==================== 门控制映射 ====================
    val DOOR_STATE_MAP = mapOf(
        DoorId.ENTRY_1 to 1,
        DoorId.EXIT_2 to 2
    )
    
    // ==================== 通道参数配置 ====================
    const val BORROW_ENABLE: Byte = 1
    const val RETURN_ENABLE: Byte = 1
    const val VERIFY_ENABLE: Byte = 1
    const val IN_TIMEOUT: Byte = 10  // 读者进入通道超时（秒）
    const val DELAY_OPEN: Byte = 0   // 开门延时
    
    const val EXIT_FLAG: Byte = 0        // 0=借书；1=还书，按需调整
    const val BLOCK_OFFSET: Char = 0.toChar() // 不读取条码
    const val BLOCK_NUM: Byte = 0        // 0 表示不读取条码
    const val B_EAS = false
    const val B_AFI = false
    const val AFI_RETURN: Byte = 0x00
    const val AFI_BORROW: Byte = 0x00
    const val RF_POWER: Byte = 0        // 0 表示不改功率，沿用设备配置
    const val RF_FREQ_LEVEL: Byte = 0    // 0 表示默认倍频
    
    // ==================== 辅助方法 ====================
    
    /**
     * 构建厂家通道连接字符串。
     * 格式：RDType=QBChannel;CommType=COM;ComPath=<串口路径>;Baund=<波特率>;Frame=<帧格式>;Addr=255
     */
    fun buildConnectionString(): String {
        return "RDType=QBChannel;CommType=COM;ComPath=$SERIAL_PORT_PATH;" +
               "Baund=$BAUD_RATE;Frame=$FRAME_FORMAT;Addr=$DEVICE_ADDR"
    }
    
    /**
     * 构建厂家通道连接字符串（支持自定义参数）。
     */
    fun buildConnectionString(
        serialPortPath: String = SERIAL_PORT_PATH,
        baudRate: Int = BAUD_RATE,
        frameFormat: String = FRAME_FORMAT,
        deviceAddr: Int = DEVICE_ADDR
    ): String {
        return "RDType=QBChannel;CommType=COM;ComPath=$serialPortPath;" +
               "Baund=$baudRate;Frame=$frameFormat;Addr=$deviceAddr"
    }
    
    /**
     * 获取门控制状态值。
     */
    fun getDoorState(door: DoorId): Int {
        return DOOR_STATE_MAP[door] ?: throw IllegalArgumentException("未知的门类型: $door")
    }
}