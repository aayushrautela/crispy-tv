package com.crispy.tv.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Single app-wide owner of Android audio focus.
 *
 * All audible playback (main player, trailer hero, YouTube extras) routes through this
 * manager instead of requesting focus directly, so the three sources never fight over it.
 * Only one source holds focus at a time; acquiring while another holds it pauses that
 * holder first (handoff). On externally triggered focus loss the current holder is told
 * to pause (no ducking, by design).
 */
class AudioFocusManager(appContext: Context) {
    private val appContext = appContext.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            ).setOnAudioFocusChangeListener(::onAudioFocusChange)
            .build()

    private val pauseHandlers = LinkedHashMap<String, () -> Unit>()
    private var holderKey: String? = null

    fun registerSource(key: String, pauseHandler: () -> Unit) {
        pauseHandlers[key] = pauseHandler
    }

    fun unregisterSource(key: String) {
        pauseHandlers.remove(key)
        if (holderKey == key) {
            abandonIfHeld()
        }
    }

    fun acquire(key: String) {
        if (holderKey == key) return
        val audioManager = audioManager ?: return
        val previousHolder = holderKey
        if (previousHolder != null) {
            pauseHandlers[previousHolder]?.invoke()
        }
        val result = audioManager.requestAudioFocus(focusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            holderKey = key
        } else if (previousHolder != null) {
            holderKey = null
        }
    }

    fun release(key: String) {
        if (holderKey != key) return
        abandonIfHeld()
    }

    private fun abandonIfHeld() {
        audioManager?.abandonAudioFocusRequest(focusRequest)
        holderKey = null
    }

    private fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                val holder = holderKey
                if (holder != null) {
                    pauseHandlers[holder]?.invoke()
                }
                abandonIfHeld()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // No auto-resume; existing UI paths decide whether to continue playing.
            }
        }
    }
}
