package com.rm.mp3tomidi.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.rm.mp3tomidi.audio.SoundEngine
import com.rm.mp3tomidi.midi.MidiFileParser
import com.rm.mp3tomidi.midi.TimedEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parses a Standard MIDI File and plays it back through [SoundEngine] in real time. Same
 * "constructed once, owned by MainViewModel, Compose collects its StateFlows directly" shape as
 * [Mp3Player], extended with position/duration/seek -- which that class has no precedent for at
 * all, since previewing a source MP3 never needed scrubbing.
 *
 * Playback timing follows the same idea roge-rm/midiTracker's MIDI player uses (re-anchor a
 * wall-clock-to-position origin on every (re)start rather than accumulating per-tick, so drift
 * never compounds), simplified for the fact the whole event list already lives in memory: no
 * incremental per-track "priming" is needed, just a single cursor into one flat, pre-sorted list.
 */
class MidiPlayer(private val soundEngine: SoundEngine) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null

    private var events: List<TimedEvent> = emptyList()
    private val channelProgram = IntArray(16) // GM default: program 0 (Acoustic Grand Piano) until a Program Change says otherwise
    private val activeNotes = mutableSetOf<Pair<Int, Int>>() // (channel, note) currently sounding
    private var cursor = 0 // index into `events` of the next not-yet-dispatched event
    private var positionAtStartMs = 0L // playback position as of the last (re)anchor -- see startPlaybackLoop()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    suspend fun load(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        playbackJob?.cancel()
        silenceAll()
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext false
            val parsed = MidiFileParser.parse(bytes)
            events = parsed.events
            channelProgram.fill(0)
            cursor = 0
            positionAtStartMs = 0
            _isPlaying.value = false
            _positionMs.value = 0
            _durationMs.value = parsed.durationMs
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load MIDI file from $uri", e)
            false
        }
    }

    fun togglePlayback() {
        if (_isPlaying.value) pauseInternal() else startPlaybackLoop()
    }

    fun seekTo(targetMs: Long) {
        if (events.isEmpty()) return
        silenceAll()

        // Rebuild per-channel program state and find the new cursor by replaying from the start
        // -- simplest correct approach given the whole list already lives in memory (no need for
        // the streaming "forward is cheap, backward re-scans" distinction a design like
        // midiTracker's needs). Note On/Off are deliberately not re-sent, only their effect on
        // channelProgram accumulates, so seeking doesn't re-trigger everything that already fired.
        channelProgram.fill(0)
        var newCursor = events.size
        for ((index, event) in events.withIndex()) {
            if (event.timestampMs > targetMs) {
                newCursor = index
                break
            }
            if (event.type == TimedEvent.Type.PROGRAM_CHANGE) channelProgram[event.channel] = event.data1
        }
        cursor = newCursor
        positionAtStartMs = targetMs.coerceIn(0, _durationMs.value)
        _positionMs.value = positionAtStartMs

        if (_isPlaying.value) startPlaybackLoop() // re-anchor timing at the new position, keep playing
    }

    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        if (events.isEmpty()) return
        _isPlaying.value = true
        val startWallNanos = System.nanoTime()
        val startPositionMs = positionAtStartMs
        playbackJob = scope.launch {
            while (isActive) {
                if (cursor >= events.size) {
                    silenceAll()
                    _isPlaying.value = false
                    _positionMs.value = _durationMs.value
                    positionAtStartMs = 0
                    cursor = 0 // ready to play from the start again next time
                    break
                }

                val elapsedMs = (System.nanoTime() - startWallNanos) / 1_000_000
                val nowMs = startPositionMs + elapsedMs
                _positionMs.value = nowMs.coerceAtMost(_durationMs.value)

                while (cursor < events.size && events[cursor].timestampMs <= nowMs) {
                    dispatch(events[cursor])
                    cursor++
                }

                // Capped even when the next event is far away, so the position readout (and thus
                // the UI's seek bar) stays reasonably live rather than jumping in big steps.
                val waitMs = if (cursor < events.size) (events[cursor].timestampMs - nowMs) else 50L
                delay(waitMs.coerceIn(1L, 50L))
            }
        }
    }

    private fun pauseInternal() {
        playbackJob?.cancel()
        playbackJob = null
        _isPlaying.value = false
        positionAtStartMs = _positionMs.value
        silenceAll()
    }

    private fun dispatch(event: TimedEvent) {
        when (event.type) {
            TimedEvent.Type.PROGRAM_CHANGE -> channelProgram[event.channel] = event.data1
            TimedEvent.Type.NOTE_ON -> {
                soundEngine.noteOn(bankFor(event.channel), channelProgram[event.channel], event.data1, event.data2 / 127f)
                activeNotes += event.channel to event.data1
            }
            TimedEvent.Type.NOTE_OFF -> {
                soundEngine.noteOff(bankFor(event.channel), channelProgram[event.channel], event.data1)
                activeNotes -= event.channel to event.data1
            }
        }
    }

    // TinySoundFont's simple note-on/off API (see SoundEngine) has no single "all notes off"
    // call, so anything left sounding has to be silenced by name -- tracked here rather than
    // relying on ScaleInKey's per-trigger release-timer approach, which only fits a single
    // fire-and-forget preview, not open-ended playback that can pause/seek at any moment.
    private fun silenceAll() {
        for ((channel, note) in activeNotes.toList()) {
            soundEngine.noteOff(bankFor(channel), channelProgram[channel], note)
        }
        activeNotes.clear()
    }

    private fun bankFor(channel: Int): Int =
        if (channel == SoundEngine.PERCUSSION_CHANNEL) SoundEngine.PERCUSSION_BANK else SoundEngine.MELODIC_BANK

    fun release() {
        playbackJob?.cancel()
        silenceAll()
    }

    private companion object {
        const val TAG = "MidiPlayer"
    }
}
