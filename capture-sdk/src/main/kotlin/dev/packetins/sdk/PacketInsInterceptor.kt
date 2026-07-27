package dev.packetins.sdk

import dev.packetins.protocol.EventType
import dev.packetins.protocol.PacketInsEvent
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.UUID

class PacketInsInterceptor internal constructor(
    private val transport: EventSink,
    private val config: PacketInsConfig,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val correlationId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val url = request.url

        transport.emit(
            PacketInsEvent(
                type = EventType.HTTP_REQUEST,
                id = UUID.randomUUID().toString(),
                correlationId = correlationId,
                sessionId = "",
                timestampMs = startedAt,
                method = request.method,
                url = url.toString(),
                host = url.host,
                path = url.encodedPath,
                headers = HeaderRedactor.redact(request.headers, config.redactHeaders),
                requestBody = BodyCapture.fromRequestBody(request.body, config.maxBodyBytes),
            )
        )

        return try {
            val response = chain.proceed(request)
            val duration = System.currentTimeMillis() - startedAt
            val peeked = try {
                response.peekBody(config.maxBodyBytes + 1)
            } catch (_: Exception) {
                null
            }
            val responseBodyPayload = if (peeked != null) {
                BodyCapture.fromPeekedBytes(
                    peeked.bytes(),
                    response.body?.contentType(),
                    config.maxBodyBytes,
                )
            } else {
                null
            }

            transport.emit(
                PacketInsEvent(
                    type = EventType.HTTP_RESPONSE,
                    id = UUID.randomUUID().toString(),
                    correlationId = correlationId,
                    sessionId = "",
                    timestampMs = System.currentTimeMillis(),
                    method = request.method,
                    url = url.toString(),
                    host = url.host,
                    path = url.encodedPath,
                    statusCode = response.code,
                    durationMs = duration,
                    headers = HeaderRedactor.redact(response.headers, config.redactHeaders),
                    responseBody = responseBodyPayload,
                )
            )
            response
        } catch (error: IOException) {
            transport.emit(
                PacketInsEvent(
                    type = EventType.HTTP_ERROR,
                    id = UUID.randomUUID().toString(),
                    correlationId = correlationId,
                    sessionId = "",
                    timestampMs = System.currentTimeMillis(),
                    method = request.method,
                    url = url.toString(),
                    host = url.host,
                    path = url.encodedPath,
                    durationMs = System.currentTimeMillis() - startedAt,
                    errorMessage = error.message ?: error.javaClass.simpleName,
                )
            )
            throw error
        }
    }
}
