package dev.packetins.sdk

import android.util.Log
import dev.packetins.protocol.EventType
import dev.packetins.protocol.PacketInsEvent
import dev.packetins.protocol.PacketInsProtocol
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class PacketInsTransport(
    private val config: PacketInsConfig,
    private val packageName: String,
    private val processId: Int,
) : EventSink, AutoCloseable {
    private val sessionId: String = UUID.randomUUID().toString()
    private val queue = ArrayBlockingQueue<PacketInsEvent>(config.maxQueuedEvents)
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    fun start() {
        if (!config.enabled) return
        if (!running.compareAndSet(false, true)) return
        worker = Thread({
            runLoop()
        }, "packetins-transport").apply {
            isDaemon = true
            start()
        }
        emit(
            PacketInsEvent(
                type = EventType.CONNECTION,
                id = UUID.randomUUID().toString(),
                correlationId = sessionId,
                sessionId = sessionId,
                timestampMs = System.currentTimeMillis(),
                packageName = packageName,
                processId = processId,
                message = "capture sdk started",
            )
        )
    }

    override fun emit(event: PacketInsEvent) {
        if (!config.enabled || !running.get()) return
        val withMeta = event.copy(
            sessionId = sessionId,
            packageName = event.packageName ?: packageName,
            processId = event.processId ?: processId,
        )
        if (!queue.offer(withMeta)) {
            queue.poll()
            queue.offer(withMeta)
        }
    }

    private fun runLoop() {
        while (running.get()) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(config.host, config.port), 2_000)
                socket.soTimeout = 0
                val output = BufferedOutputStream(socket.getOutputStream())
                Log.i(TAG, "Connected to PacketIns plugin at ${config.host}:${config.port}")
                while (running.get() && !socket.isClosed) {
                    val event = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    PacketInsProtocol.writeFrame(output, event)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Transport reconnecting: ${t.message}")
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun close() {
        running.set(false)
        worker?.interrupt()
        worker = null
        queue.clear()
    }

    companion object {
        private const val TAG = "PacketIns"
    }
}
