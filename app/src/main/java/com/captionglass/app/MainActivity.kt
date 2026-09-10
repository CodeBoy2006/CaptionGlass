package com.captionglass.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.captionglass.nativebridge.NativeRuntime
import com.captionglass.engine.Caption
import com.captionglass.engine.UntranslatedReason

class MainActivity : ComponentActivity() {
    private var permissionMessage by mutableStateOf<String?>(null)
    private var authorizing by mutableStateOf(false)
    private val projectionRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        authorizing = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                startForegroundService(Intent(this, PlaybackCaptureService::class.java)
                    .putExtra(PlaybackCaptureService.EXTRA_PROJECTION, result.data))
                permissionMessage = null
            } catch (_: IllegalStateException) {
                permissionMessage = "请回到应用前台重新开始音频检查"
            } catch (_: SecurityException) {
                permissionMessage = "授权已失效，请重新开始音频检查"
            }
        } else permissionMessage = "已取消捕获授权，可以稍后重新开始"
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            projectionRequest.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        } else {
            authorizing = false
            permissionMessage = "音频检查需要录音权限；拒绝权限不影响字幕预览"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFA7D8C4)) else
                lightColorScheme(primary = Color(0xFF235B48), background = Color(0xFFF6F7F3), surface = Color(0xFFF6F7F3))) {
                val model: ReplayViewModel = viewModel()
                val replay by model.state.collectAsStateWithLifecycle()
                val capture by PlaybackCaptureService.state.collectAsStateWithLifecycle()
                CaptionGlassScreen(replay, capture, permissionMessage, authorizing,
                    onPreview = model::start, onStopPreview = model::stop,
                    onCapture = {
                        model.stop()
                        authorizing = true
                        audioPermission.launch(if (Build.VERSION.SDK_INT >= 33)
                            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                        else arrayOf(Manifest.permission.RECORD_AUDIO))
                    },
                    onStopCapture = { stopService(Intent(this, PlaybackCaptureService::class.java)) })
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CaptionGlassScreen(
    replay: ReplayState,
    capture: CaptureState,
    permissionMessage: String?,
    authorizing: Boolean,
    onPreview: (Boolean) -> Unit,
    onStopPreview: () -> Unit,
    onCapture: () -> Unit,
    onStopCapture: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chineseSource by rememberSaveable { mutableStateOf(false) }
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("CaptionGlass", style = MaterialTheme.typography.headlineMedium)
            Text("让字幕跟上理解", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 16.dp)) {
                listOf("字幕", "记录", "设置").forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (tab) {
                    0 -> {
                        Text(if (chineseSource) "简体中文 → 英语" else "英语 → 简体中文", style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilterChip(selected = !chineseSource, onClick = { chineseSource = false },
                                enabled = !replay.running, label = { Text("英 → 中") })
                            FilterChip(selected = chineseSource, onClick = { chineseSource = true },
                                enabled = !replay.running, label = { Text("中 → 英") })
                        }
                        Text("开发预览 · 固定文本 · 本地运行", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(color = Color(0xFF192922), shape = MaterialTheme.shapes.large) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(replay.caption?.primaryText() ?: if (replay.running) "等待完整语义片段…"
                                    else if (chineseSource) "Good subtitles give you time to read." else "好的字幕，会留出阅读的时间。",
                                    style = MaterialTheme.typography.titleLarge, color = Color.White)
                                Text(replay.caption?.segment?.source ?: if (replay.running) "译文完成后整句显示"
                                    else if (chineseSource) "好的字幕，会留出阅读的时间。" else "Good subtitles give you time to read.",
                                    style = MaterialTheme.typography.bodyMedium, color = Color(0xFFCAD7CF))
                            }
                        }
                        if (replay.running) {
                            Text("正在识别 · 演示", style = MaterialTheme.typography.labelLarge)
                            Text(buildAnnotatedString {
                                append(replay.stable)
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(replay.provisional) }
                            }, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (replay.readingBehind) Text("阅读显示落后，完整内容可在记录中查看")
                        Button(onClick = { if (replay.running) onStopPreview() else onPreview(chineseSource) },
                            enabled = !capture.active && !authorizing, modifier = Modifier.fillMaxWidth()) {
                            Text(if (replay.running) "停止预览" else "预览字幕体验")
                        }
                        if (!NativeRuntime.isIntegrated) Text("真实字幕尚未开放：语言包与本地识别、翻译引擎正在接入。预览不采集声音。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    1 -> {
                        Text("本次演示", style = MaterialTheme.typography.titleLarge)
                        Text("仅保留在内存中，重新预览会清空；不会保存你的音频。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (replay.history.isEmpty()) Text("还没有字幕记录。先在“字幕”中预览体验。")
                        replay.history.forEach { caption ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(caption.primaryText(), style = MaterialTheme.typography.titleMedium)
                                Text(caption.segment.source, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                HorizontalDivider(Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                    2 -> {
                        Text("语言包", style = MaterialTheme.typography.titleLarge)
                        Text("尚无可安装的语言包。首个接入目标为中英识别与翻译；下载功能将在模型校验与真机验收后开放。")
                        HorizontalDivider()
                        Text("隐私", style = MaterialTheme.typography.titleLarge)
                        Text("无账号、无网络访问、无原始音频文件。当前记录仅为演示文本；正式文字记录将由你选择是否保存。")
                        HorizontalDivider()
                        Text("开发检查 · 跨应用音频", style = MaterialTheme.typography.titleLarge)
                        Text("开始后按系统提示授权，再切换到播放应用。这里只检查声音信号，不生成字幕，也不截取屏幕图像。")
                        Text(permissionMessage ?: capture.message, color = MaterialTheme.colorScheme.primary)
                        OutlinedButton(onClick = if (capture.active) onStopCapture else onCapture,
                            enabled = !authorizing, modifier = Modifier.fillMaxWidth()) {
                            Text(if (capture.active) "停止音频检查" else if (authorizing) "等待系统授权…" else "检查音频捕获")
                        }
                        Text("静音、尚未播放或应用限制捕获，都可能导致没有声音。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun Caption.primaryText(): String = translation ?: when (untranslatedReason) {
    UntranslatedReason.BACKLOG -> "未翻译：当前翻译积压"
    UntranslatedReason.FAILED -> "未翻译：翻译失败"
    UntranslatedReason.TIMED_OUT -> "未翻译：等待超时"
    UntranslatedReason.STOPPED -> "未翻译：任务已停止"
    null -> "未翻译"
}
