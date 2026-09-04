package com.rm.mp3tomidi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rm.mp3tomidi.convert.ConversionOptions
import com.rm.mp3tomidi.convert.OutputMode
import com.rm.mp3tomidi.convert.stages.DemucsStemSeparator

private val STEM_DISPLAY_NAMES = mapOf(
    "drums" to "Drums",
    "bass" to "Bass",
    "other" to "Other/Synth",
    "vocals" to "Vocals",
    "guitar" to "Guitar",
    "piano" to "Piano",
)

private val OUTPUT_MODE_LABELS = mapOf(
    OutputMode.SINGLE_MERGED to "Single file, merged",
    OutputMode.SINGLE_MULTI_TRACK to "Single file, one track per instrument",
    OutputMode.SEPARATE_FILES to "Separate file per stem",
)

@Composable
fun ConversionOptionsDialog(
    options: ConversionOptions,
    onOptionsChange: (ConversionOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(options) { mutableStateOf(options) }

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(shape = MaterialTheme.shapes.medium) {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
                    .then(Modifier),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionCard(title = "Stems to process") {
                    DemucsStemSeparator.SOURCES.forEach { label ->
                        Row {
                            Checkbox(
                                checked = label in draft.includedStemLabels,
                                onCheckedChange = { checked ->
                                    draft = draft.copy(
                                        includedStemLabels = if (checked) {
                                            draft.includedStemLabels + label
                                        } else {
                                            draft.includedStemLabels - label
                                        },
                                    )
                                },
                            )
                            Text(
                                text = STEM_DISPLAY_NAMES[label] ?: label,
                                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                            )
                        }
                    }
                }

                SectionCard(title = "Sensitivity") {
                    Text("Note sensitivity", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Lower catches more/quieter notes; higher keeps only clearly-sustained ones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = draft.noteFrameThreshold,
                        onValueChange = { draft = draft.copy(noteFrameThreshold = it) },
                        valueRange = 0.1f..0.6f,
                    )

                    Text("Silent stem cutoff", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "How quiet a stem must be, relative to the loudest, to be dropped as noise.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = draft.silentStemRmsRatio,
                        onValueChange = { draft = draft.copy(silentStemRmsRatio = it) },
                        valueRange = 0f..0.15f,
                    )

                    TextButton(onClick = {
                        draft = draft.copy(
                            noteFrameThreshold = ConversionOptions.DEFAULT_NOTE_FRAME_THRESHOLD,
                            silentStemRmsRatio = ConversionOptions.DEFAULT_SILENT_STEM_RMS_RATIO,
                        )
                    }) {
                        Text("Reset sensitivity to default")
                    }
                }

                SectionCard(title = "Output") {
                    OutputMode.entries.forEach { mode ->
                        Row {
                            RadioButton(
                                selected = draft.outputMode == mode,
                                onClick = { draft = draft.copy(outputMode = mode) },
                            )
                            Text(
                                text = OUTPUT_MODE_LABELS.getValue(mode),
                                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = {
                        onOptionsChange(draft)
                        onDismiss()
                    }) { Text("Done") }
                }
            }
        }
    }
}
