package com.ifautofab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

object MediaSessionHelper {
    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    var onSkipToNextListener: (() -> Unit)? = null

    fun init(context: Context) {
        if (mediaSession != null) return

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Request Audio Focus to suppress music apps
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) // Car-friendly usage
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(playbackAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { /* Do nothing for now */ }
            .build()

        audioManager?.requestAudioFocus(focusRequest!!)

        mediaSession = MediaSessionCompat(context, "IFAutoFab").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onSkipToNext() {
                    onSkipToNextListener?.invoke()
                }

                override fun onPlay() {
                    isActive = true
                    updateState(PlaybackStateCompat.STATE_PLAYING)
                }

                override fun onPause() {
                    updateState(PlaybackStateCompat.STATE_PAUSED)
                }
                
                override fun onStop() {
                    updateState(PlaybackStateCompat.STATE_STOPPED)
                }
            })
            
            updateState(PlaybackStateCompat.STATE_PLAYING)
            isActive = true
        }
    }

    private fun updateState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or 
                PlaybackStateCompat.ACTION_PLAY or 
                PlaybackStateCompat.ACTION_PAUSE or 
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, 0, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    fun shutdown() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        audioManager = null
    }
}
