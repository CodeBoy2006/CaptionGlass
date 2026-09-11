package com.captionglass.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Transient permission outcomes. Session outcomes live in [CaptureStatus]. */
private enum class Notice(val text: Int, val opensSettings: Boolean = false) {
    CONSENT_CANCELLED(R.string.notice_consent_cancelled),
    AUDIO_DENIED(R.string.notice_audio_denied, opensSettings = true),
    OVERLAY_DENIED(R.string.notice_overlay_denied),
    START_REJECTED(R.string.notice_start_rejected),
}

private enum class Destination(val label: Int, val icon: Int, val selectedIcon: Int) {
    CAPTIONS(R.string.tab_captions, R.drawable.ic_subtitles, R.drawable.ic_caption),
    RECORDS(R.string.tab_records, R.drawable.ic_article, R.drawable.ic_article_fill),
    SETTINGS(R.string.tab_settings, R.drawable.ic_settings, R.drawable.ic_settings),
}

@Composable
private fun DestinationIcon(destination: Destination, selected: Boolean) =
    Icon(painterResource(if (selected) destination.selectedIcon else destination.icon), null)

class MainActivity : ComponentActivity() {
    private var notice by mutableStateOf<Notice?>(null)
    private var authorizing by mutableStateOf(false)
    private var chineseSource by mutableStateOf(false)
    private val projectionRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        authorizing = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                startForegroundService(Intent(this, PlaybackCaptureService::class.java)
                    .putExtra(PlaybackCaptureService.EXTRA_PROJECTION, result.data)
                    .putExtra(PlaybackCaptureService.EXTRA_CHINESE, chineseSource))
            } catch (_: Exception) { notice = Notice.START_REJECTED }
        } else notice = Notice.CONSENT_CANCELLED
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) projectionRequest.launch(captureIntent())
        else { authorizing = false; notice = Notice.AUDIO_DENIED }
    }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!Settings.canDrawOverlays(this)) notice = Notice.OVERLAY_DENIED
        requestAudio()
    }
    private val importFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) ModelPack.import(applicationContext, uri)
    }
    private fun requestAudio() {
        audioPermission.launch(if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO))
    }
    /** Captions follow whatever plays, so offer only the whole-display choice where the platform allows it. */
    private fun captureIntent(): Intent {
        val manager = getSystemService(MediaProjectionManager::class.java)
        return if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
    }
    private fun overlaySettings() = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
    private fun start() {
        authorizing = true
        // Offer the overlay settings once per process; afterwards the home chip and settings row lead there.
        if (Settings.canDrawOverlays(this) || overlayOffered) requestAudio()
        else { overlayOffered = true; overlayPermission.launch(overlaySettings()) }
    }
    private fun stop() {
        startService(Intent(this, PlaybackCaptureService::class.java).setAction(PlaybackCaptureService.ACTION_STOP))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("chineseSource", chineseSource)
        super.onSaveInstanceState(outState)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chineseSource = savedInstanceState?.getBoolean("chineseSource") ?: false
        enableEdgeToEdge()
        setContent {
            CaptionGlassTheme {
                val capture by PlaybackCaptureService.state.collectAsStateWithLifecycle()
                val pack by ModelPack.state.collectAsStateWithLifecycle()
                var resumes by remember { mutableIntStateOf(0) }
                LifecycleResumeEffect(Unit) { resumes++; onPauseOrDispose { } }
                val ready = remember(pack, capture.active, resumes) { ModelPack.ready(this@MainActivity) }
                val overlayAllowed = remember(resumes) { Settings.canDrawOverlays(this@MainActivity) }
                LaunchedEffect(capture.active, capture.chineseSource) {
                    if (capture.active) chineseSource = capture.chineseSource
                }
                val selectedChinese = if (capture.active) capture.chineseSource else chineseSource
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val records = rememberLazyListState()
                val snackbar = remember { SnackbarHostState() }
                val current = notice
                val message = current?.let { stringResource(it.text) }
                val settingsLabel = stringResource(R.string.action_open_settings)
                LaunchedEffect(current) {
                    if (current == null || message == null) return@LaunchedEffect
                    val result = snackbar.showSnackbar(message, settingsLabel.takeIf { current.opensSettings },
                        duration = if (current.opensSettings) SnackbarDuration.Long else SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed)
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                    notice = null
                }
                BoxWithConstraints {
                    // Wide windows move navigation to a side rail so the stage keeps its vertical room.
                    val rail = maxWidth > maxHeight && maxWidth >= 600.dp
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbar) },
                        bottomBar = {
                            if (!rail) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Destination.entries.forEach { destination ->
                                    NavigationBarItem(tab == destination.ordinal, { tab = destination.ordinal },
                                        icon = { DestinationIcon(destination, tab == destination.ordinal) },
                                        label = { Text(stringResource(destination.label)) })
                                }
                            }
                        },
                    ) { insets ->
                        Row(Modifier.fillMaxSize().padding(insets)) {
                            if (rail) NavigationRail(containerColor = MaterialTheme.colorScheme.background,
                                windowInsets = WindowInsets(0)) {
                                Spacer(Modifier.weight(1f))
                                Destination.entries.forEach { destination ->
                                    NavigationRailItem(tab == destination.ordinal, { tab = destination.ordinal },
                                        icon = { DestinationIcon(destination, tab == destination.ordinal) },
                                        label = { Text(stringResource(destination.label)) })
                                }
                                Spacer(Modifier.weight(1f))
                            }
                            Crossfade(tab, Modifier.weight(1f).fillMaxHeight(), label = "destination") { shown ->
                                when (Destination.entries[shown]) {
                                    Destination.CAPTIONS -> HomeScreen(capture, pack, ready, overlayAllowed, selectedChinese, authorizing,
                                        onSwap = { chineseSource = !chineseSource }, onStart = ::start, onStop = ::stop,
                                        onImport = { importFolder.launch(null) }, onOpenRecords = { tab = Destination.RECORDS.ordinal },
                                        onOverlaySettings = { startActivity(overlaySettings()) })
                                    Destination.RECORDS -> RecordsScreen(capture, records)
                                    Destination.SETTINGS -> SettingsScreen(pack, ready, capture.active || authorizing || pack.importing,
                                        overlayAllowed, onImport = { importFolder.launch(null) },
                                        onOverlaySettings = { startActivity(overlaySettings()) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private companion object {
        var overlayOffered = false
    }
}
