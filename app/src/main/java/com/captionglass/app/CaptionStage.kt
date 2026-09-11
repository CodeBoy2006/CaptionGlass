package com.captionglass.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.captionglass.engine.CaptionPage
import kotlin.math.PI
import kotlin.math.sin

private val Translation = Color(CaptionPalette.TRANSLATION)
private val Source = Color(CaptionPalette.SOURCE)
private val Muted = Color(CaptionPalette.MUTED)
private val Accent = Color(CaptionPalette.ACCENT)
private val Warning = Color(CaptionPalette.WARNING)
private val TranslationStyle = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
private val SourceStyle = TextStyle(fontSize = 16.sp, lineHeight = 23.sp)

/** In-app mirror of the floating caption: status on top, translation above source. */
@Composable
internal fun CaptionStage(capture: CaptureState, chineseSource: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = LocalStage.current,
        border = if (isSystemInDarkTheme()) BorderStroke(1.dp, Translation.copy(alpha = 0.07f)) else null) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 24.dp)) {
            Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(capture)
                Spacer(Modifier.weight(1f))
                capture.page?.takeIf { it.total > 1 }?.let { PageDots(it.index, it.total) }
            }
            val live = capture.active && (capture.stable.isNotBlank() || capture.provisional.isNotBlank())
            val mode = when { capture.page != null -> 2; live -> 1; else -> 0 }
            Box(Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(top = 14.dp), contentAlignment = Alignment.CenterStart) {
                Crossfade(mode, label = "stage") { shown ->
                    when (shown) {
                        2 -> capture.page?.let { PageText(it) }
                        1 -> LiveText(capture.stable, capture.provisional)
                        else -> GhostLanes(chineseSource, capture.active && capture.status == CaptureStatus.SILENT)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(capture: CaptureState) {
    val status = if (capture.active) capture.status else CaptureStatus.IDLE
    val color = when {
        capture.stopping || status == CaptureStatus.IDLE -> Muted
        status == CaptureStatus.HEARING -> Accent
        status == CaptureStatus.SILENT -> Warning
        else -> Source
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            capture.stopping || status == CaptureStatus.PREPARING ->
                CircularProgressIndicator(Modifier.size(14.dp), color = color, strokeWidth = 2.dp, trackColor = Color.Transparent)
            status == CaptureStatus.HEARING -> Equalizer(color)
            else -> Icon(painterResource(status.icon), null, Modifier.size(16.dp), tint = color)
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (capture.stopping) R.string.state_stopping else status.label),
            style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** Four bars that breathe while capturable sound is arriving. */
@Composable
private fun Equalizer(color: Color) {
    val phase by rememberInfiniteTransition(label = "equalizer")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "phase")
    Canvas(Modifier.size(16.dp)) {
        val bar = size.width / 7
        repeat(4) { i ->
            val wave = (sin((phase + i * 0.27f) * 2 * PI).toFloat() + 1f) / 2f
            val height = size.height * (0.3f + 0.7f * wave)
            drawRoundRect(color, Offset(i * 2 * bar, (size.height - height) / 2), Size(bar, height), CornerRadius(bar / 2))
        }
    }
}

@Composable
private fun PageDots(index: Int, total: Int) {
    if (total > 6) {
        Text("${index + 1}/$total", style = MaterialTheme.typography.labelMedium, color = Muted)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(total) { i ->
            val width by animateDpAsState(if (i == index) 14.dp else 6.dp, label = "dot")
            Box(Modifier.size(width, 6.dp).background(if (i == index) Translation else Translation.copy(alpha = 0.3f), CircleShape))
        }
    }
}

@Composable
private fun PageText(page: CaptionPage) {
    Column {
        val reason = page.caption.untranslatedReason
        page.translation?.let { Text(it, style = TranslationStyle, color = Translation) } ?: reason?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(it.icon), null, Modifier.size(20.dp), tint = Warning)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(it.label), style = TranslationStyle.copy(fontSize = 18.sp), color = Warning)
            }
        }
        if (page.source.isNotEmpty()) Text(page.source, Modifier.padding(top = 6.dp), style = SourceStyle, color = Source)
    }
}

@Composable
private fun LiveText(stable: String, provisional: String) {
    val pulse by rememberInfiniteTransition(label = "pending")
        .animateFloat(0.25f, 0.8f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
    Column {
        Text(stringResource(R.string.overlay_pending), Modifier.graphicsLayer { alpha = pulse },
            style = TranslationStyle, color = Translation)
        TailText(buildAnnotatedString {
            withStyle(SpanStyle(color = Source)) { append(stable) }
            withStyle(SpanStyle(color = Muted)) { append(provisional) }
        }, SourceStyle, lines = 2, Modifier.padding(top = 6.dp))
    }
}

/** Idle lanes preview the layout in the chosen languages without inventing caption text. */
@Composable
private fun GhostLanes(chineseSource: Boolean, silent: Boolean) {
    Column {
        Crossfade(chineseSource, label = "lanes") { chinese ->
            Column {
                Text(stringResource(if (chinese) R.string.language_en else R.string.language_zh),
                    style = TranslationStyle, color = Translation.copy(alpha = 0.34f))
                Text(stringResource(if (chinese) R.string.language_zh else R.string.language_en),
                    style = SourceStyle, color = Translation.copy(alpha = 0.26f))
            }
        }
        if (silent) Text(stringResource(R.string.hint_silent), Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall, color = Warning)
    }
}

/** Keeps the newest recognized words visible instead of clipping them off the end. */
@Composable
private fun TailText(text: AnnotatedString, style: TextStyle, lines: Int, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val shown = remember(text, width, style, measurer) {
            val layout = measurer.measure(text, style, constraints = Constraints(maxWidth = width))
            if (layout.lineCount <= lines) text else text.subSequence(layout.getLineStart(layout.lineCount - lines), text.length)
        }
        Text(shown, style = style)
    }
}
