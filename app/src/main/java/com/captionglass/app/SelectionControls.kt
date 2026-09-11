package com.captionglass.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.captionglass.engine.LanguagePair
import java.util.Locale

internal fun modelSize(bytes: Long) = if (bytes >= 1_000_000_000)
    String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0) else "${(bytes + 999_999) / 1_000_000} MB"

/** Source capability and translation capability are different lists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionControls(selection: ModelSelection, catalog: ModelCatalog, installed: Set<String>, enabled: Boolean,
                               onSelect: (ModelSelection) -> Unit) {
    var chooser by remember { mutableIntStateOf(0) }
    var search by remember(chooser) { mutableStateOf("") }
    val pair = selection.languages
    val recognizer = catalog.recognizer(selection)
    LaunchedEffect(enabled) { if (!enabled) chooser = 0 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LanguagePill(pair.source.label, R.string.source_language, enabled, { chooser = 1 }, Modifier.weight(1f))
            FilledTonalIconButton({ onSelect(catalog.withLanguages(selection, pair.swapped())) },
                Modifier.padding(horizontal = 8.dp).size(48.dp), enabled = enabled && pair.target in catalog.sources) {
                Icon(painterResource(R.drawable.ic_swap), stringResource(R.string.swap_direction))
            }
            LanguagePill(pair.target.label, R.string.target_language, enabled, { chooser = 2 }, Modifier.weight(1f))
        }
        Surface({ chooser = 3 }, enabled = enabled, shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_package), null, Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(recognizer.name, style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(if (catalog.required(selection).all { it.id in installed }) R.string.models_ready else R.string.models_needed),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.choose_model))
            }
        }
        if (pair.source != com.captionglass.engine.Language.EN && pair.source != com.captionglass.engine.Language.ZH ||
            pair.target != com.captionglass.engine.Language.EN && pair.target != com.captionglass.engine.Language.ZH ||
            recognizer.id != ModelSelection().recognizerId)
            Text(stringResource(R.string.experimental_languages), Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (chooser != 0 && enabled) ModalBottomSheet(onDismissRequest = { chooser = 0 },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val title = when (chooser) { 1 -> R.string.source_language; 2 -> R.string.target_language; else -> R.string.choose_model }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding()) {
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(when (chooser) { 1 -> R.string.source_hint; 2 -> R.string.target_hint; else -> R.string.model_hint }),
                Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (chooser < 3) {
                val languages = if (chooser == 1) catalog.sources else catalog.translator.languages.filter { it != pair.source }
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.search_language)) })
                val matches = languages.filter {
                    search.isBlank() || it.label.contains(search.trim(), true) || it.promptName.contains(search.trim(), true) || it.chineseName.contains(search.trim()) || it.code.equals(search.trim(), true)
                }
                LazyColumn(Modifier.heightIn(max = 400.dp).padding(vertical = 8.dp)) {
                    if (matches.isEmpty()) item { Text(stringResource(R.string.no_language_match), Modifier.padding(16.dp)) }
                    items(matches, key = { it.code }) { language ->
                        val selected = language == if (chooser == 1) pair.source else pair.target
                        ListItem(headlineContent = { Text(language.label) }, supportingContent = { Text("${language.chineseName} · ${language.promptName}") },
                            trailingContent = { RadioButton(selected, null) },
                            modifier = Modifier.selectable(selected, role = Role.RadioButton) {
                                val next = if (chooser == 1) pair.withSource(language) else LanguagePair(pair.source, language)
                                onSelect(catalog.withLanguages(selection, next)); chooser = 0
                            })
                    }
                }
            } else LazyColumn(Modifier.heightIn(max = 400.dp).padding(bottom = 16.dp)) {
                items(catalog.recognizers, key = { it.id }) { model ->
                    val compatible = pair.source in model.languages
                    val selected = model.id == selection.recognizerId
                    ListItem(headlineContent = { Text(model.name) }, supportingContent = {
                        Column {
                            Text(model.languages.joinToString(" · ") { it.label })
                            Text("${modelSize(model.size)} · " + stringResource(if (compatible) {
                                if (model.id in installed) R.string.pack_ready else R.string.pack_missing
                            } else R.string.model_incompatible))
                        }
                    }, trailingContent = { RadioButton(selected, null, enabled = compatible) },
                        modifier = Modifier.selectable(selected, enabled = compatible, role = Role.RadioButton) {
                            onSelect(selection.copy(recognizerId = model.id)); chooser = 0
                        })
                }
                item { Text(stringResource(R.string.shared_translator), Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun LanguagePill(language: String, label: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick, modifier.heightIn(min = 64.dp), enabled = enabled, shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(language, style = MaterialTheme.typography.titleMedium)
        }
    }
}
