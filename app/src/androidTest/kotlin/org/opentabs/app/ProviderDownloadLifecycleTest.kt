package org.opentabs.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No paid-provider export is simulated here: these cover native offer/picker ownership only. */
@RunWith(AndroidJUnit4::class)
class ProviderDownloadLifecycleTest {
    private val page = "https://www.songsterr.com/a/wsa/example-tab-s1"
    private fun offer(controller: ProviderDownloadController, name: String = "Study.pdf") = controller.offer(
        page, "https://www.songsterr.com/file.pdf?signature=private", "Test WebView",
        "attachment; filename=\"$name\"", "application/pdf", 128,
    )
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test fun repeatedOffersAndSaveClicksCannotReplaceAnAcceptedChoice() = main {
        val controller = ProviderDownloadController()
        try {
            offer(controller)
            offer(controller, "Other.pdf")
            assertEquals("Study.pdf", controller.state.value.filename)
            val first = controller.chooseDestination()
            assertNotNull(first)
            assertNull(controller.chooseDestination())
            offer(controller, "Later.pdf")
            assertEquals("Study.pdf", controller.state.value.filename)
            assertEquals(ProviderDownloadUiState.Stage.CHOOSING, controller.state.value.stage)
            assertFalse(controller.state.value.toString().contains("signature"))
        } finally { controller.close() }
    }

    @Test fun canceledPickerAndStaleResultsDoNotAffectNewOffer() = main {
        val controller = ProviderDownloadController()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            offer(controller)
            val previous = controller.chooseDestination()!!.first
            controller.destination(context, previous, null)
            assertEquals(ProviderDownloadUiState.Stage.IDLE, controller.state.value.stage)
            offer(controller, "New.pdf")
            val next = controller.chooseDestination()!!.first
            assertNotEquals(previous, next)
            controller.destination(context, previous, null)
            assertEquals(ProviderDownloadUiState.Stage.CHOOSING, controller.state.value.stage)
            assertEquals("New.pdf", controller.state.value.filename)
            controller.destination(context, next, null)
            assertEquals(ProviderDownloadUiState.Stage.IDLE, controller.state.value.stage)
        } finally { controller.close() }
    }

    @Test fun repeatedSuccessfulResultNeverDeletesOrResavesCompletedFile() {
        val deleted = java.util.concurrent.CopyOnWriteArrayList<android.net.Uri>()
        val saves = java.util.concurrent.atomic.AtomicInteger()
        val controller = ProviderDownloadController(
            downloadFile = { _, _, _, _, _ -> saves.incrementAndGet(); 128L },
            documentCheck = { _, _ -> true }, deleteDocument = { _, uri -> deleted += uri; true },
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = android.net.Uri.parse("content://test.documents/document/saved")
        var token = ""
        try {
            main { offer(controller); token = controller.chooseDestination()!!.first; controller.destination(context, token, uri) }
            awaitResult(controller)
            main {
                assertTrue(controller.state.value.message.startsWith("Saved "))
                controller.destination(context, token, uri)
                assertTrue(controller.state.value.message.startsWith("Saved "))
                assertEquals(1, saves.get())
                assertTrue(deleted.isEmpty())
            }
        } finally { main { controller.close() } }
    }

    @Test fun staleNonNullResultCannotDeleteExistingFileOrReplaceNewChoice() = main {
        val deleted = mutableListOf<android.net.Uri>()
        val controller = ProviderDownloadController(
            downloadFile = { _, _, _, _, _ -> fail("Stale result started a save"); 0L },
            documentCheck = { _, _ -> true }, deleteDocument = { _, uri -> deleted += uri; true },
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            offer(controller)
            val stale = controller.chooseDestination()!!.first
            controller.cancel()
            offer(controller, "New.pdf")
            controller.chooseDestination()
            controller.destination(context, stale, android.net.Uri.parse("content://test.documents/document/existing"))
            assertEquals(ProviderDownloadUiState.Stage.CHOOSING, controller.state.value.stage)
            assertEquals("New.pdf", controller.state.value.filename)
            assertTrue(deleted.isEmpty())
        } finally { controller.close() }
    }

    @Test fun failedActiveSaveCleansOnlyItsOwnDocumentAndHidesRawException() {
        val deleted = java.util.concurrent.CopyOnWriteArrayList<android.net.Uri>()
        val controller = ProviderDownloadController(
            downloadFile = { _, _, _, _, _ -> throw java.io.IOException("https://private.example/?secret=token") },
            documentCheck = { _, _ -> true }, deleteDocument = { _, uri -> deleted += uri; true },
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = android.net.Uri.parse("content://test.documents/document/failed")
        try {
            main { offer(controller); val token = controller.chooseDestination()!!.first; controller.destination(context, token, uri) }
            awaitResult(controller)
            main {
                assertEquals(listOf(uri), deleted.toList())
                assertFalse(controller.state.value.message.contains("private"))
                assertFalse(controller.state.value.message.startsWith("Saved "))
            }
        } finally { main { controller.close() } }
    }

    private fun awaitResult(controller: ProviderDownloadController) {
        val deadline = System.currentTimeMillis() + 5_000
        while (controller.state.value.stage != ProviderDownloadUiState.Stage.RESULT && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(ProviderDownloadUiState.Stage.RESULT, controller.state.value.stage)
    }

    @Test fun unexpectedPickerUriNeverStartsSaving() = main {
        val controller = ProviderDownloadController()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            offer(controller)
            val token = controller.chooseDestination()!!.first
            controller.destination(context, token, android.net.Uri.parse("file:///tmp/unapproved.pdf"))
            assertEquals(ProviderDownloadUiState.Stage.RESULT, controller.state.value.stage)
            assertTrue(controller.state.value.message.contains("No file was saved"))
            assertNull(controller.chooseDestination())
        } finally { controller.close() }
    }

    @Test fun disposedControllerCannotRestartDownloadsAfterRotationOrClose() = main {
        val controller = ProviderDownloadController()
        offer(controller)
        controller.close()
        assertEquals(ProviderDownloadUiState.Stage.IDLE, controller.state.value.stage)
        offer(controller)
        assertEquals(ProviderDownloadUiState.Stage.IDLE, controller.state.value.stage)
        assertNull(controller.chooseDestination())
    }

    @Test fun unsupportedOffersShowHonestFallbackWithoutCreatingSaveChoice() = main {
        val controller = ProviderDownloadController()
        try {
            controller.offer(page, "blob:https://www.songsterr.com/private", "", null, "application/pdf", 100)
            assertEquals(ProviderDownloadUiState.Stage.RESULT, controller.state.value.stage)
            assertTrue(controller.state.value.message.contains("browser"))
            assertNull(controller.chooseDestination())
            assertFalse(controller.state.value.toString().contains("private"))
            controller.dismiss()
            assertEquals(ProviderDownloadUiState.Stage.IDLE, controller.state.value.stage)
        } finally { controller.close() }
    }
}
