package dev.packetins.sdk

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import java.util.concurrent.atomic.AtomicReference

object PacketIns {
    private val state = AtomicReference<State?>(null)

    @JvmStatic
    @JvmOverloads
    fun start(context: Context, config: PacketInsConfig = PacketInsConfig()) {
        val appContext = context.applicationContext
        val transport = PacketInsTransport(
            config = config,
            packageName = appContext.packageName,
            processId = android.os.Process.myPid(),
        )
        val interceptor = PacketInsInterceptor(transport, config)
        val previous = state.getAndSet(State(config, transport, interceptor))
        previous?.transport?.close()
        transport.start()
    }

    @JvmStatic
    fun stop() {
        state.getAndSet(null)?.transport?.close()
    }

    @JvmStatic
    fun interceptor(): Interceptor {
        val current = state.get()
            ?: error("PacketIns.start(context) must be called before interceptor()")
        return current.interceptor
    }

    @JvmStatic
    fun webSocketFactory(client: OkHttpClient): WebSocket.Factory {
        val current = state.get()
            ?: error("PacketIns.start(context) must be called before webSocketFactory()")
        return PacketInsWebSocketFactory(client, current.transport, current.config)
    }

    @JvmStatic
    fun isStarted(): Boolean = state.get() != null

    private data class State(
        val config: PacketInsConfig,
        val transport: PacketInsTransport,
        val interceptor: PacketInsInterceptor,
    )
}
