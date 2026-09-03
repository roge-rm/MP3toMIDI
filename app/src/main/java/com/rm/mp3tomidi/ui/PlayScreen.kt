package com.rm.mp3tomidi.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.rm.mp3tomidi.R
import com.rm.mp3tomidi.ui.theme.BrandTeal
import com.rm.mp3tomidi.util.displayNameOf
import kotlinx.coroutines.launch

// "audio/midi"/"audio/x-midi" worked on the emulator this was built against, but a real device
// reported back grays out genuine .mid files under that filter while letting unrelated files
// through (a user found real .mid files disabled while a different app's *.mid.rtx recordings
// were selectable) -- proof MIME-type-from-extension mapping for .mid is not consistent across
// real devices/OEM skins, the same class of problem SF2 already had below, just discovered the
// opposite way (there, no device recognizes the type at all; here, some devices apparently
// mis-map it). Unfiltered is the only mapping that can't be wrong on some device.
private val MIDI_MIME_TYPES = arrayOf("*/*")

// SF2 has no MIME type Android's provider framework recognizes, so filtering by type would just
// hide every real .sf2 file rather than show only them -- confirmed on-device, same discipline
// as the earlier .mid-exclusion fix on the conversion input picker (that one narrowed a filter
// that matched too much; this one would need to widen a filter that matches nothing at all).
private val SOUNDFONT_MIME_TYPES = arrayOf("*/*")

@Composable
fun PlayScreen(viewModel: MainViewModel, onSwitchScreen: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isPlaying by viewModel.midiPlayer.isPlaying.collectAsState()
    val positionMs by viewModel.midiPlayer.positionMs.collectAsState()
    val durationMs by viewModel.midiPlayer.durationMs.collectAsState()
    val usingCustomSoundFont by viewModel.soundEngine.usingCustomSoundFont.collectAsState()

    val midiUri by viewModel.midiUri.collectAsState()
    val midiFileName = remember(midiUri) { midiUri?.let { displayNameOf(context, it) } }
    var soundFontStatus by remember { mutableStateOf<String?>(null) }
    var soundFontDownloadProgress by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(Unit) {
        soundFontDownloadProgress = 0f
        viewModel.soundEngine.ensureLoaded { fraction -> soundFontDownloadProgress = fraction }
        soundFontDownloadProgress = null
    }

    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
    }

    val openMidiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewModel.setMidiUri(uri)
            scope.launch { viewModel.midiPlayer.load(context, uri) }
        }
    }

    val openSoundFontLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            soundFontStatus = null
            viewModel.soundEngine.loadFromUri(uri) { ok ->
                if (!ok) soundFontStatus = context.getString(R.string.soundfont_load_failed)
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0.dp)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            AppHeader(
                onSwitchScreen = onSwitchScreen,
                switchIcon = "↺", // back arrow -- "return to convert"
                switchContentDescription = stringResource(R.string.switch_to_convert_screen),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 20.dp, bottom = 32.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionCard(title = stringResource(R.string.midi_file_section)) {
                    OutlinedActionButton(
                        text = stringResource(R.string.choose_midi_file),
                        icon = Icons.Filled.LibraryMusic,
                        onClick = { openMidiLauncher.launch(MIDI_MIME_TYPES) },
                    )
                    FileNameChip(midiFileName ?: stringResource(R.string.no_midi_file_selected))

                    OutlinedActionButton(
                        text = stringResource(if (isPlaying) R.string.pause else R.string.play),
                        icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        enabled = midiUri != null && durationMs > 0,
                        onClick = { viewModel.midiPlayer.togglePlayback() },
                    )
                    Slider(
                        value = positionMs.toFloat(),
                        valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                        onValueChange = { viewModel.midiPlayer.seekTo(it.toLong()) },
                        enabled = durationMs > 0,
                        colors = SliderDefaults.colors(thumbColor = BrandTeal, activeTrackColor = BrandTeal),
                    )
                    Text(
                        text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionCard(title = stringResource(R.string.soundfont_section)) {
                    val statusText = soundFontDownloadProgress?.let {
                        stringResource(R.string.downloading_default_soundfont, (it * 100).toInt())
                    } ?: soundFontStatus ?: if (usingCustomSoundFont) {
                        stringResource(R.string.soundfont_custom)
                    } else {
                        stringResource(R.string.soundfont_default)
                    }
                    Text(text = statusText, style = MaterialTheme.typography.bodyLarge)

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedActionButton(
                            text = stringResource(R.string.load_custom_soundfont),
                            icon = Icons.Filled.FolderOpen,
                            onClick = { openSoundFontLauncher.launch(SOUNDFONT_MIME_TYPES) },
                        )
                    }
                    if (usingCustomSoundFont) {
                        OutlinedActionButton(
                            text = stringResource(R.string.reset_to_default_soundfont),
                            icon = Icons.Filled.Refresh,
                            onClick = {
                                scope.launch {
                                    soundFontDownloadProgress = 0f
                                    viewModel.soundEngine.resetToDefault({ f -> soundFontDownloadProgress = f }) { }
                                    soundFontDownloadProgress = null
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
