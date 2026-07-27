package dev.packetins.studio.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

class BodyPreviewDecoderTest {
    @Test
    fun decompressesGzipBase64Bodies() {
        val json = """{"ok":true,"message":"hello oneinsight"}"""
        val compressed = gzip(json)
        val text = BodyPreviewDecoder.readableText(
            text = Base64.getEncoder().encodeToString(compressed),
            encodingName = "BASE64",
            contentType = "application/json",
            contentEncodingHeader = "gzip",
        )
        assertTrue(text.contains("hello oneinsight"))
    }

    @Test
    fun decompressesGzipMagicWithoutHeaderEvenWhenContentTypeIsJson() {
        val json = """{"hello":"world"}"""
        val compressed = gzip(json)
        val mojibake = String(compressed, Charsets.ISO_8859_1)
        val text = BodyPreviewDecoder.readableText(
            text = mojibake,
            encodingName = "UTF8",
            contentType = "application/json",
            contentEncodingHeader = null,
        )
        assertTrue(text.contains("hello"))
        assertFalse(BodyPreviewDecoder.isCompressedGarbage(text))
    }

    @Test
    fun decompressesBase64GzipWithoutContentEncodingHeader() {
        val json = """{"items":[1,2,3]}"""
        val text = BodyPreviewDecoder.readableText(
            text = Base64.getEncoder().encodeToString(gzip(json)),
            encodingName = "BASE64",
            contentType = "application/json; charset=utf-8",
            contentEncodingHeader = null,
        )
        assertTrue(text.contains("items"))
        assertTrue(text.contains("1"))
    }

    @Test
    fun keepsAlreadyReadableJson() {
        val json = """{"a":1}"""
        val text = BodyPreviewDecoder.readableText(
            text = json,
            encodingName = "UTF8",
            contentType = "application/json",
            contentEncodingHeader = null,
        )
        assertTrue(text.contains("\"a\""))
        assertEquals(true, text.contains("1"))
    }

    @Test
    fun neverReturnsRawGzipMojibake() {
        val compressed = gzip("""{"secret":"value"}""")
        val mojibake = String(compressed, Charsets.ISO_8859_1)
        assertTrue(BodyPreviewDecoder.isCompressedGarbage(mojibake))
        val text = BodyPreviewDecoder.readableText(
            text = mojibake,
            encodingName = "UTF8",
            contentType = "application/json",
            contentEncodingHeader = null,
        )
        assertTrue(text.contains("secret"))
        assertFalse(text.startsWith("\u001f"))
    }

    @Test
    fun readableFromBytesDecompressesGzipJsonResponse() {
        val json = """{"status":"ok","message":"replay response"}"""
        val text = BodyPreviewDecoder.readableFromBytes(
            bytes = gzip(json),
            contentType = "application/json; charset=utf-8",
            contentEncodingHeader = "gzip",
        )
        assertTrue(text.contains("replay response"))
        assertTrue(text.contains("status"))
        assertFalse(text.startsWith("\u001f"))
    }

    @Test
    fun readableFromBytesDecompressesGzipWithoutHeader() {
        val json = """{"hello":"bytes"}"""
        val text = BodyPreviewDecoder.readableFromBytes(
            bytes = gzip(json),
            contentType = "application/json",
            contentEncodingHeader = null,
        )
        assertTrue(text.contains("hello"))
    }

    @Test
    fun decompressesTruncatedGzipPayload() {
        val json = """{"big":"${"x".repeat(5_000)}"}"""
        val full = gzip(json)
        val truncated = full.copyOf(full.size - 8) // drop CRC/isize trailer
        val text = BodyPreviewDecoder.readableText(
            text = String(truncated, Charsets.ISO_8859_1),
            encodingName = "UTF8",
            contentType = "application/json",
            contentEncodingHeader = "gzip",
        )
        assertTrue(text.contains("big"))
        assertTrue(text.contains("xxx"))
    }

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { gzip ->
            gzip.write(text.toByteArray(Charsets.UTF_8))
        }
        output.toByteArray()
    }
}
