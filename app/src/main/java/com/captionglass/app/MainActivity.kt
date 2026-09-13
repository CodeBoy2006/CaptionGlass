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
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.captionglass.engine.Language
import com.captionglass.engine.LanguagePair
import com.captionglass.nativebridge.TranslationBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

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
    private var selection by mutableStateOf(ModelSelection())
    private var pendingImport by mutableStateOf<String?>(null)
    private var exportOwner = UUID.randomUUID().toString()
    private var display by mutableStateOf(CaptionDisplay.SCROLL)
    private var style by mutableStateOf(CaptionStyle.PLATE)
    private var size by mutableStateOf(CaptionSize.STANDARD)
    private lateinit var catalog: ModelCatalog
    // The running overlay listens to the same preferences and restyles itself immediately.
    private fun chooseDisplay(value: CaptionDisplay) { display = value; CaptionPreferences.save(this, value) }
    private fun chooseStyle(value: CaptionStyle) { style = value; CaptionPreferences.save(this, value) }
    private fun chooseSize(value: CaptionSize) { size = value; CaptionPreferences.save(this, value) }
    private fun select(value: ModelSelection) {
        if (authorizing || pendingImport != null || ModelPack.busy || PlaybackCaptureService.state.value.active) return
        require(catalog.valid(value))
        selection = value
        getSharedPreferences("selection", MODE_PRIVATE).edit {
            putString("source", value.languages.source.code).putString("target", value.languages.target.code)
            putString("recognizer", value.recognizerId).putString("translator", value.translatorId)
            putString("backend", value.backend.id)
        }
    }
    private val projectionRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        authorizing = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                startForegroundService(Intent(this, PlaybackCaptureService::class.java)
                    .putExtra(PlaybackCaptureService.EXTRA_PROJECTION, result.data)
                    .putExtra(PlaybackCaptureService.EXTRA_SOURCE, selection.languages.source.code)
                    .putExtra(PlaybackCaptureService.EXTRA_TARGET, selection.languages.target.code)
                    .putExtra(PlaybackCaptureService.EXTRA_RECOGNIZER, selection.recognizerId)
                    .putExtra(PlaybackCaptureService.EXTRA_TRANSLATOR, selection.translatorId)
                    .putExtra(PlaybackCaptureService.EXTRA_BACKEND, selection.backend.id))
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
        val model = catalog.models.find { it.id == pendingImport }
        pendingImport = null
        if (uri != null && model != null) ModelPack.import(applicationContext, model, uri)
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data == null) RecordExport.launchFailed()
        else RecordExport.save(applicationContext, result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK }, exportOwner)
    }
    private fun export(format: RecordFormat) {
        val intent = RecordExport.prepare(PlaybackCaptureService.state.value, format, exportOwner) ?: return
        try { exportDocument.launch(intent) }
        catch (_: Exception) { RecordExport.launchFailed() }
    }
    private fun import(model: ModelSpec) {
        if (authorizing || pendingImport != null || ModelPack.busy || PlaybackCaptureService.state.value.active) return
        pendingImport = model.id
        importFolder.launch(null)
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
        if (authorizing || pendingImport != null || ModelPack.busy || PlaybackCaptureService.state.value.active ||
            !catalog.valid(selection) || catalog.required(selection).any { !ModelPack.ready(this, it) }) return
        authorizing = true
        // Offer the overlay settings once per process; afterwards the home chip and settings row lead there.
        if (Settings.canDrawOverlays(this) || overlayOffered) requestAudio()
        else { overlayOffered = true; overlayPermission.launch(overlaySettings()) }
    }
    private fun stop() {
        startService(Intent(this, PlaybackCaptureService::class.java).setAction(PlaybackCaptureService.ACTION_STOP))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("authorizing", authorizing)
        outState.putString("pendingImport", pendingImport)
        outState.putString("exportOwner", exportOwner)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (isFinishing) RecordExport.abandon(exportOwner)
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catalog = ModelCatalog(this)
        val preferences = getSharedPreferences("selection", MODE_PRIVATE)
        selection = runCatching {
            ModelSelection(LanguagePair(checkNotNull(Language.fromCode(preferences.getString("source", "en"))),
                checkNotNull(Language.fromCode(preferences.getString("target", "zh")))),
                checkNotNull(preferences.getString("recognizer", ModelSelection().recognizerId)),
                checkNotNull(preferences.getString("translator", ModelSelection().translatorId)),
                checkNotNull(TranslationBackend.fromId(preferences.getString("backend", "vulkan"))))
                .also { check(catalog.valid(it)) }
        }.getOrDefault(ModelSelection())
        authorizing = savedInstanceState?.getBoolean("authorizing") ?: false
        pendingImport = savedInstanceState?.getString("pendingImport")
        exportOwner = savedInstanceState?.getString("exportOwner") ?: exportOwner
        display = CaptionPreferences.display(this)
        style = CaptionPreferences.style(this)
        size = CaptionPreferences.size(this)
        ModelPack.recover(applicationContext, catalog.models)
        enableEdgeToEdge()
        setContent {
            CaptionGlassTheme {
                val capture by PlaybackCaptureService.state.collectAsStateWithLifecycle()
                val pack by ModelPack.state.collectAsStateWithLifecycle()
                var resumes by remember { mutableIntStateOf(0) }
                LifecycleResumeEffect(Unit) { resumes++; onPauseOrDispose { } }
                val localModels = remember(pack.busy, capture.active, resumes) {
                    catalog.models.associate { it.id to ModelPack.installed(this@MainActivity, it) }
                }
                val installed = localModels.filterValues { it.ready }.keys
                val availableBytes by produceState<Long?>(null, pack.busy, resumes) {
                    value = withContext(Dispatchers.IO) { runCatching { ModelPack.availableBytes(this@MainActivity) }.getOrNull() }
                }
                val overlayAllowed = remember(resumes) { Settings.canDrawOverlays(this@MainActivity) }
                val selected = if (capture.active) capture.selection else selection
                val missing = catalog.required(selected).firstOrNull { it.id !in installed }
                val choosing = authorizing || pendingImport != null
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val records = rememberLazyListState()
                val snackbar = remember { SnackbarHostState() }
                val exportMessage = RecordExport.message
                LaunchedEffect(exportMessage) {
                    if (exportMessage != null) {
                        snackbar.showSnackbar(getString(exportMessage))
                        RecordExport.dismiss()
                    }
                }
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
                                    Destination.CAPTIONS -> HomeScreen(capture, pack, missing, overlayAllowed, selected, choosing, catalog, installed,
                                        onSelect = ::select, onStart = ::start, onStop = ::stop,
                                        onManageModels = { tab = Destination.SETTINGS.ordinal }, onOpenRecords = { tab = Destination.RECORDS.ordinal },
                                        onOverlaySettings = { startActivity(overlaySettings()) }, display = display, size = size)
                                    Destination.RECORDS -> RecordsScreen(capture, records, RecordExport.busy, ::export)
                                    Destination.SETTINGS -> SettingsScreen(pack, catalog, selected, localModels, availableBytes,
                                        capture.active || choosing || pack.busy, overlayAllowed, onImport = ::import, onSelect = ::select,
                                        onDownload = { if (!authorizing && pendingImport == null) ModelPack.download(applicationContext, it) },
                                        onCheck = { if (!authorizing && pendingImport == null) ModelPack.recheck(applicationContext, it) },
                                        onRemove = { if (!authorizing && pendingImport == null) ModelPack.remove(applicationContext, it) },
                                        onOverlaySettings = { startActivity(overlaySettings()) }, display = display, style = style, size = size,
                                        onDisplay = ::chooseDisplay, onStyle = ::chooseStyle, onSize = ::chooseSize)
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
