package com.captionglass.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import com.captionglass.engine.Language
import com.captionglass.engine.LanguagePair
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
        override fun onStop() { requestStop(CaptureStatus.CONSENT_ENDED) }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent == null) {
            requestStop(); if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (started || mutableState.value.active || ModelPack.busy) { if (!started) stopSelf(); return START_NOT_STICKY }
        started = true
        val catalog = ModelCatalog(this)
        val selection = runCatching {
            ModelSelection(LanguagePair(checkNotNull(Language.fromCode(intent.getStringExtra(EXTRA_SOURCE))),
                checkNotNull(Language.fromCode(intent.getStringExtra(EXTRA_TARGET)))),
                checkNotNull(intent.getStringExtra(EXTRA_RECOGNIZER)),
                checkNotNull(intent.getStringExtra(EXTRA_TRANSLATOR))).also { check(catalog.valid(it)) }
        }.getOrElse {
            mutableState.value = CaptureState(status = CaptureStatus.START_FAILED)
            stopSelf()
            return START_NOT_STICKY
        }
        mutableState.value = CaptureState(active = true, selection = selection, status = CaptureStatus.PREPARING)
        try {
            startForeground(1, notification(selection), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_PROJECTION, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJECTION)
            check(data != null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            projection = checkNotNull(getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data))
            projection!!.registerCallback(callback, Handler(Looper.getMainLooper()))
            val view = SubtitleOverlay(this)
            overlay = view
            view.render(mutableState.value)
            if (Settings.canDrawOverlays(this)) view.show()
            val pipeline = CaptionSession(scope, selection) {
                mutableState.value = it
                view.render(it)
            }
            session = pipeline
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        try { catalog.required(selection).forEach { ModelPack.verify(this@PlaybackCaptureService, it) } }
                        catch (_: Exception) { throw CaptureFailure(CaptureStatus.PACK_INVALID) }
                    }
                    check(!stopping) { "字幕已停止" }
                    try { pipeline.start(catalog.recognizer(selection).recognizerFiles(this@PlaybackCaptureService, selection.languages.source),
                        catalog.translator(selection).file(this@PlaybackCaptureService, "model"), catalog.translator(selection).translationFormat) }
                    catch (_: Exception) {
                        if (!stopping) requestStop(CaptureStatus.LOAD_FAILED)
                        return@launch
                    }
                    check(!stopping) { "字幕已停止" }
                    val audio = createRecorder(checkNotNull(projection))
                    recorder = audio
                    readAudio(audio, pipeline)
                } catch (e: Exception) {
                    if (!stopping) requestStop(when (e) {
                        is SecurityException -> CaptureStatus.PERMISSION_LOST
                        is CaptureFailure -> e.status
                        else -> CaptureStatus.START_FAILED
                    })
                } finally {
                    withContext(NonCancellable) {
                        pipeline.stop(if (stopping) mutableState.value.status else CaptureStatus.STOPPED)
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
            mutableState.value = CaptureState(selection = selection,
                status = if (e is SecurityException) CaptureStatus.PERMISSION_LOST else CaptureStatus.START_FAILED)
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
        throw CaptureFailure(CaptureStatus.AUDIO_UNSUPPORTED)
    }

    private suspend fun readAudio(audio: AudioRecord, pipeline: CaptionSession) = withContext(Dispatchers.IO) {
        val rate = audio.sampleRate
        val buffer = ShortArray(rate / 10)
        var samples = 0L
        val beganAt = SystemClock.elapsedRealtime()
        var lastSignal = 0L
        var lastReport = 0L
        try {
            while (!stopping) {
                val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (stopping) break
                if (count <= 0) throw CaptureFailure(CaptureStatus.CAPTURE_INTERRUPTED)
                samples += count
                val values = FloatArray(count) { buffer[it] / 32768f }
                if (!pipeline.offer(PcmFrame(values, rate, samples * 1000 / rate))) throw CaptureFailure(CaptureStatus.OVERRUN)
                val now = SystemClock.elapsedRealtime()
                if ((0 until count).any { kotlin.math.abs(buffer[it].toInt()) > 64 }) lastSignal = now
                if (now - lastReport >= 1000) {
                    lastReport = now
                    // Amplitude only shows that sound arrived; it never explains why none did.
                    val observed = when {
                        lastSignal > 0 && now - lastSignal < 3000 -> CaptureStatus.HEARING
                        now - beganAt < 3000 -> CaptureStatus.WAITING
                        else -> CaptureStatus.SILENT
                    }
                    withContext(Dispatchers.Main) { pipeline.status(observed) }
                }
            }
        } finally {
            runCatching { audio.stop() }; audio.release()
            withContext(Dispatchers.Main) { if (recorder === audio) recorder = null }
        }
    }

    private fun requestStop(reason: CaptureStatus = CaptureStatus.STOPPED) {
        if (stopping) return
        stopping = true
        session?.stop(reason)
        overlay?.close()
        runCatching { recorder?.stop() }
    }
    private fun notification(selection: ModelSelection): Notification {
        val channel = "live-captions"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_caption).setColor(getColor(R.color.brand))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("${selection.languages.source.label} → ${selection.languages.target.label}")
            .setShowWhen(true).setWhen(System.currentTimeMillis()).setUsesChronometer(true)
            .setCategory(Notification.CATEGORY_SERVICE).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop),
                getString(R.string.action_stop), stop).build()).build()
    }
    override fun onDestroy() {
        requestStop()
        if (!started) scope.cancel()
        super.onDestroy()
    }
    companion object {
        const val EXTRA_PROJECTION = "projection"
        const val EXTRA_SOURCE = "source_language"
        const val EXTRA_TARGET = "target_language"
        const val EXTRA_RECOGNIZER = "recognizer_id"
        const val EXTRA_TRANSLATOR = "translator_id"
        const val ACTION_STOP = "com.captionglass.STOP_CAPTURE"
        private val mutableState = MutableStateFlow(CaptureState())
        val state = mutableState.asStateFlow()
    }
}
