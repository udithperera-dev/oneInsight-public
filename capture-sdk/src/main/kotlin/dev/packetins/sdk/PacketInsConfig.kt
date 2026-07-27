package dev.packetins.sdk

import dev.packetins.protocol.PacketInsProtocol

data class PacketInsConfig(
    val host: String = "127.0.0.1",
    val port: Int = PacketInsProtocol.DEFAULT_PORT,
    val maxBodyBytes: Long = PacketInsProtocol.DEFAULT_MAX_BODY_BYTES,
    val maxQueuedEvents: Int = 500,
    val redactHeaders: Set<String> = DEFAULT_REDACT_HEADERS,
    val enabled: Boolean = true,
) {
    companion object {
        val DEFAULT_REDACT_HEADERS: Set<String> = setOf(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "api-key",
        )
    }
}
