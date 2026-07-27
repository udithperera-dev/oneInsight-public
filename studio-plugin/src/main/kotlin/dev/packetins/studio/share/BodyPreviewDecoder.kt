package dev.packetins.studio.share

import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

object BodyPreviewDecoder {
    private const val maxBytes = 5 * 1024 * 1024

    /**
     * Best-effort conversion of a captured body into editable plain text for Replay.
     * Tries base64 decode + gzip/deflate/brotli before falling back.
     */
    fun readableText(
        text: String?,
        encodingName: String?,
        contentType: String?,
        contentEncodingHeader: String?,
    ): String {
        if (text.isNullOrBlank()) return ""
        // Do not trim binary bodies — gzip CRC/isize footers often include bytes <= 0x20.
        val rawText = text
        val trimmedForBase64 = text.trim()
        val encodingHint = encodingName?.lowercase(Locale.US)
        val hasContentEncoding = !contentEncodingHeader.isNullOrBlank() &&
            contentEncodingHeader.split(',').any { it.trim().lowercase(Locale.US) !in setOf("", "identity") }
        val compressedHint = isCompressedGarbage(rawText) || hasContentEncoding ||
            encodingHint == "base64" || encodingHint == "b64"

        if (!compressedHint && looksLikeReadableText(trimmedForBase64)) {
            return prettyIfJson(trimmedForBase64)
        }

        val candidates = ArrayList<ByteArray>(4)
        if (encodingHint == "base64" || encodingHint == "b64") {
            decodeBase64(trimmedForBase64)?.let(candidates::add)
        }
        decodeBase64(trimmedForBase64)?.let { decoded ->
            if (candidates.none { it.contentEquals(decoded) }) candidates += decoded
        }
        // Prefer latin1: preserves byte values 0-255 when binary was stored as a String.
        val latin1 = rawText.toByteArray(Charsets.ISO_8859_1)
        if (candidates.none { it.contentEquals(latin1) }) candidates += latin1
        val utf8 = rawText.toByteArray(StandardCharsets.UTF_8)
        if (candidates.none { it.contentEquals(utf8) }) candidates += utf8

        return decodeCandidates(candidates, contentType, contentEncodingHeader, compressedHint, trimmedForBase64)
    }

    /**
     * Decode a raw HTTP response/request body (e.g. from Replay Send) into readable text.
     */
    fun readableFromBytes(
        bytes: ByteArray?,
        contentType: String?,
        contentEncodingHeader: String?,
    ): String {
        if (bytes == null || bytes.isEmpty()) return ""
        val hasContentEncoding = !contentEncodingHeader.isNullOrBlank() &&
            contentEncodingHeader.split(',').any { it.trim().lowercase(Locale.US) !in setOf("", "identity") }
        val compressedHint = hasContentEncoding || isGzipMagic(bytes)
        if (!compressedHint && looksLikeText(bytes)) {
            val text = String(bytes, charsetFromContentType(contentType))
            if (looksLikeReadableText(text)) return prettyIfJson(text)
        }
        return decodeCandidates(listOf(bytes), contentType, contentEncodingHeader, compressedHint, null)
    }

    private fun decodeCandidates(
        candidates: List<ByteArray>,
        contentType: String?,
        contentEncodingHeader: String?,
        compressedHint: Boolean,
        plainFallback: String?,
    ): String {
        val hasContentEncoding = !contentEncodingHeader.isNullOrBlank() &&
            contentEncodingHeader.split(',').any { it.trim().lowercase(Locale.US) !in setOf("", "identity") }
        val encodingsToTry = buildList {
            contentEncodingHeader
                ?.split(',')
                ?.map { it.trim().lowercase(Locale.US) }
                ?.filter { it.isNotEmpty() && it != "identity" }
                ?.let(::addAll)
            add("gzip")
            add("deflate")
            add("br")
        }.distinct()

        var bestText: String? = null
        var bestScore = -1
        for (raw in candidates) {
            for (codec in encodingsToTry) {
                val decodedBytes = when (codec) {
                    "gzip", "x-gzip" -> {
                        when {
                            isGzipMagic(raw) -> gunzipLenient(raw) ?: continue
                            hasContentEncoding || codec == "gzip" -> gunzipLenient(raw) ?: continue
                            else -> continue
                        }
                    }
                    "deflate" -> {
                        if (isGzipMagic(raw)) continue
                        inflate(raw) ?: continue
                    }
                    "br" -> {
                        if (isGzipMagic(raw)) continue
                        brotli(raw) ?: continue
                    }
                    else -> continue
                }
                if (!looksLikeText(decodedBytes)) continue
                val decoded = String(decodedBytes, charsetFromContentType(contentType))
                if (!looksLikeReadableText(decoded)) continue
                val score = textScore(decoded) + if (isGzipMagic(raw)) 25 else 0
                if (score > bestScore) {
                    bestScore = score
                    bestText = decoded
                }
            }
        }

        // Also accept already-decoded plain text candidates (e.g. BASE64 of UTF-8 JSON).
        for (raw in candidates) {
            if (isGzipMagic(raw) || !looksLikeText(raw)) continue
            val decoded = String(raw, charsetFromContentType(contentType))
            if (!looksLikeReadableText(decoded)) continue
            val score = textScore(decoded)
            if (score > bestScore) {
                bestScore = score
                bestText = decoded
            }
        }

        val result = bestText?.takeIf { bestScore >= 12 }
        if (result != null) return prettyIfJson(result)

        // Never return binary/mojibake to the editor.
        return if (!compressedHint && !plainFallback.isNullOrBlank() && looksLikeReadableText(plainFallback)) {
            prettyIfJson(plainFallback)
        } else {
            ""
        }
    }

    fun isCompressedGarbage(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (looksLikeReadableText(text) && (text.trimStart().startsWith("{") || text.trimStart().startsWith("["))) {
            return false
        }
        val replacementHeavy = text.count { it == '\uFFFD' } > 2
        val sample = text.take(1024)
        val nonPrintable = sample.count { ch ->
            val code = ch.code
            code < 9 || code in 14..31 || code == 0x7F || ch == '\uFFFD'
        }
        val nonPrintableHeavy = nonPrintable > sample.length / 20
        val latin1 = text.toByteArray(Charsets.ISO_8859_1)
        val gzipLike = isGzipMagic(latin1) ||
            (text.isNotEmpty() && text[0].code == 0x1f && text.getOrNull(1)?.code?.let { it == 0x8b || it > 127 || it == 0xFFFD } == true)
        return replacementHeavy || nonPrintableHeavy || gzipLike
    }

    private fun decodeBase64(text: String): ByteArray? {
        val cleaned = text.replace("\\s".toRegex(), "")
        if (cleaned.length < 8 || cleaned.length % 4 != 0) return null
        if (!cleaned.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) return null
        return runCatching {
            Base64.getDecoder().decode(cleaned)
        }.recoverCatching {
            Base64.getUrlDecoder().decode(cleaned)
        }.getOrNull()
    }

    private fun prettyIfJson(text: String): String {
        val trimmed = text.trim()
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return text
        return runCatching {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
            kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                element,
            )
        }.getOrDefault(text)
    }

    private fun textScore(text: String): Int {
        if (text.isBlank()) return 0
        var score = 0
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) score += 40
        if (trimmed.startsWith("<")) score += 20
        if ("=" in trimmed && "&" in trimmed) score += 15
        val printable = trimmed.count { it.code in 9..10 || it.code == 13 || it.code in 32..126 || it.code > 160 }
        score += ((printable * 40) / trimmed.length.coerceAtLeast(1))
        score -= trimmed.count { it == '\uFFFD' } * 10
        score -= trimmed.count { it.code < 9 || it.code in 14..31 } * 3
        return score
    }

    private fun looksLikeReadableText(text: String): Boolean {
        if (text.isBlank()) return true
        val sample = text.take(1024)
        val weird = sample.count { it == '\uFFFD' || it.code < 9 || it.code in 14..31 || it.code == 0x7F }
        if (weird > sample.length / 25) return false
        if (sample.firstOrNull()?.code == 0x1f) return false
        val printable = sample.count { it.code in 9..10 || it.code == 13 || it.code in 32..126 || it.code > 160 }
        return printable * 100 / sample.length.coerceAtLeast(1) >= 85
    }

    private fun isGzipMagic(bytes: ByteArray): Boolean =
        bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0x1f && (bytes[1].toInt() and 0xff) == 0x8b

    /**
     * Gunzip that tolerates truncated payloads (Studio often cuts bodies at max size).
     */
    private fun gunzipLenient(bytes: ByteArray): ByteArray? {
        runCatching {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readLimited() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }

        if (!isGzipMagic(bytes)) return null
        val headerLen = gzipHeaderLength(bytes) ?: return null
        return inflateRaw(bytes, headerLen)
    }

    private fun gzipHeaderLength(bytes: ByteArray): Int? {
        if (!isGzipMagic(bytes) || bytes.size < 10) return null
        val flags = bytes[3].toInt() and 0xff
        var offset = 10
        // FEXTRA
        if (flags and 0x04 != 0) {
            if (bytes.size < offset + 2) return null
            val xlen = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
            offset += 2 + xlen
        }
        // FNAME
        if (flags and 0x08 != 0) {
            while (offset < bytes.size && bytes[offset] != 0.toByte()) offset++
            offset++ // NUL
        }
        // FCOMMENT
        if (flags and 0x10 != 0) {
            while (offset < bytes.size && bytes[offset] != 0.toByte()) offset++
            offset++ // NUL
        }
        // FHCRC
        if (flags and 0x02 != 0) offset += 2
        if (offset >= bytes.size) return null
        return offset
    }

    private fun inflateRaw(bytes: ByteArray, offset: Int): ByteArray? {
        if (offset >= bytes.size) return null
        val inflater = Inflater(true)
        return try {
            inflater.setInput(bytes, offset, bytes.size - offset)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (!inflater.finished() && total < maxBytes) {
                val count = runCatching { inflater.inflate(buffer) }.getOrDefault(0)
                if (count <= 0) break
                val toWrite = minOf(count, maxBytes - total)
                output.write(buffer, 0, toWrite)
                total += toWrite
            }
            output.toByteArray().takeIf { it.isNotEmpty() }
        } finally {
            inflater.end()
        }
    }

    private fun inflate(bytes: ByteArray): ByteArray? {
        fun tryInflate(nowrap: Boolean): ByteArray? = runCatching {
            val inflater = Inflater(nowrap)
            try {
                InflaterInputStream(ByteArrayInputStream(bytes), inflater).use { it.readLimited() }
            } finally {
                inflater.end()
            }
        }.getOrNull()
        return tryInflate(false) ?: tryInflate(true)
    }

    private fun brotli(bytes: ByteArray): ByteArray? = runCatching {
        BrotliInputStream(ByteArrayInputStream(bytes)).use { it.readLimited() }
    }.getOrNull()

    private fun java.io.InputStream.readLimited(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            val remaining = maxBytes - total
            if (remaining <= 0) break
            val toWrite = minOf(read, remaining)
            output.write(buffer, 0, toWrite)
            total += toWrite
            if (toWrite < read) break
        }
        return output.toByteArray()
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        if (isGzipMagic(bytes)) return false
        val sample = bytes.take(1024)
        if (sample.any { (it.toInt() and 0xff) == 0 }) return false
        val weird = sample.count { byte ->
            val value = byte.toInt() and 0xff
            value < 9 || value in 14..31 || value == 0x7F
        }
        return weird <= sample.size / 20
    }

    private fun charsetFromContentType(contentType: String?): Charset {
        if (contentType == null) return StandardCharsets.UTF_8
        val charsetToken = contentType.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.removeSurrounding("\"")
        return runCatching { Charset.forName(charsetToken) }.getOrDefault(StandardCharsets.UTF_8)
    }
}
