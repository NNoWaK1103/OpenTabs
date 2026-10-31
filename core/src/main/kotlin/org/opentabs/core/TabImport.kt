package org.opentabs.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class TabDocument(val lines: List<String>, val columns: Int)

/** Future Guitar Pro / MusicXML decoders can produce their own native document models. */
interface TabDecoder {
    val extensions: Set<String>
    fun decode(fileName: String, input: InputStream): TabDocument
}

object PlainTextTabDecoder : TabDecoder {
    const val MAX_BYTES = 512 * 1024
    const val MAX_LINES = 6000
    const val MAX_COLUMNS = 1000
    override val extensions = setOf("txt", "tab")
    private val stringLine = Regex("^\\s*(?:[a-gA-G][#b]?|[1-6])?\\s*[|:][-0-9xXhHpPbBrR/\\\\~(). <>+|:=*^]+$")

    override fun decode(fileName: String, input: InputStream): TabDocument {
        require(fileName.substringAfterLast('.', "").lowercase() in extensions) {
            "Choose a plain-text .txt or .tab file. Guitar Pro and MusicXML are not supported yet."
        }
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.isNotEmpty()) { "The selected file is empty." }
        require(bytes.size <= MAX_BYTES) { "Tab files must be 512 KiB or smaller." }
        val (charset, offset) = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE to 2
            bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> Charsets.UTF_8 to 3
            else -> Charsets.UTF_8 to 0
        }
        val text = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Use UTF-8 text or UTF-16 text with a byte-order mark.")
        }
        require(text.none { it.isISOControl() && it !in "\r\n\t" }) { "This file contains binary or unsupported control characters." }
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { expandTabs(it) }
        require(lines.size <= MAX_LINES) { "Tab files must contain at most $MAX_LINES lines." }
        require(lines.all { it.length <= MAX_COLUMNS }) { "Tab lines must contain at most $MAX_COLUMNS columns." }
        require(lines.joinToString("\n").toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "The normalized tab must be 512 KiB or smaller."
        }
        var consecutive = 0
        var hasSixStrings = false
        lines.forEach {
            consecutive = if (stringLine.matches(it.trimEnd())) consecutive + 1 else 0
            if (consecutive >= 6) hasSixStrings = true
        }
        require(hasSixStrings) { "No six-string tab found. Use six consecutive lines such as e|--0--, B|--0--, G|--0--, D|--2--, A|--2--, E|--0--." }
        return TabDocument(lines, lines.maxOf { it.length })
    }

    private fun expandTabs(line: String): String = buildString {
        line.forEach { char ->
            if (char == '\t') repeat(4 - length % 4) { append(' ') } else append(char)
        }
    }
}
