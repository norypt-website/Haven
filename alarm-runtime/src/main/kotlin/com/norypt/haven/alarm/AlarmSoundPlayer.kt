package com.norypt.haven.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * Loops the bundled alarm sound on the ALARM stream with USAGE_ALARM attributes (bypasses
 * media volume, honours the user's alarm volume and DND alarm rules) and vibrates with
 * alarm attributes. Requests transient audio focus so music pauses.
 *
 * Android 17 background-audio hardening: alarm-usage playback from an app holding the exact
 * alarm permission is exempt from the while-in-use requirement; the ring service is a
 * mediaPlayback foreground service in any case.
 */
public class AlarmSoundPlayer(private val context: Context, private val soundResId: Int) {
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private val audioManager get() = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    public fun start(vibrate: Boolean) {
        if (player != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req) // result ignored on purpose: an alarm must sound even if focus is refused
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(attributes)
                val afd = context.resources.openRawResourceFd(soundResId)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                prepare()
                start()
            }
        }
        if (vibrate) startVibration()
    }

    private fun startVibration() {
        val vm = context.getSystemService(VibratorManager::class.java) ?: return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 400, 600, 1200), 0)
        val attrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
        runCatching { vm.defaultVibrator.vibrate(effect, attrs) }
    }

    public fun stop() {
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        runCatching { context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel() }
    }

    public val isPlaying: Boolean get() = player != null

    public fun alarmVolumeIsZero(): Boolean = audioManager.getStreamVolume(AudioManager.STREAM_ALARM) == 0
}
