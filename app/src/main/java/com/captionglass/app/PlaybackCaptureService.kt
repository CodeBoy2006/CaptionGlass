package com.captionglass.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaptureState(val active: Boolean = false, val message: String = "尚未开始检查")

/** One diagnostic capture owner. No microphone source, screen surface, model or audio file. */
class PlaybackCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var readerStarted = false
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            mutableState.value = CaptureState(message = "捕获授权已结束")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        try {
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_PROJECTION, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJECTION)
            check(data != null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val capture = checkNotNull(getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(Activity.RESULT_OK, data))
            projection = capture
            capture.registerCallback(callback, Handler(Looper.getMainLooper()))
            val configuration = AudioPlaybackCaptureConfiguration.Builder(capture)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            val audio = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(configuration)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 6_400))
                .build()
            recorder = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            mutableState.value = CaptureState(true, "等待可捕获的声音")
            readerStarted = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) { readAudio(audio) }
        } catch (_: SecurityException) {
            mutableState.value = CaptureState(message = "缺少权限或捕获授权已失效，请重新开始")
            stopSelf()
        } catch (_: IllegalStateException) {
            mutableState.value = CaptureState(message = "无法启动音频捕获，请停止其他捕获会话后重试")
            stopSelf()
        } catch (_: IllegalArgumentException) {
            mutableState.value = CaptureState(message = "当前设备不支持此音频配置")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun readAudio(audio: AudioRecord) {
        try {
            withContext(Dispatchers.IO) {
                val buffer = ShortArray(1_600)
                val beganAt = SystemClock.elapsedRealtime()
                var lastSignal: Long? = null
                var lastReport = 0L
                while (isActive) {
                    val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    check(count > 0) { "AudioRecord read failed" }
                    val now = SystemClock.elapsedRealtime()
                    // A diagnostic threshold only; silence cannot identify capture policy or DRM.
                    if ((0 until count).any { kotlin.math.abs(buffer[it].toInt()) > 64 }) lastSignal = now
                    if (now - lastReport >= 500) {
                        lastReport = now
                        val message = when {
                            lastSignal?.let { now - it < 3_000 } == true -> "已收到播放音频 · 不保存音频"
                            now - beganAt < 3_000 -> "等待可捕获的声音"
                            else -> "未收到可捕获音频：请确认正在播放；目标应用也可能限制捕获"
                        }
                        withContext(Dispatchers.Main) { mutableState.value = CaptureState(true, message) }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            mutableState.value = CaptureState(message = "音频捕获已中断，请重新授权")
            stopSelf()
        } finally {
            audio.release()
            if (recorder === audio) recorder = null
        }
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_caption)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.capture_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_capture), stop).build())
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        recorder?.let { audio ->
            runCatching { audio.stop() }
            // The read coroutine releases its recorder after the blocking read has returned.
            if (!readerStarted) runCatching { audio.release() }
        }
        projection?.let { it.unregisterCallback(callback); it.stop() }
        projection = null
        if (mutableState.value.active) mutableState.value = CaptureState(message = "音频检查已停止")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROJECTION = "projection"
        private const val ACTION_STOP = "com.captionglass.STOP_CAPTURE"
        private const val CHANNEL = "capture-diagnostic"
        private val mutableState = MutableStateFlow(CaptureState())
        val state = mutableState.asStateFlow()
    }
}
