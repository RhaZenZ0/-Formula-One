package com.rhazenz0.f1info.data.timing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64
import java.util.zip.Deflater

class FeedCodecTest {
    @Test
    fun parsesStreamLinesWithBom() {
        val text = BOM + "00:00:03.703{\"a\":1}\r\n01:02:45.042{\"b\":2}\n\n"
        val lines = FeedCodec.parseStream(text)
        assertEquals(2, lines.size)
        assertEquals(3703L, lines[0].offsetMs)
        assertEquals(3_765_042L, lines[1].offsetMs)
        assertEquals("{\"b\":2}", lines[1].payload)
    }

    @Test
    fun inflatesRawDeflateBase64() {
        val json = """{"Entries":[{"Utc":"2026-10-11T12:04:12.145Z"}]}"""
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(json.toByteArray())
        deflater.finish()
        val buf = ByteArray(1024)
        val n = deflater.deflate(buf)
        deflater.end()
        val b64 = Base64.getEncoder().encodeToString(buf.copyOf(n))
        assertEquals(json, FeedCodec.inflate("\"$b64\""))
        assertEquals(json, FeedCodec.inflate(b64))
    }
}
