package dev.packetins.studio.store

import dev.packetins.protocol.EventType
import dev.packetins.protocol.HeaderEntry
import dev.packetins.protocol.PacketInsEvent
import dev.packetins.protocol.PacketInsProtocol
import dev.packetins.studio.model.ExchangeKind
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStoreTest {
    @Test
    fun mergesHttpEventsAndFilters() {
        val store = EventStore(maxEvents = 10)
        store.accept(
            PacketInsEvent(
                type = EventType.HTTP_REQUEST,
                id = "1",
                correlationId = "c1",
                sessionId = "s",
                timestampMs = 1,
                method = "GET",
                url = "https://example.com/api",
                host = "example.com",
                path = "/api",
            )
        )
        store.accept(
            PacketInsEvent(
                type = EventType.HTTP_RESPONSE,
                id = "2",
                correlationId = "c1",
                sessionId = "s",
                timestampMs = 2,
                method = "GET",
                url = "https://example.com/api",
                host = "example.com",
                path = "/api",
                statusCode = 500,
                durationMs = 10,
            )
        )
        store.accept(
            PacketInsEvent(
                type = EventType.WS_OPEN,
                id = "3",
                correlationId = "c2",
                sessionId = "s",
                timestampMs = 3,
                method = "WS",
                url = "wss://example.com/ws",
                host = "example.com",
                path = "/ws",
                statusCode = 101,
            )
        )

        val snapshot = store.snapshot()
        assertEquals(2, snapshot.size)
        val http = snapshot.first { it.correlationId == "c1" }
        assertEquals(500, http.statusCode)
        assertEquals(10L, http.durationMs)

        val filter = EventFilter(errorsOnly = true)
        assertTrue(filter.matches(http))
        assertTrue(!filter.matches(snapshot.first { it.correlationId == "c2" }))
    }

    @Test
    fun respectsPauseAndMaxSize() {
        val store = EventStore(maxEvents = 2)
        store.setPaused(true)
        store.accept(event("a", 1))
        assertTrue(store.snapshot().isEmpty())

        store.setPaused(false)
        store.accept(event("a", 1))
        store.accept(event("b", 2))
        store.accept(event("c", 3))
        val ids = store.snapshot().map { it.correlationId }.toSet()
        assertEquals(setOf("b", "c"), ids)
    }

    @Test
    fun identifiesSseAndWebSocketHandshakes() {
        val store = EventStore(maxEvents = 10)
        store.accept(event("sse", 1))
        store.accept(
            PacketInsEvent(
                type = EventType.HTTP_RESPONSE,
                id = "sse-response",
                correlationId = "sse",
                sessionId = "s",
                timestampMs = 2,
                statusCode = 200,
                headers = listOf(HeaderEntry("Content-Type", "text/event-stream; charset=utf-8")),
            )
        )
        store.accept(
            PacketInsEvent(
                type = EventType.HTTP_REQUEST,
                id = "ws",
                correlationId = "ws",
                sessionId = "s",
                timestampMs = 3,
                method = "GET",
                headers = listOf(HeaderEntry("Upgrade", "websocket")),
            )
        )

        val snapshot = store.snapshot()
        assertEquals(ExchangeKind.SSE, snapshot.first { it.correlationId == "sse" }.kind)
        assertEquals(ExchangeKind.WEBSOCKET, snapshot.first { it.correlationId == "ws" }.kind)
        assertTrue(EventFilter(sseOnly = true).matches(snapshot.first { it.correlationId == "sse" }))
    }

    @Test
    fun savedSessionRoundTripsAsJson() {
        val exchange = dev.packetins.studio.model.InspectedExchange.fromEvent(event("saved", 10))
        val session = SavedTrafficSession(
            id = "session-1",
            name = "Before release",
            savedAtMs = 20,
            exchanges = listOf(exchange),
        )

        val encoded = PacketInsProtocol.json.encodeToString(session)
        val decoded = PacketInsProtocol.json.decodeFromString<SavedTrafficSession>(encoded)

        assertEquals("Before release", decoded.name)
        assertEquals("https://example.com/saved", decoded.exchanges.single().url)
    }

    private fun event(id: String, ts: Long) = PacketInsEvent(
        type = EventType.HTTP_REQUEST,
        id = id,
        correlationId = id,
        sessionId = "s",
        timestampMs = ts,
        method = "GET",
        url = "https://example.com/$id",
        host = "example.com",
        path = "/$id",
    )
}
