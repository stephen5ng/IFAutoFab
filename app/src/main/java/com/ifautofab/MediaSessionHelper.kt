package com.ifautofab

import android.content.Context
import android.util.Log
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * Helper for managing MediaSession and audio focus.
 * Handles media button events from steering wheel (when supported).
 */
object MediaSessionHelper {
    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    var onSkipToNextListener: (() -> Unit)? = null

    fun init(context: Context) {
        Log.d("IFAutoFab", "MediaSessionHelper: init")
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Request Audio Focus to suppress music apps
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(playbackAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { /* Do nothing for now */ }
            .build()

        audioManager?.requestAudioFocus(focusRequest!!)

        if (mediaSession != null) {
            mediaSession?.isActive = true
            updateState(PlaybackStateCompat.STATE_PLAYING)
            return
        }

        mediaSession = MediaSessionCompat(context, "IFAutoFab").apply {
            @Suppress("DEPRECATION")
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onSkipToNext() {
                    Log.d("IFAutoFab", "MediaSession: onSkipToNext")
                    onSkipToNextListener?.invoke()
                }

                override fun onSkipToPrevious() {
                    Log.d("IFAutoFab", "MediaSession: onSkipToPrevious")
                    onSkipToNextListener?.invoke()
                }

                override fun onPlay() {
                    Log.d("IFAutoFab", "MediaSession: onPlay")
                    isActive = true
                    updateState(PlaybackStateCompat.STATE_PLAYING)
                }

                override fun onPause() {
                    Log.d("IFAutoFab", "MediaSession: onPause")
                    updateState(PlaybackStateCompat.STATE_PAUSED)
                }

                override fun onStop() {
                    Log.d("IFAutoFab", "MediaSession: onStop")
                    updateState(PlaybackStateCompat.STATE_STOPPED)
                }
            })

            val metadata = android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, "IFAutoFab")
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, "Interactive Fiction")
                .build()
            setMetadata(metadata)

            updateState(PlaybackStateCompat.STATE_PLAYING)
            isActive = true
        }
    }

    private fun updateState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, 0, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    fun shutdown() {
        Log.d("IFAutoFab", "MediaSessionHelper: shutdown")
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        audioManager = null
    }
}
