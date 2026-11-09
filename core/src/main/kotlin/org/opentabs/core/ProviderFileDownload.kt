package org.opentabs.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.RandomAccessFile
import okhttp3.OkHttpClient
import okhttp3.Request

/** Deliberately not a data class: signed URLs must not appear in generated toString/log messages. */
class ProviderFileOffer internal constructor(
    val source: WebTabSource, val url: String, val host: String, val filename: String,
    val format: ProviderFileFormat, val expectedBytes: Long, val userAgent: String,
) {
    override fun toString() = "ProviderFileOffer(${source.name}, $filename)"
}

enum class ProviderFileFormat(val extension: String, val mime: String) {
    PDF("pdf", "application/pdf"), MIDI("mid", "audio/midi"), MP3("mp3", "audio/mpeg"),
    WAV("wav", "audio/wav"), GP("gp", "application/octet-stream"),
    GP3("gp3", "application/octet-stream"), GP4("gp4", "application/octet-stream"),
    GP5("gp5", "application/octet-stream"), GPX("gpx", "application/octet-stream");
}

class ProviderDownloadException(message: String) : IOException(message)
class ProviderDownloadCancelled : IOException("Saving canceled.")

object ProviderDownloadPolicy {
    const val MAX_BYTES = 64L * 1024 * 1024
    const val MAX_REDIRECTS = 3
    const val TIMEOUT_MILLIS = 120_000L
    private val binaryTypes = setOf("", "application/octet-stream", "binary/octet-stream", "application/download", "application/x-download")

    fun offer(pageUrl: String, url: String, disposition: String?, mime: String?, length: Long, userAgent: String): ProviderFileOffer {
        val source = WebTabUrls.source(pageUrl) ?: reject("Start the download from Songsterr or Ultimate Guitar.")
        if (!allowedUrl(source, url)) reject("This download address is not supported. Use the website in your browser.")
        if (length > MAX_BYTES) reject("This file is larger than the 64 MiB saving limit.")
        val explicit = dispositionName(disposition.orEmpty())
        val pathName = runCatching { decode(URI(url).rawPath.substringAfterLast('/')) }.getOrDefault("")
        val candidate = explicit ?: pathName
        val extension = candidate.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val format = byExtension(extension) ?: if (extension.isEmpty()) byMime(mime) else null
        if (format == null) reject("This file format cannot be saved here. Use the website in your browser.")
        if (!acceptsMime(format, mime)) reject("The website did not offer a supported file type.")
        // A route without an extension may be a signed/token path, not a user-facing filename.
        val displayName = if (explicit == null && byExtension(extension) == null) "" else candidate
        val safeName = displayName.substringBeforeLast('.', displayName).map { char ->
            if (char.isLetterOrDigit() || char in " -_()") char else '_'
        }.joinToString("").trim(' ', '.', '_').take(90).ifBlank { "${source.label} download" }
        val ua = userAgent.take(1024).takeUnless { it.any(Char::isISOControl) }.orEmpty()
        return ProviderFileOffer(source, url, URI(url).host.lowercase(Locale.ROOT), "$safeName.${format.extension}", format, length, ua)
    }

    fun allowedUrl(source: WebTabSource, url: String): Boolean {
        if (!WebTabUrls.safeHttps(url)) return false
        val host = URI(url).host.lowercase(Locale.ROOT)
        val root = when (source) { WebTabSource.SONGSTERR -> "songsterr.com"; WebTabSource.ULTIMATE_GUITAR -> "ultimate-guitar.com" }
        return host == root || host.endsWith(".$root")
    }

    /** Redirects never move cookies, URLs, or auth to another origin, including sibling subdomains. */
    fun redirect(offer: ProviderFileOffer, current: String, location: String?): String {
        val next = runCatching { URI(current).resolve(location ?: "").toString() }.getOrDefault("")
        if (location.isNullOrBlank() || !allowedUrl(offer.source, next) ||
            !URI(next).host.equals(offer.host, ignoreCase = true)) {
            reject("The download redirected to another address. Open the website in your browser to continue.")
        }
        return next
    }

    fun acceptsMime(format: ProviderFileFormat, mime: String?): Boolean {
        val type = mime.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if (type in binaryTypes) return true // Signature validation is still mandatory.
        return when (format) {
            ProviderFileFormat.PDF -> type == "application/pdf"
            ProviderFileFormat.MIDI -> type in setOf("audio/midi", "audio/x-midi", "audio/mid", "application/x-midi")
            ProviderFileFormat.MP3 -> type in setOf("audio/mpeg", "audio/mp3")
            ProviderFileFormat.WAV -> type in setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
            else -> type in setOf("application/x-guitar-pro", "application/vnd.guitar-pro", "application/zip", "application/x-gtp")
        }
    }

    private fun byExtension(extension: String) = if (extension == "midi") ProviderFileFormat.MIDI else ProviderFileFormat.entries.firstOrNull { it.extension == extension }
    private fun byMime(mime: String?): ProviderFileFormat? {
        val type = mime.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if (type in binaryTypes) return null
        return ProviderFileFormat.entries.firstOrNull { it.mime != "application/octet-stream" && acceptsMime(it, type) }
    }
    private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    private fun dispositionName(header: String): String? {
        if (header.length > 8192 || header.any(Char::isISOControl)) return null
        val encoded = Regex("(?:^|;)\\s*filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        if (encoded != null) return runCatching { decode(encoded.trim().trim('"')) }.getOrNull()
        return Regex("(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))", RegexOption.IGNORE_CASE)
            .find(header)?.let { it.groupValues[1].ifEmpty { it.groupValues[2].trim() } }
    }
    internal fun reject(message: String): Nothing = throw ProviderDownloadException(message)
}

/** Cancellation closes the active network response immediately; also checked before every write. */
class ProviderDownloadCancellation {
    private val canceled = AtomicBoolean(false)
    private var closeActive: (() -> Unit)? = null
    fun check() { if (canceled.get() || Thread.currentThread().isInterrupted) throw ProviderDownloadCancelled() }
    @Synchronized fun bind(close: (() -> Unit)?) { closeActive = close; if (canceled.get()) close?.invoke() }
    @Synchronized fun cancel() { canceled.set(true); closeActive?.invoke() }
}

interface ProviderDownloadResponse : AutoCloseable {
    val status: Int
    val contentType: String?
    val contentLength: Long
    val location: String?
    val body: InputStream
}

fun interface ProviderDownloadTransport {
    fun open(url: String, userAgent: String, cookie: String?, remainingMillis: Long, cancellation: ProviderDownloadCancellation): ProviderDownloadResponse
}

/** No disk cache, cookie jar, referer, authorization header, automatic retries or redirects. */
class ProviderFileTransport : ProviderDownloadTransport {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    override fun open(url: String, userAgent: String, cookie: String?, remainingMillis: Long, cancellation: ProviderDownloadCancellation): ProviderDownloadResponse {
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity").apply {
            if (userAgent.isNotEmpty()) header("User-Agent", userAgent)
            if (!cookie.isNullOrBlank() && !cookie.any(Char::isISOControl)) header("Cookie", cookie)
        }.build()
        val call = client.newCall(request)
        call.timeout().timeout(remainingMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        cancellation.bind(call::cancel)
        cancellation.check()
        val response = call.execute()
        return object : ProviderDownloadResponse {
            override val status = response.code
            override val contentType = response.header("Content-Type")
            override val contentLength = response.body?.contentLength() ?: -1L
            override val location = response.header("Location")
            override val body: InputStream get() = response.body?.byteStream() ?: throw ProviderDownloadException("The website returned no file.")
            override fun close() = response.close()
        }
    }
}

/** Stages and validates privately before opening the user-selected output. Never parses a web page. */
class ProviderFileDownloader(
    private val transport: ProviderDownloadTransport = ProviderFileTransport(),
    private val maxBytes: Long = ProviderDownloadPolicy.MAX_BYTES,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    init { require(maxBytes in 1..ProviderDownloadPolicy.MAX_BYTES) }
    fun save(
        offer: ProviderFileOffer, temporaryDirectory: File, cancellation: ProviderDownloadCancellation,
        cookieForUrl: (String) -> String?, openDestination: () -> OutputStream,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Long {
        require(ProviderDownloadPolicy.allowedUrl(offer.source, offer.url))
        val deadline = nowMillis() + ProviderDownloadPolicy.TIMEOUT_MILLIS
        fun check() {
            cancellation.check()
            if (nowMillis() >= deadline) throw ProviderDownloadException("The file took too long to save. Try the website again.")
        }
        // The caller supplies its dedicated cache directory. Never enumerate/delete other cache files.
        if (!temporaryDirectory.isDirectory && !temporaryDirectory.mkdirs()) throw ProviderDownloadException("There is not enough temporary storage to save this file.")
        val staging = File.createTempFile("provider-export-", ".part", temporaryDirectory)
        try {
            var current = offer.url
            var hops = 0
            var bytes = 0L
            while (true) {
                var complete = false
                check()
                // Query WebView's cookie manager for THIS validated URL, never copy the page's cookies.
                val cookie = cookieForUrl(current)
                transport.open(current, offer.userAgent, cookie, deadline - nowMillis(), cancellation).use { response ->
                    check()
                    if (response.status in setOf(301, 302, 303, 307, 308)) {
                        if (++hops > ProviderDownloadPolicy.MAX_REDIRECTS) throw ProviderDownloadException("The website redirected too many times.")
                        current = ProviderDownloadPolicy.redirect(offer, current, response.location)
                    } else {
                        if (response.status != 200) throw ProviderDownloadException("The website did not provide a file (HTTP ${response.status}). Sign-in or a paid plan may be required.")
                        if (!ProviderDownloadPolicy.acceptsMime(offer.format, response.contentType)) throw ProviderDownloadException("The website returned a page or unsupported file, not a downloadable tab.")
                        if (response.contentLength > maxBytes) throw ProviderDownloadException("This file is larger than the 64 MiB saving limit.")
                        staging.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                check()
                                val count = response.body.read(buffer)
                                check()
                                if (count < 0) break
                                if (count == 0) continue
                                bytes += count
                                if (bytes > maxBytes) throw ProviderDownloadException("This file is larger than the 64 MiB saving limit.")
                                output.write(buffer, 0, count)
                                progress(bytes, response.contentLength)
                            }
                        }
                        if (bytes == 0L || (response.contentLength >= 0 && bytes != response.contentLength)) throw ProviderDownloadException("The website sent an empty or incomplete file.")
                        complete = true
                    }
                }
                if (complete) break
            }
            cancellation.bind(null)
            check()
            validate(staging, offer.format)
            check()
            // Only now can bytes reach the SAF document. Failure/cancellation cleanup belongs to its owner.
            openDestination().use { output ->
                cancellation.bind { runCatching { output.close() } }
                staging.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    check()
                    output.flush()
                }
            }
            check()
            return bytes
        } finally {
            cancellation.bind(null)
            staging.delete()
        }
    }

    private fun validate(file: File, format: ProviderFileFormat) {
        val prefix = file.inputStream().use { input -> val bytes = ByteArray(128); bytes.copyOf(input.read(bytes).coerceAtLeast(0)) }
        fun ascii(start: Int, text: String) = prefix.size >= start + text.length && text.indices.all { prefix[start + it].toInt() and 255 == text[it].code }
        val valid = when (format) {
            ProviderFileFormat.PDF -> ascii(0, "%PDF-") && file.length() >= 16
            ProviderFileFormat.MIDI -> ascii(0, "MThd") && prefix.size >= 14 && prefix.sliceArray(4..7).contentEquals(byteArrayOf(0, 0, 0, 6))
            ProviderFileFormat.WAV -> ascii(0, "RIFF") && ascii(8, "WAVE") && prefix.size >= 16
            ProviderFileFormat.MP3 -> (ascii(0, "ID3") && prefix.size >= 10) || (prefix.size >= 4 && prefix[0].toInt() and 255 == 255 && prefix[1].toInt() and 0xe0 == 0xe0 && prefix[1].toInt() and 6 != 0)
            ProviderFileFormat.GP3, ProviderFileFormat.GP4, ProviderFileFormat.GP5 -> ascii(1, "FICHIER GUITAR PRO") || ascii(1, "FICHIER GUITARE PRO")
            ProviderFileFormat.GPX -> ascii(0, "BCFZ") || ascii(0, "BCFS")
            ProviderFileFormat.GP -> ascii(0, "PK\u0003\u0004") && validGuitarProZip(file)
        }
        if (!valid) throw ProviderDownloadException("The downloaded content is not the expected file format. No file was saved.")
    }

    /** Bounded central-directory inspection, with no ZipFile entry table or decompression allocation. */
    private fun validGuitarProZip(file: File): Boolean = runCatching {
        fun u16(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        fun u32(bytes: ByteArray, offset: Int) = u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)
        RandomAccessFile(file, "r").use { input ->
            val tailSize = minOf(file.length(), 65_557L).toInt()
            if (tailSize < 22) return@use false
            val tail = ByteArray(tailSize)
            input.seek(file.length() - tailSize)
            input.readFully(tail)
            val end = (tail.size - 22 downTo 0).firstOrNull { index ->
                u32(tail, index) == 0x06054b50L && index + 22 + u16(tail, index + 20) == tail.size
            } ?: return@use false
            val entries = u16(tail, end + 10)
            val size = u32(tail, end + 12)
            val start = u32(tail, end + 16)
            // Small, non-ZIP64, single-disk archives only. Bounds apply before inspecting any entries.
            if (entries !in 1..512 || size !in 46L..262_144L || u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0 ||
                u16(tail, end + 8) != entries || start + size != file.length() - tailSize + end) return@use false
            input.seek(start)
            val wanted = "Content/score.gpif".toByteArray(Charsets.US_ASCII)
            var scoreOffset = -1L
            var scoreBytes = 0L
            repeat(entries) {
                if (input.filePointer + 46 > start + size) return@use false
                val header = ByteArray(46)
                input.readFully(header)
                if (u32(header, 0) != 0x02014b50L) return@use false
                val nameSize = u16(header, 28)
                val skip = u16(header, 30) + u16(header, 32)
                if (nameSize !in 1..512 || input.filePointer + nameSize + skip > start + size) return@use false
                val name = ByteArray(nameSize)
                input.readFully(name)
                if (name.contentEquals(wanted)) {
                    if (scoreOffset >= 0 || u16(header, 8) and 1 != 0 || u16(header, 10) !in setOf(0, 8) ||
                        u32(header, 24) !in 1..ProviderDownloadPolicy.MAX_BYTES) return@use false
                    scoreOffset = u32(header, 42)
                    scoreBytes = u32(header, 20)
                }
                input.seek(input.filePointer + skip)
            }
            if (input.filePointer != start + size || scoreOffset < 0 || scoreOffset + 30 > start || scoreBytes !in 1..file.length()) return@use false
            input.seek(scoreOffset)
            val local = ByteArray(30)
            input.readFully(local)
            if (u32(local, 0) != 0x04034b50L || u16(local, 6) and 1 != 0 || u16(local, 8) !in setOf(0, 8) ||
                u16(local, 26) != wanted.size || scoreOffset + 30 + wanted.size + u16(local, 28) + scoreBytes > start) return@use false
            val name = ByteArray(wanted.size)
            input.readFully(name)
            name.contentEquals(wanted)
        }
    }.getOrDefault(false)

}
