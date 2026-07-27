package dev.packetins.studio.share

import dev.packetins.protocol.BodyEncoding
import dev.packetins.protocol.BodyPayload
import dev.packetins.protocol.EventType
import dev.packetins.protocol.HeaderEntry
import dev.packetins.studio.model.ExchangeKind
import dev.packetins.studio.model.InspectedExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficShareTest {
    private val exchange = InspectedExchange(
        correlationId = "one",
        kind = ExchangeKind.HTTP,
        method = "POST",
        url = "https://example.com/api?item=1",
        host = "example.com",
        path = "/api",
        statusCode = 200,
        durationMs = 42,
        startedAtMs = 1_000,
        updatedAtMs = 1_042,
        packageName = "dev.example",
        requestHeaders = listOf(
            HeaderEntry("Content-Type", "application/json"),
            HeaderEntry("Authorization", "Bearer secret"),
        ),
        responseHeaders = listOf(HeaderEntry("Content-Type", "application/json")),
        requestBody = BodyPayload(
            BodyEncoding.UTF8,
            "application/json",
            38,
            text = """{"password":"secret","name":"PacketIns"}""",
        ),
        responseBody = BodyPayload(
            BodyEncoding.UTF8,
            "application/json",
            11,
            text = """{"ok":true}""",
        ),
        errorMessage = null,
        wsFrames = emptyList(),
        lastEventType = EventType.HTTP_RESPONSE,
    )

    @Test
    fun harRoundTripPreservesRequest() {
        val imported = TrafficShare.fromHar(TrafficShare.toHar(listOf(exchange), redact = false)).single()
        assertEquals("POST", imported.method)
        assertEquals(200, imported.statusCode)
        assertTrue(imported.requestBody?.text?.contains("PacketIns") == true)
    }

    @Test
    fun exportRedactsCredentials() {
        val exported = TrafficShare.toPacketInsJson("safe", listOf(exchange), redact = true)
        assertTrue(!exported.contains("Bearer secret"))
        assertTrue(!exported.contains("\"password\":\"secret\""))
        assertTrue(exported.contains("<redacted>"))
    }

    @Test
    fun generatesFetchAndOkHttp() {
        assertTrue(TrafficShare.toFetch(exchange).contains("fetch("))
        assertTrue(TrafficShare.toOkHttp(exchange).contains("OkHttpClient"))
        assertTrue(TrafficShare.toRetrofit(exchange).contains("Retrofit.Builder"))
    }
}
