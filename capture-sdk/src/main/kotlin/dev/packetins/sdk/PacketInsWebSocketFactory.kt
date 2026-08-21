package dev.packetins.sdk

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal class PacketInsWebSocketFactory(
    private val client: OkHttpClient,
    private val transport: PacketInsTransport,
    private val config: PacketInsConfig,
) : WebSocket.Factory {
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        return client.newWebSocket(request, listener)
    }
}
