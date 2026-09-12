package com.captionglass.app

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.captionglass.nativebridge.LocalTranslator
import com.captionglass.nativebridge.TranslationBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Plate = Color(CaptionPalette.PLATE_TOP)
private val Translation = Color(CaptionPalette.TRANSLATION)
private val Source = Color(CaptionPalette.SOURCE)
private val Accent = Color(CaptionPalette.ACCENT)

@Composable
internal fun SettingsScreen(pack: PackState, catalog: ModelCatalog, selection: ModelSelection, localModels: Map<String, InstalledModel>, availableBytes: Long?,
                            busy: Boolean, overlayAllowed: Boolean, onImport: (ModelSpec) -> Unit, onSelect: (ModelSelection) -> Unit,
                            onDownload: (ModelSpec) -> Unit, onCheck: (ModelSpec) -> Unit, onRemove: (ModelSpec) -> Unit,
                            onOverlaySettings: () -> Unit, display: CaptionDisplay, style: CaptionStyle,
                            onDisplay: (CaptionDisplay) -> Unit, onStyle: (CaptionStyle) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val hexagonAvailable by produceState<Boolean?>(null) {
        value = withContext(Dispatchers.IO) { LocalTranslator.hexagonAvailable() }
    }
    var family by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(family != null) { family = null }
    if (family != null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ family = null }) { Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.action_back), Modifier.rotate(180f)) }
                Text(checkNotNull(family), style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                ModelManager(pack, catalog, selection, localModels, availableBytes, busy,
                    onImport, onSelect, onDownload, onCheck, onRemove, family) { family = it }
            }
        }
        return
    }
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
            ModelManager(pack, catalog, selection, localModels, availableBytes, busy,
                onImport, onSelect, onDownload, onCheck, onRemove, null) { family = it }
        }

        Section(R.string.settings_translation_backend) {
            Column(Modifier.selectableGroup()) {
                TranslationBackend.entries.forEach { backend ->
                    val enabled = !busy && (backend != TranslationBackend.HEXAGON || hexagonAvailable == true)
                    val selected = selection.backend == backend
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
                        .selectable(selected, enabled = enabled, role = Role.RadioButton,
                            onClick = { onSelect(selection.copy(backend = backend)) }).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, onClick = null, enabled = enabled)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(when (backend) {
                                TranslationBackend.VULKAN -> R.string.backend_vulkan
                                TranslationBackend.OPENCL -> R.string.backend_opencl
                                TranslationBackend.CPU -> R.string.backend_cpu
                                TranslationBackend.HEXAGON -> R.string.backend_hexagon
                            }), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(when (backend) {
                                TranslationBackend.VULKAN -> R.string.backend_vulkan_hint
                                TranslationBackend.OPENCL -> R.string.backend_opencl_hint
                                TranslationBackend.CPU -> R.string.backend_cpu_hint
                                TranslationBackend.HEXAGON -> when (hexagonAvailable) {
                                    null -> R.string.backend_checking
                                    false -> R.string.backend_hexagon_unavailable
                                    true -> R.string.backend_hexagon_hint
                                }
                            }), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
            Hint(if (busy) R.string.backend_locked else R.string.backend_scope)
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

            Label(R.string.display_title)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CaptionDisplay.entries.forEach { mode ->
                    Choice(display == mode, mode.label, { onDisplay(mode) }, Modifier.weight(1f)) { DisplayPreview(mode) }
                }
            }
            Hint(display.hint)

            Label(R.string.style_title)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CaptionStyle.entries.forEach { look ->
                    Choice(style == look, look.label, { onStyle(look) }, Modifier.weight(1f)) { StylePreview(look) }
                }
            }
            Hint(style.hint)

            Label(R.string.overlay_touch)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OverlaySample(blocking = false, display, Modifier.weight(1f))
                OverlaySample(blocking = true, display, Modifier.weight(1f))
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
private fun Label(text: Int) {
    Text(stringResource(text), Modifier.padding(start = 2.dp, top = 20.dp, bottom = 10.dp),
        style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun Hint(text: Int) {
    Text(stringResource(text), Modifier.fillMaxWidth().padding(start = 2.dp, top = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** A selectable miniature; the preview carries the meaning so the label can stay one word. */
@Composable
private fun Choice(selected: Boolean, label: Int, onClick: () -> Unit, modifier: Modifier, preview: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val outline by animateColorAsState(if (selected) colors.primary else Color.Transparent, label = "choice")
    Column(modifier.clip(shape).background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
        .border(1.5.dp, outline, shape).selectable(selected, onClick = onClick, role = Role.RadioButton)
        .padding(horizontal = 10.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        preview()
        Row(Modifier.padding(top = 10.dp).heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(painterResource(R.drawable.ic_check_circle), null, Modifier.size(14.dp), tint = colors.primary)
                Spacer(Modifier.width(4.dp))
            }
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface)
        }
    }
}

@Composable
private fun Bar(fraction: Float, height: Dp, color: Color, outlined: Boolean = false) {
    Box(Modifier.fillMaxWidth(fraction).height(height)
        .then(if (outlined) Modifier.border(1.dp, Color.Black.copy(alpha = 0.7f), CircleShape) else Modifier)
        .background(color, CircleShape))
}

@Composable
private fun DisplayPreview(mode: CaptionDisplay) {
    val groups = when (mode) { CaptionDisplay.SCROLL -> 3; CaptionDisplay.TWO -> 2; CaptionDisplay.ONE -> 1 }
    Box(Modifier.fillMaxWidth().height(66.dp).background(Plate, RoundedCornerShape(10.dp))
        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 7.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom)) {
            repeat(groups) { i ->
                // In the scrolling display the oldest group is history scrolling away under the top edge.
                val history = mode == CaptionDisplay.SCROLL && i == 0
                Bar(if (i % 2 == 0) 0.84f else 0.7f, 5.dp, Translation.copy(alpha = if (history) 0.3f else 0.95f))
                Bar(0.56f, 3.dp, Source.copy(alpha = if (history) 0.22f else 0.6f))
            }
            if (mode != CaptionDisplay.SCROLL) Bar(0.42f, 3.dp, Color(CaptionPalette.PROVISIONAL).copy(alpha = 0.55f))
        }
        if (mode == CaptionDisplay.SCROLL) Box(Modifier.align(Alignment.CenterEnd).offset(x = 4.dp).width(2.dp).height(24.dp)
            .background(Color.White.copy(alpha = 0.3f), CircleShape))
    }
}

@Composable
private fun StylePreview(style: CaptionStyle) {
    val light = Color(0xFFF1F4F2)
    val dark = Color(0xFF0B1216)
    val plate = style == CaptionStyle.PLATE
    Box(Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(10.dp))
        .background(Brush.horizontalGradient(0f to light, 0.5f to light, 0.5f to dark, 1f to dark)),
        contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(0.84f)
            .then(if (plate) Modifier.background(Plate, RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                else Modifier)
            .padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Bar(0.9f, 5.dp, Translation, outlined = !plate)
            Bar(0.6f, 3.dp, Source, outlined = !plate)
        }
    }
}

/** A miniature of the overlay in each touch mode, so the handle gestures need no paragraph. */
@Composable
private fun OverlaySample(blocking: Boolean, display: CaptionDisplay, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.background(colors.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.padding(top = 6.dp).fillMaxWidth()
                .border(if (blocking) 1.5.dp else 1.dp, if (blocking) Accent else Color.White.copy(alpha = 0.12f), shape)
                .background(Plate.copy(alpha = if (blocking) 1f else 0.8f), shape).padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Bar(0.8f, 6.dp, Translation)
                Bar(0.55f, 4.dp, Source.copy(alpha = 0.7f))
            }
            // The handle rides the card's top edge, as on screen.
            Box(Modifier.size(30.dp, 12.dp).background(Plate, CircleShape).border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(14.dp, 2.5.dp).background(if (blocking) Accent else Color.White.copy(alpha = 0.55f), CircleShape))
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(if (blocking) R.drawable.ic_back_hand else R.drawable.ic_touch_app), null, Modifier.size(16.dp),
                tint = if (blocking) colors.primary else colors.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(when {
                !blocking -> R.string.overlay_pass_through
                display.scrolls -> R.string.overlay_scrollable
                else -> R.string.overlay_blocking
            }), style = MaterialTheme.typography.labelLarge)
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
