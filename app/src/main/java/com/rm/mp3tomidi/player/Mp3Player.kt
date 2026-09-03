package com.rm.mp3tomidi.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.rm.mp3tomidi.playback.PlaybackNotificationService
import com.rm.mp3tomidi.util.displayNameOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Thin ExoPlayer wrapper for previewing the selected MP3 before conversion. */
class Mp3Player(private val context: Context) : PlaybackSource {
    private val appContext = context.applicationContext
    private val player = ExoPlayer.Builder(appContext).build()
    private var currentUri: Uri? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    override var title: String = "MP3 preview"
        private set
    override val isPlayingFlow: StateFlow<Boolean> get() = _isPlaying

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                // PlaybackFacadePlayer.getState() reads this via isPlayingFlow.value, but nothing
                // else observes this flow to know a fresh snapshot is needed -- re-announcing
                // (idempotent if we're already the active source) is what actually triggers
                // invalidateState(), so the notification's play/pause action reflects reality
                // instead of whatever was true the last time something else announced us.
                PlaybackNotificationService.setActiveSource(appContext, this@Mp3Player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                    player.pause()
                }
                // ExoPlayer's duration is unknown until preparation finishes, which happens
                // asynchronously after play() (and thus after this source already announced
                // itself to PlaybackNotificationService, possibly with an unknown duration) --
                // re-announce once duration is actually known so the notification's seek bar
                // isn't stuck reporting one that never resolves. Guarded on playWhenReady so an
                // unrelated prepare() (e.g. just picking a new input file) can't steal the
                // notification from whichever source is actually meant to be playing.
                if (playbackState == Player.STATE_READY && player.playWhenReady) {
                    PlaybackNotificationService.setActiveSource(appContext, this@Mp3Player)
                }
            }
        })
    }

    fun togglePlayback(uri: Uri) {
        if (currentUri != uri) {
            currentUri = uri
            title = displayNameOf(context, uri) ?: "MP3 preview"
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
        if (player.isPlaying) pause() else play()
    }

    override fun play() {
        player.play()
        PlaybackNotificationService.setActiveSource(appContext, this)
    }

    override fun pause() {
        player.pause()
    }

    override fun positionMs(): Long = player.currentPosition.coerceAtLeast(0L)
    override fun durationMs(): Long = player.duration.coerceAtLeast(0L)

    override fun seekTo(targetMs: Long) {
        player.seekTo(targetMs)
    }

    fun release() {
        player.release()
    }
}
