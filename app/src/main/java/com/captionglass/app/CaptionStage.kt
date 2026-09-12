package com.captionglass.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.captionglass.engine.LanguagePair
import kotlin.math.PI
import kotlin.math.sin

private val Translation = Color(CaptionPalette.TRANSLATION)
private val Source = Color(CaptionPalette.SOURCE)
private val Muted = Color(CaptionPalette.MUTED)
private val Accent = Color(CaptionPalette.ACCENT)
private val Warning = Color(CaptionPalette.WARNING)

/** Uses the same transcript and display choice as the floating window. */
@Composable
internal fun CaptionStage(capture: CaptureState, languages: LanguagePair, display: CaptionDisplay, modifier: Modifier = Modifier) {
    val stage = LocalStage.current
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = stage,
        border = if (isSystemInDarkTheme()) BorderStroke(1.dp, Translation.copy(alpha = 0.07f)) else null) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            StatusBadge(capture)
            Spacer(Modifier.height(14.dp))
            if (capture.lines.isNotEmpty() || capture.stable.isNotBlank() || capture.provisional.isNotBlank()) {
                AndroidView(factory = { CaptionTranscriptView(it) }, modifier = Modifier.fillMaxWidth().height(240.dp),
                    update = {
                        it.edgeColor = stage.toArgb()
                        it.display = display
                        it.render(capture)
                    })
            } else Column(Modifier.fillMaxWidth().height(160.dp), verticalArrangement = Arrangement.Center) {
                Text(languages.target.label, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
                    color = Translation.copy(alpha = 0.34f))
                Text(languages.source.label, Modifier.padding(top = 6.dp), style = TextStyle(fontSize = 16.sp),
                    color = Translation.copy(alpha = 0.26f))
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
