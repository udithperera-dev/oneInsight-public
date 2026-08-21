package dev.packetins.studio.share

import dev.packetins.protocol.PacketInsProtocol
import dev.packetins.studio.model.InspectedExchange
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path

object TrafficShare {
    fun toJson(exchanges: List<InspectedExchange>): String =
        PacketInsProtocol.json.encodeToString(exchanges)

    fun exportJson(path: Path, exchanges: List<InspectedExchange>) {
        Files.writeString(path, toJson(exchanges))
    }

    fun curl(exchange: InspectedExchange): String {
        val headerFlags = exchange.requestHeaders.joinToString(" \\\n  ") {
            "-H '${it.name}: ${it.value.replace("'", "'\\''")}'"
        }
        val body = BodyPreviewDecoder.preview(exchange.requestBody, maxChars = 50_000)
        val bodyFlag = if (body.isBlank()) "" else " \\\n  --data-binary '${body.replace("'", "'\\''")}'"
        val headersPart = if (headerFlags.isBlank()) "" else " \\\n  $headerFlags"
        return "curl -X ${exchange.method}$headersPart$bodyFlag \\\n  '${exchange.url}'"
    }
}
