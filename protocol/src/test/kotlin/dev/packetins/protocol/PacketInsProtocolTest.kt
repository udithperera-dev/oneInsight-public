package dev.packetins.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PacketInsProtocolTest {
    @Test
    fun roundTripJsonAndFrame() {
        val event = PacketInsEvent(
            type = EventType.HTTP_RESPONSE,
            id = "id-1",
            correlationId = "corr-1",
            sessionId = "session-1",
            timestampMs = 1234L,
            method = "GET",
            url = "https://example.com/x",
            host = "example.com",
            path = "/x",
            statusCode = 200,
            durationMs = 12,
            headers = listOf(HeaderEntry("Content-Type", "application/json")),
            responseBody = BodyPayload(
                encoding = BodyEncoding.UTF8,
                contentType = "application/json",
                sizeBytes = 2,
                truncated = false,
                text = "{}",
            ),
        )

        val encoded = PacketInsProtocol.encode(event)
        val decoded = PacketInsProtocol.decode(encoded)
        assertEquals(event, decoded)

        val output = ByteArrayOutputStream()
        PacketInsProtocol.writeFrame(output, event)
        val framed = PacketInsProtocol.readFrame(ByteArrayInputStream(output.toByteArray()))
        assertEquals(event, framed)
    }

    @Test
    fun rejectsOversizedFrame() {
        val huge = ByteArray(PacketInsProtocol.MAX_FRAME_BYTES + 1) { 'a'.code.toByte() }
        try {
            PacketInsProtocol.decode(huge)
            throw AssertionError("expected failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("max"))
        }
    }
}
