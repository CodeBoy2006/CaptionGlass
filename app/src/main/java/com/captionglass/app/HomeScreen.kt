package com.captionglass.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeScreen(
    capture: CaptureState, pack: PackState, missing: ModelSpec?, overlayAllowed: Boolean,
    selection: ModelSelection, authorizing: Boolean, catalog: ModelCatalog, installed: Set<String>,
    onSelect: (ModelSelection) -> Unit, onStart: () -> Unit, onStop: () -> Unit, onManageModels: () -> Unit,
    onOpenRecords: () -> Unit, onOverlaySettings: () -> Unit,
) {
    val busy = capture.active || authorizing || pack.busy
    val direction = @Composable { SelectionControls(selection, catalog, installed, enabled = !busy, onSelect, onManageModels) }
    val signals = @Composable { Signals(capture, pack, overlayAllowed, onOpenRecords, onOverlaySettings) }
    val control = @Composable { PrimaryControl(capture, pack, missing, authorizing, onStart, onStop, onManageModels) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        if (maxWidth > maxHeight && maxWidth >= 600.dp) {
            // Wide: the stage reads on the left; everything you operate sits together on the right.
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    Header(); CaptionStage(capture, selection.languages); signals()
                }
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).heightIn(min = viewport).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally) { direction(); control() }
            }
        } else {
            // Tall: the direction switch sits right under the stage, whose idle lanes preview the chosen languages.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Header(); CaptionStage(capture, selection.languages)
                    Spacer(Modifier.height(12.dp)); direction(); signals()
                }
                Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) { control() }
            }
        }
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(colorResource(R.color.launcher_background)),
            contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(46.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_cloud_off), null, Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.offline), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Signals(capture: CaptureState, pack: PackState, overlayAllowed: Boolean,
                    onOpenRecords: () -> Unit, onOverlaySettings: () -> Unit) {
    var dismissed by remember(capture.status, capture.active) { mutableStateOf(false) }
    val chips = !overlayAllowed || capture.readingBehind > 0 || capture.translationBacklog > 0
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedVisibility(!capture.active && capture.status.isProblem && !dismissed) {
            Notice(capture.status.icon, stringResource(capture.status.label), capture.status.hint?.let { stringResource(it) }) { dismissed = true }
        }
        if (pack.failed) Notice(R.drawable.ic_package, pack.detail ?: stringResource(R.string.pack_import_failed), null, ModelPack::dismiss)
        if (chips) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!overlayAllowed) SignalChip(R.drawable.ic_picture_in_picture, stringResource(R.string.signal_overlay_off),
                MaterialTheme.colorScheme.tertiary, onOverlaySettings)
            if (capture.readingBehind > 0) SignalChip(R.drawable.ic_fast_forward,
                stringResource(R.string.signal_reading_behind, capture.readingBehind), MaterialTheme.colorScheme.primary, onOpenRecords)
            if (capture.translationBacklog > 0) SignalChip(R.drawable.ic_speed,
                stringResource(R.string.signal_backlog, capture.translationBacklog), MaterialTheme.colorScheme.tertiary, onOpenRecords)
        }
    }
}

@Composable
internal fun Notice(icon: Int, title: String, hint: String?, onDismiss: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = if (onDismiss == null) 16.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(colors.errorContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(20.dp), tint = colors.onErrorContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
            }
            if (onDismiss != null) IconButton(onDismiss) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_dismiss), Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun SignalChip(icon: Int, text: String, tint: Color, onClick: () -> Unit) {
    Surface(onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.heightIn(min = 36.dp).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private enum class Control { MODELS, MANAGING, START, AUTHORIZING, PREPARING, RUNNING, STOPPING }

/** One round button always performs the next sensible step: model preparation, start or stop. */
@Composable
private fun PrimaryControl(capture: CaptureState, pack: PackState, missing: ModelSpec?, authorizing: Boolean,
                           onStart: () -> Unit, onStop: () -> Unit, onManageModels: () -> Unit) {
    val control = when {
        capture.stopping -> Control.STOPPING
        capture.active && capture.status == CaptureStatus.PREPARING -> Control.PREPARING
        capture.active -> Control.RUNNING
        pack.busy -> Control.MANAGING
        authorizing -> Control.AUTHORIZING
        missing != null -> Control.MODELS
        else -> Control.START
    }
    val colors = MaterialTheme.colorScheme
    val stops = control == Control.PREPARING || control == Control.RUNNING || control == Control.STOPPING
    val enabled = control == Control.MODELS || control == Control.START || control == Control.PREPARING || control == Control.RUNNING
    val container by animateColorAsState(if (stops) colors.inverseSurface else colors.primary, label = "container")
    val content = (if (stops) colors.inverseOnSurface else colors.onPrimary).copy(alpha = if (enabled) 1f else 0.6f)
    val action = stringResource(when (control) {
        Control.MODELS, Control.MANAGING -> if (missing?.kind == "asr") R.string.action_import_asr else R.string.action_import_mt
        Control.START, Control.AUTHORIZING -> R.string.action_start
        else -> R.string.action_stop_captions
    })
    val label = when (control) {
        Control.MODELS -> action
        Control.MANAGING -> stringResource(pack.phase.label, (pack.progress * 100).toInt())
        Control.START -> action
        Control.AUTHORIZING -> stringResource(R.string.state_authorizing)
        Control.PREPARING -> stringResource(R.string.status_preparing)
        Control.RUNNING -> sessionTime(capture.audioMs)
        Control.STOPPING -> stringResource(R.string.state_stopping)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            if (control == Control.RUNNING && capture.status == CaptureStatus.HEARING) Halo(colors.primary)
            when (control) {
                Control.MANAGING -> CircularProgressIndicator({ pack.progress }, Modifier.size(108.dp), strokeWidth = 4.dp,
                    trackColor = colors.surfaceContainerHighest, strokeCap = StrokeCap.Round)
                Control.AUTHORIZING, Control.PREPARING, Control.STOPPING ->
                    CircularProgressIndicator(Modifier.size(108.dp), strokeWidth = 4.dp, strokeCap = StrokeCap.Round)
                else -> Unit
            }
            Surface({ when (control) { Control.MODELS -> onManageModels(); Control.START -> onStart(); else -> onStop() } },
                Modifier.size(88.dp).semantics { contentDescription = action }, enabled = enabled,
                shape = CircleShape, color = container, contentColor = content) {
                Box(contentAlignment = Alignment.Center) {
                    Crossfade(when (control) {
                        Control.MODELS, Control.MANAGING -> R.drawable.ic_folder_open
                        Control.START, Control.AUTHORIZING -> R.drawable.ic_power
                        else -> R.drawable.ic_stop
                    }, label = "icon") { Icon(painterResource(it), null, Modifier.size(36.dp)) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
        if (control == Control.MANAGING) TextButton(onManageModels) { Text(stringResource(R.string.settings_pack)) }
        Text(if (control == Control.MODELS && missing != null) "${missing.name} · ${modelSize(missing.size)}" else "",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** A soft ripple around the stop button while sound is being heard. */
@Composable
private fun Halo(color: Color) {
    val progress by rememberInfiniteTransition(label = "halo")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearOutSlowInEasing)), label = "spread")
    Box(Modifier.size(88.dp).graphicsLayer {
        scaleX = 1f + 0.34f * progress; scaleY = scaleX; alpha = (1f - progress) * 0.45f
    }.background(color, CircleShape))
}
