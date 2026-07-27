package dev.packetins.sdk

import dev.packetins.protocol.HeaderEntry
import okhttp3.Headers

internal object HeaderRedactor {
    fun redact(headers: Headers, redactNames: Set<String>): List<HeaderEntry> {
        val lower = redactNames.map { it.lowercase() }.toSet()
        return headers.map { (name, value) ->
            val redacted = if (name.lowercase() in lower) "••••" else value
            HeaderEntry(name = name, value = redacted)
        }
    }
}
