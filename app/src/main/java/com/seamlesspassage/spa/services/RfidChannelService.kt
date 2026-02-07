package com.seamlesspassage.spa.services

import kotlinx.coroutines.delay

/**
 * 抽象的 RFID 无感借书通道接口。
 *
 * 这里不依赖具体厂商实现，后续可用厂家库适配实现该接口。
 */
interface RfidChannelService {
    /** 连接并初始化通道（串口 COM 等）。*/
    suspend fun connect(): ChannelConnectResult

    /** 按门编号开门：一号门（进入通道）、二号门（离开通道）。*/
    suspend fun openDoor(door: DoorId): DoorControlResult

    /**
     * 启动一次盘点流程，直到成功读取或超时/失败。
     * 仅读标签信息，不写防盗位与标签数据。
     */
    suspend fun startInventory(timeoutMillis: Long = DEFAULT_INVENTORY_TIMEOUT): InventoryResult

    companion object {
        const val DEFAULT_INVENTORY_TIMEOUT: Long = 5_000
    }
}

/** 一号门 / 二号门，语义上对应进馆/出馆方向。*/
enum class DoorId {
    ENTRY_1, // 借书进入通道，人脸认证成功后开
    EXIT_2   // 借书成功后开，出馆方向
}

sealed class ChannelConnectResult {
    object Connected : ChannelConnectResult()
    data class Failed(val message: String) : ChannelConnectResult()
}

sealed class DoorControlResult {
    object Opened : DoorControlResult()
    data class Failed(val message: String) : DoorControlResult()
}

/**
 * 通道盘点得到的单本图书标签信息。
 *
 * - epc: 业务 EPC 编码，长度约定为 32 Bytes（建议用 64 个十六进制字符表示）；
 * - uid: 芯片出厂唯一 UID 标识。
 */
data class RfidTag(
    val epc: String,
    val uid: String
)

sealed class InventoryResult {
    data class Success(val tags: List<RfidTag>) : InventoryResult()
    object NoTags : InventoryResult()
    data class Error(val message: String) : InventoryResult()
}

/**
 * 默认的模拟实现：不依赖任何厂商库，便于在没有通道设备时联调整体流程。
 */
class SimulatedRfidChannelService : RfidChannelService {

    override suspend fun connect(): ChannelConnectResult {
        delay(200)
        return ChannelConnectResult.Connected
    }

    override suspend fun openDoor(door: DoorId): DoorControlResult {
        delay(150)
        // 这里可以根据 door 做不同日志/调试输出，当前统一视为成功
        return DoorControlResult.Opened
    }

    override suspend fun startInventory(timeoutMillis: Long): InventoryResult {
        // 模拟盘点耗时
        delay(500)
        // 简单随机：大部分情况读到标签，少数情况无标签
        return if ((0..9).random() < 8) {
            InventoryResult.Success(
                listOf(
                    RfidTag(
                        epc = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
                        uid = "SIM_TAG_123456"
                    )
                )
            )
        } else {
            InventoryResult.NoTags
        }
    }
}
