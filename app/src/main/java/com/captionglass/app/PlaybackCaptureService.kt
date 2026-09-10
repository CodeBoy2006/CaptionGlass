package com.captionglass.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sole capture owner. Consent is consumed once; models are released only after workers join. */
class PlaybackCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var session: CaptionSession? = null
    private var overlay: SubtitleOverlay? = null
    @Volatile private var stopping = false
    private var started = false
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { requestStop("捕获授权已结束") }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent == null) {
            requestStop(); if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (started || mutableState.value.active || ModelPack.importing) { if (!started) stopSelf(); return START_NOT_STICKY }
        started = true
        mutableState.value = CaptureState(active = true, chineseSource = intent.getBooleanExtra(EXTRA_CHINESE, false), message = "正在准备字幕…")
        try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_PROJECTION, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJECTION)
            check(data != null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            projection = checkNotNull(getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data))
            projection!!.registerCallback(callback, Handler(Looper.getMainLooper()))
            val view = SubtitleOverlay(this)
            overlay = view
            if (Settings.canDrawOverlays(this)) view.show()
            val pipeline = CaptionSession(scope, intent.getBooleanExtra(EXTRA_CHINESE, false), view::pages) {
                mutableState.value = it
                view.render(it)
            }
            session = pipeline
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { ModelPack.verify(this@PlaybackCaptureService) }
                    check(!stopping) { "字幕已停止" }
                    try { pipeline.start(ModelPack.directory(this@PlaybackCaptureService)) }
                    catch (_: Exception) {
                        if (!stopping) requestStop("无法加载本地语言包，请关闭其他高内存应用后重试")
                        return@launch
                    }
                    check(!stopping) { "字幕已停止" }
                    val audio = createRecorder(checkNotNull(projection))
                    recorder = audio
                    readAudio(audio, pipeline)
                } catch (e: Exception) {
                    if (!stopping) requestStop(if (e is SecurityException) "捕获权限已失效，请重新授权"
                        else e.message?.take(100) ?: "无法启动字幕，请检查语言包和捕获权限")
                } finally {
                    withContext(NonCancellable) {
                        pipeline.stop(if (stopping) mutableState.value.message else "字幕已停止")
                        runCatching { pipeline.close() }
                        overlay?.close(); overlay = null
                        projection?.let { it.unregisterCallback(callback); it.stop() }; projection = null
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        scope.cancel()
                    }
                }
            }
        } catch (e: Exception) {
            mutableState.value = CaptureState(message = if (e is SecurityException) "捕获权限已失效，请重新授权" else "无法启动字幕，请重新开始")
            projection?.let { it.unregisterCallback(callback); it.stop() }; projection = null
            overlay?.close(); overlay = null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            scope.cancel()
        }
        return START_NOT_STICKY
    }

    private fun createRecorder(capture: MediaProjection): AudioRecord {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Audio capture permission revoked")
        val configuration = AudioPlaybackCaptureConfiguration.Builder(capture)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        for (rate in listOf(16_000, 48_000)) {
            var audio: AudioRecord? = null
            try {
                val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                audio = AudioRecord.Builder().setAudioPlaybackCaptureConfig(configuration)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, rate * 2 / 5)).build()
                check(audio.state == AudioRecord.STATE_INITIALIZED)
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                return audio
            } catch (e: Exception) {
                audio?.release()
                if (e is SecurityException) throw e
            }
        }
        error("当前设备无法以 16 kHz 或 48 kHz 捕获播放音频")
    }

    private suspend fun readAudio(audio: AudioRecord, pipeline: CaptionSession) = withContext(Dispatchers.IO) {
        val rate = audio.sampleRate
        val buffer = ShortArray(rate / 10)
        var samples = 0L
        val beganAt = SystemClock.elapsedRealtime()
        var lastSignal = beganAt
        var lastReport = 0L
        try {
            while (!stopping) {
                val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (stopping) break
                check(count > 0) { "音频捕获中断，请重新授权" }
                samples += count
                val values = FloatArray(count) { buffer[it] / 32768f }
                check(pipeline.offer(PcmFrame(values, rate, samples * 1000 / rate))) { "识别跟不上播放，音频缓冲已满；请重新开始" }
                val now = SystemClock.elapsedRealtime()
                if ((0 until count).any { kotlin.math.abs(buffer[it].toInt()) > 64 }) lastSignal = now
                if (now - lastReport >= 1000) {
                    lastReport = now
                    withContext(Dispatchers.Main) { pipeline.message(if (now - lastSignal < 3000) "正在生成本地字幕 · 不保存音频"
                        else "未收到可捕获的声音：请确认正在播放，或尝试允许捕获的应用") }
                }
            }
        } finally {
            runCatching { audio.stop() }; audio.release()
            withContext(Dispatchers.Main) { if (recorder === audio) recorder = null }
        }
    }

    private fun requestStop(message: String = "字幕已停止") {
        if (stopping) return
        stopping = true
        session?.stop(message)
        overlay?.close()
        runCatching { recorder?.stop() }
    }
    private fun notification(): Notification {
        val channel = "live-captions"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "实时字幕", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_caption).setContentTitle("CaptionGlass")
            .setContentText("本地字幕正在运行 · 点击停止结束音频捕获").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止字幕", stop).build()).build()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.configurationChanged(); session?.reflow()
    }
    override fun onDestroy() {
        requestStop()
        if (!started) scope.cancel()
        super.onDestroy()
    }
    companion object {
        const val EXTRA_PROJECTION = "projection"
        const val EXTRA_CHINESE = "chineseSource"
        const val ACTION_STOP = "com.captionglass.STOP_CAPTURE"
        private val mutableState = MutableStateFlow(CaptureState())
        val state = mutableState.asStateFlow()
    }
}
