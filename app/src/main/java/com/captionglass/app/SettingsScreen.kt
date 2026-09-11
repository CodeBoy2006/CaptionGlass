package com.captionglass.app

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(pack: PackState, catalog: ModelCatalog, selection: ModelSelection, installed: Set<String>,
                            busy: Boolean, overlayAllowed: Boolean, onImport: (ModelSpec) -> Unit, onSelect: (ModelSelection) -> Unit, onOverlaySettings: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val version = remember {
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()?.versionName.orEmpty()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Box(Modifier.height(64.dp), contentAlignment = Alignment.CenterStart) {
            Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.titleLarge)
        }

        Section(R.string.settings_pack) {
            catalog.models.forEachIndexed { index, model ->
                val ready = model.id in installed
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(if (model.kind == "asr") R.drawable.ic_hearing else R.drawable.ic_subtitles)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(model.name, style = MaterialTheme.typography.titleMedium)
                        Text(modelSize(model.size), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    StateTag(ready, if (ready) R.string.pack_ready else R.string.pack_missing)
                }
                Text(if (model.kind == "asr") model.languages.joinToString(" · ") { it.label }
                    else stringResource(R.string.translation_languages, model.languages.size),
                    Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (pack.importing && pack.modelId == model.id) {
                    LinearProgressIndicator({ pack.progress }, Modifier.fillMaxWidth().padding(top = 16.dp), strokeCap = StrokeCap.Round)
                    Text(stringResource(R.string.state_importing, (pack.progress * 100).toInt()), Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelLarge)
                } else {
                    if (pack.failed && pack.modelId == model.id) Text(pack.detail ?: stringResource(R.string.pack_import_failed),
                        Modifier.padding(top = 10.dp), color = colors.error, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton({ onImport(model) }, enabled = !busy) {
                            Icon(painterResource(R.drawable.ic_folder_open), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (ready) R.string.action_replace else R.string.action_import))
                        }
                        if (model.kind == "asr") TextButton({ onSelect(selection.copy(recognizerId = model.id)) },
                            enabled = !busy && selection.languages.source in model.languages && selection.recognizerId != model.id) {
                            Text(stringResource(if (selection.recognizerId == model.id) R.string.model_selected else R.string.model_use))
                        }
                    }
                }
            }
            Text(stringResource(R.string.shared_translator), Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }

        Section(R.string.settings_overlay) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onOverlaySettings).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconTile(R.drawable.ic_picture_in_picture)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.overlay_permission), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                StateTag(overlayAllowed, if (overlayAllowed) R.string.overlay_allowed else R.string.overlay_denied)
                Icon(painterResource(R.drawable.ic_chevron_right), null, tint = colors.onSurfaceVariant)
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OverlaySample(blocking = false, Modifier.weight(1f))
                OverlaySample(blocking = true, Modifier.weight(1f))
            }
            Text(stringResource(R.string.overlay_gesture), Modifier.fillMaxWidth().padding(top = 12.dp),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }

        Section(R.string.settings_privacy) {
            Row(Modifier.fillMaxWidth()) {
                PrivacyItem(R.drawable.ic_cloud_off, R.string.privacy_network, Modifier.weight(1f))
                PrivacyItem(R.drawable.ic_mic_off, R.string.privacy_microphone, Modifier.weight(1f))
                PrivacyItem(R.drawable.ic_folder_off, R.string.privacy_audio, Modifier.weight(1f))
                PrivacyItem(R.drawable.ic_hide_image, R.string.privacy_screen, Modifier.weight(1f))
            }
        }

        Text(stringResource(R.string.version, version), Modifier.fillMaxWidth().padding(vertical = 24.dp),
            textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun Section(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Text(stringResource(title), Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(16.dp), content = content)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun IconTile(icon: Int) {
    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun StateTag(ok: Boolean, text: Int) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (ok) Icon(painterResource(R.drawable.ic_check_circle), null, Modifier.size(16.dp), tint = color)
        else Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** A miniature of the overlay in each touch mode, so the handle gestures need no paragraph. */
@Composable
private fun OverlaySample(blocking: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val accent = Color(CaptionPalette.ACCENT)
    val stage = Color(CaptionPalette.STAGE)
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.background(colors.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(34.dp, 14.dp).background(stage, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(16.dp, 3.dp).background(if (blocking) accent else Color(CaptionPalette.MUTED), CircleShape))
        }
        Spacer(Modifier.height(4.dp))
        Column(Modifier.fillMaxWidth().then(if (blocking) Modifier.border(1.5.dp, accent, shape) else Modifier)
            .background(stage.copy(alpha = if (blocking) 1f else 0.78f), shape).padding(10.dp)) {
            Box(Modifier.fillMaxWidth(0.8f).height(6.dp).background(Color(CaptionPalette.TRANSLATION), CircleShape))
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth(0.55f).height(4.dp).background(Color(CaptionPalette.SOURCE).copy(alpha = 0.7f), CircleShape))
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(if (blocking) R.drawable.ic_back_hand else R.drawable.ic_touch_app), null, Modifier.size(16.dp),
                tint = if (blocking) colors.primary else colors.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(if (blocking) R.string.overlay_blocking else R.string.overlay_pass_through),
                style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PrivacyItem(icon: Int, label: Int, modifier: Modifier) {
    Column(modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(label), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}
