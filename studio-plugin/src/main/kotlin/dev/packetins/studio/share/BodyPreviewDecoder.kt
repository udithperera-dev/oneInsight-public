package dev.packetins.studio.share

import dev.packetins.protocol.BodyEncoding
import dev.packetins.protocol.BodyPayload
import java.nio.charset.StandardCharsets
import java.util.Base64

object BodyPreviewDecoder {
    fun preview(body: BodyPayload?, maxChars: Int = 8_000): String {
        if (body == null) return ""
        val text = when (body.encoding) {
            BodyEncoding.UTF8 -> body.text.orEmpty()
            BodyEncoding.BASE64 -> runCatching {
                String(Base64.getDecoder().decode(body.text.orEmpty()), StandardCharsets.UTF_8)
            }.getOrDefault(body.text.orEmpty())
            BodyEncoding.NONE -> body.text.orEmpty()
        }
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n… truncated …"
    }
}
