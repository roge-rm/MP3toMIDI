package com.rm.mp3tomidi.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Thin ExoPlayer wrapper for previewing the selected MP3 before conversion. */
class Mp3Player(context: Context) {
    private val player = ExoPlayer.Builder(context.applicationContext).build()
    private var currentUri: Uri? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                    player.pause()
                }
            }
        })
    }

    fun togglePlayback(uri: Uri) {
        if (currentUri != uri) {
            currentUri = uri
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
        if (player.isPlaying) player.pause() else player.play()
    }

    fun release() {
        player.release()
    }
}
