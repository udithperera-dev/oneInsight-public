package dev.packetins.sdk

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

object PacketIns {
    @JvmStatic
    @JvmOverloads
    fun start(context: Context, config: PacketInsConfig = PacketInsConfig()) {
        // no-op in release
    }

    @JvmStatic
    fun stop() {
        // no-op
    }

    @JvmStatic
    fun interceptor(): Interceptor = Interceptor { chain -> chain.proceed(chain.request()) }

    @JvmStatic
    fun webSocketFactory(client: OkHttpClient): WebSocket.Factory {
        return WebSocket.Factory { request: Request, listener: WebSocketListener ->
            client.newWebSocket(request, listener)
        }
    }

    @JvmStatic
    fun isStarted(): Boolean = false
}
