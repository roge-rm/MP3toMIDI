package com.rm.mp3tomidi.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.rm.mp3tomidi.convert.ConversionWorker
import com.rm.mp3tomidi.player.Mp3Player
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val player = Mp3Player(application)

    private val workManager = WorkManager.getInstance(application)

    private val _inputUri = MutableStateFlow<Uri?>(null)
    val inputUri: StateFlow<Uri?> = _inputUri

    private val _outputUri = MutableStateFlow<Uri?>(null)
    val outputUri: StateFlow<Uri?> = _outputUri

    private val _workId = MutableStateFlow<UUID?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val workInfo: StateFlow<WorkInfo?> = _workId
        .flatMapLatest { id -> if (id == null) flowOf(null) else workManager.getWorkInfoByIdFlow(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    }
}
