package dev.packetins.sdk

import dev.packetins.protocol.EventType
import dev.packetins.protocol.PacketInsEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PacketInsInterceptorTest {
    private lateinit var server: MockWebServer
    private val events = CopyOnWriteArrayList<PacketInsEvent>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun capturesRestRequestAndResponse() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true}"""))
        val latch = CountDownLatch(2)
        val sink = RecordingSink(events, latch)
        val config = PacketInsConfig(enabled = true)
        val client = OkHttpClient.Builder()
            .addInterceptor(PacketInsInterceptor(sink, config))
            .build()

        val request = Request.Builder()
            .url(server.url("/items"))
            .header("Authorization", "secret")
            .post("""{"name":"a"}""".toRequestBody())
            .build()
        client.newCall(request).execute().use { response ->
            assertEquals(201, response.code)
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS))
        val requestEvent = events.first { it.type == EventType.HTTP_REQUEST }
        val responseEvent = events.first { it.type == EventType.HTTP_RESPONSE }
        assertEquals("POST", requestEvent.method)
        assertEquals("••••", requestEvent.headers.first { it.name.equals("Authorization", true) }.value)
        assertEquals(201, responseEvent.statusCode)
        assertEquals(requestEvent.correlationId, responseEvent.correlationId)
        assertTrue(responseEvent.responseBody?.text?.contains("ok") == true)
    }

    @Test
    fun capturesWebSocketFrames() {
        val latch = CountDownLatch(3)
        val sink = RecordingSink(events, latch)
        val config = PacketInsConfig(enabled = true)
        lateinit var appListener: WebSocketListener
        val fakeFactory = WebSocket.Factory { request, listener ->
            appListener = listener
            object : WebSocket {
                override fun request(): Request = request
                override fun queueSize(): Long = 0
                override fun send(text: String): Boolean = true
                override fun send(bytes: okio.ByteString): Boolean = true
                override fun close(code: Int, reason: String?): Boolean = true
                override fun cancel() = Unit
            }
        }
        val factory = PacketInsWebSocketFactory(fakeFactory, sink, config)
        val request = Request.Builder().url("https://example.com/ws").build()
        val socket = factory.newWebSocket(request, object : WebSocketListener() {})

        val openResponse = Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
        appListener.onOpen(socket, openResponse)
        socket.send("ping")
        appListener.onMessage(socket, "echo:ping")

        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertTrue(events.any { it.type == EventType.WS_OPEN })
        assertTrue(events.any { it.type == EventType.WS_SEND && it.requestBody?.text?.contains("ping") == true })
        assertTrue(events.any { it.type == EventType.WS_MESSAGE && it.responseBody?.text?.contains("echo:ping") == true })
    }

    private class RecordingSink(
        private val events: CopyOnWriteArrayList<PacketInsEvent>,
        private val latch: CountDownLatch,
    ) : EventSink {
        override fun emit(event: PacketInsEvent) {
            events += event
            latch.countDown()
        }
    }
}
