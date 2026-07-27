package dev.packetins.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

object PacketInsProtocol {
    const val VERSION: Int = 1
    const val DEFAULT_PORT: Int = 54789
    const val DEFAULT_MAX_BODY_BYTES: Long = 256 * 1024
    const val DEFAULT_MAX_EVENTS: Int = 2_000
    const val MAX_FRAME_BYTES: Int = 2 * 1024 * 1024

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        isLenient = true
    }

    fun encode(event: PacketInsEvent): ByteArray {
        val payload = json.encodeToString(event).toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= MAX_FRAME_BYTES) {
            "Encoded event exceeds max frame size: ${payload.size}"
        }
        return payload
    }

    fun decode(bytes: ByteArray): PacketInsEvent {
        require(bytes.size <= MAX_FRAME_BYTES) {
            "Frame exceeds max size: ${bytes.size}"
        }
        return json.decodeFromString(PacketInsEvent.serializer(), bytes.toString(StandardCharsets.UTF_8))
    }

    fun writeFrame(output: OutputStream, event: PacketInsEvent) {
        val payload = encode(event)
        val data = DataOutputStream(output)
        data.writeInt(payload.size)
        data.write(payload)
        data.flush()
    }

    fun readFrame(input: InputStream): PacketInsEvent? {
        val data = DataInputStream(input)
        val size = try {
            data.readInt()
        } catch (_: EOFException) {
            return null
        }
        require(size in 0..MAX_FRAME_BYTES) { "Invalid frame size: $size" }
        val payload = ByteArray(size)
        data.readFully(payload)
        return decode(payload)
    }
}

@Serializable
enum class EventType {
    @SerialName("http_request") HTTP_REQUEST,
    @SerialName("http_response") HTTP_RESPONSE,
    @SerialName("http_error") HTTP_ERROR,
    @SerialName("ws_open") WS_OPEN,
    @SerialName("ws_send") WS_SEND,
    @SerialName("ws_message") WS_MESSAGE,
    @SerialName("ws_closing") WS_CLOSING,
    @SerialName("ws_closed") WS_CLOSED,
    @SerialName("ws_failure") WS_FAILURE,
    @SerialName("connection") CONNECTION,
    @SerialName("heartbeat") HEARTBEAT,
}

@Serializable
enum class BodyEncoding {
    @SerialName("utf8") UTF8,
    @SerialName("base64") BASE64,
    @SerialName("none") NONE,
}

@Serializable
data class HeaderEntry(
    val name: String,
    val value: String,
)

@Serializable
data class BodyPayload(
    val encoding: BodyEncoding = BodyEncoding.NONE,
    val contentType: String? = null,
    val sizeBytes: Long = 0,
    val truncated: Boolean = false,
    val text: String? = null,
)

@Serializable
data class PacketInsEvent(
    val version: Int = PacketInsProtocol.VERSION,
    val type: EventType,
    val id: String,
    val correlationId: String,
    val sessionId: String,
    val timestampMs: Long,
    val packageName: String? = null,
    val processId: Int? = null,
    val method: String? = null,
    val url: String? = null,
    val host: String? = null,
    val path: String? = null,
    val statusCode: Int? = null,
    val durationMs: Long? = null,
    val headers: List<HeaderEntry> = emptyList(),
    val requestBody: BodyPayload? = null,
    val responseBody: BodyPayload? = null,
    val errorMessage: String? = null,
    val wsOpcode: String? = null,
    val wsCode: Int? = null,
    val wsReason: String? = null,
    val message: String? = null,
)
