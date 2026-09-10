package com.captionglass.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private var message by mutableStateOf<String?>(null)
    private var authorizing by mutableStateOf(false)
    private var chineseSource by mutableStateOf(false)
    private val projectionRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        authorizing = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                startForegroundService(Intent(this, PlaybackCaptureService::class.java)
                    .putExtra(PlaybackCaptureService.EXTRA_PROJECTION, result.data)
                    .putExtra(PlaybackCaptureService.EXTRA_CHINESE, chineseSource))
                message = null
            } catch (_: Exception) { message = "请回到应用前台重新授权字幕捕获" }
        } else message = "已取消捕获授权"
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true)
            projectionRequest.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        else { authorizing = false; message = "需要音频权限才能获取播放声音；不会使用麦克风" }
    }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!Settings.canDrawOverlays(this)) message = "悬浮窗未开启，字幕仍可在本应用内查看"
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
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("chineseSource", chineseSource)
        super.onSaveInstanceState(outState)
    }
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chineseSource = savedInstanceState?.getBoolean("chineseSource") ?: false
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFA7D8C4)) else
                lightColorScheme(primary = Color(0xFF235B48), background = Color(0xFFF6F7F3), surface = Color(0xFFF6F7F3))) {
                val capture by PlaybackCaptureService.state.collectAsStateWithLifecycle()
                val selectedChinese = if (capture.active) capture.chineseSource else chineseSource
                LaunchedEffect(capture.active, capture.chineseSource) {
                    if (capture.active) chineseSource = capture.chineseSource
                }
                val packMessage by ModelPack.state.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val ready = remember(packMessage, capture.active) { ModelPack.ready(this) }
                val busy = capture.active || authorizing || ModelPack.importing
                Scaffold { insets ->
                    Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 24.dp)) {
                        Spacer(Modifier.height(24.dp))
                        Text("CaptionGlass", style = MaterialTheme.typography.headlineMedium)
                        Text("跨应用双语字幕 · 本地运行", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PrimaryTabRow(tab, Modifier.padding(top = 16.dp)) {
                            listOf("字幕", "记录", "设置").forEachIndexed { index, label ->
                                Tab(tab == index, { tab = index }, text = { Text(label) })
                            }
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            when (tab) {
                                0 -> {
                                    Text(if (selectedChinese) "简体中文 → 英语" else "英语 → 简体中文", style = MaterialTheme.typography.titleLarge)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        FilterChip(!selectedChinese, { chineseSource = false }, enabled = !busy, label = { Text("英 → 中") })
                                        FilterChip(selectedChinese, { chineseSource = true }, enabled = !busy, label = { Text("中 → 英") })
                                    }
                                    Surface(color = Color(0xFF192922), shape = MaterialTheme.shapes.large) {
                                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            val page = capture.page
                                            Text(page?.translation ?: page?.caption?.untranslatedReason?.label() ?: "等待译文…",
                                                style = MaterialTheme.typography.titleLarge, color = Color.White)
                                            Text(page?.source ?: "开启后播放视频、播客或课程", color = Color(0xFFCAD7CF))
                                            if (page != null && page.total > 1) Text("${page.index + 1} / ${page.total}", color = Color(0xFFCAD7CF))
                                        }
                                    }
                                    if (capture.stable.isNotBlank() || capture.provisional.isNotBlank()) {
                                        Text("识别原文", style = MaterialTheme.typography.labelLarge)
                                        Text(buildAnnotatedString {
                                            append(capture.stable)
                                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(capture.provisional) }
                                        })
                                    }
                                    Text(message ?: capture.message, color = MaterialTheme.colorScheme.primary)
                                    if (capture.readingBehind > 0) Text("有 ${capture.readingBehind} 条内容未赶上阅读显示，请到记录查看。")
                                    if (capture.translationBacklog > 0) Text("翻译积压：${capture.translationBacklog} 条内容保留原文，可在记录中查看。")
                                    if (!ready) {
                                        Text("先导入中英语言包，约 1.8 GB。选择包含五个模型文件的文件夹。")
                                        OutlinedButton({ importFolder.launch(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导入语言包") }
                                        if (packMessage.isNotEmpty()) Text(packMessage)
                                    }
                                    Button(onClick = {
                                        if (capture.active) startService(Intent(this@MainActivity, PlaybackCaptureService::class.java).setAction(PlaybackCaptureService.ACTION_STOP))
                                        else {
                                            authorizing = true
                                            if (Settings.canDrawOverlays(this@MainActivity)) requestAudio()
                                            else overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
                                        }
                                    }, enabled = !capture.stopping && (capture.active || (ready && !busy)), modifier = Modifier.fillMaxWidth()) {
                                        Text(if (capture.stopping) "正在停止…" else if (capture.active) "停止字幕" else if (authorizing) "等待系统授权…" else "开启字幕")
                                    }
                                }
                                1 -> {
                                    Text("本次字幕", style = MaterialTheme.typography.titleLarge)
                                    Text("已确认 ${capture.confirmed} 条 · 已处理 ${capture.outcomes} 条。仅在内存保留最近 200 条，开启新会话后清空。")
                                    if (capture.history.isEmpty()) Text("还没有字幕记录。")
                                    capture.history.forEach { caption ->
                                        Text(caption.translation ?: caption.untranslatedReason!!.label(), style = MaterialTheme.typography.titleMedium)
                                        Text(caption.segment.source, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        HorizontalDivider()
                                    }
                                }
                                2 -> {
                                    Text("中英语言包", style = MaterialTheme.typography.titleLarge)
                                    Text(if (ready) "已校验 · 离线可用" else "尚未导入")
                                    Text("M1 提供离线文件导入。文件版本、大小及 SHA-256 必须与随应用固定的清单一致。")
                                    OutlinedButton({ importFolder.launch(null) }, enabled = !busy) { Text("导入 / 替换语言包") }
                                    if (packMessage.isNotEmpty()) Text(packMessage)
                                    HorizontalDivider()
                                    Text("隐私", style = MaterialTheme.typography.titleLarge)
                                    Text("无账号、无网络访问、不保存原始音频。仅捕获你授权的应用播放声音，不使用麦克风、不截取屏幕图像。")
                                    Text("静音、尚未播放或目标应用限制捕获都可能没有声音。请按系统提示选择整个屏幕，再切换到播放应用。")
                                    HorizontalDivider()
                                    Text("悬浮字幕", style = MaterialTheme.typography.titleLarge)
                                    Text("拖动小把手调整位置；点击把手切换字幕触摸穿透。长字幕分成多页，每种语言每页最多两行。")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
