package com.seamlesspassage.spa.services

/**
 * 全局通道提供者，确保整个进程内只创建一个实际的通道实现实例，
 * 避免同一串口被多个 ADReaderInterface 实例同时打开导致冲突。
 */
object GlobalChannelProvider {
    val channel: RfidChannelService by lazy { QbChannelRfidChannelService() }
}
