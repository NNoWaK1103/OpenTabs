package org.opentabs.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.CookieManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opentabs.core.*
import java.io.File
import java.io.IOException
import java.util.UUID

/** Only nonsensitive presentation data is exposed to Compose; offers and credentials are not saved. */
data class ProviderDownloadUiState(
    val stage: Stage = Stage.IDLE, val filename: String = "", val provider: String = "", val host: String = "",
    val bytes: Long = 0, val total: Long = -1, val message: String = "",
) {
    enum class Stage { IDLE, OFFER, CHOOSING, SAVING, RESULT }
}

/** Rotation/closing cancels saves instead of retaining an Activity, a signed URL, or an old destination. */
class ProviderDownloadController(
    private val downloadFile: (Context, ProviderFileOffer, Uri, ProviderDownloadCancellation, (Long, Long) -> Unit) -> Long = ::saveProviderFile,
    private val documentCheck: (Context, Uri) -> Boolean = DocumentsContract::isDocumentUri,
    private val deleteDocument: (Context, Uri) -> Boolean = { context, uri ->
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
    },
) {
    private val mutableState = MutableStateFlow(ProviderDownloadUiState())
    val state = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var offer: ProviderFileOffer? = null
    private var choice: String? = null
    private var cancellation: ProviderDownloadCancellation? = null
    private var saving: Job? = null
    private var disposed = false

    fun offer(pageUrl: String, url: String, userAgent: String, disposition: String?, mime: String?, size: Long) {
        if (disposed || state.value.stage in setOf(ProviderDownloadUiState.Stage.CHOOSING, ProviderDownloadUiState.Stage.SAVING, ProviderDownloadUiState.Stage.OFFER)) return
        try {
            val next = ProviderDownloadPolicy.offer(pageUrl, url, disposition, mime, size, userAgent)
            offer = next
            mutableState.value = ProviderDownloadUiState(ProviderDownloadUiState.Stage.OFFER, next.filename, next.source.label, next.host, total = size)
        } catch (error: ProviderDownloadException) { result(error.message.orEmpty()) }
    }

    /** A unique token prevents a late or repeated activity result from starting a different offer. */
    fun chooseDestination(): Pair<String, ProviderFileOffer>? {
        val current = offer ?: return null
        if (state.value.stage != ProviderDownloadUiState.Stage.OFFER || disposed) return null
        val token = UUID.randomUUID().toString()
        choice = token
        mutableState.value = state.value.copy(stage = ProviderDownloadUiState.Stage.CHOOSING)
        return token to current
    }

    fun destination(context: Context, token: String?, uri: Uri?) {
        val appContext = context.applicationContext
        if (uri == null) {
            if (choice == token && state.value.stage == ProviderDownloadUiState.Stage.CHOOSING) cancel()
            return
        }
        val current = offer
        if (disposed || token == null || token != choice || current == null || state.value.stage != ProviderDownloadUiState.Stage.CHOOSING) {
            // A stale/repeated URI may already be a successfully saved file. It is NOT ours to delete.
            // It must not change an active newer offer either. After rotation an empty picker file may remain.
            if (!disposed && state.value.stage == ProviderDownloadUiState.Stage.IDLE) {
                result("No download started. If Android created an empty file, remove it from the location you chose and use the website’s Download button again.")
            }
            return
        }
        // SAF creates document URIs. Reject unexpected result schemes before writing or deleting.
        if (uri.scheme != "content" || !runCatching { documentCheck(appContext, uri) }.getOrDefault(false)) {
            choice = null
            offer = null
            result("Android did not provide a usable document location. No file was saved.")
            return
        }
        choice = null
        val stop = ProviderDownloadCancellation()
        cancellation = stop
        mutableState.value = state.value.copy(stage = ProviderDownloadUiState.Stage.SAVING, bytes = 0)
        saving = scope.launch {
            var completed = false
            var failure = "Could not save this file. Try the website again or open it in your browser."
            try {
                val count = withContext(Dispatchers.IO) {
                    downloadFile(appContext, current, uri, stop) { bytes, total ->
                        mutableState.value = mutableState.value.copy(bytes = bytes, total = total)
                    }
                }
                ensureActive()
                completed = true
                result("Saved ${current.filename} (${formatFileBytes(count)}). Open it from your chosen Files location with a compatible app. This does not save the website player.")
            } catch (_: CancellationException) {
                failure = "Saving canceled. No file was saved."
            } catch (_: ProviderDownloadCancelled) {
                failure = "Saving canceled. No file was saved."
            } catch (error: ProviderDownloadException) {
                failure = error.message ?: failure
            } catch (_: Exception) {
                // Never surface exception messages: network errors may contain signed URLs or cookies.
            } finally {
                stop.cancel()
                if (!completed) {
                    val removed = withContext(NonCancellable + Dispatchers.IO) { deleteDocument(appContext, uri) }
                    if (!removed) failure += " Android could not remove the incomplete file; delete it from the location you chose."
                    if (!disposed) result(failure)
                }
                cancellation = null
                saving = null
                offer = null
            }
        }
    }

    fun pickerUnavailable() { choice = null; offer = null; result("Android’s file picker is unavailable. Open the website in your browser to save this file.") }
    fun cancel() {
        choice = null
        offer = null
        cancellation?.cancel()
        saving?.cancel()
        if (saving == null) mutableState.value = ProviderDownloadUiState()
    }
    fun dismiss() { if (saving == null) { offer = null; mutableState.value = ProviderDownloadUiState() } }
    fun close() { disposed = true; cancel(); scope.cancel() }
    private fun result(message: String) { mutableState.value = state.value.copy(stage = ProviderDownloadUiState.Stage.RESULT, message = message) }

}

private fun saveProviderFile(
    context: Context, offer: ProviderFileOffer, uri: Uri, stop: ProviderDownloadCancellation, progress: (Long, Long) -> Unit,
): Long = ProviderFileDownloader().save(offer, File(context.cacheDir, "provider-exports"), stop,
    cookieForUrl = { target -> CookieManager.getInstance().getCookie(target) },
    openDestination = { context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream") },
    progress = progress,
)

internal fun formatFileBytes(bytes: Long): String = if (bytes < 1024 * 1024) "${(bytes / 1024).coerceAtLeast(1)} KiB" else String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
