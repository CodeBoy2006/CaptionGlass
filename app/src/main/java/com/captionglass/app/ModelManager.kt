package com.captionglass.app

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/** Existing settings list, ordered by the two models this caption session actually needs. */
@Composable
internal fun ModelManager(pack: PackState, catalog: ModelCatalog, selection: ModelSelection,
                          localModels: Map<String, InstalledModel>, availableBytes: Long?, busy: Boolean,
                          onImport: (ModelSpec) -> Unit, onSelect: (ModelSelection) -> Unit,
                          onDownload: (ModelSpec) -> Unit, onCheck: (ModelSpec) -> Unit, onRemove: (ModelSpec) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    val required = catalog.required(selection)
    val ordered = required + catalog.recognizers.filter { it !in required }
    Text(stringResource(R.string.model_storage, modelSize(localModels.values.sumOf { it.bytes }), availableBytes?.let(::modelSize) ?: "—"),
        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    Text(stringResource(R.string.model_network_hint), Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    if (busy && !pack.busy) Text(stringResource(R.string.model_session_locked), Modifier.padding(top = 10.dp),
        style = MaterialTheme.typography.bodySmall, color = colors.tertiary)
    if (pack.busy && pack.modelId == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
    if (pack.modelId == null && pack.detail != null) Text(pack.detail, Modifier.padding(top = 8.dp),
        color = if (pack.failed) colors.error else colors.primary, style = MaterialTheme.typography.bodySmall)

    ordered.forEachIndexed { index, model ->
        if (index == 0 || index == required.size) Text(stringResource(if (index == 0) R.string.models_current else R.string.models_other),
            Modifier.padding(top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall)
        else HorizontalDivider(Modifier.padding(vertical = 16.dp))
        val local = localModels.getValue(model.id)
        val selected = model.id == selection.recognizerId
        val compatible = selection.languages.source in model.languages
        val working = pack.busy && pack.modelId == model.id
        var menu by remember(model.id) { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (model.kind == "asr") R.string.model_recognizer_summary else R.string.model_translator_summary,
                    model.languages.size, modelSize(model.size)), Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Box {
                IconButton({ menu = true }, Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.model_actions, model.name))
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.model_details)) }, onClick = { detailsId = model.id; menu = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_import)) }, enabled = !busy,
                        onClick = { detailsId = model.id; menu = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.model_check)) }, enabled = !busy && local.bytes > 0,
                        onClick = { onCheck(model); menu = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.model_delete), color = if (busy || local.bytes == 0L) colors.onSurface.copy(alpha = 0.38f) else colors.error) },
                        enabled = !busy && local.bytes > 0, onClick = { removeId = model.id; menu = false })
                }
            }
        }
        Text(stringResource(model.description), Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (working) {
            if (pack.phase == PackPhase.DOWNLOADING) Text(stringResource(R.string.model_transfer_hint),
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (pack.phase in listOf(PackPhase.REMOVING, PackPhase.CANCELLING))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            else LinearProgressIndicator({ pack.progress }, Modifier.fillMaxWidth().padding(top = 12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(pack.phase.label, (pack.progress * 100).toInt()), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge)
                if (pack.phase !in listOf(PackPhase.REMOVING, PackPhase.CANCELLING)) TextButton(ModelPack::cancel) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        } else {
            Text(stringResource(when {
                local.ready && selected -> R.string.model_selected_ready
                local.ready -> R.string.pack_ready
                local.bytes > 0 -> R.string.pack_needs_check
                else -> R.string.pack_missing
            }), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge,
                color = if (local.ready) colors.primary else colors.tertiary)
            if (model.kind == "asr" && !compatible) Text(stringResource(R.string.model_language_mismatch, selection.languages.source.label),
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!local.ready) {
                    Button({ onDownload(model) }, enabled = !busy) { Text(stringResource(R.string.model_download)) }
                    TextButton({ detailsId = model.id }, enabled = !busy) { Text(stringResource(R.string.action_import)) }
                } else if (model.kind == "asr" && compatible && !selected) {
                    FilledTonalButton({ onSelect(selection.copy(recognizerId = model.id)) }, enabled = !busy) {
                        Text(stringResource(R.string.model_use))
                    }
                }
            }
        }
        if (!pack.busy && pack.modelId == model.id && pack.detail != null) Text(pack.detail,
            Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
            color = if (pack.failed) colors.error else colors.primary)
    }

    catalog.models.find { it.id == detailsId }?.let { model ->
        var linkFailed by remember(model.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { detailsId = null }, title = { Text(model.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(model.description))
                    Text(model.languages.joinToString(" · ") { it.chineseName })
                    Text(stringResource(R.string.model_import_hint, modelSize(model.size)))
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            model.files.forEach { Text("${it.name}\n${modelSize(it.size)}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    Text(stringResource(R.string.model_license_revision, model.license, model.revision.take(12)),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.experimental_languages), style = MaterialTheme.typography.bodySmall)
                    TextButton({
                        linkFailed = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, model.source.toUri())) }.isFailure
                    }) { Text(stringResource(R.string.model_source)) }
                    if (linkFailed) Text(stringResource(R.string.model_source_failed), color = colors.error)
                }
            },
            confirmButton = { TextButton({ detailsId = null; onImport(model) }, enabled = !busy) { Text(stringResource(R.string.model_choose_folder)) } },
            dismissButton = { TextButton({ detailsId = null }) { Text(stringResource(R.string.action_close)) } })
    }
    catalog.models.find { it.id == removeId }?.let { model ->
        AlertDialog(onDismissRequest = { removeId = null }, title = { Text(stringResource(R.string.model_delete_title, model.name)) },
            text = { Text(stringResource(if (model in required) R.string.model_delete_required else R.string.model_delete_other,
                modelSize(localModels.getValue(model.id).bytes))) },
            confirmButton = { TextButton({ removeId = null; onRemove(model) }, enabled = !busy) {
                Text(stringResource(R.string.model_delete), color = colors.error)
            } }, dismissButton = { TextButton({ removeId = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}
