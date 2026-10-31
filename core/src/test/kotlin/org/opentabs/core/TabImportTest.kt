package org.opentabs.core

import org.junit.Assert.*
import org.junit.Test

class TabImportTest {
    private val valid = "e|--0--|\nB|--0--|\nG|--0--|\nD|--2--|\nA|--2--|\nE|--0--|"
    private fun reject(name: String, bytes: ByteArray) {
        try { PlainTextTabDecoder.decode(name, bytes.inputStream()); fail("Expected rejection: $name") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun `six string tabs preserve alignment and normalize line endings`() {
        val document = PlainTextTabDecoder.decode("exercise.TXT", valid.replace("\n", "\r\n").byteInputStream())
        assertEquals(6, document.lines.size)
        assertEquals(8, document.columns)
        assertEquals("e|--0--|", document.lines.first())
    }

    @Test fun `UTF8 BOM and UTF16 BOM text import correctly`() {
        val utf8 = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + valid.toByteArray()
        val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + valid.toByteArray(Charsets.UTF_16LE)
        assertEquals(6, PlainTextTabDecoder.decode("bom.txt", utf8.inputStream()).lines.size)
        assertEquals(6, PlainTextTabDecoder.decode("wide.tab", utf16.inputStream()).lines.size)
    }

    @Test fun `tabs expand to four column stops`() {
        val document = PlainTextTabDecoder.decode("tabs.tab", "Header\twords\n$valid".byteInputStream())
        assertEquals("Header  words", document.lines.first())
    }

    @Test fun `empty unsupported binary and invalid encoding are rejected`() {
        reject("empty.txt", byteArrayOf())
        reject("song.gp5", valid.toByteArray())
        reject("notes.txt", "just some notes".toByteArray())
        reject("binary.txt", byteArrayOf(0) + valid.toByteArray())
        reject("encoding.txt", byteArrayOf(0xc3.toByte(), 0x28) + valid.toByteArray())
    }

    @Test fun `oversized files too many lines and excessively wide lines are rejected`() {
        reject("huge.txt", ByteArray(PlainTextTabDecoder.MAX_BYTES + 1) { 65 })
        reject("long.txt", ("\n".repeat(6000) + valid).toByteArray())
        reject("wide.txt", ("a".repeat(1001) + "\n" + valid).toByteArray())
    }

    @Test fun `five string content is not claimed as six string tablature`() {
        reject("bass.txt", valid.lines().take(5).joinToString("\n").toByteArray())
    }
}
