package dev.packetins.studio.inspection

import dev.packetins.protocol.BodyEncoding
import dev.packetins.protocol.BodyPayload
import dev.packetins.protocol.EventType
import dev.packetins.protocol.HeaderEntry
import dev.packetins.protocol.PacketInsEvent
import dev.packetins.protocol.PacketInsProtocol
import org.brotli.dec.BrotliInputStream
import studio.network.inspection.NetworkInspectorProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

internal class StudioNetworkEventMapper(
    private val packageName: () -> String?,
    private val processId: () -> Int?,
    private val maxBodyBytes: Int = PacketInsProtocol.DEFAULT_MAX_BODY_BYTES.toInt(),
) {
    private data class ConnectionState(
        var url: String = "",
        var method: String = "?",
        var startedAtMs: Long = 0,
        var requestHeaders: List<HeaderEntry> = emptyList(),
        var responseHeaders: List<HeaderEntry> = emptyList(),
        var statusCode: Int? = null,
        var requestPayload: ByteArray = ByteArray(0),
        var responsePayload: ByteArray = ByteArray(0),
        var requestTruncated: Boolean = false,
        var responseTruncated: Boolean = false,
    )

    private data class GrpcState(
        var service: String = "",
        var method: String = "",
        var startedAtMs: Long = 0,
        var requestHeaders: List<HeaderEntry> = emptyList(),
        var responseHeaders: List<HeaderEntry> = emptyList(),
    )

    private val connections = ConcurrentHashMap<Long, ConnectionState>()
    private val grpcConnections = ConcurrentHashMap<Long, GrpcState>()
    private var firstAgentTimestampNs: Long? = null
    private var firstWallTimestampMs: Long = 0

    fun map(bytes: ByteArray): List<PacketInsEvent> {
        val event = NetworkInspectorProtocol.Event.parseFrom(bytes)
        if (event.hasGrpcEvent()) return mapGrpc(event)
        if (!event.hasHttpConnectionEvent()) return emptyList()

        val http = event.httpConnectionEvent
        val connectionId = http.connectionId
        val correlationId = "studio-$connectionId"
        val timestampMs = wallTime(event.timestamp)
        val state = connections.computeIfAbsent(connectionId) { ConnectionState(startedAtMs = timestampMs) }

        return when {
            http.hasHttpRequestStarted() -> {
                val started = http.httpRequestStarted
                state.url = started.url
                state.method = started.method.ifBlank { "?" }
                state.startedAtMs = timestampMs
                state.requestHeaders = headers(started.headersList)
                listOf(packetEvent(
                    type = EventType.HTTP_REQUEST,
                    correlationId = correlationId,
                    timestampMs = timestampMs,
                    state = state,
                    headers = state.requestHeaders,
                    message = "Captured by Android Studio ${started.transport.name} hook",
                ))
            }

            http.hasRequestPayload() -> {
                val merged = mergePayload(state.requestPayload, http.requestPayload.payload.toByteArray())
                state.requestPayload = merged.bytes
                state.requestTruncated = state.requestTruncated || merged.truncated
                listOf(packetEvent(
                    type = EventType.HTTP_REQUEST,
                    correlationId = correlationId,
                    timestampMs = timestampMs,
                    state = state,
                    headers = state.requestHeaders,
                    requestBody = body(state.requestPayload, state.requestHeaders, state.requestTruncated),
                ))
            }

            http.hasHttpResponseStarted() -> {
                val started = http.httpResponseStarted
                state.responseHeaders = headers(started.headersList)
                state.statusCode = started.responseCode
                listOf(packetEvent(
                    type = EventType.HTTP_RESPONSE,
                    correlationId = correlationId,
                    timestampMs = timestampMs,
                    state = state,
                    headers = state.responseHeaders,
                    statusCode = state.statusCode,
                    durationMs = timestampMs - state.startedAtMs,
                ))
            }

            http.hasResponsePayload() -> {
                val merged = mergePayload(state.responsePayload, http.responsePayload.payload.toByteArray())
                state.responsePayload = merged.bytes
                state.responseTruncated = state.responseTruncated || merged.truncated
                listOf(packetEvent(
                    type = EventType.HTTP_RESPONSE,
                    correlationId = correlationId,
                    timestampMs = timestampMs,
                    state = state,
                    headers = state.responseHeaders,
                    statusCode = state.statusCode,
                    durationMs = timestampMs - state.startedAtMs,
                    responseBody = body(state.responsePayload, state.responseHeaders, state.responseTruncated),
                ))
            }

            http.hasHttpClosed() -> {
                val result = if (http.httpClosed.completed) {
                    emptyList()
                } else {
                    listOf(packetEvent(
                        type = EventType.HTTP_ERROR,
                        correlationId = correlationId,
                        timestampMs = timestampMs,
                        state = state,
                        statusCode = state.statusCode,
                        durationMs = timestampMs - state.startedAtMs,
                        errorMessage = "Connection closed before completion",
                    ))
                }
                connections.remove(connectionId)
                result
            }

            else -> emptyList()
        }
    }

    private fun mapGrpc(event: NetworkInspectorProtocol.Event): List<PacketInsEvent> {
        val grpc = event.grpcEvent
        val id = grpc.connectionId
        val correlationId = "studio-grpc-$id"
        val timestampMs = wallTime(event.timestamp)
        val state = grpcConnections.computeIfAbsent(id) { GrpcState(startedAtMs = timestampMs) }
        return when {
            grpc.hasGrpcCallStarted() -> {
                val started = grpc.grpcCallStarted
                state.service = started.service
                state.method = started.method
                state.startedAtMs = timestampMs
                state.requestHeaders = grpcHeaders(started.requestHeadersList)
                listOf(grpcPacket(
                    EventType.HTTP_REQUEST,
                    correlationId,
                    timestampMs,
                    state,
                    headers = state.requestHeaders,
                    message = "gRPC call started",
                ))
            }
            grpc.hasGrpcMessageSent() -> listOf(grpcPacket(
                EventType.HTTP_REQUEST,
                correlationId,
                timestampMs,
                state,
                headers = state.requestHeaders,
                requestBody = grpcBody(grpc.grpcMessageSent.payload),
                message = "gRPC message sent",
            ))
            grpc.hasGrpcResponseHeaders() -> {
                state.responseHeaders = grpcHeaders(grpc.grpcResponseHeaders.responseHeadersList)
                listOf(grpcPacket(
                    EventType.HTTP_RESPONSE,
                    correlationId,
                    timestampMs,
                    state,
                    headers = state.responseHeaders,
                    durationMs = timestampMs - state.startedAtMs,
                    message = "gRPC response headers",
                ))
            }
            grpc.hasGrpcMessageReceived() -> listOf(grpcPacket(
                EventType.HTTP_RESPONSE,
                correlationId,
                timestampMs,
                state,
                headers = state.responseHeaders,
                responseBody = grpcBody(grpc.grpcMessageReceived.payload),
                durationMs = timestampMs - state.startedAtMs,
                message = "gRPC message received",
            ))
            grpc.hasGrpcCallEnded() -> {
                val ended = grpc.grpcCallEnded
                val error = ended.error.takeIf { ended.hasError() && it.isNotBlank() }
                    ?: ended.status.takeIf { it.isNotBlank() && !it.equals("OK", ignoreCase = true) }
                grpcConnections.remove(id)
                listOf(grpcPacket(
                    if (error == null) EventType.HTTP_RESPONSE else EventType.HTTP_ERROR,
                    correlationId,
                    timestampMs,
                    state,
                    headers = grpcHeaders(ended.trailersList),
                    durationMs = timestampMs - state.startedAtMs,
                    errorMessage = error,
                    message = "gRPC call ended: ${ended.status}",
                ))
            }
            else -> emptyList()
        }
    }

    private fun grpcPacket(
        type: EventType,
        correlationId: String,
        timestampMs: Long,
        state: GrpcState,
        headers: List<HeaderEntry> = emptyList(),
        durationMs: Long? = null,
        requestBody: BodyPayload? = null,
        responseBody: BodyPayload? = null,
        errorMessage: String? = null,
        message: String? = null,
    ): PacketInsEvent {
        val path = "/${state.service}/${state.method}".replace("//", "/")
        return PacketInsEvent(
            type = type,
            id = "$correlationId-$timestampMs-${type.name}",
            correlationId = correlationId,
            sessionId = "studio-app-inspection",
            timestampMs = timestampMs,
            packageName = packageName(),
            processId = processId(),
            method = "gRPC",
            url = "grpc://${state.service}$path",
            host = state.service,
            path = path,
            durationMs = durationMs,
            headers = headers,
            requestBody = requestBody,
            responseBody = responseBody,
            errorMessage = errorMessage,
            message = message,
        )
    }

    private fun grpcHeaders(
        values: List<NetworkInspectorProtocol.GrpcEvent.GrpcMetadata>,
    ): List<HeaderEntry> = values.flatMap { metadata ->
        metadata.valuesList.map { value -> HeaderEntry(metadata.key, value) }
    }

    private fun grpcBody(payload: NetworkInspectorProtocol.GrpcEvent.GrpcPayload): BodyPayload {
        if (payload.text.isNotBlank()) {
            return BodyPayload(
                BodyEncoding.UTF8,
                payload.type.ifBlank { "application/grpc" },
                payload.text.toByteArray(StandardCharsets.UTF_8).size.toLong(),
                text = payload.text,
            )
        }
        val bytes = payload.bytes.toByteArray()
        return BodyPayload(
            BodyEncoding.BASE64,
            payload.type.ifBlank { "application/grpc" },
            bytes.size.toLong(),
            text = Base64.getEncoder().encodeToString(bytes),
        )
    }

    private fun packetEvent(
        type: EventType,
        correlationId: String,
        timestampMs: Long,
        state: ConnectionState,
        headers: List<HeaderEntry> = emptyList(),
        statusCode: Int? = null,
        durationMs: Long? = null,
        requestBody: BodyPayload? = null,
        responseBody: BodyPayload? = null,
        errorMessage: String? = null,
        message: String? = null,
    ): PacketInsEvent {
        val uri = runCatching { URI(state.url) }.getOrNull()
        return PacketInsEvent(
            type = type,
            id = "$correlationId-$timestampMs-${type.name}",
            correlationId = correlationId,
            sessionId = "studio-app-inspection",
            timestampMs = timestampMs,
            packageName = packageName(),
            processId = processId(),
            method = state.method,
            url = state.url,
            host = uri?.host.orEmpty(),
            path = uri?.rawPath ?: state.url,
            statusCode = statusCode,
            durationMs = durationMs,
            headers = headers,
            requestBody = requestBody,
            responseBody = responseBody,
            errorMessage = errorMessage,
            message = message,
        )
    }

    private fun headers(
        values: List<NetworkInspectorProtocol.HttpConnectionEvent.Header>,
    ): List<HeaderEntry> = values.flatMap { header ->
        header.valuesList.map { value -> HeaderEntry(header.key, value) }
    }

    private fun headerValue(headers: List<HeaderEntry>, name: String): String? =
        headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    private data class MergedPayload(val bytes: ByteArray, val truncated: Boolean)

    private fun mergePayload(existing: ByteArray, incoming: ByteArray): MergedPayload {
        val max = maxBodyBytes
        if (existing.isEmpty()) {
            return MergedPayload(incoming.copyOf(minOf(incoming.size, max)), incoming.size > max)
        }
        if (incoming.size >= existing.size && incoming.copyOfRange(0, existing.size).contentEquals(existing)) {
            return MergedPayload(incoming.copyOf(minOf(incoming.size, max)), incoming.size > max)
        }
        val remaining = max - existing.size
        if (remaining <= 0) return MergedPayload(existing, incoming.isNotEmpty())
        return MergedPayload(
            existing + incoming.copyOf(minOf(incoming.size, remaining)),
            incoming.size > remaining,
        )
    }

    private fun body(rawBytes: ByteArray, headers: List<HeaderEntry>, truncated: Boolean = false): BodyPayload {
        val contentType = headerValue(headers, "Content-Type")
        val contentEncoding = headerValue(headers, "Content-Encoding")
        val decoded = decodeBodyBytes(rawBytes, contentEncoding)
        val displayType = when {
            decoded.codec == null -> contentType
            contentType.isNullOrBlank() -> "decoded from ${decoded.codec}"
            else -> "$contentType · decoded from ${decoded.codec}"
        }
        val bytes = decoded.bytes
        val charset = charsetFromContentType(contentType)
        val stillCompressed = isGzipMagic(bytes)
        val isText = !stillCompressed && (
            contentType?.startsWith("text/", ignoreCase = true) == true ||
            contentType?.contains("json", ignoreCase = true) == true ||
            contentType?.contains("xml", ignoreCase = true) == true ||
            contentType?.contains("javascript", ignoreCase = true) == true ||
            contentType?.contains("x-www-form-urlencoded", ignoreCase = true) == true ||
            looksLikeText(bytes)
        )
        // Never turn compressed/binary bytes into a UTF-8 String — that irreversibly corrupts the payload.
        return if (isText) {
            BodyPayload(
                encoding = BodyEncoding.UTF8,
                contentType = displayType,
                sizeBytes = bytes.size.toLong(),
                truncated = truncated,
                text = String(bytes, charset),
            )
        } else {
            BodyPayload(
                encoding = BodyEncoding.BASE64,
                contentType = displayType,
                sizeBytes = bytes.size.toLong(),
                truncated = truncated,
                text = Base64.getEncoder().encodeToString(bytes),
            )
        }
    }

    private data class DecodedBody(val bytes: ByteArray, val codec: String?)

    private fun decodeBodyBytes(rawBytes: ByteArray, contentEncoding: String?): DecodedBody {
        val encoding = contentEncoding
            ?.split(',')
            ?.map { it.trim().lowercase(Locale.US) }
            ?.filter { it.isNotEmpty() && it != "identity" }
            .orEmpty()
        if (encoding.isEmpty()) {
            return if (isGzipMagic(rawBytes)) {
                gunzipLenient(rawBytes)?.let { DecodedBody(it, "gzip") } ?: DecodedBody(rawBytes, null)
            } else {
                DecodedBody(rawBytes, null)
            }
        }

        var current = rawBytes
        val applied = mutableListOf<String>()
        // Content-Encoding is listed outer-first; unwrap in reverse.
        for (codec in encoding.asReversed()) {
            val next = when (codec) {
                "gzip", "x-gzip" -> gunzipLenient(current)
                "deflate" -> inflate(current)
                "br" -> brotli(current)
                else -> null
            } ?: break
            current = next
            applied += codec
        }
        // Even when Content-Encoding was missing/wrong, unwrap gzip magic.
        if (applied.isEmpty() && isGzipMagic(current)) {
            gunzipLenient(current)?.let { return DecodedBody(it, "gzip") }
        }
        return DecodedBody(current, applied.takeIf { it.isNotEmpty() }?.joinToString(" + "))
    }

    private fun isGzipMagic(bytes: ByteArray): Boolean =
        bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0x1f && (bytes[1].toInt() and 0xff) == 0x8b

    private fun gunzipLenient(bytes: ByteArray): ByteArray? {
        runCatching {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                input.readBytes(maxBodyBytes = maxBodyBytes)
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }

        if (!isGzipMagic(bytes) || bytes.size < 10) return null
        val headerLen = gzipHeaderLength(bytes) ?: return null
        val inflater = java.util.zip.Inflater(true)
        return try {
            inflater.setInput(bytes, headerLen, bytes.size - headerLen)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (!inflater.finished() && total < maxBodyBytes) {
                val count = runCatching { inflater.inflate(buffer) }.getOrDefault(0)
                if (count <= 0) break
                val toWrite = minOf(count, maxBodyBytes - total)
                output.write(buffer, 0, toWrite)
                total += toWrite
            }
            output.toByteArray().takeIf { it.isNotEmpty() }
        } finally {
            inflater.end()
        }
    }

    private fun gzipHeaderLength(bytes: ByteArray): Int? {
        if (!isGzipMagic(bytes) || bytes.size < 10) return null
        val flags = bytes[3].toInt() and 0xff
        var offset = 10
        if (flags and 0x04 != 0) {
            if (bytes.size < offset + 2) return null
            val xlen = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
            offset += 2 + xlen
        }
        if (flags and 0x08 != 0) {
            while (offset < bytes.size && bytes[offset] != 0.toByte()) offset++
            offset++
        }
        if (flags and 0x10 != 0) {
            while (offset < bytes.size && bytes[offset] != 0.toByte()) offset++
            offset++
        }
        if (flags and 0x02 != 0) offset += 2
        if (offset >= bytes.size) return null
        return offset
    }

    private fun inflate(bytes: ByteArray): ByteArray? {
        fun tryInflate(nowrap: Boolean): ByteArray? = runCatching {
            val inflater = java.util.zip.Inflater(nowrap)
            try {
                InflaterInputStream(ByteArrayInputStream(bytes), inflater).use { input ->
                    input.readBytes(maxBodyBytes = maxBodyBytes)
                }
            } finally {
                inflater.end()
            }
        }.getOrNull()
        return tryInflate(nowrap = false) ?: tryInflate(nowrap = true)
    }

    private fun brotli(bytes: ByteArray): ByteArray? = runCatching {
        BrotliInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.readBytes(maxBodyBytes = maxBodyBytes)
        }
    }.getOrNull()

    private fun java.io.InputStream.readBytes(maxBodyBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            val remaining = maxBodyBytes - total
            if (remaining <= 0) break
            val toWrite = minOf(read, remaining)
            output.write(buffer, 0, toWrite)
            total += toWrite
            if (toWrite < read) break
        }
        return output.toByteArray()
    }

    private fun charsetFromContentType(contentType: String?): Charset {
        if (contentType == null) return StandardCharsets.UTF_8
        val charsetToken = contentType.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.removeSurrounding("\"")
        return runCatching { Charset.forName(charsetToken) }.getOrDefault(StandardCharsets.UTF_8)
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        if (isGzipMagic(bytes)) return false
        val sample = bytes.take(512)
        if (sample.any { (it.toInt() and 0xff) == 0 }) return false
        return sample.all { byte ->
            val value = byte.toInt() and 0xff
            value == 9 || value == 10 || value == 13 || value in 32..126 || value >= 0x80
        }
    }

    @Synchronized
    private fun wallTime(agentTimestampNs: Long): Long {
        val first = firstAgentTimestampNs
        if (first == null) {
            firstAgentTimestampNs = agentTimestampNs
            firstWallTimestampMs = System.currentTimeMillis()
            return firstWallTimestampMs
        }
        return firstWallTimestampMs + (agentTimestampNs - first) / 1_000_000L
    }
}
