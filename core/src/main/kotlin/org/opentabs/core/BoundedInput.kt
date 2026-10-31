package org.opentabs.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Kept from the previous HTTP helper because bounded GP7 imports also use it. */
internal fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() <= maxBytes) {
        val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
        if (count < 0) break
        if (count > 0) output.write(buffer, 0, count)
    }
    require(output.size() <= maxBytes) { "Imported file is too large." }
    require(output.size() > 0) { "Imported file is empty." }
    return output.toByteArray()
}
