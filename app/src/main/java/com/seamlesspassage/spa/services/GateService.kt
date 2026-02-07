package com.seamlesspassage.spa.services

import kotlinx.coroutines.delay

sealed class GateResult {
    /** 整个通道流程成功：一号门进 → 盘点成功 → 二号门出。*/
    object Opened : GateResult()

    /** 任一环节失败（连接 / 开门 / 盘点），message 中给出原因。*/
    data class Failed(val message: String) : GateResult()
}

/**
 * 门禁与 RFID 通道的业务封装。
 *
 * 对上暴露一个简单的 open()，内部完成：
 * 1. 连接并初始化通道（串口 COM 等）；
 * 2. 打开一号门（借书进入通道，人脸认证成功后开）；
 * 3. 启动盘点，仅读取标签信息；
 * 4. 若盘点成功，打开二号门（借书成功后出馆）；
 * 5. 若盘点失败/无标签，则重新打开一号门，提示读者退回。
 */
class GateService(
    private val channel: RfidChannelService = QbChannelRfidChannelService()
) {

    /**
     * 仅做通道连接与初始化，用于 APP 启动阶段先把设备连好。
     */
    suspend fun initChannel(): GateResult {
        return when (val c = channel.connect()) {
            is ChannelConnectResult.Connected -> GateResult.Opened
            is ChannelConnectResult.Failed -> GateResult.Failed("通道连接失败: ${c.message}")
        }
    }

    /**
     * 打开一号门（进馆方向）。
     */
    suspend fun openEntryDoor(): GateResult {
        when (val c = channel.connect()) {
            is ChannelConnectResult.Connected -> Unit
            is ChannelConnectResult.Failed -> return GateResult.Failed("通道连接失败: ${c.message}")
        }

        return when (val d1 = channel.openDoor(DoorId.ENTRY_1)) {
            is DoorControlResult.Opened -> GateResult.Opened
            is DoorControlResult.Failed -> GateResult.Failed("一号门开门失败: ${d1.message}")
        }
    }

    /**
     * 打开二号门（出馆方向）。
     */
    suspend fun openExitDoor(): GateResult {
        when (val c = channel.connect()) {
            is ChannelConnectResult.Connected -> Unit
            is ChannelConnectResult.Failed -> return GateResult.Failed("通道连接失败: ${c.message}")
        }

        return when (val d2 = channel.openDoor(DoorId.EXIT_2)) {
            is DoorControlResult.Opened -> GateResult.Opened
            is DoorControlResult.Failed -> GateResult.Failed("二号门开门失败: ${d2.message}")
        }
    }

    /**
     * 一号门开门后启动一次盘点，仅返回盘点结果，不负责决定开哪扇门。
     */
    suspend fun inventoryAfterEntry(): InventoryResult {
        // 确保通道已连接
        when (val c = channel.connect()) {
            is ChannelConnectResult.Connected -> Unit
            is ChannelConnectResult.Failed -> return InventoryResult.Error("通道连接失败: ${c.message}")
        }

        // 打开一号门让读者进入
        when (val d1 = channel.openDoor(DoorId.ENTRY_1)) {
            is DoorControlResult.Opened -> Unit
            is DoorControlResult.Failed -> return InventoryResult.Error("一号门开门失败: ${d1.message}")
        }

        // 给读者一点时间进入通道
        delay(300)

        // 启动盘点
        return channel.startInventory()
    }
}
