package org.opentabs.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProviderFileDownloadTest {
    private val page = "https://www.songsterr.com/a/wsa/example-tab-s1"
    private val pdf = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n".toByteArray()
    private fun offer(url: String = "https://www.songsterr.com/export/tab.pdf", disposition: String? = "attachment; filename=\"Study.pdf\"", mime: String? = "application/pdf", length: Long = -1) =
        ProviderDownloadPolicy.offer(page, url, disposition, mime, length, "WebView Test")

    @Test fun `only first party https offers from provider pages qualify`() {
        assertEquals("www.songsterr.com", offer().host)
        assertEquals("files.songsterr.com", offer("https://files.songsterr.com/study.pdf").host)
        listOf("http://www.songsterr.com/tab.pdf", "https://user:pass@www.songsterr.com/tab.pdf", "https://www.songsterr.com:444/tab.pdf",
            "https://songsterr.com.evil.example/tab.pdf", "https://not-songsterr.com/tab.pdf", "https://www.songsterr.com./tab.pdf",
            "https://www.ultimate-guitar.com/tab.pdf", "blob:https://www.songsterr.com/a", "data:application/pdf,abc",
            "https://www.songsterr.com\\@evil.example/tab.pdf", "https://127.0.0.1/tab.pdf").forEach { target -> rejects { offer(target) } }
        rejects { ProviderDownloadPolicy.offer("https://evil.example/", "https://www.songsterr.com/tab.pdf", null, "application/pdf", -1, "") }
    }

    @Test fun `filename is sanitized decoded bounded and format constrained`() {
        val safe = offer(disposition = "attachment; filename*=UTF-8''..%2F..%2FMy%20Study.pdf")
        assertEquals("My Study.pdf", safe.filename)
        assertTrue(offer(disposition = "attachment; filename=\"${"A".repeat(300)}.pdf\"").filename.length <= 94)
        rejects { offer(disposition = "attachment; filename=\"song.apk\"") }
        rejects { offer(mime = "text/html") }
        rejects { offer(mime = "application/javascript") }
        rejects { offer(length = ProviderDownloadPolicy.MAX_BYTES + 1) }
        assertFalse(offer("https://www.songsterr.com/tab.pdf?secret=token").toString().contains("secret"))
    }

    @Test fun `generic mime never invents format and token path is not used as filename`() {
        rejects { offer("https://www.songsterr.com/secret-token", null, "APPLICATION/OCTET-STREAM") }
        rejects { offer("https://www.songsterr.com/secret-token", null, " application/octet-stream ; charset=x") }
        val inferred = offer("https://www.songsterr.com/secret-token?signature=private", null, " APPLICATION/PDF; charset=x")
        assertEquals("Songsterr download.pdf", inferred.filename)
        assertFalse(inferred.toString().contains("secret-token"))
        assertFalse(inferred.toString().contains("private"))
    }

    @Test fun `redirect allows relative same origin but never credentials or another host`() {
        val request = offer()
        assertEquals("https://www.songsterr.com/final.pdf", ProviderDownloadPolicy.redirect(request, request.url, "/final.pdf"))
        listOf("https://files.songsterr.com/final.pdf", "https://evil.example/final.pdf", "http://www.songsterr.com/final.pdf",
            "https://user@www.songsterr.com/final.pdf", "https://www.songsterr.com:444/final.pdf", "//evil.example/x", "data:a", "").forEach {
            rejects { ProviderDownloadPolicy.redirect(request, request.url, it) }
        }
    }

    @Test fun `authorized bytes are staged validated then written once and temp files removed`() = inDirectory { directory ->
        val output = ByteArrayOutputStream()
        var opened = 0
        var progress = 0L
        val downloaded = ProviderFileDownloader(transport { _, _, _ -> response(pdf) }).save(offer(), directory, ProviderDownloadCancellation(),
            cookieForUrl = { null }, openDestination = { opened++; output }, progress = { bytes, _ -> progress = bytes })
        assertEquals(pdf.size.toLong(), downloaded)
        assertArrayEquals(pdf, output.toByteArray())
        assertEquals(1, opened)
        assertEquals(downloaded, progress)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `guitar pro archive validates bounded directory without decompressing`() = inDirectory { directory ->
        val request = offer(disposition = "attachment; filename=Study.gp", mime = "application/octet-stream")
        val fixture = javaClass.getResourceAsStream("/tabforge-ashen-meridian.gp")!!.use { it.readBytes() }
        val output = ByteArrayOutputStream()
        ProviderFileDownloader(transport { _, _, _ -> response(fixture, mime = "application/octet-stream") })
            .save(request, directory, ProviderDownloadCancellation(), { null }, { output })
        assertArrayEquals(fixture, output.toByteArray())

        val oversizedDirectory = ByteArrayOutputStream().apply {
            java.util.zip.ZipOutputStream(this).use { zip ->
                repeat(513) { index ->
                    zip.putNextEntry(java.util.zip.ZipEntry(if (index == 0) "Content/score.gpif" else "entry-$index"))
                    zip.write("x".toByteArray()); zip.closeEntry()
                }
            }
        }.toByteArray()
        rejects {
            ProviderFileDownloader(transport { _, _, _ -> response(oversizedDirectory, mime = "application/octet-stream") })
                .save(request, directory, ProviderDownloadCancellation(), { null }, { fail("Unbounded directory reached output"); ByteArrayOutputStream() })
        }
        val notGuitarPro = ByteArrayOutputStream().apply {
            java.util.zip.ZipOutputStream(this).use { zip -> zip.putNextEntry(java.util.zip.ZipEntry("malware.exe")); zip.write(byteArrayOf(1)); zip.closeEntry() }
        }.toByteArray()
        rejects {
            ProviderFileDownloader(transport { _, _, _ -> response(notGuitarPro, mime = "application/octet-stream") })
                .save(request, directory, ProviderDownloadCancellation(), { null }, { fail("Unrelated ZIP reached output"); ByteArrayOutputStream() })
        }
    }

    @Test fun `cookies queried only for exact approved request and same origin redirects`() = inDirectory { directory ->
        val seen = mutableListOf<Pair<String, String?>>()
        val cookies = mutableListOf<String>()
        var calls = 0
        val transport = transport { url, cookie, _ ->
            seen += url to cookie
            if (calls++ == 0) response(byteArrayOf(), 302, location = "/download/final.pdf") else response(pdf)
        }
        ProviderFileDownloader(transport).save(offer(), directory, ProviderDownloadCancellation(),
            cookieForUrl = { url -> cookies += url; if (url.contains("/export/")) "export=one" else "download=two" }, openDestination = { ByteArrayOutputStream() })
        assertEquals(listOf("https://www.songsterr.com/export/tab.pdf", "https://www.songsterr.com/download/final.pdf"), cookies)
        assertEquals(listOf("export=one", "download=two"), seen.map { it.second })
    }

    @Test fun `untrusted redirect is rejected before another request or cookie lookup`() = inDirectory { directory ->
        var calls = 0
        var cookies = 0
        rejects {
            ProviderFileDownloader(transport { _, _, _ -> calls++; response(byteArrayOf(), 302, location = "https://evil.example/download.pdf") })
                .save(offer(), directory, ProviderDownloadCancellation(), { cookies++; "private=session" }, { fail("Must not open output"); ByteArrayOutputStream() })
        }
        assertEquals(1, calls)
        assertEquals(1, cookies)
    }

    @Test fun `http errors html spoofing empty truncated and oversized responses never open destination`() = inDirectory { directory ->
        val cases = listOf(
            response(pdf, 403), response(pdf, 206), response("<html>Sign in</html>".toByteArray(), mime = "text/html"),
            response("<html>Sign in</html>".toByteArray(), mime = "application/octet-stream"), response(byteArrayOf()),
            response(pdf, length = pdf.size + 1L), response(pdf, length = ProviderDownloadPolicy.MAX_BYTES + 1),
            response(ByteArray(200) { 1 }, length = -1),
        )
        for (response in cases) {
            rejects {
                ProviderFileDownloader(transport { _, _, _ -> response }, maxBytes = 128).save(offer(), directory,
                    ProviderDownloadCancellation(), { null }, { fail("Invalid response reached destination"); ByteArrayOutputStream() })
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun `cancel before transport or mid stream prevents destination and cleans staging`() = inDirectory { directory ->
        val canceled = ProviderDownloadCancellation().apply { cancel() }
        try {
            ProviderFileDownloader(transport { _, _, _ -> fail("Canceled request must not start"); response(pdf) })
                .save(offer(), directory, canceled, { null }, { ByteArrayOutputStream() })
            fail("Expected cancellation")
        } catch (_: ProviderDownloadCancelled) { }
        val token = ProviderDownloadCancellation()
        var closed = false
        val source = object : ByteArrayInputStream(pdf) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { token.cancel(); return super.read(bytes, offset, length) }
        }
        try {
            ProviderFileDownloader(transport { _, _, cancellation ->
                cancellation.bind { closed = true }
                response(pdf, input = source)
            }).save(offer(), directory, token, { null }, { fail("Canceled stream reached output"); ByteArrayOutputStream() })
            fail("Expected cancellation")
        } catch (_: ProviderDownloadCancelled) { }
        assertTrue(closed)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `destination write failure closes output and cleans only owned staging`() = inDirectory { directory ->
        val other = File(directory, "unrelated").apply { writeText("keep") }
        var closed = false
        try {
            ProviderFileDownloader(transport { _, _, _ -> response(pdf) }).save(offer(), directory, ProviderDownloadCancellation(), { null }, {
                object : OutputStream() {
                    override fun write(byte: Int) { throw IOException("Storage full") }
                    override fun close() { closed = true }
                }
            })
            fail("Expected write error")
        } catch (_: IOException) { }
        assertTrue(closed)
        assertEquals(listOf(other), directory.listFiles()!!.toList())
    }

    @Test fun `cancel during destination writing closes it and reports failure`() = inDirectory { directory ->
        val token = ProviderDownloadCancellation()
        var closed = false
        try {
            ProviderFileDownloader(transport { _, _, _ -> response(pdf) }).save(offer(), directory, token, { null }, {
                object : OutputStream() {
                    override fun write(byte: Int) { token.cancel() }
                    override fun close() { closed = true }
                }
            })
            fail("Expected cancellation")
        } catch (_: ProviderDownloadCancelled) { }
        assertTrue(closed)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `redirect count and overall deadline are bounded`() = inDirectory { directory ->
        var calls = 0
        rejects {
            ProviderFileDownloader(transport { _, _, _ -> calls++; response(byteArrayOf(), 302, location = "/loop.pdf") })
                .save(offer(), directory, ProviderDownloadCancellation(), { null }, { ByteArrayOutputStream() })
        }
        assertEquals(ProviderDownloadPolicy.MAX_REDIRECTS + 1, calls)
        var clock = 0L
        rejects {
            ProviderFileDownloader(transport { _, _, _ -> clock = 130_000L; response(pdf) }, nowMillis = { clock })
                .save(offer(), directory, ProviderDownloadCancellation(), { null }, { fail("Expired save reached output"); ByteArrayOutputStream() })
        }
    }

    private fun transport(block: (String, String?, ProviderDownloadCancellation) -> ProviderDownloadResponse) =
        ProviderDownloadTransport { url, _, cookie, _, cancellation -> block(url, cookie, cancellation) }

    private fun response(bytes: ByteArray, status: Int = 200, mime: String = "application/pdf", length: Long = bytes.size.toLong(), location: String? = null, input: InputStream = ByteArrayInputStream(bytes)) = object : ProviderDownloadResponse {
        override val status = status
        override val contentType = mime
        override val contentLength = length
        override val location = location
        override val body = input
        override fun close() { input.close() }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected safe rejection") } catch (_: ProviderDownloadException) { }
    }
    private fun inDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("provider-download-test-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
