package dev.packetins.sdk

import dev.packetins.protocol.BodyEncoding
import dev.packetins.protocol.BodyPayload
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.Buffer

internal object BodyCapture {
    fun fromRequest(body: RequestBody?, maxBytes: Long): BodyPayload? {
        if (body == null) return null
        val buffer = Buffer()
        return runCatching {
            body.writeTo(buffer)
            toPayload(buffer, body.contentType(), maxBytes)
        }.getOrNull()
    }

    fun fromResponse(body: ResponseBody?, maxBytes: Long): BodyPayload? {
        if (body == null) return null
        val source = body.source()
        source.request(maxBytes + 1)
        return toPayload(source.buffer.clone(), body.contentType(), maxBytes)
    }

    private fun toPayload(buffer: Buffer, contentType: MediaType?, maxBytes: Long): BodyPayload {
        val truncated = buffer.size > maxBytes
        val size = minOf(buffer.size, maxBytes)
        val bytes = buffer.readByteArray(size)
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        return BodyPayload(
            encoding = if (text != null) BodyEncoding.UTF8 else BodyEncoding.NONE,
            contentType = contentType?.toString(),
            sizeBytes = buffer.size,
            truncated = truncated,
            text = text,
        )
    }
}
