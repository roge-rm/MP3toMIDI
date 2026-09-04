package com.rm.mp3tomidi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rm.mp3tomidi.convert.IntermediateResult
import com.rm.mp3tomidi.convert.ReviewSelections
import com.rm.mp3tomidi.convert.stages.Stem
import com.rm.mp3tomidi.midi.GmInstrument
import kotlin.math.roundToInt

@Composable
fun ReviewDialog(
    result: IntermediateResult,
    onConfirm: (ReviewSelections) -> Unit,
    onDismiss: () -> Unit,
) {
    val excluded = remember(result) { mutableStateOf(emptySet<String>()) }
    val overrides = remember(result) { mutableStateOf(emptyMap<String, Int>()) }
    var bpmText by remember(result) { mutableStateOf(result.bpm.toString()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(modifier = Modifier.heightIn(max = 560.dp)) {
                SectionCard(title = "Detected instruments") {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(result.stems) { stem ->
                            StemReviewRow(
                                stem = stem,
                                included = stem.label !in excluded.value,
                                overrideProgram = overrides.value[stem.label],
                                onIncludedChange = { included ->
                                    excluded.value = if (included) excluded.value - stem.label else excluded.value + stem.label
                                },
                                onProgramOverride = { program ->
                                    overrides.value = overrides.value + (stem.label to program)
                                },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = bpmText,
                        onValueChange = { bpmText = it },
                        label = { Text("Tempo (BPM)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = {
                        val bpmOverride = bpmText.toIntOrNull()?.takeIf { it != result.bpm }
                        onConfirm(
                            ReviewSelections(
                                excludedStemLabels = excluded.value,
                                gmProgramOverrides = overrides.value,
                                bpmOverride = bpmOverride,
                            ),
                        )
                    }) { Text("Write MIDI") }
                }
            }
        }
    }
}

@Composable
private fun StemReviewRow(
    stem: Stem,
    included: Boolean,
    overrideProgram: Int?,
    onIncludedChange: (Boolean) -> Unit,
    onProgramOverride: (Int) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val effectiveProgram = overrideProgram ?: stem.gmProgram

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(checked = included, onCheckedChange = onIncludedChange)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(stem.label.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                if (stem.isDrumKit) {
                    // Reassigning a drum stem to a melodic GM program would silently break
                    // MidiFileWriter's channel-10 assignment, which keys off isDrumKit rather than
                    // gmProgram -- keep the drum-kit override picker out of scope for v1.
                    Text("Standard Drum Kit", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        text = GmInstrument.nameOf(effectiveProgram),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box {
                        Text(
                            "Change instrument",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { menuExpanded = true }
                                .padding(vertical = 4.dp),
                        )
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            GmInstrument.NAMES.forEachIndexed { program, name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        onProgramOverride(program)
                                        menuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                val confidenceText = if (stem.confidence >= 0f) {
                    "Confidence: ${(stem.confidence * 100).roundToInt()}%"
                } else {
                    "Confidence: Default"
                }
                Text(
                    "$confidenceText  ·  ${stem.notes.size} notes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
