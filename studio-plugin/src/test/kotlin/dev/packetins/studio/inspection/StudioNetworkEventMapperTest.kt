package dev.packetins.studio.inspection

import dev.packetins.protocol.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.network.inspection.NetworkInspectorProtocol

class StudioNetworkEventMapperTest {
    private val mapper = StudioNetworkEventMapper(
        packageName = { "dev.example" },
        processId = { 42 },
    )

    @Test
    fun mapsRequestResponseAndPayloadEvents() {
        val request = NetworkInspectorProtocol.HttpConnectionEvent.RequestStarted.newBuilder()
            .setUrl("https://example.com/api/items")
            .setMethod("POST")
            .setTransport(NetworkInspectorProtocol.HttpConnectionEvent.HttpTransport.OKHTTP3)
            .addHeaders(header("Content-Type", "application/json"))
            .build()

        val requestEvents = mapper.map(event(
            1,
            1_000_000_000,
            NetworkInspectorProtocol.HttpConnectionEvent.newBuilder()
                .setConnectionId(1)
                .setHttpRequestStarted(request)
                .build(),
        ))
        assertEquals(EventType.HTTP_REQUEST, requestEvents.single().type)
        assertEquals("POST", requestEvents.single().method)

        val requestPayloadEvents = mapper.map(event(
            1,
            1_100_000_000,
            NetworkInspectorProtocol.HttpConnectionEvent.newBuilder()
                .setConnectionId(1)
                .setRequestPayload(
                    NetworkInspectorProtocol.HttpConnectionEvent.Payload.newBuilder()
                        .setPayload(com.android.tools.idea.protobuf.ByteString.copyFromUtf8("""{"name":"test"}"""))
                )
                .build(),
        ))
        assertTrue(requestPayloadEvents.single().requestBody?.text?.contains("test") == true)

        val responseEvents = mapper.map(event(
            1,
            1_200_000_000,
            NetworkInspectorProtocol.HttpConnectionEvent.newBuilder()
                .setConnectionId(1)
                .setHttpResponseStarted(
                    NetworkInspectorProtocol.HttpConnectionEvent.ResponseStarted.newBuilder()
                        .setResponseCode(201)
                        .addHeaders(header("Content-Type", "application/json"))
                )
                .build(),
        ))
        assertEquals(201, responseEvents.single().statusCode)
        assertEquals(200L, responseEvents.single().durationMs)
    }

    @Test
    fun decodesGzipJsonResponseBodies() {
        val json = """{"ok":true,"message":"hello packetins"}"""
        val compressed = java.io.ByteArrayOutputStream().use { output ->
            java.util.zip.GZIPOutputStream(output).use { gzip ->
                gzip.write(json.toByteArray(Charsets.UTF_8))
            }
            output.toByteArray()
        }

        mapper.map(event(
            7,
            2_000_000_000,
            NetworkInspectorProtocol.HttpConnectionEvent.newBuilder()
                .setConnectionId(7)
                .setHttpRequestStarted(
                    NetworkInspectorProtocol.HttpConnectionEvent.RequestStarted.newBuilder()
                        .setUrl("https://example.com/api/gzip")
                        .setMethod("GET")
                )
                .build(),
        ))
        mapper.map(event(
            7,
            2_100_000_000,
            NetworkInspectorProtocol.HttpConnectionEvent.newBuilder()
                .setConnectionId(7)
                .setHttpResponseStarted(
                    NetworkInspectorProtocol.HttpConnectionEvent.ResponseStarted.newBuilder()
                        .setResponseCode(200)
                        .addHeaders(header("Content-Type", "application/json;charset=utf-8"))
                        .addHeaders(header("Content-Encoding", "gzip"))
                )
                .build(),
        ))

        val payloadEvents = mapper.map(event(
            7,
            2_200_000_000,
            NetworkInspectorProtocol.HttpConnectionEvent.newBuilder()
                .setConnectionId(7)
                .setResponsePayload(
                    NetworkInspectorProtocol.HttpConnectionEvent.Payload.newBuilder()
                        .setPayload(com.android.tools.idea.protobuf.ByteString.copyFrom(compressed))
                )
                .build(),
        ))

        val body = payloadEvents.single().responseBody
        assertTrue(body?.text?.contains("hello packetins") == true)
        assertTrue(body?.contentType?.contains("decoded from gzip") == true)
    }

    @Test
    fun accumulatesChunkedResponsePayloads() {
        mapper.map(event(
            8,
            3_000_000_000,
            NetworkInspectorProtocol.HttpConnectionEvent.newBuilder()
                .setConnectionId(8)
                .setHttpRequestStarted(
                    NetworkInspectorProtocol.HttpConnectionEvent.RequestStarted.newBuilder()
                        .setUrl("https://example.com/stream")
                        .setMethod("GET")
                )
                .build(),
        ))
        mapper.map(event(
            8,
            3_100_000_000,
            NetworkInspectorProtocol.HttpConnectionEvent.newBuilder()
                .setConnectionId(8)
                .setHttpResponseStarted(
                    NetworkInspectorProtocol.HttpConnectionEvent.ResponseStarted.newBuilder()
                        .setResponseCode(200)
                        .addHeaders(header("Content-Type", "text/event-stream"))
                )
                .build(),
        ))
        mapper.map(event(
            8,
            3_200_000_000,
            NetworkInspectorProtocol.HttpConnectionEvent.newBuilder()
                .setConnectionId(8)
                .setResponsePayload(
                    NetworkInspectorProtocol.HttpConnectionEvent.Payload.newBuilder()
                        .setPayload(com.android.tools.idea.protobuf.ByteString.copyFromUtf8("data: one\n\n"))
                )
                .build(),
        ))
        val final = mapper.map(event(
            8,
            3_300_000_000,
            NetworkInspectorProtocol.HttpConnectionEvent.newBuilder()
                .setConnectionId(8)
                .setResponsePayload(
                    NetworkInspectorProtocol.HttpConnectionEvent.Payload.newBuilder()
                        .setPayload(com.android.tools.idea.protobuf.ByteString.copyFromUtf8("data: two\n\n"))
                )
                .build(),
        )).single()

        assertTrue(final.responseBody?.text?.contains("data: one") == true)
        assertTrue(final.responseBody?.text?.contains("data: two") == true)
    }

    @Test
    fun mapsGrpcCallsAndTextPayloads() {
        val started = NetworkInspectorProtocol.GrpcEvent.GrpcCallStarted.newBuilder()
            .setService("dev.example.Items")
            .setMethod("List")
            .build()
        val startEvent = mapper.map(grpcEvent(
            10,
            4_000_000_000,
            NetworkInspectorProtocol.GrpcEvent.newBuilder()
                .setConnectionId(10)
                .setGrpcCallStarted(started)
                .build(),
        )).single()
        assertEquals("gRPC", startEvent.method)
        assertTrue(startEvent.url?.startsWith("grpc://") == true)

        val message = mapper.map(grpcEvent(
            10,
            4_100_000_000,
            NetworkInspectorProtocol.GrpcEvent.newBuilder()
                .setConnectionId(10)
                .setGrpcMessageReceived(
                    NetworkInspectorProtocol.GrpcEvent.GrpcMessageReceived.newBuilder()
                        .setPayload(
                            NetworkInspectorProtocol.GrpcEvent.GrpcPayload.newBuilder()
                                .setType("application/json")
                                .setText("""{"items":2}""")
                        )
                )
                .build(),
        )).single()
        assertTrue(message.responseBody?.text?.contains("items") == true)
    }

    private fun header(
        name: String,
        value: String,
    ): NetworkInspectorProtocol.HttpConnectionEvent.Header =
        NetworkInspectorProtocol.HttpConnectionEvent.Header.newBuilder()
            .setKey(name)
            .addValues(value)
            .build()

    private fun event(
        connectionId: Long,
        timestampNs: Long,
        http: NetworkInspectorProtocol.HttpConnectionEvent,
    ): ByteArray =
        NetworkInspectorProtocol.Event.newBuilder()
            .setTimestamp(timestampNs)
            .setHttpConnectionEvent(http.toBuilder().setConnectionId(connectionId))
            .build()
            .toByteArray()

    private fun grpcEvent(
        connectionId: Long,
        timestampNs: Long,
        grpc: NetworkInspectorProtocol.GrpcEvent,
    ): ByteArray =
        NetworkInspectorProtocol.Event.newBuilder()
            .setTimestamp(timestampNs)
            .setGrpcEvent(grpc.toBuilder().setConnectionId(connectionId))
            .build()
            .toByteArray()
}
