package org.opentabs.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.opentabs.core.ProviderFileOffer

/** ACTION_CREATE_DOCUMENT creates an explicitly user-chosen file without broad storage permission. */
private class CreateProviderFile : ActivityResultContract<ProviderFileOffer, android.net.Uri?>() {
    override fun createIntent(context: Context, input: ProviderFileOffer) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.format.mime).putExtra(Intent.EXTRA_TITLE, input.filename)
    override fun parseResult(resultCode: Int, intent: Intent?) = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

@Composable
fun ProviderDownloadUi(controller: ProviderDownloadController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    // The token can survive rotation, but the offer cannot. An old result is ignored, never reused or deleted.
    var pickerToken by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(CreateProviderFile()) { uri ->
        controller.destination(context, pickerToken, uri)
        pickerToken = null
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    when (state.stage) {
        ProviderDownloadUiState.Stage.OFFER -> AlertDialog(
            onDismissRequest = controller::cancel,
            title = { Text("Save the offered file?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.filename)
                Text("${state.provider} • ${state.host}")
                if (state.total > 0) Text(formatFileBytes(state.total))
                Text("Choose a location in Android Files. Only this file is saved, not the online tab player. The website controls access and any paid-plan requirements.")
                Text("Keep OpenTabs open while saving. Leaving this page or rotating the device cancels the save.", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(enabled = pickerToken == null, onClick = {
                // Do not overwrite an outstanding launch token: its late URI belongs to that launch.
                if (pickerToken == null) controller.chooseDestination()?.let { (token, offer) ->
                    pickerToken = token
                    try { launcher.launch(offer) }
                    catch (_: ActivityNotFoundException) { pickerToken = null; controller.pickerUnavailable() }
                    catch (_: SecurityException) { pickerToken = null; controller.pickerUnavailable() }
                }
            }) { Text("Choose save location") } },
            dismissButton = { TextButton(onClick = controller::cancel) { Text("Cancel") } },
        )
        ProviderDownloadUiState.Stage.SAVING -> AlertDialog(
            onDismissRequest = controller::cancel,
            title = { Text("Saving file") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.filename)
                if (state.total > 0) LinearProgressIndicator(progress = { (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (state.bytes == 0L) "Connecting securely…" else "${formatFileBytes(state.bytes)} received. Checking and saving the file…")
            } },
            confirmButton = { TextButton(onClick = controller::cancel) { Text("Cancel save") } },
        )
        ProviderDownloadUiState.Stage.RESULT -> AlertDialog(
            onDismissRequest = controller::dismiss,
            title = { Text("Website file") }, text = { Text(state.message) },
            confirmButton = { TextButton(onClick = controller::dismiss) { Text("OK") } },
        )
        else -> Unit
    }
}
