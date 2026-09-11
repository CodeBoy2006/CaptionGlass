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

/** One row per family; individual variants own their installation and selection. */
@Composable
internal fun ModelManager(pack: PackState, catalog: ModelCatalog, selection: ModelSelection,
                          localModels: Map<String, InstalledModel>, availableBytes: Long?, busy: Boolean,
                          onImport: (ModelSpec) -> Unit, onSelect: (ModelSelection) -> Unit,
                          onDownload: (ModelSpec) -> Unit, onCheck: (ModelSpec) -> Unit, onRemove: (ModelSpec) -> Unit,
                          family: String?, onOpenFamily: (String) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    val required = catalog.required(selection)
    val currentIds = required.map { it.id }.toSet()
    val visible = family?.let { catalog.families.getValue(it) }

    if (family == null) Text(stringResource(R.string.model_storage,
        modelSize(localModels.values.sumOf { it.bytes }), availableBytes?.let(::modelSize) ?: "—"),
        Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    if (busy && !pack.busy) Text(stringResource(R.string.model_session_locked), Modifier.padding(bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    if (pack.busy) {
        val working = catalog.models.find { it.id == pack.modelId }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(working?.name ?: stringResource(R.string.state_verifying, 0), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(pack.phase.label, (pack.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            if (pack.phase !in listOf(PackPhase.REMOVING, PackPhase.CANCELLING)) TextButton(ModelPack::cancel) {
                Text(stringResource(R.string.action_cancel))
            }
        }
        if (pack.phase == PackPhase.DOWNLOADING) Text(stringResource(R.string.model_keep_open),
            Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (pack.phase in listOf(PackPhase.REMOVING, PackPhase.CANCELLING) || pack.modelId == null)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
        else LinearProgressIndicator({ pack.progress }, Modifier.fillMaxWidth().padding(bottom = 12.dp))
    }
    if (!pack.busy && pack.detail != null) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(pack.detail, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = if (pack.failed) colors.error else colors.primary)
        IconButton(ModelPack::dismiss) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close)) }
    }

    if (visible == null) {
        listOf("asr", "mt").forEach { kind ->
            Text(stringResource(if (kind == "asr") R.string.models_asr else R.string.models_mt),
                Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = colors.primary)
            catalog.families.filterValues { it.first().kind == kind }.entries
                .sortedByDescending { (_, models) -> models.any { it.id in currentIds } }.forEach { (name, models) ->
                    val installed = models.count { localModels.getValue(it.id).ready }
                    val selected = models.any { it.id in currentIds }
                    Surface(onClick = { onOpenFamily(name) }, color = colors.surface.copy(alpha = 0f)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.model_family_summary, models.size, installed),
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            if (selected) Text(stringResource(R.string.model_current), Modifier.padding(horizontal = 8.dp),
                                style = MaterialTheme.typography.labelMedium, color = colors.primary)
                            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = colors.onSurfaceVariant)
                        }
                    }
                }
        }
    } else visible.forEachIndexed { index, model ->
        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp))
        val local = localModels.getValue(model.id)
        val selected = model.id in currentIds
        val compatible = model.supports(selection.languages)
        val working = pack.busy && pack.modelId == model.id
        var menu by remember(model.id) { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.variant, style = MaterialTheme.typography.titleMedium)
                val mode = stringResource(if (model.kind == "mt") R.string.models_mt else if (model.segmented) R.string.model_segmented else R.string.model_streaming)
                Text(if (model.installable) "$mode · ${model.languages.size} ${stringResource(R.string.model_languages)} · ${modelSize(model.size)}"
                    else model.unavailableReason.orEmpty(), Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (model.adapter == "japanese-zipformer") Text(stringResource(R.string.model_zipformer_quality),
                    style = MaterialTheme.typography.bodySmall, color = colors.tertiary)
            }
            Box {
                IconButton({ menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.model_actions, model.name)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.model_details)) }, onClick = { detailsId = model.id; menu = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.model_download)) }, enabled = !busy && model.installable,
                        onClick = { onDownload(model); menu = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_import)) }, enabled = !busy && model.installable,
                        onClick = { onImport(model); menu = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.model_check)) }, enabled = !busy && local.bytes > 0 && model.installable,
                        onClick = { onCheck(model); menu = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.model_delete)) }, enabled = !busy && local.bytes > 0,
                        onClick = { removeId = model.id; menu = false })
                }
            }
        }
        if (model.installable) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(when {
                        selected && local.ready -> R.string.model_selected_ready
                        selected -> R.string.model_current
                        local.ready -> R.string.pack_ready
                        local.bytes > 0 -> R.string.pack_needs_check
                        else -> R.string.pack_missing
                    }), style = MaterialTheme.typography.labelMedium, color = if (local.ready || selected) colors.primary else colors.onSurfaceVariant)
                    if (!compatible) Text(stringResource(R.string.model_pair_mismatch), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
                when {
                    working -> Text(stringResource(pack.phase.label, (pack.progress * 100).toInt()), style = MaterialTheme.typography.labelMedium)
                    !local.ready && local.bytes > 0 -> TextButton({ onCheck(model) }, enabled = !busy) { Text(stringResource(R.string.model_check)) }
                    !local.ready -> FilledTonalButton({ onDownload(model) }, enabled = !busy) { Text(stringResource(R.string.model_download)) }
                    !selected -> FilledTonalButton({ onSelect(if (model.kind == "asr") selection.copy(recognizerId = model.id)
                        else selection.copy(translatorId = model.id)) }, enabled = !busy && compatible) { Text(stringResource(R.string.model_use)) }
                }
            }
        }
    }

    catalog.models.find { it.id == detailsId }?.let { model ->
        var linkFailed by remember(model.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { detailsId = null }, title = { Text(model.name) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (model.kind == "mt") Text(model.sourceLanguages.joinToString(" · ") { it.chineseName } + " →\n" +
                    model.languages.joinToString(" · ") { it.chineseName })
                else Text(model.languages.joinToString(" · ") { it.chineseName })
                if (model.adapter == "japanese-zipformer") Text(stringResource(R.string.model_zipformer_quality), style = MaterialTheme.typography.bodySmall, color = colors.tertiary)
                Text(model.unavailableReason ?: stringResource(R.string.experimental_languages), style = MaterialTheme.typography.bodySmall)
                if (model.installable) {
                    Text(stringResource(R.string.model_import_hint, modelSize(model.size)), style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        model.files.forEach { Text("${it.name} · ${modelSize(it.size)}", style = MaterialTheme.typography.bodySmall) }
                    } }
                }
                Text(stringResource(R.string.model_license_revision, model.license, model.revision.take(12).ifEmpty { "—" }),
                    style = MaterialTheme.typography.bodySmall)
                if (model.communityConversion) Text(stringResource(R.string.model_community), style = MaterialTheme.typography.bodySmall)
                TextButton({ linkFailed = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, model.source.toUri())) }.isFailure }) {
                    Text(stringResource(R.string.model_source))
                }
                if (linkFailed) Text(stringResource(R.string.model_source_failed), color = colors.error)
            }
        }, confirmButton = { TextButton({ detailsId = null }) { Text(stringResource(R.string.action_close)) } },
            dismissButton = { if (model.installable) TextButton({ detailsId = null; onImport(model) }, enabled = !busy) {
                Text(stringResource(R.string.model_choose_folder))
            } })
    }
    catalog.models.find { it.id == removeId }?.let { model ->
        AlertDialog(onDismissRequest = { removeId = null }, title = { Text(stringResource(R.string.model_delete_title, model.name)) },
            text = { Text(stringResource(if (model in required) R.string.model_delete_required else R.string.model_delete_other,
                modelSize(localModels.getValue(model.id).bytes))) },
            confirmButton = { TextButton({ removeId = null; onRemove(model) }, enabled = !busy) { Text(stringResource(R.string.model_delete), color = colors.error) } },
            dismissButton = { TextButton({ removeId = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}
