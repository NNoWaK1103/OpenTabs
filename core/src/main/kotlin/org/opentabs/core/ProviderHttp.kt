package org.opentabs.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class HttpPayload(val bytes: ByteArray, val cached: Boolean = false, val stale: Boolean = false)
interface ProviderHttp {
    suspend fun get(url: String, maxBytes: Int, cacheMillis: Long = 0): HttpPayload
}

fun providerHttp(cacheDirectory: File): ProviderHttp = OkHttpProviderHttp(LicensedHttpCache(cacheDirectory))

/** Only explicitly CC0 provider endpoints use disk caching. Twelve files, at most 2 MiB in total. */
class LicensedHttpCache(private val folder: File, private val clock: () -> Long = System::currentTimeMillis) {
    @Synchronized fun read(url: String, ttl: Long, allowStale: Boolean): HttpPayload? {
        val file = File(folder, key(url))
        if (!file.isFile || file.length() > 1024 * 1024) return null
        val stale = clock() - file.lastModified() > ttl
        if (stale && (!allowStale || clock() - file.lastModified() > 7L * 24 * 60 * 60 * 1000)) return null
        return HttpPayload(file.readBytes(), cached = true, stale = stale)
    }

    @Synchronized fun write(url: String, bytes: ByteArray) {
        if (bytes.size > 1024 * 1024) return
        if (!folder.isDirectory && !folder.mkdirs()) return
        val file = File(folder, key(url))
        val temporary = File(folder, "${file.name}.tmp")
        temporary.writeBytes(bytes)
        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        file.setLastModified(clock())
        val files = folder.listFiles()?.filter { !it.name.endsWith(".tmp") }?.sortedByDescending { it.lastModified() }.orEmpty()
        var total = 0L
        files.forEachIndexed { index, entry ->
            total += entry.length()
            if (index >= 12 || total > 2 * 1024 * 1024) entry.delete()
        }
    }
    private fun key(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
}

class OkHttpProviderHttp(
    private val cache: LicensedHttpCache? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val approvedHosts: Set<String> = setOf("musicbrainz.org", "raw.githubusercontent.com"),
    private val requireHttps: Boolean = true,
) : ProviderHttp {
    override suspend fun get(url: String, maxBytes: Int, cacheMillis: Long): HttpPayload = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "OpenTabs/0.2 (https://github.com/NNoWaK1103/OpenTabs)")
            .header("Accept", "application/json, application/octet-stream, text/plain").build()
        require(request.url.host in approvedHosts && (!requireHttps || request.url.isHttps)) { "Unapproved provider URL." }
        require(maxBytes in 1..1024 * 1024) { "Invalid response limit." }
        if (cacheMillis > 0) runCatching { cache?.read(url, cacheMillis, false) }.getOrNull()?.takeIf { it.bytes.size <= maxBytes }?.let {
            return@withContext it
        }
        try {
            val bytes = suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val outcome = runCatching {
                            response.use {
                                if (it.code == 429 || it.code == 503) throw IOException("Source is rate limited or busy. Try again later.")
                                require(it.isSuccessful) { "Source returned HTTP ${it.code}." }
                                val body = it.body ?: error("Source returned an empty response.")
                                require(body.contentLength() <= maxBytes) { "Provider response is too large." }
                                body.byteStream().use { stream -> readBounded(stream, maxBytes) }
                            }
                        }
                        if (continuation.isActive) continuation.resumeWith(outcome)
                    }
                })
            }
            // Providers validate the payload before calling cacheValidated; malformed data never replaces a good cache.
            HttpPayload(bytes)
        } catch (error: IOException) {
            if (cacheMillis > 0) runCatching { cache?.read(url, cacheMillis, true) }.getOrNull()?.takeIf { it.bytes.size <= maxBytes }?.let {
                return@withContext it
            }
            throw IOException("Cannot reach this source. Check your connection; saved tabs still work.", error)
        }
    }

    fun cacheValidated(url: String, payload: HttpPayload) {
        if (!payload.cached) runCatching { cache?.write(url, payload.bytes) }
    }
}

internal fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() <= maxBytes) {
        val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
        if (count < 0) break
        if (count > 0) output.write(buffer, 0, count)
    }
    require(output.size() <= maxBytes) { "Provider response is too large." }
    require(output.size() > 0) { "Source returned an empty response." }
    return output.toByteArray()
}
