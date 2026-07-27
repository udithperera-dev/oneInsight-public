package dev.packetins.sdk

import dev.packetins.protocol.EventType
import dev.packetins.protocol.PacketInsEvent
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID

class PacketInsWebSocketFactory internal constructor(
    private val delegate: WebSocket.Factory,
    private val transport: EventSink,
    private val config: PacketInsConfig,
) : WebSocket.Factory {
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val correlationId = UUID.randomUUID().toString()
        val url = request.url
        val wrappedListener = CapturingWebSocketListener(
            delegate = listener,
            transport = transport,
            config = config,
            correlationId = correlationId,
            request = request,
            url = url,
        )
        val socket = delegate.newWebSocket(request, wrappedListener)
        return CapturingWebSocket(socket, transport, config, correlationId, request, url)
    }
}

private class CapturingWebSocket(
    private val delegate: WebSocket,
    private val transport: EventSink,
    private val config: PacketInsConfig,
    private val correlationId: String,
    private val request: Request,
    private val url: HttpUrl,
) : WebSocket {
    override fun request(): Request = delegate.request()

    override fun queueSize(): Long = delegate.queueSize()

    override fun send(text: String): Boolean {
        transport.emit(
            PacketInsEvent(
                type = EventType.WS_SEND,
                id = UUID.randomUUID().toString(),
                correlationId = correlationId,
                sessionId = "",
                timestampMs = System.currentTimeMillis(),
                method = "WS",
                url = request.url.toString(),
                host = url.host,
                path = url.encodedPath,
                wsOpcode = "text",
                requestBody = BodyCapture.fromText(text, config.maxBodyBytes),
            )
        )
        return delegate.send(text)
    }

    override fun send(bytes: ByteString): Boolean {
        transport.emit(
            PacketInsEvent(
                type = EventType.WS_SEND,
                id = UUID.randomUUID().toString(),
                correlationId = correlationId,
                sessionId = "",
                timestampMs = System.currentTimeMillis(),
                method = "WS",
                url = request.url.toString(),
                host = url.host,
                path = url.encodedPath,
                wsOpcode = "binary",
                requestBody = BodyCapture.fromBinary(bytes.toByteArray(), config.maxBodyBytes),
            )
        )
        return delegate.send(bytes)
    }

    override fun close(code: Int, reason: String?): Boolean = delegate.close(code, reason)

    override fun cancel() = delegate.cancel()
}

private class CapturingWebSocketListener(
    private val delegate: WebSocketListener,
    private val transport: EventSink,
    private val config: PacketInsConfig,
    private val correlationId: String,
    private val request: Request,
    private val url: HttpUrl,
) : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
        transport.emit(
            PacketInsEvent(
                type = EventType.WS_OPEN,
                id = UUID.randomUUID().toString(),
                correlationId = correlationId,
                sessionId = "",
                timestampMs = System.currentTimeMillis(),
                method = "WS",
                url = request.url.toString(),
                host = url.host,
                path = url.encodedPath,
                statusCode = response.code,
                headers = HeaderRedactor.redact(response.headers, config.redactHeaders),
                message = "websocket opened",
            )
        )
        delegate.onOpen(webSocket, response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        transport.emit(
            PacketInsEvent(
                type = EventType.WS_MESSAGE,
                id = UUID.randomUUID().toString(),
                correlationId = correlationId,
                sessionId = "",
                timestampMs = System.currentTimeMillis(),
                method = "WS",
                url = request.url.toString(),
                host = url.host,
                path = url.encodedPath,
                wsOpcode = "text",
                responseBody = BodyCapture.fromText(text, config.maxBodyBytes),
            )
        )
        delegate.onMessage(webSocket, text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        transport.emit(
            PacketInsEvent(
                type = EventType.WS_MESSAGE,
                id = UUID.randomUUID().toString(),
                correlationId = correlationId,
                sessionId = "",
                timestampMs = System.currentTimeMillis(),
                method = "WS",
                url = request.url.toString(),
                host = url.host,
                path = url.encodedPath,
                wsOpcode = "binary",
                responseBody = BodyCapture.fromBinary(bytes.toByteArray(), config.maxBodyBytes),
            )
        )
        delegate.onMessage(webSocket, bytes)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        transport.emit(
            PacketInsEvent(
                type = EventType.WS_CLOSING,
                id = UUID.randomUUID().toString(),
                correlationId = correlationId,
                sessionId = "",
                timestampMs = System.currentTimeMillis(),
                method = "WS",
                url = request.url.toString(),
                host = url.host,
                path = url.encodedPath,
                wsCode = code,
                wsReason = reason,
            )
        )
        delegate.onClosing(webSocket, code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        transport.emit(
            PacketInsEvent(
                type = EventType.WS_CLOSED,
                id = UUID.randomUUID().toString(),
                correlationId = correlationId,
                sessionId = "",
                timestampMs = System.currentTimeMillis(),
                method = "WS",
                url = request.url.toString(),
                host = url.host,
                path = url.encodedPath,
                wsCode = code,
                wsReason = reason,
            )
        )
        delegate.onClosed(webSocket, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        transport.emit(
            PacketInsEvent(
                type = EventType.WS_FAILURE,
                id = UUID.randomUUID().toString(),
                correlationId = correlationId,
                sessionId = "",
                timestampMs = System.currentTimeMillis(),
                method = "WS",
                url = request.url.toString(),
                host = url.host,
                path = url.encodedPath,
                statusCode = response?.code,
                errorMessage = t.message ?: t.javaClass.simpleName,
            )
        )
        delegate.onFailure(webSocket, t, response)
    }
}
