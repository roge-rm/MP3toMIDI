"""
Exports htdemucs_6s (the 6-stem Hybrid Transformer Demucs model) to ONNX for on-device
inference. Run from this directory:

    uv venv --python 3.11 venv
    uv pip install -p venv/bin/python torch --index-url https://download.pytorch.org/whl/cpu
    uv pip install -p venv/bin/python demucs onnx onnxruntime
    venv/bin/python export.py

Produces htdemucs_6s.onnx (~235MB, float32). Copy it to
app/src/main/assets/models/htdemucs_6s.onnx (gitignored -- too large to commit) before
building the app.

See onnx_patches.py for why this isn't a plain torch.onnx.export() call: htdemucs's
STFT/ISTFT frontend uses genuine torch.complex64 tensors, which the exporter can't
represent (ONNX has no complex dtype).
"""
import torch
from demucs.pretrained import get_model

from onnx_patches import patch_htdemucs_for_onnx_export

MODEL_NAME = "htdemucs_6s"
OUTPUT_PATH = "htdemucs_6s.onnx"


def main():
    bag = get_model(MODEL_NAME)
    model = bag.models[0]
    model.eval()
    patch_htdemucs_for_onnx_export(model)

    segment_length = int(model.samplerate * model.segment)
    dummy_input = torch.randn(1, model.audio_channels, segment_length)

    with torch.no_grad():
        model(dummy_input)  # sanity check the patched model still runs before exporting

    torch.onnx.export(
        model,
        (dummy_input,),
        OUTPUT_PATH,
        input_names=["mixture"],
        output_names=["stems"],
        opset_version=18,
        dynamo=False,
    )
    print(f"Exported {OUTPUT_PATH}")
    print(f"sources: {model.sources}")
    print(f"segment_length (samples): {segment_length}")
    print(f"samplerate: {model.samplerate}, channels: {model.audio_channels}")


if __name__ == "__main__":
    main()
