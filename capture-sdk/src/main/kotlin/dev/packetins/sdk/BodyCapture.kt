package dev.packetins.sdk

import dev.packetins.protocol.BodyEncoding
import dev.packetins.protocol.BodyPayload
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.Buffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64

internal object BodyCapture {
    fun fromRequestBody(body: RequestBody?, maxBytes: Long): BodyPayload? {
        if (body == null) return null
        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            fromBytes(buffer.readByteArray(), body.contentType(), maxBytes)
        } catch (_: Exception) {
            BodyPayload(
                encoding = BodyEncoding.NONE,
                contentType = body.contentType()?.toString(),
                sizeBytes = body.contentLength().coerceAtLeast(0),
                truncated = false,
                text = null,
            )
        }
    }

    fun fromPeekedBytes(bytes: ByteArray, contentType: MediaType?, maxBytes: Long): BodyPayload {
        return fromBytes(bytes, contentType, maxBytes)
    }

    fun fromText(text: String, maxBytes: Long, contentType: String? = "text/plain"): BodyPayload {
        val raw = text.toByteArray(StandardCharsets.UTF_8)
        return fromBytes(raw, contentType?.toMediaTypeOrNull(), maxBytes)
    }

    fun fromBinary(bytes: ByteArray, maxBytes: Long, contentType: String? = "application/octet-stream"): BodyPayload {
        return fromBytes(bytes, contentType?.toMediaTypeOrNull(), maxBytes)
    }

    private fun fromBytes(bytes: ByteArray, contentType: MediaType?, maxBytes: Long): BodyPayload {
        val truncated = bytes.size > maxBytes
        val slice = if (truncated) bytes.copyOf(maxBytes.toInt()) else bytes
        val charset = charsetFor(contentType)
        val looksText = isProbablyText(slice, contentType)
        return if (looksText) {
            BodyPayload(
                encoding = BodyEncoding.UTF8,
                contentType = contentType?.toString(),
                sizeBytes = bytes.size.toLong(),
                truncated = truncated,
                text = String(slice, charset),
            )
        } else {
            BodyPayload(
                encoding = BodyEncoding.BASE64,
                contentType = contentType?.toString(),
                sizeBytes = bytes.size.toLong(),
                truncated = truncated,
                text = Base64.getEncoder().encodeToString(slice),
            )
        }
    }

    private fun charsetFor(contentType: MediaType?): Charset {
        return contentType?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
    }

    private fun isProbablyText(bytes: ByteArray, contentType: MediaType?): Boolean {
        val type = contentType?.toString()?.lowercase().orEmpty()
        if (type.startsWith("text/") ||
            type.contains("json") ||
            type.contains("xml") ||
            type.contains("javascript") ||
            type.contains("x-www-form-urlencoded")
        ) {
            return true
        }
        if (bytes.isEmpty()) return true
        var control = 0
        val sample = bytes.take(512)
        for (b in sample) {
            val c = b.toInt() and 0xff
            if (c == 9 || c == 10 || c == 13) continue
            if (c < 32 || c == 127) control++
        }
        return control * 100 / sample.size < 5
    }
}
