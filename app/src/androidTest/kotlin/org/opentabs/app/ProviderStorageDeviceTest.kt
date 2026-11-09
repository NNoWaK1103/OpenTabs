package org.opentabs.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.opentabs.core.WebTabSource

/** Launch and provider operations may never seed, repair, delete, or migrate native-library data. */
class ProviderStorageDeviceTest {
    private class IsolatedApplication(private val context: Context, private val directory: File) : Application() {
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("provider-test-${directory.name}-$name", mode)
    }

    private fun withFiles(test: (Application, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = File.createTempFile("provider-files-", "", context.cacheDir).also { it.delete(); it.mkdirs() }
        try { test(IsolatedApplication(context, files), files) }
        finally { files.deleteRecursively() }
    }

    private fun snapshot(folder: File): Map<String, List<Byte>> = folder.walkTopDown().filter(File::isFile)
        .associate { it.relativeTo(folder).path to it.readBytes().toList() }

    @Test fun freshProviderLaunchDoesNotCreateLibraryOrExercises() = withFiles { application, files ->
        val vm = ProviderTabsViewModel(application)
        assertNull(vm.screen.value.webTab)
        assertTrue(runBlocking { vm.bookmarks.list() }.isEmpty())
        assertFalse(File(files, "library").exists())
        assertTrue(snapshot(files).isEmpty())
    }

    @Test fun oldLibraryAndCorruptMetadataStayByteForByteUntouched() = withFiles { application, files ->
        val library = File(files, "library").also { it.mkdirs() }
        File(library, "library.json").writeText("An intentionally corrupt old index must not block provider search.")
        File(library, "my-import.tab").writeBytes(byteArrayOf(0, 1, 2, 3, 127))
        File(library, "example-0.txt").writeText("Existing exercise stays private and untouched.")
        File(library, "my-import.practice.json").writeText("{\"existing\":\"authored audio data\"}")
        File(library, "audio").mkdirs()
        File(library, "audio/my-import.mid").writeBytes(byteArrayOf(77, 84, 104, 100))
        val before = snapshot(files)
        val vm = ProviderTabsViewModel(application)
        vm.setQuery("Nutshell Alice in Chains")
        vm.browse(WebTabSource.SONGSTERR)
        vm.fullScreen(true)
        vm.closeBrowser()
        vm.browse(WebTabSource.ULTIMATE_GUITAR)
        vm.closeBrowser()
        assertEquals(before, snapshot(files))
        assertEquals("Nutshell Alice in Chains", vm.screen.value.query)
        assertNull(vm.screen.value.webTab)
    }

    @Test fun existingSavedLinksReopenAtTheExactProviderUrlWithoutCreatingLocalTabs() = withFiles { application, files ->
        val url = "https://www.songsterr.com/a/wsa/open-tabs-test-tab-s1"
        val vm = ProviderTabsViewModel(application)
        runBlocking { vm.bookmarks.save(url, "Saved provider tab") }
        val before = snapshot(files)
        vm.openSavedLink(runBlocking { vm.bookmarks.list() }.single().url)
        assertEquals(WebTabSource.SONGSTERR, vm.screen.value.webTab!!.source)
        assertEquals(url, vm.webTabMemory.lastUrl)
        assertEquals(vm.screen.value.webTab!!.revision, vm.webTabMemory.revision)
        assertNull(vm.webTabMemory.history)
        assertEquals(before, snapshot(files))
        assertFalse(File(files, "library").exists())
    }
}
