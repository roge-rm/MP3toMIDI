package com.rm.mp3tomidi.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.rm.mp3tomidi.player.PlaybackSource

/**
 * A single persistent `androidx.media3` [Player] that delegates to whichever of [Mp3Player]/
 * [MidiPlayer] (see [PlaybackSource]) is currently "active", for the whole lifetime of
 * [PlaybackNotificationService]. Not two sessions, and not a swapped session player: media3's own
 * docs describe one [Player] per `MediaSession` for its lifetime with no supported way to change
 * it at runtime, and explicitly discourage multiple simultaneous sessions in one app (each would
 * show up separately on Bluetooth/Android Auto surfaces, which list sessions, not apps). A facade
 * that itself never changes, but reads through to whichever real player is active, satisfies both
 * constraints at once.
 *
 * Position while playing is reported via [PositionSupplier.getExtrapolating] rather than a fixed
 * value, so the notification's seek bar keeps advancing smoothly between [getState] rebuilds --
 * [invalidateState] only needs to fire on real transitions (source switched, played, paused,
 * seeked), matching how both real players already compute position from a wall-clock anchor
 * rather than being polled continuously.
 */
class PlaybackFacadePlayer(looper: Looper) : SimpleBasePlayer(looper) {

    @Volatile
    private var activeSource: PlaybackSource? = null

    fun setActiveSource(source: PlaybackSource) {
        activeSource = source
        invalidateState()
    }

    override fun getState(): State {
        val source = activeSource
        val durationMs = source?.durationMs() ?: 0L

        if (source == null || durationMs <= 0L) {
            // Nothing has ever played (or we're between "announced" and "duration known") --
            // no MediaItem in the playlist means no notification shows yet, per media3's own
            // documented behavior.
            return State.Builder()
                .setAvailableCommands(Player.Commands.EMPTY)
                .setPlaybackState(Player.STATE_IDLE)
                .build()
        }

        val isPlaying = source.isPlayingFlow.value
        val mediaItem = MediaItemData.Builder(MEDIA_ITEM_UID)
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(source.title).build())
                    .build(),
            )
            .setDurationUs(durationMs * 1000)
            .setIsSeekable(true)
            .build()

        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_METADATA)
                    .add(Player.COMMAND_GET_TIMELINE)
                    .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
                    .add(Player.COMMAND_STOP)
                    .build(),
            )
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(listOf(mediaItem))
            .setContentPositionMs(
                PositionSupplier.getExtrapolating(source.positionMs(), if (isPlaying) 1f else 0f),
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val source = activeSource
        if (source != null) {
            if (playWhenReady) source.play() else source.pause()
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        activeSource?.seekTo(if (positionMs == C.TIME_UNSET) 0L else positionMs)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        activeSource?.pause()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val MEDIA_ITEM_UID = "active"
    }
}
