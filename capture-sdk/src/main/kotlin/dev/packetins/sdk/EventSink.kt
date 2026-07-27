package dev.packetins.sdk

import dev.packetins.protocol.PacketInsEvent

internal interface EventSink {
    fun emit(event: PacketInsEvent)
}
