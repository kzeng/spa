package com.seamlesspassage.spa.services

import com.seamlesspassage.spa.AppConfig
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
    private val channel: RfidChannelService = GlobalChannelProvider.channel
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
     * 对于 QbChannelService：使用 QB_DetectBooks(detectResult=0) 开一号门退回
     * 对于普通 RfidChannelService：使用 openDoor(DoorId.ENTRY_1)
     */
    suspend fun openEntryDoor(): GateResult {
        when (val c = channel.connect()) {
            is ChannelConnectResult.Connected -> Unit
            is ChannelConnectResult.Failed -> return GateResult.Failed("通道连接失败: ${c.message}")
        }

        return if (channel is QbChannelService) {
            when (val d1 = channel.detectBooks(0)) {
                is DoorControlResult.Opened -> GateResult.Opened
                is DoorControlResult.Failed -> GateResult.Failed("一号门开门失败: ${d1.message}")
            }
        } else {
            when (val d1 = channel.openDoor(DoorId.ENTRY_1)) {
                is DoorControlResult.Opened -> GateResult.Opened
                is DoorControlResult.Failed -> GateResult.Failed("一号门开门失败: ${d1.message}")
            }
        }
    }

    /**
     * 打开二号门（出馆方向）。
     * 对于 QbChannelService：使用 QB_DetectBooks(detectResult=1) 开二号门出馆
     * 对于普通 RfidChannelService：使用 openDoor(DoorId.EXIT_2)
     */
    suspend fun openExitDoor(): GateResult {
        when (val c = channel.connect()) {
            is ChannelConnectResult.Connected -> Unit
            is ChannelConnectResult.Failed -> return GateResult.Failed("通道连接失败: ${c.message}")
        }

        return if (channel is QbChannelService) {
            when (val d2 = channel.detectBooks(1)) {
                is DoorControlResult.Opened -> GateResult.Opened
                is DoorControlResult.Failed -> GateResult.Failed("二号门开门失败: ${d2.message}")
            }
        } else {
            when (val d2 = channel.openDoor(DoorId.EXIT_2)) {
                is DoorControlResult.Opened -> GateResult.Opened
                is DoorControlResult.Failed -> GateResult.Failed("二号门开门失败: ${d2.message}")
            }
        }
    }

    /**
     * 还书进馆场景：依次打开二号门和一号门，不做盘点。
     *
     * 语义：
     * - 先开二号门，让读者从馆外侧进入通道；
     * - 再开一号门，让读者从通道进入馆内。
     */
    suspend fun openAllDoorsForReturn(): GateResult {
        // 先开二号门（出馆方向的那扇门）
        when (val exitResult = openExitDoor()) {
            is GateResult.Failed -> return exitResult
            is GateResult.Opened -> Unit
        }

        // 给闸机一点时间完成动作
        delay(AppConfig.OPEN_DOOR_DELAY_MS)

        // 再开一号门（进馆方向的那扇门）
        return when (val entryResult = openEntryDoor()) {
            is GateResult.Failed -> entryResult
            is GateResult.Opened -> GateResult.Opened
        }
    }

    /**
     * 一号门开门后启动一次盘点，仅返回盘点结果，不负责决定开哪扇门。
     * 对于 QbChannelService：使用 QB_Authentication(flag=1) 开一号门 + 启动盘点
     * 对于普通 RfidChannelService：使用 openDoor(DoorId.ENTRY_1)
     */
    suspend fun inventoryAfterEntry(): InventoryResult {
        // 确保通道已连接
        when (val c = channel.connect()) {
            is ChannelConnectResult.Connected -> Unit
            is ChannelConnectResult.Failed -> return InventoryResult.Error("通道连接失败: ${c.message}")
        }

        // 开门逻辑
        if (channel is QbChannelService) {
            // 使用 QB_Authentication(flag=2) 开一号门 + 启动盘点（借书方向）
            when (val auth = channel.authenticate(2)) {
                is DoorControlResult.Opened -> Unit
                is DoorControlResult.Failed -> return InventoryResult.Error("一号门开门失败: ${auth.message}")
            }
        } else {
            // 使用普通开门方法
            when (val d1 = channel.openDoor(DoorId.ENTRY_1)) {
                is DoorControlResult.Opened -> Unit
                is DoorControlResult.Failed -> return InventoryResult.Error("一号门开门失败: ${d1.message}")
            }
        }

        // 给读者一点时间进入通道
        delay(AppConfig.ENTRY_DOOR_DELAY_MS)

        // 启动盘点
        return channel.startInventory()
    }
}
