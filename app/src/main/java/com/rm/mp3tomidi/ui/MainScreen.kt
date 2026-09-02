package com.rm.mp3tomidi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import com.rm.mp3tomidi.R
import com.rm.mp3tomidi.convert.ConversionWorker
import com.rm.mp3tomidi.util.displayNameOf

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val inputUri by viewModel.inputUri.collectAsState()
    val outputUri by viewModel.outputUri.collectAsState()
    val workInfo by viewModel.workInfo.collectAsState()
    val isPlaying by viewModel.player.isPlaying.collectAsState()

    var outputFileName by rememberSaveable { mutableStateOf("output.mid") }
    val inputFileName = remember(inputUri) { inputUri?.let { displayNameOf(context, it) } }

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

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)

            HorizontalDivider()

            Button(onClick = { openInputLauncher.launch(arrayOf("audio/*")) }) {
                Text(stringResource(R.string.select_mp3))
            }
            Text(inputFileName ?: stringResource(R.string.no_file_selected))
            Button(
                onClick = { inputUri?.let { viewModel.player.togglePlayback(it) } },
                enabled = inputUri != null,
            ) {
                Text(stringResource(if (isPlaying) R.string.pause else R.string.play))
            }

            HorizontalDivider()

            OutlinedTextField(
                value = outputFileName,
                onValueChange = { outputFileName = it },
                label = { Text(stringResource(R.string.output_file_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { createOutputLauncher.launch(outputFileName) }) {
                Text(stringResource(R.string.choose_output))
            }

            HorizontalDivider()

            Button(
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
                enabled = inputUri != null && outputUri != null && !isRunning,
            ) {
                Text(stringResource(R.string.convert))
            }

            val progress = workInfo?.progress
            val stage = progress?.getString(ConversionWorker.KEY_PROGRESS_STAGE)
            val fraction = progress?.getFloat(ConversionWorker.KEY_PROGRESS_FRACTION, 0f) ?: 0f

            when (workInfo?.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                    Text(stage ?: "")
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                WorkInfo.State.SUCCEEDED -> Text("Conversion complete")
                WorkInfo.State.FAILED -> Text("Conversion failed")
                else -> {}
            }
        }
    }
}
