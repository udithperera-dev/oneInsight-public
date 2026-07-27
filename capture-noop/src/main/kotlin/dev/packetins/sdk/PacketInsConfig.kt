package dev.packetins.sdk

data class PacketInsConfig(
    val host: String = "127.0.0.1",
    val port: Int = 54789,
    val maxBodyBytes: Long = 256 * 1024,
    val maxQueuedEvents: Int = 500,
    val redactHeaders: Set<String> = DEFAULT_REDACT_HEADERS,
    val enabled: Boolean = false,
) {
    companion object {
        val DEFAULT_REDACT_HEADERS: Set<String> = emptySet()
    }
}
