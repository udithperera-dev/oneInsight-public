package dev.packetins.sdk

import dev.packetins.protocol.PacketInsEvent

internal class PacketInsTransport(
    private val config: PacketInsConfig,
    private val packageName: String,
    private val processId: Int,
) : EventSink {
    fun start() = Unit
    fun close() = Unit
    override fun emit(event: PacketInsEvent) = Unit
}
