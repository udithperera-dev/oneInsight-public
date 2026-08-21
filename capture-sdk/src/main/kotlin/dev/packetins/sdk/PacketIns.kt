package dev.packetins.sdk

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicReference

object PacketIns {
    private val started = AtomicReference(false)

    @JvmStatic
    @JvmOverloads
    fun start(context: Context, config: PacketInsConfig = PacketInsConfig()) {
        started.set(config.enabled)
    }

    @JvmStatic
    fun stop() {
        started.set(false)
    }

    @JvmStatic
    fun interceptor(): Interceptor = PacketInsInterceptor()

    @JvmStatic
    fun webSocketFactory(client: OkHttpClient): WebSocket.Factory {
        return WebSocket.Factory { request: Request, listener: WebSocketListener ->
            client.newWebSocket(request, listener)
        }
    }

    @JvmStatic
    fun isStarted(): Boolean = started.get()
}
