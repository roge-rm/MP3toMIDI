# YAMNet ONNX export

Google's [YAMNet](https://www.kaggle.com/models/google/yamnet/tensorFlow2/yamnet/1) (AudioSet,
521-class general audio event classifier, MobileNetV1 backbone, Apache-2.0) is used to pick a
real General MIDI program for each separated stem based on what it actually sounds like
(`TimbreClassifier`), replacing `DemucsSourceClassifier`'s fixed one-program-per-Demucs-label
lookup.

## Why YAMNet instead of an instrument-specific model

No usable pretrained checkpoint trained specifically on an instrument-recognition dataset
(OpenMIC-2018, IRMAS) turned out to be available standalone -- those datasets only show up in
papers using YAMNet/VGGish/OpenL3 embeddings plus a custom classifier head, not as a downloadable
model. YAMNet's 521-class AudioSet ontology already includes the granular instrument classes
needed here (`Electric guitar`, `Acoustic guitar`, `Bass guitar`, `Piano`, `Electric piano`,
`Organ`, `Violin, fiddle`, `Cello`, `Trumpet`, `Saxophone`, `Synthesizer`, `Drum kit`, etc. --
see `yamnet_class_map.csv`), at 16MB and a license clear for redistribution.

## Why this isn't a from-scratch conversion

Unlike Demucs's STFT (which hit PyTorch's TorchScript exporter refusing complex tensors, see
`../demucs_export/README.md`), TensorFlow's `tf.signal.stft`/mel-spectrogram ops decompose to
real-valued ONNX ops without issue, so a full-graph export -- mel frontend and MobileNet backbone
both in one graph -- already exists and needed no conversion work here. `yamnet.onnx` is
`zeropointnine/yamnet-onnx`'s tf2onnx conversion of the real Google model, downloaded verbatim
rather than re-converted.

Numerical correctness isn't just taken on faith from that repo's description, though --
`verify.py` runs the real TF-Hub model (`https://tfhub.dev/google/yamnet/1`) and this ONNX file
on the same real audio (a clip of the drum stem captured while calibrating `DrumHitClassifier`)
and confirms the outputs match (max abs diff `3e-6` across all 521 classes, identical top-5
ranking). As a sanity check beyond pure numerics, both models' top predictions on that drum clip
are `Drum machine`/`Drum kit`/`Percussion`/`House music` -- exactly what a human would expect.

## Model facts

- Input `waveform`: f32 `[-1]`, raw mono @ 16kHz, dynamic length -- the mel-spectrogram framing
  (25ms window / 10ms hop, 64 mel bins, 125-7500Hz, `log(mel + 0.001)`) and the classifier's own
  0.96s-patch / 0.48s-hop windowing both happen inside the graph. No manual windowing needed on
  the Kotlin side, unlike Basic Pitch -- just resample to 16kHz mono and hand over the whole clip.
- Output `output_0`: f32 `[N, 521]`, one row of AudioSet class scores (sigmoid, multi-label) per
  0.96s patch, `N` determined by input length. `output_1` (1024-d embeddings) and `output_2`
  (64-bin log-mel) are unused here.
- Size: 16.1MB (vs. Demucs's 235MB) -- downloaded as a GitHub Release asset via `ModelProvider`,
  same pattern as Demucs, not bundled in assets like Basic Pitch.

## Usage

```bash
uv venv --python 3.11 venv
uv pip install -p venv/bin/python tensorflow tensorflow-hub huggingface_hub onnx onnxruntime numpy
uv pip install -p venv/bin/python "setuptools<81"  # tensorflow-hub imports pkg_resources, removed in 81+

venv/bin/python export.py   # -> yamnet.onnx, yamnet_class_map.csv
venv/bin/python verify.py   # confirms the ONNX export matches the real TF-Hub model
```

## What's ported vs. not

`TimbreClassifier` (Kotlin) resamples each stem to 16kHz mono, runs the whole clip through
`yamnet.onnx` in chunks (to bound peak memory on a multi-minute stem), averages `output_0` across
all patches, and maps the highest-scoring *instrument-relevant* AudioSet class to a GM program via
a hand-built lookup table (`YamnetGmMapping.kt`) -- non-instrument classes (`Speech`, `Music`,
`Silence`, genre tags, etc.) are ignored in favor of the next-highest instrument class, falling
back to `DemucsSourceClassifier`'s original per-label default if no instrument class clears a
confidence threshold.
