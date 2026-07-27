package dev.packetins.studio.share

import dev.packetins.protocol.BodyEncoding
import dev.packetins.protocol.BodyPayload
import dev.packetins.protocol.EventType
import dev.packetins.protocol.HeaderEntry
import dev.packetins.protocol.PacketInsProtocol
import dev.packetins.studio.model.ExchangeKind
import dev.packetins.studio.model.InspectedExchange
import dev.packetins.studio.store.SavedTrafficSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

object TrafficShare {
    private val sensitiveHeaders = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "x-auth-token",
    )
    private val sensitiveJsonKeys = setOf(
        "password",
        "passwd",
        "secret",
        "token",
        "access_token",
        "refresh_token",
        "authorization",
        "cookie",
        "api_key",
        "apikey",
    )

    fun toPacketInsJson(name: String, exchanges: List<InspectedExchange>, redact: Boolean): String {
        val safe = if (redact) exchanges.map(::redact) else exchanges
        val session = SavedTrafficSession(
            id = UUID.randomUUID().toString(),
            name = name,
            savedAtMs = System.currentTimeMillis(),
            exchanges = safe,
        )
        return PacketInsProtocol.json.encodeToString(session)
    }

    fun fromPacketInsJson(text: String): SavedTrafficSession =
        PacketInsProtocol.json.decodeFromString(text)

    fun toHar(exchanges: List<InspectedExchange>, redact: Boolean): String {
        val entries = if (redact) exchanges.map(::redact) else exchanges
        val root = buildJsonObject {
            put("log", buildJsonObject {
                put("version", "1.2")
                put("creator", buildJsonObject {
                    put("name", "OneInsight")
                    put("version", "0.1.0")
                })
                put("entries", buildJsonArray {
                    entries.forEach { add(toHarEntry(it)) }
                })
            })
        }
        return root.toString()
    }

    fun fromHar(text: String): List<InspectedExchange> {
        val root = PacketInsProtocol.json.parseToJsonElement(text).jsonObject
        return root["log"]?.jsonObject?.get("entries")?.jsonArray.orEmpty().mapIndexed { index, element ->
            fromHarEntry(index, element.jsonObject)
        }
    }

    fun toFetch(exchange: InspectedExchange): String {
        val headers = exchange.requestHeaders
            .filterNot { it.name.equals("Content-Length", ignoreCase = true) }
            .joinToString(",\n    ") {
                "${jsQuote(it.name)}: ${jsQuote(it.value)}"
            }
        val body = exchange.requestBody?.takeIf { it.encoding == BodyEncoding.UTF8 }?.text
        return buildString {
            append("const response = await fetch(${jsQuote(exchange.url)}, {\n")
            append("  method: ${jsQuote(exchange.method)},\n")
            append("  headers: {\n    $headers\n  }")
            if (!body.isNullOrEmpty()) append(",\n  body: ${jsQuote(body)}")
            append("\n});\n\n")
            append("const data = await response.json();")
        }
    }

    fun toOkHttp(exchange: InspectedExchange): String {
        val body = exchange.requestBody?.takeIf { it.encoding == BodyEncoding.UTF8 }?.text
        val contentType = exchange.requestHeaders
            .firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?: "application/json"
        return buildString {
            append("val client = OkHttpClient()\n")
            if (!body.isNullOrEmpty()) {
                append("val body = ${kotlinQuote(body)}.toRequestBody(${kotlinQuote(contentType)}.toMediaType())\n")
            }
            append("val request = Request.Builder()\n")
            append("    .url(${kotlinQuote(exchange.url)})\n")
            exchange.requestHeaders
                .filterNot { it.name.equals("Content-Length", ignoreCase = true) }
                .forEach { append("    .header(${kotlinQuote(it.name)}, ${kotlinQuote(it.value)})\n") }
            when {
                !body.isNullOrEmpty() -> append("    .method(${kotlinQuote(exchange.method)}, body)\n")
                exchange.method.equals("GET", true) -> append("    .get()\n")
                exchange.method.equals("HEAD", true) -> append("    .head()\n")
                else -> append("    .method(${kotlinQuote(exchange.method)}, null)\n")
            }
            append("    .build()\n\n")
            append("client.newCall(request).execute().use { response ->\n")
            append("    println(response.body?.string())\n")
            append("}")
        }
    }

    fun toRetrofit(exchange: InspectedExchange): String {
        val uri = runCatching { URI(exchange.url) }.getOrNull()
        val baseUrl = uri?.let { "${it.scheme}://${it.authority}/" } ?: exchange.url
        val hasBody = !exchange.requestBody?.text.isNullOrEmpty()
        val headers = exchange.requestHeaders
            .filterNot { it.name.equals("Content-Length", ignoreCase = true) }
            .joinToString(",\n        ") { kotlinQuote("${it.name}: ${it.value}") }
        return buildString {
            append("interface OneInsightApi {\n")
            if (headers.isNotEmpty()) append("    @Headers(\n        $headers\n    )\n")
            append("    @HTTP(method = ${kotlinQuote(exchange.method)}, path = ${kotlinQuote(exchange.path.trimStart('/'))}, hasBody = $hasBody)\n")
            if (hasBody) {
                append("    suspend fun replay(@Body body: RequestBody): Response<ResponseBody>\n")
            } else {
                append("    suspend fun replay(): Response<ResponseBody>\n")
            }
            append("}\n\n")
            append("val api = Retrofit.Builder()\n")
            append("    .baseUrl(${kotlinQuote(baseUrl)})\n")
            append("    .build()\n")
            append("    .create(OneInsightApi::class.java)\n")
            exchange.requestBody?.takeIf { hasBody }?.let { requestBody ->
                append("val body = ${kotlinQuote(requestBody.text.orEmpty())}.toRequestBody(")
                append(kotlinQuote(requestBody.contentType ?: "application/json"))
                append(".toMediaType())\n")
                append("val response = api.replay(body)\n")
            } ?: append("val response = api.replay()\n")
        }
    }

    private fun toHarEntry(exchange: InspectedExchange): JsonObject = buildJsonObject {
        put("startedDateTime", Instant.ofEpochMilli(exchange.startedAtMs).toString())
        put("time", exchange.durationMs ?: 0)
        put("request", buildJsonObject {
            put("method", exchange.method)
            put("url", exchange.url)
            put("httpVersion", "HTTP/1.1")
            put("headers", headersToHar(exchange.requestHeaders))
            put("queryString", queryToHar(exchange.url))
            put("cookies", JsonArray(emptyList()))
            put("headersSize", -1)
            put("bodySize", exchange.requestBody?.sizeBytes ?: 0)
            exchange.requestBody?.let { body ->
                put("postData", buildJsonObject {
                    put("mimeType", body.contentType ?: "application/octet-stream")
                    put("text", body.text ?: "")
                    if (body.encoding == BodyEncoding.BASE64) put("encoding", "base64")
                })
            }
        })
        put("response", buildJsonObject {
            put("status", exchange.statusCode ?: 0)
            put("statusText", exchange.displayStatus)
            put("httpVersion", "HTTP/1.1")
            put("headers", headersToHar(exchange.responseHeaders))
            put("cookies", JsonArray(emptyList()))
            put("content", buildJsonObject {
                put("size", exchange.responseBody?.sizeBytes ?: 0)
                put("mimeType", exchange.responseBody?.contentType ?: "application/octet-stream")
                put("text", exchange.responseBody?.text ?: "")
                if (exchange.responseBody?.encoding == BodyEncoding.BASE64) put("encoding", "base64")
            })
            put("redirectURL", "")
            put("headersSize", -1)
            put("bodySize", exchange.responseBody?.sizeBytes ?: 0)
        })
        put("cache", buildJsonObject {})
        put("timings", buildJsonObject {
            put("send", 0)
            put("wait", exchange.durationMs ?: 0)
            put("receive", 0)
        })
    }

    private fun fromHarEntry(index: Int, entry: JsonObject): InspectedExchange {
        val request = entry["request"]?.jsonObject.orEmpty()
        val response = entry["response"]?.jsonObject.orEmpty()
        val content = response["content"]?.jsonObject.orEmpty()
        val postData = request["postData"]?.jsonObject
        val started = entry["startedDateTime"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: System.currentTimeMillis()
        val url = request["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val uri = runCatching { URI(url) }.getOrNull()
        return InspectedExchange(
            correlationId = "har-$index-${UUID.randomUUID()}",
            kind = ExchangeKind.HTTP,
            method = request["method"]?.jsonPrimitive?.contentOrNull ?: "GET",
            url = url,
            host = uri?.host.orEmpty(),
            path = uri?.rawPath.orEmpty(),
            statusCode = response["status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            durationMs = entry["time"]?.jsonPrimitive?.longOrNull,
            startedAtMs = started,
            updatedAtMs = started + (entry["time"]?.jsonPrimitive?.longOrNull ?: 0),
            packageName = null,
            requestHeaders = harHeaders(request["headers"]),
            responseHeaders = harHeaders(response["headers"]),
            requestBody = postData?.let {
                harBody(
                    text = it["text"]?.jsonPrimitive?.contentOrNull,
                    contentType = it["mimeType"]?.jsonPrimitive?.contentOrNull,
                    encoding = it["encoding"]?.jsonPrimitive?.contentOrNull,
                )
            },
            responseBody = harBody(
                text = content["text"]?.jsonPrimitive?.contentOrNull,
                contentType = content["mimeType"]?.jsonPrimitive?.contentOrNull,
                encoding = content["encoding"]?.jsonPrimitive?.contentOrNull,
            ),
            errorMessage = null,
            wsFrames = emptyList(),
            lastEventType = EventType.HTTP_RESPONSE,
        )
    }

    private fun headersToHar(headers: List<HeaderEntry>): JsonArray = buildJsonArray {
        headers.forEach { header ->
            add(buildJsonObject {
                put("name", header.name)
                put("value", header.value)
            })
        }
    }

    private fun queryToHar(url: String): JsonArray = buildJsonArray {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return@buildJsonArray
        query.split('&').forEach { item ->
            add(buildJsonObject {
                put("name", URLDecoder.decode(item.substringBefore('='), StandardCharsets.UTF_8))
                put("value", URLDecoder.decode(item.substringAfter('=', ""), StandardCharsets.UTF_8))
            })
        }
    }

    private fun harHeaders(element: JsonElement?): List<HeaderEntry> =
        element?.jsonArray.orEmpty().mapNotNull {
            val header = it.jsonObject
            val name = header["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            HeaderEntry(name, header["value"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }

    private fun harBody(text: String?, contentType: String?, encoding: String?): BodyPayload? {
        if (text == null) return null
        val bodyEncoding = if (encoding.equals("base64", true)) BodyEncoding.BASE64 else BodyEncoding.UTF8
        val size = if (bodyEncoding == BodyEncoding.BASE64) {
            runCatching { Base64.getDecoder().decode(text).size.toLong() }.getOrDefault(text.length.toLong())
        } else {
            text.toByteArray().size.toLong()
        }
        return BodyPayload(bodyEncoding, contentType, size, text = text)
    }

    private fun redact(exchange: InspectedExchange): InspectedExchange = exchange.copy(
        requestHeaders = redactHeaders(exchange.requestHeaders),
        responseHeaders = redactHeaders(exchange.responseHeaders),
        requestBody = redactBody(exchange.requestBody),
        responseBody = redactBody(exchange.responseBody),
    )

    private fun redactHeaders(headers: List<HeaderEntry>): List<HeaderEntry> = headers.map {
        if (it.name.lowercase() in sensitiveHeaders) it.copy(value = "<redacted>") else it
    }

    private fun redactBody(body: BodyPayload?): BodyPayload? {
        if (body?.encoding != BodyEncoding.UTF8) return body
        val text = body.text?.takeIf { it.isNotBlank() } ?: return body
        val json = runCatching { PacketInsProtocol.json.parseToJsonElement(text) }.getOrNull() ?: return body
        return body.copy(text = redactJson(json).toString())
    }

    private fun redactJson(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            if (key.lowercase() in sensitiveJsonKeys) JsonPrimitive("<redacted>") else redactJson(value)
        })
        is JsonArray -> JsonArray(element.map(::redactJson))
        else -> element
    }

    private fun jsQuote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun kotlinQuote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
