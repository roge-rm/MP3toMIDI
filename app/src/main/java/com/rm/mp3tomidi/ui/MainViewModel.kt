package com.rm.mp3tomidi.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.rm.mp3tomidi.audio.SoundEngine
import com.rm.mp3tomidi.convert.AnalysisWorker
import com.rm.mp3tomidi.convert.ConversionOptions
import com.rm.mp3tomidi.convert.IntermediateResult
import com.rm.mp3tomidi.convert.IntermediateResultStore
import com.rm.mp3tomidi.convert.OutputMode
import com.rm.mp3tomidi.convert.ReviewSelections
import com.rm.mp3tomidi.convert.WriteWorker
import com.rm.mp3tomidi.player.MidiPlayer
import com.rm.mp3tomidi.player.Mp3Player
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val player = Mp3Player(application)
    val soundEngine = SoundEngine(application)
    val midiPlayer = MidiPlayer(soundEngine)

    private val workManager = WorkManager.getInstance(application)

    private val _inputUri = MutableStateFlow<Uri?>(null)
    val inputUri: StateFlow<Uri?> = _inputUri

    private val _outputUri = MutableStateFlow<Uri?>(null)
    val outputUri: StateFlow<Uri?> = _outputUri

    private val _outputDirUri = MutableStateFlow<Uri?>(null)
    val outputDirUri: StateFlow<Uri?> = _outputDirUri

    private val _conversionOptions = MutableStateFlow(ConversionOptions())
    val conversionOptions: StateFlow<ConversionOptions> = _conversionOptions

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

    private val _analysisWorkId = MutableStateFlow<UUID?>(null)
    private val _writeWorkId = MutableStateFlow<UUID?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val analysisWorkInfo: StateFlow<WorkInfo?> = _analysisWorkId
        .flatMapLatest { id -> if (id == null) flowOf(null) else workManager.getWorkInfoByIdFlow(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val writeWorkInfo: StateFlow<WorkInfo?> = _writeWorkId
        .flatMapLatest { id -> if (id == null) flowOf(null) else workManager.getWorkInfoByIdFlow(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _intermediateResult = MutableStateFlow<IntermediateResult?>(null)
    val intermediateResult: StateFlow<IntermediateResult?> = _intermediateResult

    // Tracks the analysis work id ReviewDialog has already been shown/confirmed for, so it
    // doesn't reappear once WriteWorker (which also eventually reaches SUCCEEDED) is enqueued.
    private val _reviewedAnalysisWorkId = MutableStateFlow<UUID?>(null)
    val reviewPending: StateFlow<Boolean> = combineReviewPending()

    private fun combineReviewPending(): StateFlow<Boolean> {
        return combine(analysisWorkInfo, _reviewedAnalysisWorkId) { info, reviewedId ->
            info?.state == WorkInfo.State.SUCCEEDED &&
                info.outputData.getString(AnalysisWorker.KEY_INTERMEDIATE_PATH) != null &&
                info.id != reviewedId
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    }

    init {
        // _analysisWorkId/_writeWorkId only ever get set by startAnalysis()/startWrite() below,
        // which means a *fresh* ViewModel (e.g. after the system killed the Activity/process
        // while a conversion ran in the background, and the user tapped the ongoing-conversion
        // notification to come back) has no way to know one is already running -- WorkManager
        // itself keeps tracking the work regardless, but this ViewModel's own ids have no memory
        // of it. Reconnect by tag instead of relying on ever having seen the UUID before; the two
        // phases use distinct tags so this can tell which one is actually running.
        viewModelScope.launch {
            val existingAnalysis = workManager.getWorkInfosByTagFlow(AnalysisWorker.WORK_TAG).first()
                .firstOrNull { !it.state.isFinished }
            if (existingAnalysis != null) {
                _analysisWorkId.value = existingAnalysis.id
            }

            val existingWrite = workManager.getWorkInfosByTagFlow(WriteWorker.WORK_TAG).first()
                .firstOrNull { !it.state.isFinished }
            if (existingWrite != null) {
                _writeWorkId.value = existingWrite.id
            }
        }

        // Restores SOURCE/DESTINATION display from AnalysisWorker's echoed input/output URIs --
        // needed after a reconnect above, where this is a fresh ViewModel with no other memory of
        // what the user picked. Reactive rather than a one-shot read: a freshly-restarted worker
        // (after process death interrupted it) hasn't necessarily posted its first setProgress()
        // yet at the moment _analysisWorkId is set above, so grabbing a single snapshot here can
        // race and silently miss it -- verified on-device, an early one-shot read left SOURCE/
        // DESTINATION blank even though the reconnect itself had succeeded. Progress carries these
        // while the worker runs; outputData carries the same values once it reaches SUCCEEDED.
        viewModelScope.launch {
            analysisWorkInfo.collect { info ->
                val data = info?.progress?.takeIf { it.keyValueMap.isNotEmpty() } ?: info?.outputData
                data?.getString(AnalysisWorker.KEY_INPUT_URI)?.let { _inputUri.value = Uri.parse(it) }
                data?.getString(AnalysisWorker.KEY_OUTPUT_URI)?.let { _outputUri.value = Uri.parse(it) }
                data?.getString(AnalysisWorker.KEY_OUTPUT_DIR_URI)?.let { _outputDirUri.value = Uri.parse(it) }
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
        _outputDirUri.value = null
    }

    fun setOutputUri(uri: Uri) {
        _outputUri.value = uri
    }

    fun setOutputDirUri(uri: Uri) {
        _outputDirUri.value = uri
    }

    fun setConversionOptions(options: ConversionOptions) {
        _conversionOptions.value = options
    }

    fun startAnalysis() {
        val input = _inputUri.value ?: return
        val request = AnalysisWorker.buildRequest(input, _outputUri.value, _outputDirUri.value, _conversionOptions.value)
        _analysisWorkId.value = request.id
        _reviewedAnalysisWorkId.value = null
        workManager.enqueue(request)
    }

    /** Loads the cached [IntermediateResult] once [analysisWorkInfo] succeeds, for [ReviewDialog]
     * to display. */
    fun loadIntermediateResult(path: String) {
        viewModelScope.launch {
            _intermediateResult.value = withContext(Dispatchers.IO) { IntermediateResultStore.read(File(path)) }
        }
    }

    /** Dismissing the review step with nothing written yet -- there's no partial output to keep,
     * so this just abandons the cached analysis result rather than proceeding to WriteWorker. */
    fun cancelReview() {
        val analysisInfo = analysisWorkInfo.value ?: return
        _reviewedAnalysisWorkId.value = analysisInfo.id
        analysisInfo.outputData.getString(AnalysisWorker.KEY_INTERMEDIATE_PATH)?.let { File(it).delete() }
        _intermediateResult.value = null
    }

    fun startWrite(selections: ReviewSelections) {
        val analysisInfo = analysisWorkInfo.value ?: return
        val intermediatePath = analysisInfo.outputData.getString(AnalysisWorker.KEY_INTERMEDIATE_PATH) ?: return
        val options = _conversionOptions.value

        _reviewedAnalysisWorkId.value = analysisInfo.id
        val request = WriteWorker.buildRequest(
            intermediatePath = intermediatePath,
            outputMode = options.outputMode,
            outputUri = if (options.outputMode == OutputMode.SEPARATE_FILES) null else _outputUri.value,
            outputDirUri = if (options.outputMode == OutputMode.SEPARATE_FILES) _outputDirUri.value else null,
            selections = selections,
        )
        _writeWorkId.value = request.id
        workManager.enqueue(request)
    }

    /**
     * This alone doesn't stop anything running or delete temp files -- it just flips
     * [androidx.work.ListenableWorker.isStopped] to true. `AnalysisWorker`/`WriteWorker` thread
     * that flag down through `ConversionPipeline`/`DemucsStemSeparator`/`ModelProvider` as an
     * explicit `isCancelled` check polled between chunks; when one of them sees it, it throws and
     * its own try/catch cleans up its temp files. Plain coroutine cancellation was tried first and
     * verified on-device to *not* reliably stop a running conversion -- see DemucsStemSeparator's
     * doc.
     */
    fun cancelConversion() {
        _writeWorkId.value?.let { workManager.cancelWorkById(it) }
        _analysisWorkId.value?.let { workManager.cancelWorkById(it) }
    }

    override fun onCleared() {
        player.release()
        midiPlayer.release()
        soundEngine.release()
    }
}
