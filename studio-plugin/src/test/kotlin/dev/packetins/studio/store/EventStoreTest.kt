package dev.packetins.studio.store

import dev.packetins.protocol.BodyEncoding
import dev.packetins.protocol.BodyPayload
import dev.packetins.protocol.EventType
import dev.packetins.protocol.HeaderEntry
import dev.packetins.protocol.PacketInsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStoreTest {
    @Test
    fun acceptsAndMergesHttpExchange() {
        val store = EventStore()
        store.accept(
            PacketInsEvent(
                type = EventType.HTTP_REQUEST,
                id = "1",
                correlationId = "c1",
                sessionId = "s1",
                timestampMs = 1_000,
                method = "GET",
                url = "https://example.com/api",
                host = "example.com",
                path = "/api",
                headers = listOf(HeaderEntry("Accept", "application/json")),
            ),
        )
        store.accept(
            PacketInsEvent(
                type = EventType.HTTP_RESPONSE,
                id = "2",
                correlationId = "c1",
                sessionId = "s1",
                timestampMs = 1_120,
                statusCode = 200,
                durationMs = 120,
                responseBody = BodyPayload(
                    encoding = BodyEncoding.UTF8,
                    contentType = "application/json",
                    sizeBytes = 2,
                    text = "{}",
                ),
            ),
        )

        val snapshot = store.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(200, snapshot.first().statusCode)
        assertEquals("{}", snapshot.first().responseBody?.text)
        assertTrue(snapshot.first().requestHeaders.isNotEmpty())
    }
}
