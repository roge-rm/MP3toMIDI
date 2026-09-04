package com.rm.mp3tomidi.convert

import com.rm.mp3tomidi.convert.stages.NoteEvent
import com.rm.mp3tomidi.convert.stages.Stem
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Hand-rolled binary read/write for [IntermediateResult] -- this project has no JSON/serialization
 * library (see [com.rm.mp3tomidi.midi.MidiFileWriter]/[com.rm.mp3tomidi.util.PcmUtils] for the same
 * DataOutputStream/DataInputStream convention already used for other private, non-portable
 * artifacts), and adding one just for a cache file that only needs to survive from [AnalysisWorker]
 * succeeding to [WriteWorker] running would be overkill.
 */
object IntermediateResultStore {

    fun write(file: File, result: IntermediateResult) {
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            out.writeInt(result.bpm)
            writeOptions(out, result.options)
            out.writeInt(result.stems.size)
            result.stems.forEach { writeStem(out, it) }
        }
    }

    fun read(file: File): IntermediateResult {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            val bpm = input.readInt()
            val options = readOptions(input)
            val stemCount = input.readInt()
            val stems = List(stemCount) { readStem(input) }
            return IntermediateResult(stems, bpm, options)
        }
    }

    private fun writeOptions(out: DataOutputStream, options: ConversionOptions) {
        out.writeInt(options.includedStemLabels.size)
        options.includedStemLabels.forEach { out.writeUTF(it) }
        out.writeFloat(options.noteFrameThreshold)
        out.writeFloat(options.silentStemRmsRatio)
        out.writeUTF(options.outputMode.name)
    }

    private fun readOptions(input: DataInputStream): ConversionOptions {
        val labelCount = input.readInt()
        val labels = (0 until labelCount).map { input.readUTF() }.toSet()
        val frameThreshold = input.readFloat()
        val silentRatio = input.readFloat()
        val outputMode = OutputMode.valueOf(input.readUTF())
        return ConversionOptions(labels, frameThreshold, silentRatio, outputMode)
    }

    private fun writeStem(out: DataOutputStream, stem: Stem) {
        out.writeUTF(stem.label)
        out.writeInt(stem.gmProgram)
        out.writeBoolean(stem.isDrumKit)
        out.writeFloat(stem.confidence)
        out.writeInt(stem.notes.size)
        stem.notes.forEach { note ->
            out.writeLong(note.startTick)
            out.writeLong(note.endTick)
            out.writeInt(note.pitch)
            out.writeInt(note.velocity)
        }
    }

    private fun readStem(input: DataInputStream): Stem {
        val label = input.readUTF()
        val gmProgram = input.readInt()
        val isDrumKit = input.readBoolean()
        val confidence = input.readFloat()
        val noteCount = input.readInt()
        val notes = List(noteCount) {
            NoteEvent(
                startTick = input.readLong(),
                endTick = input.readLong(),
                pitch = input.readInt(),
                velocity = input.readInt(),
            )
        }
        return Stem(label, gmProgram, isDrumKit, notes, confidence)
    }
}
