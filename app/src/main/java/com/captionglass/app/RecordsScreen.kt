package com.captionglass.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.captionglass.engine.CaptionLine

/** Newest caption stays anchored at the bottom; scrolling up to reread is not disturbed by new arrivals. */
@Composable
internal fun RecordsScreen(capture: CaptureState, listState: LazyListState, exporting: Boolean, onExport: (RecordFormat) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var exportMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tab_records), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { exportMenu = true }, enabled = capture.lines.isNotEmpty() && !exporting) {
                    Text(stringResource(if (exporting) R.string.records_export_busy else R.string.records_export))
                }
                DropdownMenu(exportMenu, onDismissRequest = { exportMenu = false }) {
                    RecordFormat.entries.forEach { format ->
                        DropdownMenuItem(text = { Text(format.name) }, onClick = { exportMenu = false; onExport(format) })
                    }
                }
            }
        }
        if (capture.lines.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (capture.confirmed > 0) Text(stringResource(R.string.records_segments, capture.confirmed),
                style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            val pending = capture.lines.count { it.outcome == null }
            if (pending > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.records_pending, pending), style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant)
                }
            }
        }
        if (capture.lines.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(bottom = 64.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).background(colors.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_article), null, Modifier.size(32.dp), tint = colors.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.records_empty), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            return@Column
        }
        Text(stringResource(R.string.records_export_scope), Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        val newestFirst = remember(capture.lines) { capture.lines.asReversed() }
        LazyColumn(Modifier.fillMaxSize(), listState, PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            reverseLayout = true, verticalArrangement = Arrangement.Top) {
            items(newestFirst, key = { "${it.segment.key.sessionId}.${it.segment.key.sequence}.${it.segment.key.revision}" }) { RecordRow(it) }
            if (capture.lines.size >= 200) item {
                Text(stringResource(R.string.records_limit), Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RecordRow(caption: CaptionLine) {
    val colors = MaterialTheme.colorScheme
    val reason = caption.outcome?.untranslatedReason
    val progressing = caption.outcome == null && caption.translation.isNotEmpty()
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(sessionTime(caption.segment.startMs), Modifier.width(52.dp).padding(top = 3.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = colors.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            caption.translation.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge,
                color = when { reason != null -> colors.tertiary; progressing -> colors.primary; else -> colors.onSurface }) }
            reason?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(it.icon), null, Modifier.size(16.dp), tint = colors.tertiary)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(it.label), style = MaterialTheme.typography.labelLarge, color = colors.tertiary)
                }
            }
            Text(caption.segment.source, Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (progressing) colors.primary else colors.onSurfaceVariant)
        }
    }
}
