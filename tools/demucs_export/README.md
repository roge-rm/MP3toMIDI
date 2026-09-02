# Demucs ONNX export

Converts `htdemucs_6s` (drums/bass/other/vocals/guitar/piano, the highest-quality 6-stem
Demucs model) to ONNX so `DemucsStemSeparator` can run it on-device via ONNX Runtime Mobile.

## Why this isn't a plain `torch.onnx.export()`

htdemucs's STFT frontend calls `torch.stft(..., return_complex=True)` and
`torch.view_as_complex`, producing genuine `torch.complex64` tensors. ONNX has no complex
dtype, and PyTorch's exporter refuses both of those ops when they'd need one (confirmed by
running the export and reading the resulting `SymbolicValueError` / `UnsupportedOperatorError`
directly rather than assuming). `real_stft.py` replaces the analysis/synthesis STFT with an
equivalent Conv1d/ConvTranspose1d implementation that never produces a complex tensor;
`onnx_patches.py` monkeypatches `HTDemucs._spec`/`_ispec`/`_magnitude`/`_mask` (the only four
methods that touch complex tensors) to use it instead of `demucs.spec`, without touching the
frozen pretrained weights.

Numerically this is within float32 conv-vs-FFT accumulation noise of the original
(~0.05% relative error end to end) -- verified in `verify.py`, not assumed.

## Usage

```bash
uv venv --python 3.11 venv
uv pip install -p venv/bin/python torch --index-url https://download.pytorch.org/whl/cpu
uv pip install -p venv/bin/python demucs onnx onnxruntime

venv/bin/python export.py   # -> htdemucs_6s.onnx (~235MB, float32)
venv/bin/python verify.py   # confirms ONNX Runtime output matches PyTorch
```

Then copy the model into the app (gitignored -- too large for the repo):

```bash
cp htdemucs_6s.onnx ../../app/src/main/assets/models/htdemucs_6s.onnx
```

`uv` (https://astral.sh/uv) is used instead of plain `venv`/`pip` because it bundles its own
Python builds, sidestepping the `python3-venv` apt package / PEP 668 externally-managed-environment
friction on a bare Debian/Ubuntu box.

## Model facts baked into the Android-side code

These come straight from the loaded model (`model.sources`, `model.segment`,
`model.samplerate`, `model.audio_channels`) and are hardcoded as constants in
`DemucsStemSeparator` since the exported graph has static input/output shapes:

- sources: `drums, bass, other, vocals, guitar, piano` (fixed order -- also the output tensor's
  source-axis order)
- segment length: `343980` samples (`44100 * 39/5` seconds)
- samplerate: `44100`, channels: `2` (stereo)
