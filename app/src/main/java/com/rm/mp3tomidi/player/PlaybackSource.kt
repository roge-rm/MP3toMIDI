package com.rm.mp3tomidi.player

import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal control surface both [Mp3Player] and [MidiPlayer] expose, so a single facade
 * `androidx.media3.common.SimpleBasePlayer` (see `PlaybackFacadePlayer`) can drive one system
 * media notification for whichever of the two is currently active, without needing either player
 * to know anything about media3 itself.
 */
interface PlaybackSource {
    val title: String
    val isPlayingFlow: StateFlow<Boolean>
    fun positionMs(): Long
    fun durationMs(): Long
    fun play()
    fun pause()
    fun seekTo(ms: Long)
}
