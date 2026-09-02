# Basic Pitch export

Spotify's Basic Pitch (ICASSP 2022 model) does polyphonic note transcription -- pitch, onset,
offset -- for `BasicPitchTranscriber`. Unlike Demucs, the `basic-pitch` PyPI package already
ships a pre-converted ONNX file, so there's no export/conversion step, just extraction.

The exported file is tiny (~230KB, vs. Demucs' 235MB), so it's committed and bundled directly
in `app/src/main/assets/models/basic_pitch_icassp_2022.onnx` rather than downloaded on first
use -- see `ModelProvider`/`DemucsStemSeparator` for that pattern, used for the much larger
Demucs model instead.

## Usage

```bash
uv venv --python 3.11 venv
uv pip install -p venv/bin/python basic-pitch onnx onnxruntime
uv pip install -p venv/bin/python "setuptools<81"  # resampy imports pkg_resources, removed in 81+

venv/bin/python export.py   # -> basic_pitch_icassp_2022.onnx
venv/bin/python verify.py   # confirms output shapes and the [onset, note, contour] output order
```

Then copy it into the app (already committed there for the current version):

```bash
cp basic_pitch_icassp_2022.onnx ../../app/src/main/assets/models/basic_pitch_icassp_2022.onnx
```

`generate_decoder_fixture.py` regenerates the ground-truth values in
`BasicPitchNoteDecoderTest.kt` by running the real
`basic_pitch.note_creation.output_to_notes_polyphonic` / `get_infered_onsets` /
`model_frames_to_time` on a small synthetic input -- run it again (and update the test) if this
model or basic-pitch's decoding algorithm ever changes.

## What's ported vs. not

`BasicPitchNoteDecoder.kt` ports the onset/frame-activation-matrix -> note-event algorithm
(`get_infered_onsets` + `output_to_notes_polyphonic` + `model_frames_to_time`), verified against
the real Python output rather than derived by hand. Deliberately **not** ported:

- **The melodia trick** (a second pass that grows notes from leftover energy that never had a
  clear onset). A real quality feature of upstream Basic Pitch; left out to keep the initial
  port's scope manageable.
- **Pitch bends** (contour-based micro-tuning). Our MIDI writer has no representation for them,
  and they don't fit the single-pitch-per-note `NoteEvent` model this app uses anyway.

## Model facts baked into the Android-side code

From `basic_pitch/constants.py`, hardcoded as constants in `BasicPitchTranscriber` and
`BasicPitchNoteDecoder` since the exported graph has a static input shape:

- sample rate: `22050` Hz, mono
- input window: `43844` samples (~2s), hop `256`, 30-frame (7680-sample) overlap between windows
- output: 3 tensors per window, each covering 172 frames x 88 note bins (A0-C8, MIDI 21-108) --
  `onset` = `StatefulPartitionedCall:2`, `note`/frame = `StatefulPartitionedCall:1`, `contour`
  (unused, see above) = `StatefulPartitionedCall:0`. This ordering comes from
  `basic_pitch/inference.py`'s name mapping, not the ONNX graph itself -- `verify.py` checks it
  explicitly since nothing about the graph would catch a silent reordering in a future version.
- default thresholds: onset 0.5, frame 0.3, minimum note length 11 frames (~128ms)
