package com.ifautofab

import android.content.Context
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

object MediaSessionHelper {
    private var mediaSession: MediaSessionCompat? = null
    var onSkipToNextListener: (() -> Unit)? = null

    fun init(context: Context) {
        if (mediaSession != null) return

        mediaSession = MediaSessionCompat(context, "IFAutoFab").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onSkipToNext() {
                    onSkipToNextListener?.invoke()
                }
            })
            
            val state = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_PLAY)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                .build()
            
            setPlaybackState(state)
            isActive = true
        }
    }

    fun shutdown() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }
}
