package com.rm.mp3tomidi.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import com.rm.mp3tomidi.R
import com.rm.mp3tomidi.convert.ConversionWorker
import com.rm.mp3tomidi.ui.theme.BrandNavy
import com.rm.mp3tomidi.ui.theme.BrandPink
import com.rm.mp3tomidi.ui.theme.BrandTeal
import com.rm.mp3tomidi.ui.theme.DangerRed
import com.rm.mp3tomidi.ui.theme.OutlineLavender
import com.rm.mp3tomidi.util.displayNameOf

// Explicit list rather than the "audio/*" wildcard -- that wildcard also matches audio/midi, and
// a .mid file picked as input doesn't fail cleanly: Android's built-in MIDI softsynth renders it
// to generic-sounding PCM, which the pipeline then happily separates/transcribes/reclassifies as
// if it were a real song, producing a nonsensical, degraded MIDI reconstruction instead of an
// error. Covers common lossy and lossless formats Android's MediaExtractor/MediaCodec can decode.
private val SUPPORTED_INPUT_MIME_TYPES = arrayOf(
    "audio/mpeg",
    "audio/mp4",
    "audio/aac",
    "audio/ogg",
    "audio/opus",
    "audio/flac",
    "audio/x-flac",
    "audio/wav",
    "audio/x-wav",
)

@Composable
fun MainScreen(viewModel: MainViewModel, onSwitchScreen: () -> Unit) {
    val context = LocalContext.current
    val inputUri by viewModel.inputUri.collectAsState()
    val outputUri by viewModel.outputUri.collectAsState()
    val workInfo by viewModel.workInfo.collectAsState()
    val isPlaying by viewModel.player.isPlaying.collectAsState()

    var outputFileName by rememberSaveable { mutableStateOf("output.mid") }
    val inputFileName = remember(inputUri) { inputUri?.let { displayNameOf(context, it) } }
    var showCancelConfirmation by remember { mutableStateOf(false) }

    // The gradient header sits edge-to-edge behind the status bar (see enableEdgeToEdge in
    // MainActivity), so its icons need to stay light/white rather than following the system's
    // light/dark-background default.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Conversion still runs without this; it just won't show a progress notification. */ }

    val openInputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewModel.setInputUri(uri)
            displayNameOf(context, uri)?.let { name ->
                outputFileName = name.substringBeforeLast('.') + ".mid"
            }
        }
    }

    val createOutputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/midi"),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.setOutputUri(uri)
        }
    }

    val isRunning = workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED

    // Insets are handled by hand below (status bar padding inside AppHeader so the gradient
    // itself still extends behind it, navigation bar padding at the bottom of the scrolling
    // content) -- Scaffold's own default inset reservation would otherwise double up with that.
    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0.dp)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            AppHeader(
                onSwitchScreen = onSwitchScreen,
                switchIcon = "♪", // musical note -- "go listen to a MIDI file"
                switchContentDescription = stringResource(R.string.switch_to_play_screen),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 20.dp, bottom = 32.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionCard(title = "Source") {
                    OutlinedActionButton(
                        text = stringResource(R.string.choose_input),
                        icon = Icons.Filled.LibraryMusic,
                        onClick = { openInputLauncher.launch(SUPPORTED_INPUT_MIME_TYPES) },
                    )
                    Text(
                        text = stringResource(R.string.input_formats_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FileNameChip(inputFileName ?: stringResource(R.string.no_file_selected))
                    OutlinedActionButton(
                        text = stringResource(if (isPlaying) R.string.pause else R.string.play),
                        icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        enabled = inputUri != null,
                        onClick = { inputUri?.let { viewModel.player.togglePlayback(it) } },
                    )
                }

                SectionCard(title = "Destination") {
                    OutlinedTextField(
                        value = outputFileName,
                        onValueChange = { outputFileName = it },
                        label = { Text(stringResource(R.string.output_file_name)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandNavy,
                            focusedLabelColor = BrandNavy,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedActionButton(
                        text = stringResource(R.string.choose_output),
                        icon = Icons.Filled.FileDownload,
                        onClick = { createOutputLauncher.launch(outputFileName) },
                    )
                }

                if (isRunning) {
                    SolidActionButton(
                        text = stringResource(R.string.cancel_conversion),
                        icon = Icons.Filled.Cancel,
                        color = DangerRed,
                        onClick = { showCancelConfirmation = true },
                    )
                } else {
                    GradientButton(
                        text = stringResource(R.string.convert),
                        icon = Icons.Filled.Piano,
                        enabled = inputUri != null && outputUri != null,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            viewModel.startConversion()
                        },
                    )
                }

                if (showCancelConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showCancelConfirmation = false },
                        title = { Text(stringResource(R.string.cancel_conversion_title)) },
                        text = { Text(stringResource(R.string.cancel_conversion_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showCancelConfirmation = false
                                viewModel.cancelConversion()
                            }) {
                                Text(stringResource(R.string.cancel_conversion), color = DangerRed)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCancelConfirmation = false }) {
                                Text(stringResource(R.string.keep_converting))
                            }
                        },
                    )
                }

                val progress = workInfo?.progress
                val stage = progress?.getString(ConversionWorker.KEY_PROGRESS_STAGE)
                val fraction = progress?.getFloat(ConversionWorker.KEY_PROGRESS_FRACTION, 0f) ?: 0f

                when (workInfo?.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        SectionCard(title = "Converting") {
                            Text(stage ?: "", style = MaterialTheme.typography.bodyLarge)
                            LinearProgressIndicator(
                                progress = { fraction },
                                color = BrandTeal,
                                trackColor = OutlineLavender,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(50)),
                            )
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> ResultBanner(
                        text = "Conversion complete",
                        icon = Icons.Filled.CheckCircle,
                        color = BrandTeal,
                    )
                    WorkInfo.State.FAILED -> ResultBanner(
                        text = "Conversion failed",
                        icon = Icons.Filled.Error,
                        color = BrandPink,
                    )
                    else -> {}
                }
            }
        }
    }
}

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = BrandNavy.copy(alpha = 0.6f),
            )
            content()
        }
    }
}

@Composable
internal fun FileNameChip(name: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = BrandNavy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun OutlinedActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Box(modifier = Modifier.padding(start = 8.dp)) { Text(text, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun GradientButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background = if (enabled) Modifier.background(HeaderGradient) else Modifier.background(OutlineLavender)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Color.White)
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun SolidActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(color)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Color.White)
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun ResultBanner(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(color.copy(alpha = 0.15f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(
            text = text,
            color = BrandNavy,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
