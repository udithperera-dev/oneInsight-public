package dev.packetins.studio.inspection

import dev.packetins.protocol.PacketInsEvent

/**
 * Maps App Inspection network events into the OneInsight protocol model.
 */
object StudioNetworkEventMapper {
    fun map(rawEvent: ByteArray): PacketInsEvent? {
        if (rawEvent.isEmpty()) return null
        return null
    }
}
