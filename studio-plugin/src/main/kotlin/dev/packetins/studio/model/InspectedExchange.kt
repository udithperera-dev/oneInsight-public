package dev.packetins.studio.model

import dev.packetins.protocol.BodyPayload
import dev.packetins.protocol.EventType
import dev.packetins.protocol.HeaderEntry
import dev.packetins.protocol.PacketInsEvent
import kotlinx.serialization.Serializable

@Serializable
enum class ExchangeKind {
    HTTP,
    WEBSOCKET,
    SSE,
    GRPC,
    CONNECTION,
}

@Serializable
data class WsFrame(
    val timestampMs: Long,
    val direction: String,
    val opcode: String?,
    val body: BodyPayload?,
    val message: String?,
)

@Serializable
data class InspectedExchange(
    val correlationId: String,
    val kind: ExchangeKind,
    val method: String,
    val url: String,
    val host: String,
    val path: String,
    val statusCode: Int?,
    val durationMs: Long?,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val packageName: String?,
    val requestHeaders: List<HeaderEntry>,
    val responseHeaders: List<HeaderEntry>,
    val requestBody: BodyPayload?,
    val responseBody: BodyPayload?,
    val errorMessage: String?,
    val wsFrames: List<WsFrame>,
    val lastEventType: EventType,
    val pinned: Boolean = false,
    val tags: List<String> = emptyList(),
    val note: String? = null,
) {
    val displayStatus: String
        get() = when {
            errorMessage != null -> "ERR"
            statusCode != null -> statusCode.toString()
            kind == ExchangeKind.WEBSOCKET -> "WS"
            kind == ExchangeKind.SSE -> "SSE"
            kind == ExchangeKind.GRPC -> "gRPC"
            else -> "…"
        }

    companion object {
        fun fromEvent(event: PacketInsEvent): InspectedExchange {
            val kind = when (event.type) {
                EventType.HTTP_REQUEST, EventType.HTTP_RESPONSE, EventType.HTTP_ERROR -> when {
                    event.url?.startsWith("grpc://") == true -> ExchangeKind.GRPC
                    isSse(event.headers) -> ExchangeKind.SSE
                    isWebSocketHandshake(event.headers, emptyList(), event.statusCode) -> ExchangeKind.WEBSOCKET
                    else -> ExchangeKind.HTTP
                }
                EventType.CONNECTION, EventType.HEARTBEAT -> ExchangeKind.CONNECTION
                else -> ExchangeKind.WEBSOCKET
            }
            val frames = mutableListOf<WsFrame>()
            when (event.type) {
                EventType.WS_SEND -> frames += WsFrame(event.timestampMs, "OUT", event.wsOpcode, event.requestBody, null)
                EventType.WS_MESSAGE -> frames += WsFrame(event.timestampMs, "IN", event.wsOpcode, event.responseBody, null)
                EventType.WS_OPEN, EventType.WS_CLOSING, EventType.WS_CLOSED, EventType.WS_FAILURE ->
                    frames += WsFrame(event.timestampMs, "SYS", event.wsOpcode, null, event.message ?: event.errorMessage ?: event.type.name)
                else -> Unit
            }
            return InspectedExchange(
                correlationId = event.correlationId,
                kind = kind,
                method = event.method ?: when (kind) {
                    ExchangeKind.WEBSOCKET -> "WS"
                    ExchangeKind.SSE -> "SSE"
                    ExchangeKind.GRPC -> "gRPC"
                    ExchangeKind.CONNECTION -> "CONN"
                    else -> "?"
                },
                url = event.url.orEmpty(),
                host = event.host.orEmpty(),
                path = event.path.orEmpty(),
                statusCode = event.statusCode,
                durationMs = event.durationMs,
                startedAtMs = event.timestampMs,
                updatedAtMs = event.timestampMs,
                packageName = event.packageName,
                requestHeaders = if (event.type == EventType.HTTP_REQUEST) event.headers else emptyList(),
                responseHeaders = if (event.type == EventType.HTTP_RESPONSE || event.type == EventType.WS_OPEN) event.headers else emptyList(),
                requestBody = event.requestBody,
                responseBody = event.responseBody,
                errorMessage = event.errorMessage,
                wsFrames = frames,
                lastEventType = event.type,
            )
        }

        private fun isSse(headers: List<HeaderEntry>): Boolean =
            headers.any {
                it.name.equals("Content-Type", ignoreCase = true) &&
                    it.value.substringBefore(';').trim().equals("text/event-stream", ignoreCase = true)
            }

        private fun isWebSocketHandshake(
            requestHeaders: List<HeaderEntry>,
            responseHeaders: List<HeaderEntry>,
            statusCode: Int?,
        ): Boolean {
            val upgrade = (requestHeaders + responseHeaders).any {
                it.name.equals("Upgrade", ignoreCase = true) && it.value.equals("websocket", ignoreCase = true)
            }
            return upgrade || statusCode == 101
        }
    }

    fun merge(event: PacketInsEvent): InspectedExchange {
        val base = this
        val frames = base.wsFrames.toMutableList()
        val updatedResponseHeaders =
            if ((event.type == EventType.HTTP_RESPONSE || event.type == EventType.WS_OPEN) && event.headers.isNotEmpty()) {
                event.headers
            } else {
                base.responseHeaders
            }
        val updatedKind = when {
            event.url?.startsWith("grpc://") == true || base.url.startsWith("grpc://") -> ExchangeKind.GRPC
            isSse(updatedResponseHeaders) -> ExchangeKind.SSE
            isWebSocketHandshake(base.requestHeaders, updatedResponseHeaders, event.statusCode ?: base.statusCode) ->
                ExchangeKind.WEBSOCKET
            else -> base.kind
        }
        when (event.type) {
            EventType.WS_SEND -> frames += WsFrame(event.timestampMs, "OUT", event.wsOpcode, event.requestBody, null)
            EventType.WS_MESSAGE -> frames += WsFrame(event.timestampMs, "IN", event.wsOpcode, event.responseBody, null)
            EventType.WS_OPEN, EventType.WS_CLOSING, EventType.WS_CLOSED, EventType.WS_FAILURE ->
                frames += WsFrame(event.timestampMs, "SYS", event.wsOpcode, null, event.message ?: event.errorMessage ?: event.type.name)
            else -> Unit
        }
        return base.copy(
            kind = updatedKind,
            method = event.method ?: base.method,
            url = event.url ?: base.url,
            host = event.host ?: base.host,
            path = event.path ?: base.path,
            statusCode = event.statusCode ?: base.statusCode,
            durationMs = event.durationMs ?: base.durationMs,
            updatedAtMs = event.timestampMs,
            packageName = event.packageName ?: base.packageName,
            requestHeaders = if (event.type == EventType.HTTP_REQUEST && event.headers.isNotEmpty()) event.headers else base.requestHeaders,
            responseHeaders = updatedResponseHeaders,
            requestBody = event.requestBody ?: base.requestBody,
            responseBody = event.responseBody ?: base.responseBody,
            errorMessage = event.errorMessage ?: base.errorMessage,
            wsFrames = frames,
            lastEventType = event.type,
        )
    }

}
