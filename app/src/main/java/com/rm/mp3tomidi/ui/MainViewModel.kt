package com.rm.mp3tomidi.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.rm.mp3tomidi.audio.SoundEngine
import com.rm.mp3tomidi.convert.ConversionWorker
import com.rm.mp3tomidi.player.MidiPlayer
import com.rm.mp3tomidi.player.Mp3Player
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val player = Mp3Player(application)
    val soundEngine = SoundEngine(application)
    val midiPlayer = MidiPlayer(soundEngine)

    private val workManager = WorkManager.getInstance(application)

    private val _inputUri = MutableStateFlow<Uri?>(null)
    val inputUri: StateFlow<Uri?> = _inputUri

    private val _outputUri = MutableStateFlow<Uri?>(null)
    val outputUri: StateFlow<Uri?> = _outputUri

    // Owned here rather than as PlayScreen-local remember state, same reasoning as inputUri/
    // outputUri above: PlayScreen's composable is fully torn down and recreated every time the
    // header toggle switches away and back (MainActivity's `when (screen)` swaps to a completely
    // different composable, it doesn't just hide this one) -- verified on-device that local
    // remember state loses the selected file on a screen switch, while midiPlayer's own
    // ViewModel-owned position/duration survive it, an inconsistency a user would notice
    // immediately as "my file disappeared but playback position didn't".
    private val _midiUri = MutableStateFlow<Uri?>(null)
    val midiUri: StateFlow<Uri?> = _midiUri

    fun setMidiUri(uri: Uri) {
        _midiUri.value = uri
    }

    private val _workId = MutableStateFlow<UUID?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val workInfo: StateFlow<WorkInfo?> = _workId
        .flatMapLatest { id -> if (id == null) flowOf(null) else workManager.getWorkInfoByIdFlow(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // _workId only ever gets set by startConversion() below, which means a *fresh* ViewModel
        // (e.g. after the system killed the Activity/process while a conversion ran in the
        // background, and the user tapped the ongoing-conversion notification to come back) has
        // no way to know one is already running -- WorkManager itself keeps tracking the work
        // regardless, but this ViewModel's own _workId has no memory of it. Reconnect by tag
        // (see ConversionWorker.WORK_TAG) instead of relying on ever having seen the UUID before.
        viewModelScope.launch {
            val existing = workManager.getWorkInfosByTagFlow(ConversionWorker.WORK_TAG).first()
                .firstOrNull { !it.state.isFinished }
            if (existing != null) {
                _workId.value = existing.id
                // WorkInfo doesn't expose the original input Data the work was enqueued with, only
                // progress/output Data set from inside doWork() -- ConversionWorker echoes the
                // URIs back through setProgress() specifically so this can restore the
                // SOURCE/DESTINATION display, not just the progress bar. Can be empty if reconnect
                // happens before the worker's first progress update; the fields just stay unset in
                // that narrow window, same as before this existed.
                existing.progress.getString(ConversionWorker.KEY_INPUT_URI)?.let { _inputUri.value = Uri.parse(it) }
                existing.progress.getString(ConversionWorker.KEY_OUTPUT_URI)?.let { _outputUri.value = Uri.parse(it) }
            }
        }
    }

    fun setInputUri(uri: Uri) {
        _inputUri.value = uri
        // The displayed output filename suggestion updates for the new input (see MainScreen),
        // but that's just text -- without also clearing the actual write target here, it and the
        // stale outputUri from a previous conversion silently diverge. Convert stays enabled
        // (outputUri is still non-null) and would silently overwrite that previous file with the
        // new conversion's output, with nothing on screen to indicate the two ever disagreed.
        _outputUri.value = null
    }

    fun setOutputUri(uri: Uri) {
        _outputUri.value = uri
    }

    fun startConversion() {
        val input = _inputUri.value ?: return
        val output = _outputUri.value ?: return
        val request = ConversionWorker.buildRequest(input, output)
        _workId.value = request.id
        workManager.enqueue(request)
    }

    /**
     * This alone doesn't stop anything running or delete temp files -- it just flips
     * [androidx.work.ListenableWorker.isStopped] to true. `ConversionWorker` threads that flag
     * down through `ConversionPipeline`/`DemucsStemSeparator`/`ModelProvider` as an explicit
     * `isCancelled` check polled between chunks; when one of them sees it, it throws and its own
     * try/catch cleans up its temp files. Plain coroutine cancellation was tried first and verified
     * on-device to *not* reliably stop a running conversion -- see DemucsStemSeparator's doc.
     */
    fun cancelConversion() {
        _workId.value?.let { workManager.cancelWorkById(it) }
    }

    override fun onCleared() {
        player.release()
        midiPlayer.release()
        soundEngine.release()
    }
}
