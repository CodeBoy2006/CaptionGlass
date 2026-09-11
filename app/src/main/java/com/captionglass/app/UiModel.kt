package com.captionglass.app

import com.captionglass.engine.UntranslatedReason
import java.util.Locale

/** Caption surfaces stay dark in both themes, like broadcast subtitles. Shared by the overlay and the in-app stage. */
internal object CaptionPalette {
    const val STAGE = 0xFF121815.toInt()
    const val STAGE_ON_DARK = 0xFF1B231F.toInt()
    const val TRANSLATION = 0xFFFFFFFF.toInt()
    const val SOURCE = 0xFFB4C0B9.toInt()
    const val MUTED = 0xFF85928B.toInt()
    const val ACCENT = 0xFF78D8AC.toInt()
    const val WARNING = 0xFFF2C47F.toInt()
}

internal val CaptureStatus.isLive
    get() = this == CaptureStatus.PREPARING || this == CaptureStatus.WAITING || this == CaptureStatus.HEARING || this == CaptureStatus.SILENT

/** Terminal states worth explaining; a user stop or a natural end needs no notice. */
internal val CaptureStatus.isProblem
    get() = !isLive && this != CaptureStatus.IDLE && this != CaptureStatus.STOPPED && this != CaptureStatus.ENDED

internal val CaptureStatus.label: Int
    get() = when (this) {
        CaptureStatus.IDLE -> R.string.status_idle
        CaptureStatus.PREPARING -> R.string.status_preparing
        CaptureStatus.WAITING -> R.string.status_waiting
        CaptureStatus.HEARING -> R.string.status_hearing
        CaptureStatus.SILENT -> R.string.status_silent
        CaptureStatus.STOPPED, CaptureStatus.ENDED -> R.string.status_stopped
        CaptureStatus.CONSENT_ENDED -> R.string.status_consent_ended
        CaptureStatus.PERMISSION_LOST -> R.string.status_permission_lost
        CaptureStatus.PACK_INVALID -> R.string.status_pack_invalid
        CaptureStatus.LOAD_FAILED -> R.string.status_load_failed
        CaptureStatus.AUDIO_UNSUPPORTED -> R.string.status_audio_unsupported
        CaptureStatus.CAPTURE_INTERRUPTED -> R.string.status_capture_interrupted
        CaptureStatus.OVERRUN -> R.string.status_overrun
        CaptureStatus.RECOGNITION_FAILED -> R.string.status_recognition_failed
        CaptureStatus.START_FAILED -> R.string.status_start_failed
    }

internal val CaptureStatus.icon: Int
    get() = when (this) {
        CaptureStatus.IDLE, CaptureStatus.STOPPED, CaptureStatus.ENDED -> R.drawable.ic_power
        CaptureStatus.PREPARING -> R.drawable.ic_progress
        CaptureStatus.WAITING -> R.drawable.ic_hearing
        CaptureStatus.HEARING -> R.drawable.ic_graphic_eq
        CaptureStatus.SILENT -> R.drawable.ic_volume_off
        CaptureStatus.CONSENT_ENDED -> R.drawable.ic_stop_screen_share
        CaptureStatus.PERMISSION_LOST -> R.drawable.ic_block
        CaptureStatus.PACK_INVALID -> R.drawable.ic_package
        CaptureStatus.LOAD_FAILED -> R.drawable.ic_memory
        CaptureStatus.AUDIO_UNSUPPORTED -> R.drawable.ic_music_off
        CaptureStatus.CAPTURE_INTERRUPTED -> R.drawable.ic_link_off
        CaptureStatus.OVERRUN -> R.drawable.ic_speed
        CaptureStatus.RECOGNITION_FAILED -> R.drawable.ic_sync_problem
        CaptureStatus.START_FAILED -> R.drawable.ic_error
    }

internal val CaptureStatus.hint: Int?
    get() = when (this) {
        CaptureStatus.SILENT -> R.string.hint_silent
        CaptureStatus.CONSENT_ENDED, CaptureStatus.PERMISSION_LOST, CaptureStatus.CAPTURE_INTERRUPTED,
        CaptureStatus.RECOGNITION_FAILED -> R.string.hint_restart
        CaptureStatus.PACK_INVALID -> R.string.hint_reimport
        CaptureStatus.LOAD_FAILED -> R.string.hint_memory
        CaptureStatus.OVERRUN -> R.string.hint_overrun
        CaptureStatus.START_FAILED -> R.string.hint_start_failed
        else -> null
    }

internal val UntranslatedReason.label: Int
    get() = when (this) {
        UntranslatedReason.BACKLOG -> R.string.reason_backlog
        UntranslatedReason.FAILED -> R.string.reason_failed
        UntranslatedReason.TIMED_OUT -> R.string.reason_timed_out
        UntranslatedReason.STOPPED -> R.string.reason_stopped
        UntranslatedReason.SUPERSEDED -> R.string.reason_superseded
    }

internal val UntranslatedReason.icon: Int
    get() = when (this) {
        UntranslatedReason.BACKLOG -> R.drawable.ic_speed
        UntranslatedReason.FAILED -> R.drawable.ic_error
        UntranslatedReason.TIMED_OUT -> R.drawable.ic_hourglass_disabled
        UntranslatedReason.STOPPED -> R.drawable.ic_stop_circle
        UntranslatedReason.SUPERSEDED -> R.drawable.ic_autorenew
    }

/** Capture-session clock, never a source-media position. */
internal fun sessionTime(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
